package site.springcat.bionicrunner;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * ウイルスの定義 (ClamAV の公式のデータベース main.cvd・daily.cvd)。
 *
 * - 「定義の更新」で ClamAV の公式の配布元 (database.clamav.net) から取り、ファイルの頭に書かれた
 *   MD5 で中身が壊れていないことを確かめる (ClamAV のエンジンも、読み込むときに署名を確かめる)。
 * - 取った定義から「ファイル全体のハッシュ値」の定義 (*.hdb・*.hsb、約 50 万件) を抜き出して、
 *   小さな索引 (files/av/index) を作る。リアルタイム保護はこの索引で、メモリをほとんど使わずに
 *   1 ファイルずつ照合する (ClamAV のエンジンを毎回読み込むと 1GB 近く使うため)。
 */
final class AvDefs {

    static final String[] DATABASES = {"daily", "main"};
    private static final String BASE = "https://database.clamav.net/";
    /** 配布元は ClamAV の形式の名乗り (User-Agent) でないと受け付けない */
    private static final String USER_AGENT = "ClamAV/1.5.4 (OS: android, ARCH: "
            + System.getProperty("os.arch") + ") BionicRunner";

    /** 索引の 1 件の大きさ: ハッシュ値 + 大きさ (int、-1 は問わない) + 名前の番号 (int) */
    private static final int[] HASH_LEN = {16, 20, 32};
    private static final String[] HASH_FILE = {"md5.idx", "sha1.idx", "sha256.idx"};

    private AvDefs() {
    }

    static File dir(Context c) {
        return new File(c.getFilesDir(), "av");
    }

    static File dbDir(Context c) {
        return new File(dir(c), "db");
    }

    private static File indexDir(Context c) {
        return new File(dir(c), "index");
    }

    static File cvd(Context c, String name) {
        return new File(dbDir(c), name + ".cvd");
    }

    /** 定義があるか (両方そろっているか) */
    static boolean ready(Context c) {
        for (String n : DATABASES) if (!cvd(c, n).isFile()) return false;
        return new File(indexDir(c), "names.txt").isFile();
    }

    /** CVD の頭 (512 バイト) の中身: 日付・版・定義の数・中身の MD5 */
    static final class Header {
        String date;
        int version;
        int signatures;
        String md5;

        static Header parse(byte[] h) throws IOException {
            String s = new String(h, 0, Math.min(512, h.length), StandardCharsets.US_ASCII).trim();
            String[] f = s.split(":");
            if (f.length < 6 || !f[0].equals("ClamAV-VDB")) throw new IOException("定義のファイルではありません");
            Header r = new Header();
            r.date = f[1];
            try {
                r.version = Integer.parseInt(f[2]);
                r.signatures = Integer.parseInt(f[3]);
            } catch (NumberFormatException e) {
                throw new IOException("定義のファイルの頭が壊れています");
            }
            r.md5 = f[5].toLowerCase(Locale.ROOT);
            if (!r.md5.matches("[0-9a-f]{32}")) throw new IOException("定義のファイルの頭が壊れています");
            return r;
        }
    }

    static Header localHeader(Context c, String name) {
        File f = cvd(c, name);
        if (!f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            byte[] h = new byte[512];
            if (readFully(in, h) < 512) return null;
            return Header.parse(h);
        } catch (IOException e) {
            return null;
        }
    }

    /** 定義の数の合計 (表示用) */
    static int signatureCount(Context c) {
        int n = 0;
        for (String d : DATABASES) {
            Header h = localHeader(c, d);
            if (h != null) n += h.signatures;
        }
        return n;
    }

    // ------------------------------------------------------------------ 更新

    /**
     * 定義を新しくする。新しい版があるものだけを取ってくる。何か変わったら索引を作り直す。
     * 戻り値は結果の説明。時間がかかるので UI スレッド以外で呼ぶ
     */
    static String update(Context c, Vm.Progress progress) throws IOException {
        File db = dbDir(c);
        if (!db.isDirectory() && !db.mkdirs()) throw new IOException("保存先を作れません");
        boolean changed = false;
        StringBuilder msg = new StringBuilder();
        for (String name : DATABASES) {
            Header local = localHeader(c, name);
            progress.update(name + ".cvd の版を確認しています…", 0);
            Header remote = remoteHeader(name);
            if (local != null && remote != null && remote.version <= local.version) {
                msg.append(name).append(": 最新 (版 ").append(local.version).append(")\n");
                continue;
            }
            download(c, name, progress);
            Header now = localHeader(c, name);
            msg.append(name).append(": 版 ").append(now == null ? "?" : now.version).append(" に更新\n");
            changed = true;
        }
        if (changed || !ready(c)) {
            progress.update("検査用の索引を作っています…", 100);
            buildIndex(c);
        }
        Prefs.setAvUpdated(c, System.currentTimeMillis());
        return msg.toString().trim();
    }

