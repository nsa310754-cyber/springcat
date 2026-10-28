package site.ragdollp.binrunner;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 書庫の展開。.deb(ar 書庫)・tar・gzip・zip に対応し、中から最初の ELF 実行ファイルを取り出す。
 * xz / zstd は pure Java では展開できないので、その旨を案内する。
 */
public final class Archive {

    private static final long MAX_MEMBER = 256L * 1024 * 1024; // 読み込み上限

    private Archive() {}

    public static final class Found {
        public File file;     // 取り出した実行ファイル(null なら見つからず)
        public String name;   // 書庫内の名前
        public String note;   // 説明・エラー内容
    }

    private static boolean isGzip(byte[] h) {
        return h.length >= 2 && (h[0] & 0xFF) == 0x1F && (h[1] & 0xFF) == 0x8B;
    }

    /** .deb(ar)を展開し、data.tar.* の中の最初の ELF 実行ファイルを取り出す。 */
    public static Found extractDeb(File deb, File outDir) throws IOException {
        Found f = new Found();
        try (RandomAccessFile raf = new RandomAccessFile(deb, "r")) {
            long pos = 8, len = raf.length();   // 先頭 "!<arch>\n" を飛ばす
            String dataName = null;
            long dataOff = -1, dataSize = 0;
            while (pos + 60 <= len) {
                byte[] hdr = new byte[60];
                raf.seek(pos);
                if (raf.read(hdr) < 60) break;
                String name = new String(hdr, 0, 16, "US-ASCII").trim();
                if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
                long size;
                try { size = Long.parseLong(new String(hdr, 48, 10, "US-ASCII").trim()); }
                catch (NumberFormatException e) { break; }
                long dataPos = pos + 60;
                if (name.startsWith("data.tar")) { dataName = name; dataOff = dataPos; dataSize = size; break; }
                pos = dataPos + size + (size & 1);   // 偶数境界へパディング
            }
            if (dataOff < 0) { f.note = "data.tar メンバが見つかりません(壊れた .deb?)"; return f; }

            byte[] buf = new byte[(int) Math.min(dataSize, MAX_MEMBER)];
            raf.seek(dataOff);
            raf.readFully(buf);

            if (dataName.endsWith(".gz") || isGzip(buf)) {
                return firstElfInTar(new GZIPInputStream(new ByteArrayInputStream(buf)), outDir);
            } else if (dataName.endsWith(".tar")) {
                return firstElfInTar(new ByteArrayInputStream(buf), outDir);
            } else {
                f.note = dataName + " は " + ext(dataName) + " 圧縮です。pure Java では gzip のみ対応。\n"
                        + "xz/zstd は Termux で `ar x foo.deb && tar xf data.tar.*` で展開してから、"
                        + "出てきた実行ファイルを選び直してください。";
                return f;
            }
        }
    }

    /** gzip 単体(.gz / .tar.gz)を展開。中が tar なら最初の ELF を、素のファイルならそれ自身を返す。 */
    public static Found extractGzip(File gz, File outDir) throws IOException {
        File inner = new File(outDir, "inner.bin");
        try (InputStream in = new GZIPInputStream(new FileInputStream(gz));
             OutputStream os = new FileOutputStream(inner)) {
            copyAll(in, os);
        }
        // 中身が tar なら ELF を取り出す。素の ELF ならそれ自身。どちらでもなければ「実行物なし」。
        try (RandomAccessFile raf = new RandomAccessFile(inner, "r")) {
            byte[] h = new byte[Math.min(265, (int) Math.min(raf.length(), 265))];
            raf.seek(0); int rn = raf.read(h);
            if (rn >= 265 && h[257] == 'u' && h[258] == 's' && h[259] == 't' && h[260] == 'a' && h[261] == 'r') {
                return firstElfInTar(new FileInputStream(inner), outDir);
            }
            if (isElf(h, rn)) {
                Found f = new Found(); f.file = inner; f.name = "(gunzip 済み)";
                f.note = "gzip を展開しました(中身は ELF 実行ファイル)。"; return f;
            }
        }
        Found f = new Found();
        f.note = "展開しましたが、中身は実行ファイルではありません(ただのデータ)。";
        return f;   // file=null → 呼び出し側で禁止マークを付ける
    }

    /** 素の tar から最初の ELF を取り出す。 */
    public static Found extractTar(File tar, File outDir) throws IOException {
        return firstElfInTar(new FileInputStream(tar), outDir);
    }

