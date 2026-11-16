package site.springcat.bionicrunner;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 圧縮ファイル (tar / tar.gz / tgz / tar.xz / tar.bz2 / zip / 単体の .gz .xz .bz2) の展開と、
 * 展開した中にあるプログラムを登録名で起動するための置き場所の管理。
 *
 * 展開先は files/pkg/&lt;登録名&gt;/root/ 。同じ階層の exe というファイルに、起動するファイルの
 * root からの相対パスを書いておく。bin/&lt;登録名&gt; にはそれを起動するスクリプトを置く。
 *
 * 外から受け取ったファイルを扱うので、展開先の外へ書き出そうとする項目 (../ や絶対パス、
 * 外を指すリンク) は取り出さず、合計の大きさと個数にも上限を設ける。
 */
final class Archive {

    enum Format { NONE, ZIP, GZIP, XZ, BZIP2, TAR, UNSUPPORTED }

    /** 展開した 1 ファイル */
    static final class Entry {
        final File file;
        /** 展開先からの相対パス (区切りは /) */
        final String path;
        /** アーカイブ内で実行可能の印が付いていた (zip では分からないので常に偽) */
        final boolean execBit;

        Entry(File file, String path, boolean execBit) {
            this.file = file;
            this.path = path;
            this.execBit = execBit;
        }
    }

    /** 展開の結果 */
    static final class Result {
        /** 展開した通常ファイル。tree が偽なら 1 個だけ (圧縮されていた単体のファイル) */
        final List<Entry> files = new ArrayList<>();
        /** tar / zip だった (フォルダごと展開した) */
        boolean tree;
        /** 展開先の外を指すなどの理由で取り出さなかった項目の数 */
        int skipped;
    }

    /** 登録前の、展開したままの一式 */
    static final class Pkg {
        /** 仮の置き場所 (files/pkg/.incoming-…)。登録すると files/pkg/<登録名> へ移る */
        File staging;
        Result result;
        /** 起動するファイル */
        Entry exe;
        /** 起動前に、起動するファイルのあるフォルダへ移動する */
        boolean cd;
    }

    /** 展開後の合計の上限。小さな圧縮ファイルが巨大に膨らむもの (zip 爆弾) で端末を埋めないため */
    static final long MAX_TOTAL = 4L * 1024 * 1024 * 1024;
    static final int MAX_ENTRIES = 50_000;
    private static final int BLOCK = 512;

    private Archive() {
    }

    // ------------------------------------------------------------------ 判定

    static Format detect(File f) throws IOException {
        byte[] h = new byte[BLOCK];
        int n;
        try (InputStream in = new FileInputStream(f)) {
            n = readFully(in, h);
        }
        if (n >= 4 && h[0] == 'P' && h[1] == 'K' && h[2] == 3 && h[3] == 4) return Format.ZIP;
        if (n >= 3 && (h[0] & 255) == 0x1f && (h[1] & 255) == 0x8b && h[2] == 8) return Format.GZIP;
        if (n >= 6 && (h[0] & 255) == 0xfd && h[1] == '7' && h[2] == 'z' && h[3] == 'X'
                && h[4] == 'Z' && h[5] == 0) return Format.XZ;
        if (n >= 10 && h[0] == 'B' && h[1] == 'Z' && h[2] == 'h' && h[3] >= '1' && h[3] <= '9'
                && h[4] == 0x31 && h[5] == 0x41 && h[6] == 0x59) return Format.BZIP2;
        if (n == BLOCK && isTarHeader(h)) return Format.TAR;
        if (n >= 4 && (h[0] & 255) == 0x28 && (h[1] & 255) == 0xb5 && (h[2] & 255) == 0x2f
                && (h[3] & 255) == 0xfd) return Format.UNSUPPORTED; // zstd
        if (n >= 6 && h[0] == '7' && h[1] == 'z' && (h[2] & 255) == 0xbc && (h[3] & 255) == 0xaf
                && h[4] == 0x27 && h[5] == 0x1c) return Format.UNSUPPORTED; // 7z
        if (n >= 6 && h[0] == 'R' && h[1] == 'a' && h[2] == 'r' && h[3] == '!' && h[4] == 0x1a
                && h[5] == 7) return Format.UNSUPPORTED; // rar
        return Format.NONE;
    }

    /** zip のうち、Android アプリ (APK) であるもの */
    static boolean isApk(File zip) {
        try (ZipFile z = new ZipFile(zip)) {
            return z.getEntry("AndroidManifest.xml") != null;
        } catch (IOException e) {
            return true; // 読めない zip はこれまで通り APK として扱い、インストーラに判断させる
        }
    }