    private static HttpURLConnection open(String url, boolean head) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(20_000);
        con.setReadTimeout(60_000);
        con.setRequestProperty("User-Agent", USER_AGENT);
        if (head) con.setRequestProperty("Range", "bytes=0-511");
        return con;
    }

    /** 配布元の版を、ファイルの頭 (512 バイト) だけ取って調べる。分からなければ null */
    private static Header remoteHeader(String name) {
        try {
            HttpURLConnection con = open(BASE + name + ".cvd", true);
            try {
                int code = con.getResponseCode();
                if (code != 206 && code != 200) return null;
                try (InputStream in = con.getInputStream()) {
                    byte[] h = new byte[512];
                    if (readFully(in, h) < 512) return null;
                    return Header.parse(h);
                }
            } finally {
                con.disconnect();
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static void download(Context c, String name, Vm.Progress progress) throws IOException {
        File part = new File(dbDir(c), name + ".cvd.part");
        HttpURLConnection con = open(BASE + name + ".cvd", false);
        MessageDigest md5;
        try {
            md5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e.getMessage());
        }
        Header header;
        try {
            int code = con.getResponseCode();
            if (code == 403 || code == 429) {
                throw new IOException("配布元に断られました (HTTP " + code + ")。短い時間に何度も更新すると"
                        + "一時的に断られます。しばらくしてから試してください");
            }
            if (code != 200) throw new IOException("ダウンロードできませんでした (HTTP " + code + ")");
            long size = con.getContentLengthLong();
            long got = 0;
            int last = -1;
            try (InputStream in = con.getInputStream(); OutputStream os = new FileOutputStream(part)) {
                byte[] h = new byte[512];
                if (readFully(in, h) < 512) throw new IOException("定義のファイルが途中で切れています");
                header = Header.parse(h);
                os.write(h);
                got = 512;
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    md5.update(buf, 0, n);
                    got += n;
                    int p = size > 0 ? (int) (got * 100 / size) : 0;
                    if (p != last) {
                        last = p;
                        progress.update(name + ".cvd をダウンロード中… " + (got / 1024 / 1024) + "MB"
                                + (size > 0 ? " / " + (size / 1024 / 1024) + "MB" : ""), p);
                    }
                }
            }
        } catch (IOException e) {
            part.delete();
            throw e;
        } finally {
            con.disconnect();
        }
        if (!hex(md5.digest()).equals(header.md5)) {
            part.delete();
            throw new IOException(name + ".cvd が壊れているか、すり替えられています (MD5 が一致しません)");
        }
        File dst = cvd(c, name);
        dst.delete();
        if (!part.renameTo(dst)) throw new IOException("保存できません");
    }

    // ------------------------------------------------------------------ 索引

    /**
     * 定義の中の「ファイル全体のハッシュ値」から索引を作る。メモリを節約するため、行を読みながら
     * そのまま索引の項目 (小さなバイト列) にし、名前はすぐファイルへ書き出す
     */
    static void buildIndex(Context c) throws IOException {
        List<List<byte[]>> recs = new ArrayList<>();
        for (int i = 0; i < 3; i++) recs.add(new ArrayList<>());
        Set<String> ignored = new HashSet<>();
        File tmp = new File(dir(c), "index.new");
        Archive.deleteTree(tmp);
        if (!tmp.mkdirs()) throw new IOException("索引を作れません");
        int nextId = 0;
        // 無視する定義の名前 (*.ign2) は daily にあるので、daily を先に読む (DATABASES の順)
        try (OutputStream names = new java.io.BufferedOutputStream(
                new FileOutputStream(new File(tmp, "names.txt")), 65536)) {
            for (String db : DATABASES) {
                File f = cvd(c, db);
                if (!f.isFile()) continue;
                try (InputStream raw = new FileInputStream(f)) {
                    if (raw.skip(512) != 512) throw new IOException("定義のファイルが壊れています");
                    TarReader tar = new TarReader(new GZIPInputStream(new BufferedInputStream(raw, 65536), 65536));
                    String entry;
                    while ((entry = tar.next()) != null) {
                        String ext = entry.substring(entry.lastIndexOf('.') + 1);
                        boolean ign = ext.equals("ign2") || ext.equals("ign");
                        if (!ext.equals("hdb") && !ext.equals("hsb") && !ign) continue;
                        BufferedReader r = new BufferedReader(new InputStreamReader(tar.stream(), StandardCharsets.UTF_8), 65536);
                        String line;
                        while ((line = r.readLine()) != null) {
                            if (line.isEmpty() || line.charAt(0) == '#') continue;
                            if (ign) {
                                String[] p = line.trim().split(":");
                                ignored.add(p.length >= 3 ? p[2] : p[0]);
                                continue;
                            }
                            int c1 = line.indexOf(':'), c2 = c1 < 0 ? -1 : line.indexOf(':', c1 + 1);
                            if (c2 < 0) continue;
                            int c3 = line.indexOf(':', c2 + 1);
                            String name = c3 < 0 ? line.substring(c2 + 1) : line.substring(c2 + 1, c3);
                            if (ignored.contains(name)) continue;
                            int algo = c1 == 32 ? 0 : c1 == 40 ? 1 : c1 == 64 ? 2 : -1;
                            if (algo < 0) continue;
                            int size;
                            if (c2 - c1 == 2 && line.charAt(c1 + 1) == '*') {
                                size = -1;
                            } else {
                                try {
                                    size = Integer.parseInt(line.substring(c1 + 1, c2));
                                } catch (NumberFormatException e) {
                                    continue;
                                }
                            }
                            int len = HASH_LEN[algo];
                            byte[] rec = new byte[len + 8];
                            boolean ok = true;
                            for (int i = 0; i < len && ok; i++) {
                                int hi = Character.digit(line.charAt(i * 2), 16), lo = Character.digit(line.charAt(i * 2 + 1), 16);
                                if (hi < 0 || lo < 0) ok = false;
                                rec[i] = (byte) (hi << 4 | lo);
                            }
                            if (!ok) continue;
                            putInt(rec, len, size);
                            putInt(rec, len + 4, nextId++);
                            names.write((name + "\n").getBytes(StandardCharsets.UTF_8));
                            recs.get(algo).add(rec);
                        }
                    }
                }
            }
        }
        for (int a = 0; a < 3; a++) {
            final int len = HASH_LEN[a];
            List<byte[]> list = recs.get(a);
            Collections.sort(list, (x, y) -> compare(x, y, len));
            try (OutputStream os = new java.io.BufferedOutputStream(new FileOutputStream(new File(tmp, HASH_FILE[a])), 65536)) {
                for (byte[] r : list) os.write(r);
            }
            list.clear();
        }
        File idx = indexDir(c);
        synchronized (AvDefs.class) {
            closeIndex();
            Archive.deleteTree(idx);
            if (!tmp.renameTo(idx)) throw new IOException("索引を保存できません");
        }
    }

    private static int compare(byte[] x, byte[] y, int len) {
        for (int i = 0; i < len; i++) {
            int d = (x[i] & 255) - (y[i] & 255);
            if (d != 0) return d;
        }
        return 0;
    }

    // ------------------------------------------------------------------ 照合

    private static MappedByteBuffer[] maps;

    private static synchronized void closeIndex() {
        maps = null;
    }

    private static synchronized MappedByteBuffer[] maps(Context c) throws IOException {
        if (maps != null) return maps;
        File idx = indexDir(c);
        if (!new File(idx, "names.txt").isFile()) return null;
        MappedByteBuffer[] m = new MappedByteBuffer[3];
        for (int a = 0; a < 3; a++) {
            try (RandomAccessFile f = new RandomAccessFile(new File(idx, HASH_FILE[a]), "r")) {
                m[a] = f.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, f.length());
            }
        }
        maps = m;
        return m;
    }

    /** id 番目の定義の名前。名前の一覧は大きい (約 15MB) ので、覚えておかずに、見つかったときだけ読む */
    private static String name(Context c, int id) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(new File(indexDir(c), "names.txt")), StandardCharsets.UTF_8), 65536)) {
            String line;
            for (int i = 0; (line = r.readLine()) != null; i++) if (i == id) return line;
        } catch (IOException ignored) {
            // 読めない
        }
        return "?";
    }

    /**
     * ファイル全体のハッシュ値 (MD5・SHA-1・SHA-256) を定義と照合する。一致した定義の名前を返す
     * (一致しなければ null、定義が無ければ null)
     */
    static String lookup(Context c, byte[][] hashes, long size) {
        try {
            MappedByteBuffer[] m = maps(c);
            if (m == null) return null;
            for (int a = 0; a < 3; a++) {
                int id = search(m[a], HASH_LEN[a], hashes[a], size);
                if (id >= 0) return name(c, id);
            }
        } catch (IOException ignored) {
            // 索引が読めない: 照合できなかった扱い
        }
        return null;
    }

    private static int search(MappedByteBuffer buf, int len, byte[] key, long size) {
        int rec = len + 8;
        int lo = 0, hi = buf.capacity() / rec - 1;
        byte[] cur = new byte[len];
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            synchronized (buf) {
                buf.position(mid * rec);
                buf.get(cur);
            }
            int d = compare(cur, key, len);
            if (d < 0) {
                lo = mid + 1;
            } else if (d > 0) {
                hi = mid - 1;
            } else {
                // 同じハッシュ値の項目が並んでいることがあるので、前後も見る
                for (int i = mid; i >= 0 && match(buf, i, rec, len, key); i--) {
                    int s = buf.getInt(i * rec + len);
                    if (s < 0 || s == size) return buf.getInt(i * rec + len + 4);
                }
                for (int i = mid + 1; i < buf.capacity() / rec && match(buf, i, rec, len, key); i++) {
                    int s = buf.getInt(i * rec + len);
                    if (s < 0 || s == size) return buf.getInt(i * rec + len + 4);
                }
                return -1;
            }
        }
        return -1;
    }

    private static boolean match(MappedByteBuffer buf, int i, int rec, int len, byte[] key) {
        for (int k = 0; k < len; k++) if (buf.get(i * rec + k) != key[k]) return false;
        return true;
    }

    /** f の MD5・SHA-1・SHA-256 をまとめて計算する */
    static byte[][] hashes(File f) throws IOException {
        MessageDigest[] md = new MessageDigest[3];
        try {
            md[0] = MessageDigest.getInstance("MD5");
            md[1] = MessageDigest.getInstance("SHA-1");
            md[2] = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e.getMessage());
        }
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) for (MessageDigest d : md) d.update(buf, 0, n);
        }
        return new byte[][]{md[0].digest(), md[1].digest(), md[2].digest()};
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    private static void putInt(byte[] b, int at, int v) {
        b[at] = (byte) (v >> 24);
        b[at + 1] = (byte) (v >> 16);
        b[at + 2] = (byte) (v >> 8);
        b[at + 3] = (byte) v;
    }

    static int readFully(InputStream in, byte[] b) throws IOException {
        int got = 0;
        while (got < b.length) {
            int n = in.read(b, got, b.length - got);
            if (n <= 0) break;
            got += n;
        }
        return got;
    }

    /** CVD の中の tar を 1 項目ずつ読む小さな読み取り器 */
    private static final class TarReader {
        private final InputStream in;
        private long left, pad;

        TarReader(InputStream in) {
            this.in = in;
        }

        String next() throws IOException {
            skip(left + pad);
            left = pad = 0;
            byte[] h = new byte[512];
            while (true) {
                if (readFully(in, h) < 512) return null;
                boolean zero = true;
                for (byte b : h) if (b != 0) zero = false;
                if (zero) return null;
                int end = 0;
                while (end < 100 && h[end] != 0) end++;
                String name = new String(h, 0, end, StandardCharsets.US_ASCII);
                String sz = new String(h, 124, 12, StandardCharsets.US_ASCII).replace("\0", "").trim();
                long size = sz.isEmpty() ? 0 : Long.parseLong(sz, 8);
                left = size;
                pad = (512 - size % 512) % 512;
                if (h[156] == '0' || h[156] == 0) return name;
                skip(left + pad);
                left = pad = 0;
            }
        }

        InputStream stream() {
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    if (left <= 0) return -1;
                    int b = in.read();
                    if (b >= 0) left--;
                    return b;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (left <= 0) return -1;
                    int n = in.read(b, off, (int) Math.min(len, left));
                    if (n > 0) left -= n;
                    return n;
                }
            };
        }

        private void skip(long n) throws IOException {
            byte[] buf = new byte[8192];
            while (n > 0) {
                int r = in.read(buf, 0, (int) Math.min(buf.length, n));
                if (r <= 0) throw new IOException("定義のファイルが途中で切れています");
                n -= r;
            }
        }
    }
}