    /** zip から最初の ELF を取り出す。 */
    public static Found extractZip(File zip, File outDir) throws IOException {
        Found f = new Found();
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                byte[] peek = new byte[4];
                int pr = readN(zis, peek, 4);
                if (isElf(peek, pr)) {
                    File out = new File(outDir, "payload.bin");
                    try (OutputStream os = new FileOutputStream(out)) {
                        os.write(peek, 0, pr);
                        copyAll(zis, os);
                    }
                    f.file = out; f.name = e.getName();
                    f.note = "取り出した実行ファイル: " + e.getName();
                    return f;
                }
            }
        }
        f.note = "zip 内に ELF 実行ファイルが見つかりませんでした。";
        return f;
    }

    // --- tar ストリームから最初の ELF を取り出す中核 ---
    private static Found firstElfInTar(InputStream in, File outDir) throws IOException {
        Found f = new Found();
        byte[] hdr = new byte[512];
        try {
            while (readN(in, hdr, 512) == 512) {
                if (allZero(hdr)) break;                       // 終端(ゼロブロック)
                String name = cstr(hdr, 0, 100);
                String prefix = cstr(hdr, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
                long size = octal(hdr, 124, 12);
                int mode = (int) octal(hdr, 100, 8);
                char type = (char) hdr[156];
                long padded = ((size + 511) / 512) * 512;

                if (type == '0' || type == '\0') {            // 通常ファイル
                    byte[] peek = new byte[4];
                    int want = (int) Math.min(4, size);
                    int pr = readN(in, peek, want);
                    if (isElf(peek, pr)) {
                        File out = new File(outDir, "payload.bin");
                        try (OutputStream os = new FileOutputStream(out)) {
                            os.write(peek, 0, pr);
                            copyN(in, os, size - pr);
                        }
                        skipN(in, padded - size);
                        f.file = out; f.name = name;
                        f.note = "取り出した実行ファイル: " + name + " (mode "
                                + Integer.toOctalString(mode) + ")";
                        return f;
                    } else {
                        skipN(in, (size - pr) + (padded - size));
                    }
                } else {
                    skipN(in, padded);                        // ディレクトリ等は読み飛ばし
                }
            }
        } finally {
            try { in.close(); } catch (IOException ignore) {}
        }
        f.note = "書庫内に ELF 実行ファイルが見つかりませんでした。";
        return f;
    }

    // --- ユーティリティ ---
    private static boolean isElf(byte[] h, int n) {
        return n >= 4 && (h[0] & 0xFF) == 0x7F && h[1] == 'E' && h[2] == 'L' && h[3] == 'F';
    }

    private static boolean allZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String cstr(byte[] b, int off, int max) {
        int end = off;
        while (end < off + max && b[end] != 0) end++;
        return new String(b, off, end - off).trim();
    }

    /** tar の数値は ASCII 8 進数(空白/NUL パディング)。 */
    private static long octal(byte[] b, int off, int max) {
        long v = 0;
        for (int i = off; i < off + max; i++) {
            int c = b[i] & 0xFF;
            if (c == 0 || c == ' ') { if (v == 0) continue; else break; }
            if (c < '0' || c > '7') break;
            v = v * 8 + (c - '0');
        }
        return v;
    }

    private static String ext(String name) {
        int d = name.lastIndexOf('.');
        return d >= 0 ? name.substring(d + 1) : name;
    }

    /** ちょうど want バイト読む(EOF で早期終了可)。実際に読めたバイト数を返す。 */
    private static int readN(InputStream in, byte[] buf, int want) throws IOException {
        int off = 0;
        while (off < want) {
            int r = in.read(buf, off, want - off);
            if (r < 0) break;
            off += r;
        }
        return off;
    }

    private static void copyAll(InputStream in, OutputStream out) throws IOException {
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) out.write(b, 0, n);
        out.flush();
    }

    private static void copyN(InputStream in, OutputStream out, long n) throws IOException {
        byte[] b = new byte[8192];
        long left = n;
        while (left > 0) {
            int r = in.read(b, 0, (int) Math.min(b.length, left));
            if (r < 0) break;
            out.write(b, 0, r);
            left -= r;
        }
        out.flush();
    }

    private static void skipN(InputStream in, long n) throws IOException {
        long left = n;
        byte[] b = new byte[8192];
        while (left > 0) {
            long s = in.skip(left);
            if (s > 0) { left -= s; continue; }
            int r = in.read(b, 0, (int) Math.min(b.length, left));  // skip が効かない時の保険
            if (r < 0) break;
            left -= r;
        }
    }
}