    /** 圧縮ファイルの名前から、中身の名前を作る (prog.tar.gz → prog) */
    static String stripExtension(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        for (String ext : new String[]{".tar.gz", ".tar.xz", ".tar.bz2", ".tgz", ".txz", ".tbz2",
                ".tbz", ".tar", ".zip", ".gz", ".xz", ".bz2"}) {
            if (lower.endsWith(ext) && name.length() > ext.length()) {
                return name.substring(0, name.length() - ext.length());
            }
        }
        return name;
    }

    // ------------------------------------------------------------------ 展開

    /**
     * src を dest (空のフォルダ) へ展開する。format は {@link #detect} の結果。
     * 圧縮された tar はフォルダごと、圧縮されただけの単体のファイルは singleName という名前で取り出す。
     */
    static Result extract(File src, Format format, File dest, String singleName)
            throws IOException {
        return extract(src, format, dest, singleName, null);
    }

    /**
     * password はパスワード付きの zip 用 (それ以外では使わない)。パスワードが要るのに null、
     * または違っているときは {@link ZipCrypt.PasswordException}。
     */
    static Result extract(File src, Format format, File dest, String singleName, char[] password)
            throws IOException {
        if (format == Format.ZIP) {
            // 暗号化された zip は Java 標準の機能では読めないので、先に目次を見て振り分ける
            List<ZipCrypt.ZEntry> entries = null;
            try {
                entries = ZipCrypt.list(src);
            } catch (IOException e) {
                // 目次を読めない zip は、これまで通り標準の機能に任せる (そちらのエラーを伝える)
            }
            if (entries != null && ZipCrypt.isEncrypted(entries)) {
                if (password == null || password.length == 0) {
                    throw new ZipCrypt.PasswordException(false);
                }
                if (!dest.isDirectory() && !dest.mkdirs()) throw new IOException("展開先を作れません");
                Result r = new Result();
                r.tree = true;
                extractEncryptedZip(src, entries, dest, r, new Budget(dest), password);
                return r;
            }
        }
        if (!dest.isDirectory() && !dest.mkdirs()) throw new IOException("展開先を作れません");
        Result r = new Result();
        Budget budget = new Budget(dest);
        switch (format) {
            case ZIP:
                r.tree = true;
                extractZip(src, dest, r, budget);
                break;
            case TAR:
                r.tree = true;
                try (InputStream in = new BufferedInputStream(new FileInputStream(src), 65536)) {
                    extractTar(in, null, dest, r, budget);
                }
                break;
            case GZIP:
                try (InputStream in = new GZIPInputStream(
                        new BufferedInputStream(new FileInputStream(src), 65536), 65536)) {
                    extractStream(in, dest, singleName, r, budget);
                }
                break;
            case XZ:
            case BZIP2:
                extractWithTool(src, format, dest, singleName, r, budget);
                break;
            default:
                throw new IOException("対応していない形式です");
        }
        return r;
    }

    /** 伸長した中身が tar ならフォルダごと、そうでなければ 1 個のファイルとして取り出す */
    private static void extractStream(InputStream in, File dest, String singleName, Result r,
                                      Budget budget) throws IOException {
        byte[] first = new byte[BLOCK];
        int n = readFully(in, first);
        if (n == BLOCK && isTarHeader(first)) {
            r.tree = true;
            extractTar(in, first, dest, r, budget);
            return;
        }
        File out = new File(dest, singleName);
        try (OutputStream os = new FileOutputStream(out)) {
            budget.add(n);
            os.write(first, 0, n);
            byte[] buf = new byte[65536];
            int k;
            while ((k = in.read(buf)) > 0) {
                budget.add(k);
                os.write(buf, 0, k);
            }
        }
        r.files.add(new Entry(out, singleName, false));
    }

    /** xz と bzip2 は Java の標準に無いので、端末に入っているコマンド (toybox の xzcat / bzcat) に任せる */
    private static void extractWithTool(File src, Format format, File dest, String singleName,
                                        Result r, Budget budget) throws IOException {
        String tool = findTool(format == Format.XZ ? "xzcat" : "bzcat");
        if (tool == null) {
            throw new IOException((format == Format.XZ ? "xz" : "bzip2")
                    + " 形式を展開するコマンドがこの端末にありません。"
                    + "tar.gz か zip にしてから選んでください");
        }
        Process p = new ProcessBuilder(tool, src.getAbsolutePath()).start();
        try {
            p.getOutputStream().close();
            Yara.drain(p.getErrorStream());
            try (InputStream in = new BufferedInputStream(p.getInputStream(), 65536)) {
                extractStream(in, dest, singleName, r, budget);
            }
            int code;
            try {
                code = p.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("中断されました");
            }
            if (code != 0) throw new IOException("圧縮ファイルが壊れています");
        } finally {
            p.destroy();
        }
    }

    private static String findTool(String name) {
        for (String dir : new String[]{"/system/bin", "/usr/bin", "/bin"}) {
            File f = new File(dir, name);
            if (f.canExecute()) return f.getAbsolutePath();
        }
        return null;
    }

    private static void extractZip(File src, File dest, Result r, Budget budget)
            throws IOException {
        byte[] buf = new byte[65536];
        try (ZipFile z = new ZipFile(src)) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                budget.entry();
                String path = cleanPath(ze.getName());
                if (path == null) {
                    r.skipped++;
                    continue;
                }
                File out = new File(dest, path);
                if (ze.isDirectory()) {
                    makeDirs(dest, out);
                    continue;
                }
                makeDirs(dest, out.getParentFile());
                replaceable(out);
                try (InputStream in = z.getInputStream(ze);
                     OutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        budget.add(n);
                        os.write(buf, 0, n);
                    }
                }
                r.files.add(new Entry(out, path, false));
            }
        }
    }

    private static void extractEncryptedZip(File src, List<ZipCrypt.ZEntry> entries, File dest,
                                            Result r, Budget budget, char[] password)
            throws IOException {
        byte[] buf = new byte[65536];
        for (ZipCrypt.ZEntry ze : entries) {
            budget.entry();
            String path = cleanPath(ze.name);
            if (path == null) {
                r.skipped++;
                continue;
            }
            File out = new File(dest, path);
            if (ze.dir) {
                makeDirs(dest, out);
                continue;
            }
            makeDirs(dest, out.getParentFile());
            replaceable(out);
            try (InputStream in = ZipCrypt.open(src, ze, password);
                 OutputStream os = new FileOutputStream(out)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    budget.add(n);
                    os.write(buf, 0, n);
                }
            }
            r.files.add(new Entry(out, path, (ze.mode & 0111) != 0));
        }
    }

    /**
     * tar を読んで展開する。first は、判定のために先に読んでしまった最初の 512 バイト (無ければ null)。
     * 普通のファイル・フォルダ・リンクに対応し、GNU の長い名前 (L / K) と pax の拡張 (x) を解釈する。
     */
    private static void extractTar(InputStream in, byte[] first, File dest, Result r,
                                   Budget budget) throws IOException {
        byte[] h = new byte[BLOCK];
        byte[] buf = new byte[65536];
        String longName = null, longLink = null;
        String paxPath = null, paxLink = null;
        long paxSize = -1;
        while (true) {
            if (first != null) {
                System.arraycopy(first, 0, h, 0, BLOCK);
                first = null;
            } else {
                int n = readFully(in, h);
                if (n == 0) break; // 終わりの印 (空のブロック) が無い tar もある
                if (n < BLOCK) throw new IOException("tar が途中で切れています");
            }
            if (isZeroBlock(h)) break;
            if (!isTarHeader(h)) throw new IOException("tar の中身が壊れています");
            budget.entry();

            String name = tarString(h, 0, 100);
            String prefix = tarString(h, 345, 155);
            boolean ustar = h[257] == 'u' && h[258] == 's' && h[259] == 't' && h[260] == 'a'
                    && h[261] == 'r' && h[262] == 0;
            if (ustar && !prefix.isEmpty()) name = prefix + "/" + name;
            long size = tarNumber(h, 124, 12);
            int mode = (int) tarNumber(h, 100, 8);
            String link = tarString(h, 157, 100);
            int type = h[156] & 255;
            if (size < 0) throw new IOException("tar の中身が壊れています");

            if (type == 'L' || type == 'K' || type == 'x' || type == 'g') {
                // 次の項目に付く追加情報。大きさは高々数 KB のはず
                if (size > 1024 * 1024) throw new IOException("tar の中身が壊れています");
                byte[] data = new byte[(int) size];
                if (readFully(in, data) < data.length) throw new EOFException("tar が途中で切れています");
                skipFully(in, pad(size));
                if (type == 'L') {
                    longName = cString(data);
                } else if (type == 'K') {
                    longLink = cString(data);
                } else if (type == 'x') {
                    for (String[] kv : paxRecords(data)) {
                        if (kv[0].equals("path")) paxPath = kv[1];
                        else if (kv[0].equals("linkpath")) paxLink = kv[1];
                        else if (kv[0].equals("size")) {
                            try {
                                paxSize = Long.parseLong(kv[1]);
                            } catch (NumberFormatException ignored) {
                                // 読めない値は無視してヘッダの値を使う
                            }
                        }
                    }
                }
                continue;
            }
            if (paxPath != null) name = paxPath;
            else if (longName != null) name = longName;
            if (paxLink != null) link = paxLink;
            else if (longLink != null) link = longLink;
            if (paxSize >= 0) size = paxSize;
            longName = longLink = paxPath = paxLink = null;
            paxSize = -1;

            String path = cleanPath(name);
            File out = path == null ? null : new File(dest, path);
            switch (type) {
                case 0:
                case '0':
                case '7': // 普通のファイル
                    if (out == null || name.endsWith("/")) {
                        // 古い tar は、名前が / で終わる通常ファイルとしてフォルダを表す
                        if (out != null) makeDirs(dest, out);
                        else r.skipped++;
                        skipFully(in, size + pad(size));
                        break;
                    }
                    makeDirs(dest, out.getParentFile());
                    replaceable(out);
                    budget.add(size);
                    try (OutputStream os = new FileOutputStream(out)) {
                        long left = size;
                        while (left > 0) {
                            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                            if (n <= 0) throw new EOFException("tar が途中で切れています");
                            os.write(buf, 0, n);
                            left -= n;
                        }
                    }
                    skipFully(in, pad(size));
                    r.files.add(new Entry(out, path, (mode & 0111) != 0));
                    break;
                case '5': // フォルダ
                    if (out != null) makeDirs(dest, out);
                    skipFully(in, size + pad(size));
                    break;
                case '2': // シンボリックリンク
                    if (out == null || !symlink(dest, out, path, link)) r.skipped++;
                    skipFully(in, size + pad(size));
                    break;
                case '1': { // ハードリンク: 先に取り出した同じ中身を写す
                    String target = cleanPath(link);
                    File from = target == null ? null : new File(dest, target);
                    if (out == null || from == null || !isPlainInside(dest, from)) {
                        r.skipped++;
                    } else {
                        makeDirs(dest, out.getParentFile());
                        replaceable(out);
                        budget.add(from.length());
                        copy(from, out, buf);
                        r.files.add(new Entry(out, path, from.canExecute() || (mode & 0111) != 0));
                    }
                    skipFully(in, size + pad(size));
                    break;
                }
                default: // デバイスファイルや名前付きパイプなどは作らない
                    r.skipped++;
                    skipFully(in, size + pad(size));
            }
        }
    }

    // ------------------------------------------------------- 安全に書き出すための確認

    /** 展開先からの相対パスに直す。展開先の外へ出るもの (..) は null */
    static String cleanPath(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String part : raw.replace('\\', '/').split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..") || part.indexOf('\0') >= 0) return null;
            if (sb.length() > 0) sb.append('/');
            sb.append(part);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * dest の中にフォルダ dir を作る。途中にリンクがあって実際の場所が dest の外になる場合は拒否する
     * (先にリンクを置き、次にそのリンク越しに書き込ませる手口への対策)。
     */
    private static void makeDirs(File dest, File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("フォルダを作れません: " + dir.getName());
        }
        String root = dest.getCanonicalPath();
        String real = dir.getCanonicalPath();
        if (!real.equals(root) && !real.startsWith(root + File.separator)) {
            throw new IOException("展開先の外へ書き出そうとするアーカイブです");
        }
    }

    /** 同じ名前で先に取り出したもの (リンクを含む) があれば消し、リンク越しに書き込まないようにする */
    private static void replaceable(File out) throws IOException {
        if (isSymlink(out) || out.isFile()) {
            if (!out.delete()) throw new IOException("書き込めません: " + out.getName());
        } else if (out.isDirectory()) {
            throw new IOException("フォルダと同じ名前のファイルがあります: " + out.getName());
        }
    }

    private static boolean isPlainInside(File dest, File f) throws IOException {
        if (!f.isFile() || isSymlink(f)) return false;
        String root = dest.getCanonicalPath();
        return f.getCanonicalPath().startsWith(root + File.separator);
    }

    static boolean isSymlink(File f) {
        try {
            return java.nio.file.Files.isSymbolicLink(f.toPath());
        } catch (LinkageError | RuntimeException e) {
            return false; // Android 7 には java.nio.file が無い。そこではリンクも作らない
        }
    }

    /** 展開先の中だけを指す相対リンクなら作る。絶対パスや外を指すものは作らない */
    private static boolean symlink(File dest, File out, String path, String target)
            throws IOException {
        if (target.isEmpty() || target.startsWith("/") || target.indexOf('\0') >= 0) return false;
        // リンクを置く場所から target をたどった先が、展開先の中に収まるか
        int depth = 0;
        for (int i = 0; i < path.length(); i++) if (path.charAt(i) == '/') depth++;
        for (String part : target.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (--depth < 0) return false;
            } else {
                depth++;
            }
        }
        makeDirs(dest, out.getParentFile());
        try {
            if (isSymlink(out) || out.isFile()) out.delete();
            if (out.exists()) return false;
            java.nio.file.Files.createSymbolicLink(out.toPath(), java.nio.file.Paths.get(target));
            return true;
        } catch (LinkageError | RuntimeException | IOException e) {
            return false;
        }
    }

    /** 合計の大きさ・個数・空き容量の見張り */
    private static final class Budget {
        private final File dest;
        private long total;
        private long sinceCheck;
        private int entries;

        Budget(File dest) {
            this.dest = dest;
        }

        void entry() throws IOException {
            if (++entries > MAX_ENTRIES) throw new IOException("ファイルの数が多すぎます");
        }

        void add(long n) throws IOException {
            total += n;
            sinceCheck += n;
            if (total > MAX_TOTAL) throw new IOException("展開後の大きさが上限 (4GB) を超えます");
            if (sinceCheck >= 8L * 1024 * 1024 || n >= 8L * 1024 * 1024) {
                sinceCheck = 0;
                // 端末の空きを使い切ると他のアプリまで動かなくなるので、少し残して止める
                long free = dest.getUsableSpace();
                long need = n >= 8L * 1024 * 1024 ? n : 0;
                if (free > 0 && free - need < 64L * 1024 * 1024) {
                    throw new IOException("端末の空き容量が足りません");
                }
            }
        }
    }

    // ------------------------------------------------------------- tar の読み取り

    /** ヘッダの検査合計が合っていれば tar の 1 ブロックとみなす */
    static boolean isTarHeader(byte[] h) {
        if (isZeroBlock(h)) return false;
        long stored = tarNumber(h, 148, 8);
        if (stored < 0) return false;
        long unsigned = 0, signed = 0;
        for (int i = 0; i < BLOCK; i++) {
            int b = (i >= 148 && i < 156) ? ' ' : h[i];
            unsigned += b & 255;
            signed += b; // 古い実装には符号付きで足したものがある
        }
        return stored == unsigned || stored == signed;
    }

    private static boolean isZeroBlock(byte[] h) {
        for (byte b : h) if (b != 0) return false;
        return true;
    }

    private static String tarString(byte[] h, int off, int len) {
        int end = off;
        while (end < off + len && h[end] != 0) end++;
        return new String(h, off, end - off, StandardCharsets.UTF_8);
    }

    private static String cString(byte[] data) {
        int end = 0;
        while (end < data.length && data[end] != 0) end++;
        return new String(data, 0, end, StandardCharsets.UTF_8);
    }

    /** 8 進数の文字列、または (大きな値用の) 先頭ビットを立てた 2 進数。読めなければ -1 */
    private static long tarNumber(byte[] h, int off, int len) {
        if ((h[off] & 0x80) != 0) {
            long v = h[off] & 0x7f;
            for (int i = 1; i < len; i++) {
                if (v > (Long.MAX_VALUE >> 8)) return -1;
                v = (v << 8) | (h[off + i] & 255);
            }
            return v;
        }
        long v = 0;
        boolean any = false;
        for (int i = off; i < off + len; i++) {
            int c = h[i] & 255;
            if (c == ' ' || c == 0) {
                if (any) break;
                continue;
            }
            if (c < '0' || c > '7') return -1;
            v = (v << 3) | (c - '0');
            any = true;
        }
        return v;
    }

    /** pax の拡張ヘッダ ("長さ 名前=値\n" の並び) を読む */
    private static List<String[]> paxRecords(byte[] data) {
        List<String[]> out = new ArrayList<>();
        int pos = 0;
        while (pos < data.length) {
            int sp = pos;
            while (sp < data.length && data[sp] != ' ') sp++;
            if (sp >= data.length) break;
            int len;
            try {
                len = Integer.parseInt(new String(data, pos, sp - pos, StandardCharsets.US_ASCII));
            } catch (NumberFormatException e) {
                break;
            }
            if (len <= sp - pos + 1 || pos + len > data.length) break;
            String rec = new String(data, sp + 1, pos + len - sp - 1, StandardCharsets.UTF_8);
            if (rec.endsWith("\n")) rec = rec.substring(0, rec.length() - 1);
            int eq = rec.indexOf('=');
            if (eq > 0) out.add(new String[]{rec.substring(0, eq), rec.substring(eq + 1)});
            pos += len;
        }
        return out;
    }

    private static long pad(long size) {
        return (BLOCK - (size % BLOCK)) % BLOCK;
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int got = 0;
        while (got < buf.length) {
            int n = in.read(buf, got, buf.length - got);
            if (n <= 0) break;
            got += n;
        }
        return got;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        byte[] buf = null;
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (buf == null) buf = new byte[8192];
                int r = in.read(buf, 0, (int) Math.min(buf.length, n));
                if (r <= 0) throw new EOFException("tar が途中で切れています");
                s = r;
            }
            n -= s;
        }
    }

    private static void copy(File from, File to, byte[] buf) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream os = new FileOutputStream(to)) {
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    // ------------------------------------------------------------------ 圧縮

    /**
     * sources (ファイルやフォルダ。フォルダは中身ごと) を 1 つの圧縮ファイル out にまとめる。
     * tarGz が真なら tar.gz、偽なら zip。中での名前は、それぞれの sources の名前から始まる相対パス。
     * リンクは入れない (展開した先で外を指すものになりかねないため)。
     */
    static void create(List<File> sources, File out, boolean tarGz) throws IOException {
        create(sources, out, tarGz, null);
    }

    /** password が空でなければ、パスワード付きの zip (AES-256) にする。tar.gz には付けられない */
    static void create(List<File> sources, File out, boolean tarGz, char[] password)
            throws IOException {
        if (password != null && password.length > 0) {
            if (tarGz) throw new IOException("パスワードを付けられるのは zip だけです");
            ZipCrypt.create(sources, out, password);
            return;
        }
        byte[] buf = new byte[65536];
        int[] count = {0};
        if (tarGz) {
            try (OutputStream os = new java.util.zip.GZIPOutputStream(
                    new java.io.BufferedOutputStream(new FileOutputStream(out), 65536))) {
                for (File f : sources) addTar(os, f, f.getName(), out, buf, count);
                os.write(new byte[BLOCK * 2]); // 終わりの印
            }
        } else {
            try (java.util.zip.ZipOutputStream zs = new java.util.zip.ZipOutputStream(
                    new java.io.BufferedOutputStream(new FileOutputStream(out), 65536))) {
                for (File f : sources) addZip(zs, f, f.getName(), out, buf, count);
            }
        }
    }

    private static File[] children(File dir) throws IOException {
        File[] kids = dir.listFiles();
        if (kids == null) throw new IOException("読み込めませんでした: " + dir.getName());
        java.util.Arrays.sort(kids);
        return kids;
    }

    private static void addZip(java.util.zip.ZipOutputStream zs, File f, String name, File out,
                               byte[] buf, int[] count) throws IOException {
        if (isSymlink(f) || f.equals(out)) return;
        if (++count[0] > MAX_ENTRIES) throw new IOException("ファイルの数が多すぎます");
        if (f.isDirectory()) {
            ZipEntry e = new ZipEntry(name + "/");
            e.setTime(f.lastModified());
            zs.putNextEntry(e);
            zs.closeEntry();
            for (File k : children(f)) addZip(zs, k, name + "/" + k.getName(), out, buf, count);
        } else if (f.isFile()) {
            ZipEntry e = new ZipEntry(name);
            e.setTime(f.lastModified());
            zs.putNextEntry(e);
            try (InputStream in = new FileInputStream(f)) {
                int n;
                while ((n = in.read(buf)) > 0) zs.write(buf, 0, n);
            }
            zs.closeEntry();
        }
    }

    private static void addTar(OutputStream os, File f, String name, File out, byte[] buf,
                               int[] count) throws IOException {
        if (isSymlink(f) || f.equals(out)) return;
        if (++count[0] > MAX_ENTRIES) throw new IOException("ファイルの数が多すぎます");
        long mtime = f.lastModified() / 1000;
        if (f.isDirectory()) {
            tarHeader(os, name + "/", 0, 0755, mtime, '5');
            for (File k : children(f)) addTar(os, k, name + "/" + k.getName(), out, buf, count);
        } else if (f.isFile()) {
            // 書いている途中で大きさが変わると tar が壊れるので、最初に見た大きさぶんだけ書く
            long size = f.length();
            tarHeader(os, name, size, f.canExecute() ? 0755 : 0644, mtime, '0');
            long left = size;
            try (InputStream in = new FileInputStream(f)) {
                while (left > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n <= 0) break;
                    os.write(buf, 0, n);
                    left -= n;
                }
            }
            if (left > 0) throw new IOException("読み込めませんでした: " + f.getName());
            os.write(new byte[(int) pad(size)]);
        }
    }

    /** tar のヘッダ 1 個を書く。100 バイトを超える名前は、GNU 形式の「長い名前」を先に付ける */
    private static void tarHeader(OutputStream os, String name, long size, int mode, long mtime,
                                  char type) throws IOException {
        byte[] nb = name.getBytes(StandardCharsets.UTF_8);
        if (nb.length > 100) {
            tarHeader(os, "././@LongLink", nb.length + 1, 0644, 0, 'L');
            os.write(nb);
            os.write(new byte[1 + (int) pad(nb.length + 1)]);
        }
        byte[] h = new byte[BLOCK];
        System.arraycopy(nb, 0, h, 0, Math.min(nb.length, 100));
        octal(h, 100, 8, mode);
        octal(h, 108, 8, 0);
        octal(h, 116, 8, 0);
        if (size < 077777777777L) {
            octal(h, 124, 12, size);
        } else {
            // 8GB 以上は 8 進数に収まらないので、先頭ビットを立てた 2 進数で書く
            h[124] = (byte) 0x80;
            for (int i = 0; i < 8; i++) h[135 - i] = (byte) (size >>> (8 * i));
        }
        octal(h, 136, 12, Math.max(0, mtime));
        h[156] = (byte) type;
        byte[] magic = {'u', 's', 't', 'a', 'r', 0, '0', '0'};
        System.arraycopy(magic, 0, h, 257, magic.length);
        java.util.Arrays.fill(h, 148, 156, (byte) ' ');
        long sum = 0;
        for (byte b : h) sum += b & 255;
        octal(h, 148, 7, sum);
        os.write(h);
    }

    /** len - 1 桁の 8 進数と終わりの NUL を書く */
    private static void octal(byte[] h, int off, int len, long v) {
        for (int i = len - 2; i >= 0; i--) {
            h[off + i] = (byte) ('0' + (v & 7));
            v >>>= 3;
        }
        h[off + len - 1] = 0;
    }

    /** 名前から見て、解凍の対象にできそうなファイルか (中身は解凍するときに確かめる) */
    static boolean looksLikeArchive(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return !stripExtension(n).equals(n) || n.endsWith(".apk") || n.endsWith(".jar");
    }

    // ------------------------------------------------------ 起動するファイルの候補

    /**
     * 展開した中から、起動できそうなファイル (ELF の実行ファイルとスクリプト) を、それらしい順に並べる。
     * この端末の CPU 用であること、実行可能の印、浅い場所にあること、圧縮ファイルと同じ名前であることを
     * 手がかりにする。
     */
    static List<Entry> candidates(Result r, String archiveName, String[] supportedAbis) {
        final String base = stripExtension(archiveName).toLowerCase(java.util.Locale.ROOT);
        final List<Entry> out = new ArrayList<>();
        final List<Integer> scores = new ArrayList<>();
        for (Entry e : r.files) {
            ElfInfo info;
            try {
                info = ElfInfo.read(e.file);
            } catch (IOException ex) {
                continue;
            }
            int score;
            switch (info.kind) {
                case BIONIC_DYNAMIC:
                case STATIC:
                case FOREIGN_LIBC:
                    score = info.cpuMatches(supportedAbis) ? 100 : 0;
                    break;
                case SCRIPT:
                    score = 60;
                    break;
                default:
                    continue;
            }
            if (e.execBit) score += 30;
            String leaf = e.path.substring(e.path.lastIndexOf('/') + 1)
                    .toLowerCase(java.util.Locale.ROOT);
            int depth = 0;
            for (int i = 0; i < e.path.length(); i++) if (e.path.charAt(i) == '/') depth++;
            score -= depth * 5;
            String stem = leaf.contains(".") ? leaf.substring(0, leaf.lastIndexOf('.')) : leaf;
            if (!stem.isEmpty() && (base.equals(stem) || base.startsWith(stem))) score += 25;
            if (e.path.contains("bin/")) score += 5;
            // 設定やビルド用の補助スクリプトは、たいてい本体ではない
            if (leaf.startsWith("install") || leaf.startsWith("uninstall")
                    || leaf.startsWith("configure") || leaf.equals("gradlew")) score -= 40;
            int at = 0;
            while (at < out.size() && scores.get(at) >= score) at++;
            out.add(at, e);
            scores.add(at, score);
        }
        return out;
    }

    // ------------------------------------------------------------ 登録済みの一式

    static File pkgRoot(File filesDir) {
        return new File(filesDir, "pkg");
    }

    /** 登録名 name の一式の置き場所 (無くてもパスは返す) */
    static File pkgDir(File filesDir, String name) {
        return new File(pkgRoot(filesDir), name);
    }

    /** これから展開する先。登録するまでの仮の場所で、登録先と同じ記憶領域に作る (移動で済ませるため) */
    static File newStaging(File filesDir) throws IOException {
        File d = new File(pkgRoot(filesDir), ".incoming-" + System.nanoTime());
        if (!d.mkdirs()) throw new IOException("展開先を作れません");
        return d;
    }

    /** 登録されずに残った仮の展開先を消す (展開の途中でアプリが止められた場合の後始末) */
    static void cleanStaging(File filesDir) {
        File[] kids = pkgRoot(filesDir).listFiles();
        if (kids == null) return;
        for (File k : kids) if (k.getName().startsWith(".incoming-")) deleteTree(k);
    }

    /** 登録名 name が圧縮ファイルから展開した一式なら、起動するファイルを返す。違えば null */
    static File packageExe(File filesDir, String name) {
        File dir = pkgDir(filesDir, name);
        File meta = new File(dir, "exe");
        if (!meta.isFile()) return null;
        try (InputStream in = new FileInputStream(meta)) {
            byte[] b = new byte[4096];
            int n = readFully(in, b);
            String rel = cleanPath(new String(b, 0, n, StandardCharsets.UTF_8).trim());
            if (rel == null) return null;
            File exe = new File(new File(dir, "root"), rel);
            return exe.isFile() ? exe : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** 登録名 name の一式を消す (無ければ何もしない) */
    static void deletePackage(File filesDir, String name) {
        File dir = pkgDir(filesDir, name);
        if (dir.exists()) deleteTree(dir);
    }

    /** 一式の合計の大きさ */
    static long treeSize(File f) {
        if (isSymlink(f)) return 0;
        File[] kids = f.listFiles();
        if (kids == null) return f.length();
        long n = 0;
        for (File k : kids) n += treeSize(k);
        return n;
    }

    /**
     * 仮の展開先を files/pkg/<name> へ移して登録する。戻り値は移動後の「起動するファイル」。
     * root のシェル (別の利用者) からも読めるよう、全体に読み取りを、実行ファイルには実行を許可する。
     */
    static File install(File filesDir, Pkg pkg, String name) throws IOException {
        File dir = pkgDir(filesDir, name);
        deleteTree(dir);
        if (!pkg.staging.renameTo(dir)) throw new IOException("ファイルを移動できません");
        pkg.staging = dir;
        File root = new File(dir, "root");
        try (OutputStream os = new FileOutputStream(new File(dir, "exe"))) {
            os.write(pkg.exe.path.getBytes(StandardCharsets.UTF_8));
        }
        openUp(pkgRoot(filesDir));
        openUp(dir);
        allowAll(root);
        File exe = new File(root, pkg.exe.path);
        exe.setExecutable(true, false);
        return exe;
    }

    private static void openUp(File dir) {
        dir.setReadable(true, false);
        dir.setExecutable(true, false);
    }

    private static void allowAll(File f) {
        if (isSymlink(f)) return;
        File[] kids = f.listFiles();
        if (kids == null) {
            f.setReadable(true, false);
            // 一式の中の別のプログラムを本体が呼び出すことがあるので、実行できるものはすべて許可する
            try {
                ElfInfo info = ElfInfo.read(f);
                if (info.isElf() || info.kind == ElfInfo.Kind.SCRIPT) f.setExecutable(true, false);
            } catch (IOException ignored) {
                // 読めないものはそのまま
            }
            return;
        }
        openUp(f);
        for (File k : kids) allowAll(k);
    }

    static void deleteTree(File f) {
        if (!isSymlink(f)) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        f.delete();
    }

    /**
     * bin に置く起動スクリプト。登録名をコマンドとして打つと、展開した中の本体が起動する。
     * glibcLines は glibc 用のときの起動部分 ({@link Glibc#launchLines})、それ以外は null。
     */
    static String launcherScript(ElfInfo info, File exe, boolean cd, String glibcLines) {
        String real = MainActivity.sq(exe.getAbsolutePath());
        StringBuilder sb = new StringBuilder("#!/system/bin/sh\n"
                + "# Bionic Runner: 圧縮ファイルから展開したプログラムの起動スクリプト (自動生成)\n");
        if (cd) {
            sb.append("cd ").append(MainActivity.sq(exe.getParentFile().getAbsolutePath()))
                    .append(" || exit 126\n");
        }
        if (glibcLines != null) {
            sb.append(glibcLines);
            return sb.toString();
        }
        // root が無いと、アプリのデータ領域にあるファイルは直接 exec できない (Android 10 以降)。
        // システムのローダやシェルに読み込ませる形なら起動できる。
        sb.append("if [ \"$(id -u)\" = 0 ]; then\n  exec ").append(real).append(" \"$@\"\nfi\n");
        if (info.kind == ElfInfo.Kind.SCRIPT) {
            sb.append("exec sh ").append(real).append(" \"$@\"\n");
        } else if (info.kind == ElfInfo.Kind.BIONIC_DYNAMIC) {
            sb.append("exec ").append(info.is64 ? "/system/bin/linker64 " : "/system/bin/linker ")
                    .append(real).append(" \"$@\"\n");
        } else {
            sb.append("exec ").append(real).append(" \"$@\"\n");
        }
        return sb.toString();
    }

    /** 候補の並びを画面に出すための一覧 */
    static List<String> paths(List<Entry> entries) {
        List<String> out = new ArrayList<>();
        for (Entry e : entries) out.add(e.path);
        return Collections.unmodifiableList(out);
    }
}
