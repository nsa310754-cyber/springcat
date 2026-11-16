package site.springcat.bionicrunner;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * パスワード付き zip の読み書き。Java 標準の zip の機能は暗号化に対応していないので、
 * zip の構造を自前で読み書きし、暗号の計算だけ標準の機能 (AES・HMAC-SHA1) を使う。
 *
 * 書くときは WinZip と同じ方式の AES-256 (AE-2) を使う。WinZip・7-Zip・Windows 11 のエクスプローラー
 * などで開ける。読むときは AES (128 / 192 / 256) と、昔からある弱い方式 (ZipCrypto) の両方に対応する。
 * Android の API は使っていないので、PC 上の JVM でも単体で検証できる。
 */
final class ZipCrypt {

    /** パスワードが必要 (渡されていない)、または違っている */
    static final class PasswordException extends IOException {
        /** 渡したパスワードが違っていた (偽なら、そもそも渡されていない) */
        final boolean wrong;

        PasswordException(boolean wrong) {
            super(wrong ? "パスワードが違います" : "パスワードが必要です");
            this.wrong = wrong;
        }
    }

    /** zip の中の 1 項目 */
    static final class ZEntry {
        String name;
        boolean dir;
        boolean encrypted;
        /** AES で暗号化されている (偽で encrypted なら ZipCrypto) */
        boolean aes;
        /** AES の鍵の長さ (1 = 128、2 = 192、3 = 256 ビット) と版 (1 = AE-1、2 = AE-2) */
        int aesStrength, aesVersion;
        /** 圧縮の方式 (0 = 無圧縮、8 = deflate)。AES のときは、暗号の下にある本当の方式 */
        int method;
        int flags;
        int dosTime;
        long crc, csize, usize, offset;
        /** Unix で作られた zip なら権限 (実行できる印の判定に使う)。分からなければ 0 */
        int mode;
    }

    private static final int SIG_LOCAL = 0x04034b50;
    private static final int SIG_CENTRAL = 0x02014b50;
    private static final int SIG_END = 0x06054b50;
    private static final int SIG_END64 = 0x06064b50;
    private static final int SIG_LOCATOR64 = 0x07064b50;
    private static final int AES_EXTRA = 0x9901;
    private static final int PBKDF2_ROUNDS = 1000;
    private static final long MAX32 = 0xFFFFFFFFL;

    private ZipCrypt() {
    }

    // ------------------------------------------------------------------ 読む

    /** zip の目次 (末尾にある一覧) を読む */
    static List<ZEntry> list(File file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long len = raf.length();
            // 末尾の「終わりの記録」を後ろから探す (後ろに最大 64KB のコメントが付きうる)
            int tail = (int) Math.min(len, 65557);
            byte[] t = new byte[tail];
            raf.seek(len - tail);
            raf.readFully(t);
            int at = -1;
            for (int i = tail - 22; i >= 0; i--) {
                if (u32(t, i) == (SIG_END & MAX32)) {
                    at = i;
                    break;
                }
            }
            if (at < 0) throw new IOException("zip の目次が見つかりません (壊れています)");
            long count = u16(t, at + 10);
            long dirSize = u32(t, at + 12);
            long dirOffset = u32(t, at + 16);
            long endPos = len - tail + at;
            if (count == 0xFFFF || dirSize == MAX32 || dirOffset == MAX32) {
                // 4GB や 65535 個を超える zip (zip64): 本当の値は別の記録にある
                if (endPos < 20) throw new IOException("zip が壊れています");
                byte[] loc = new byte[20];
                raf.seek(endPos - 20);
                raf.readFully(loc);
                if (u32(loc, 0) != (SIG_LOCATOR64 & MAX32)) throw new IOException("zip が壊れています");
                byte[] e64 = new byte[56];
                raf.seek(u64(loc, 8));
                raf.readFully(e64);
                if (u32(e64, 0) != (SIG_END64 & MAX32)) throw new IOException("zip が壊れています");
                count = u64(e64, 32);
                dirSize = u64(e64, 40);
                dirOffset = u64(e64, 48);
            }
            if (dirOffset < 0 || dirSize < 0 || dirOffset + dirSize > len
                    || dirSize > 256L * 1024 * 1024 || count > Archive.MAX_ENTRIES) {
                throw new IOException("zip の目次が大きすぎるか、壊れています");
            }
            byte[] d = new byte[(int) dirSize];
            raf.seek(dirOffset);
            raf.readFully(d);
            List<ZEntry> out = new ArrayList<>();
            int p = 0;
            for (long i = 0; i < count; i++) {
                if (p + 46 > d.length || u32(d, p) != (SIG_CENTRAL & MAX32)) {
                    throw new IOException("zip の目次が壊れています");
                }
                ZEntry e = new ZEntry();
                int madeBy = u16(d, p + 4);
                e.flags = u16(d, p + 8);
                e.method = u16(d, p + 10);
                e.dosTime = (int) u32(d, p + 12);
                e.crc = u32(d, p + 16);
                e.csize = u32(d, p + 20);
                e.usize = u32(d, p + 24);
                int nameLen = u16(d, p + 28), extraLen = u16(d, p + 30), commentLen = u16(d, p + 32);
                long attrs = u32(d, p + 38);
                e.offset = u32(d, p + 42);
                if (p + 46 + nameLen + extraLen + commentLen > d.length) {
                    throw new IOException("zip の目次が壊れています");
                }
                // 名前は、印 (ビット 11) があれば UTF-8。無い古い zip も、まず UTF-8 として読む
                e.name = new String(d, p + 46, nameLen, StandardCharsets.UTF_8);
                e.dir = e.name.endsWith("/");
                e.encrypted = (e.flags & 1) != 0;
                if ((madeBy >> 8) == 3) e.mode = (int) (attrs >>> 16);
                int x = p + 46 + nameLen, xEnd = x + extraLen;
                while (x + 4 <= xEnd) {
                    int id = u16(d, x), size = u16(d, x + 2);
                    int body = x + 4;
                    if (body + size > xEnd) break;
                    if (id == 1) { // zip64: 32 ビットに収まらなかった値が、この順で入っている
                        int q = body;
                        if (e.usize == MAX32 && q + 8 <= body + size) {
                            e.usize = u64(d, q);
                            q += 8;
                        }
                        if (e.csize == MAX32 && q + 8 <= body + size) {
                            e.csize = u64(d, q);
                            q += 8;
                        }
                        if (e.offset == MAX32 && q + 8 <= body + size) e.offset = u64(d, q);
                    } else if (id == AES_EXTRA && size >= 7) {
                        e.aes = true;
                        e.aesVersion = u16(d, body);
                        e.aesStrength = d[body + 4] & 255;
                        e.method = u16(d, body + 5);
                    }
                    x = body + size;
                }
                if (e.csize < 0 || e.usize < 0 || e.offset < 0 || e.offset > len) {
                    throw new IOException("zip の目次が壊れています");
                }
                out.add(e);
                p += 46 + nameLen + extraLen + commentLen;
            }
            return out;
        }
    }

    /** 暗号化された項目が 1 つでもあるか */
    static boolean isEncrypted(List<ZEntry> entries) {
        for (ZEntry e : entries) if (e.encrypted) return true;
        return false;
    }

    /**
     * 項目の中身 (伸長・復号したもの) を読む流れを開く。パスワードが要るのに無い、または違うときは
     * {@link PasswordException}。中身の検査 (AES は改ざん検出の符号、それ以外は CRC) は、
     * 最後まで読んだところで行い、合わなければ IOException にする。
     */
    static InputStream open(File file, ZEntry e, char[] password) throws IOException {
        if (e.encrypted && (password == null || password.length == 0)) {
            throw new PasswordException(false);
        }
        if (e.method != 0 && e.method != 8) {
            throw new IOException("対応していない圧縮方式です: " + e.name);
        }
        if ((e.flags & 0x40) != 0 || (e.encrypted && !e.aes && (e.flags & 0x2000) != 0)) {
            throw new IOException("対応していない暗号方式です: " + e.name);
        }
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        try {
            byte[] h = new byte[30];
            raf.seek(e.offset);
            raf.readFully(h);
            if (u32(h, 0) != (SIG_LOCAL & MAX32)) throw new IOException("zip が壊れています: " + e.name);
            long data = e.offset + 30 + u16(h, 26) + u16(h, 28);
            if (data + e.csize > raf.length()) throw new IOException("zip が途中で切れています");
            raf.seek(data);
            InputStream raw = new BufferedInputStream(new Slice(raf, e.csize), 65536);
            InputStream plain;
            AesIn aes = null;
            long expectCrc = e.crc;
            if (!e.encrypted) {
                plain = raw;
            } else if (e.aes) {
                plain = aes = new AesIn(raw, e, password);
                if (e.aesVersion == 2) expectCrc = -1; // AE-2 は CRC を記録しない (符号で確かめる)
            } else {
                plain = new ZipCryptoIn(raw, e, password);
            }
            InputStream body = e.method == 8
                    ? new InflaterInputStream(plain, new Inflater(true), 65536) : plain;
            return new Checked(body, expectCrc, e, aes);
        } catch (IOException | RuntimeException ex) {
            raf.close();
            throw ex;
        }
    }

    /** ファイルの一部分だけを読む流れ。閉じるとファイルも閉じる */
    private static final class Slice extends InputStream {
        private final RandomAccessFile raf;
        private long left;

        Slice(RandomAccessFile raf, long length) {
            this.raf = raf;
            this.left = length;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            return read(b, 0, 1) <= 0 ? -1 : b[0] & 255;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = raf.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }

        @Override
        public void close() throws IOException {
            raf.close();
        }
    }

    /** 読み終わりに大きさと CRC を確かめる */
    private static final class Checked extends FilterInputStream {
        private final CRC32 crc = new CRC32();
        private final long expect;
        private final ZEntry entry;
        /** AES の項目なら、その復号の流れ (読み終わりに改ざん検出の符号を確かめさせる) */
        private final AesIn aes;
        private long total;
        private boolean done;

        Checked(InputStream in, long expectCrc, ZEntry entry, AesIn aes) {
            super(in);
            this.expect = expectCrc;
            this.entry = entry;
            this.aes = aes;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            return read(b, 0, 1) <= 0 ? -1 : b[0] & 255;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n;
            try {
                n = in.read(b, off, len);
            } catch (java.util.zip.ZipException ex) {
                // 弱い方式 (ZipCrypto) は、違うパスワードでも 256 回に 1 回は最初の確認を通ってしまう
                if (entry.encrypted && !entry.aes) throw new PasswordException(true);
                throw ex;
            }
            if (n > 0) {
                crc.update(b, off, n);
                total += n;
                // 目次に書かれた大きさより膨らむものは、容量を食いつぶすための細工とみなす
                if (total > entry.usize) throw new IOException("zip が壊れています: " + entry.name);
            } else if (n < 0 && !done) {
                done = true;
                if (total != entry.usize || (expect >= 0 && crc.getValue() != expect)) {
                    if (entry.encrypted && !entry.aes) throw new PasswordException(true);
                    throw new IOException("中身が壊れています: " + entry.name);
                }
                // 伸長は必要なぶんを読んだところで止まるので、符号の確認はここで明示的に行う
                if (aes != null) aes.verify();
            }
            return n;
        }
    }

    // ------------------------------------------------------------------- AES

    private static int keyBytes(int strength) throws IOException {
        if (strength < 1 || strength > 3) throw new IOException("対応していない暗号方式です");
        return 8 + strength * 8;
    }

    /** パスワードから、暗号の鍵・符号の鍵・パスワード確認用の 2 バイトを作る (PBKDF2-HMAC-SHA1) */
    private static byte[] deriveKeys(char[] password, byte[] salt, int keyLen) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            byte[] pw = new String(password).getBytes(StandardCharsets.UTF_8);
            mac.init(new SecretKeySpec(pw.length == 0 ? new byte[1] : pw, "HmacSHA1"));
            int want = keyLen * 2 + 2;
            byte[] out = new byte[want];
            int blocks = (want + 19) / 20;
            for (int i = 1; i <= blocks; i++) {
                mac.update(salt);
                mac.update(new byte[]{(byte) (i >>> 24), (byte) (i >>> 16), (byte) (i >>> 8), (byte) i});
                byte[] u = mac.doFinal();
                byte[] t = u.clone();
                for (int r = 1; r < PBKDF2_ROUNDS; r++) {
                    u = mac.doFinal(u);
                    for (int k = 0; k < t.length; k++) t[k] ^= u[k];
                }
                System.arraycopy(t, 0, out, (i - 1) * 20, Math.min(20, want - (i - 1) * 20));
            }
            return out;
        } catch (GeneralSecurityException e) {
            throw new IOException("この端末では暗号を使えません: " + e.getMessage());
        }
    }

    /** WinZip 方式の AES の計算 (カウンタを 1 から数える CTR モード。カウンタは下位バイトが先) */
    private static final class Ctr {
        private final Cipher aes;
        private final Mac mac;
        private final byte[] counter = new byte[16];
        private final byte[] pad = new byte[16];
        private int used = 16;

        Ctr(byte[] keys, int keyLen) throws IOException {
            try {
                aes = Cipher.getInstance("AES/ECB/NoPadding");
                aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keys, 0, keyLen, "AES"));
                mac = Mac.getInstance("HmacSHA1");
                mac.init(new SecretKeySpec(keys, keyLen, keyLen, "HmacSHA1"));
            } catch (GeneralSecurityException e) {
                throw new IOException("この端末では暗号を使えません: " + e.getMessage());
            }
        }

        private void xor(byte[] b, int off, int len) throws IOException {
            for (int i = 0; i < len; i++) {
                if (used == 16) {
                    for (int k = 0; k < 16; k++) {
                        if (++counter[k] != 0) break;
                    }
                    try {
                        aes.update(counter, 0, 16, pad, 0);
                    } catch (GeneralSecurityException e) {
                        throw new IOException(e.getMessage());
                    }
                    used = 0;
                }
                b[off + i] ^= pad[used++];
            }
        }

        /** 暗号化: 暗号文に対して符号を計算する */
        void encrypt(byte[] b, int off, int len) throws IOException {
            xor(b, off, len);
            mac.update(b, off, len);
        }

        void decrypt(byte[] b, int off, int len) throws IOException {
            mac.update(b, off, len);
            xor(b, off, len);
        }

        /** 改ざん検出の符号 (HMAC-SHA1 の先頭 10 バイト) */
        byte[] tag() {
            return Arrays.copyOf(mac.doFinal(), 10);
        }
    }

    private static final class AesIn extends InputStream {
        private final InputStream in;
        private final Ctr ctr;
        private long left;
        private boolean verified;

        AesIn(InputStream in, ZEntry e, char[] password) throws IOException {
            this.in = in;
            int keyLen = keyBytes(e.aesStrength);
            byte[] salt = new byte[keyLen / 2];
            byte[] check = new byte[2];
            left = e.csize - salt.length - 2 - 10;
            if (left < 0) throw new IOException("zip が壊れています: " + e.name);
            fill(in, salt);
            fill(in, check);
            byte[] keys = deriveKeys(password, salt, keyLen);
            if (keys[keyLen * 2] != check[0] || keys[keyLen * 2 + 1] != check[1]) {
                throw new PasswordException(true);
            }
            ctr = new Ctr(keys, keyLen);
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            return read(b, 0, 1) <= 0 ? -1 : b[0] & 255;
        }

        /** 残りを読み切って、改ざん検出の符号が合うか確かめる */
        void verify() throws IOException {
            byte[] skip = new byte[8192];
            while (left > 0) {
                if (read(skip, 0, skip.length) < 0) break;
            }
            if (verified) return;
            verified = true;
            byte[] tag = new byte[10];
            fill(in, tag);
            if (!java.security.MessageDigest.isEqual(tag, ctr.tag())) {
                throw new IOException("中身が書き換えられているか、壊れています");
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) {
                verify();
                return -1;
            }
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n < 0) throw new EOFException("zip が途中で切れています");
            ctr.decrypt(b, off, n);
            left -= n;
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    // ------------------------------------------------- 昔からある方式 (ZipCrypto)

    private static final int[] CRC_TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int k = 0; k < 8; k++) c = (c & 1) != 0 ? (c >>> 1) ^ 0xEDB88320 : c >>> 1;
            CRC_TABLE[i] = c;
        }
    }

    private static final class ZipCryptoIn extends InputStream {
        private final InputStream in;
        private int k0 = 0x12345678, k1 = 0x23456789, k2 = 0x34567890;

        ZipCryptoIn(InputStream in, ZEntry e, char[] password) throws IOException {
            this.in = in;
            for (byte b : new String(password).getBytes(StandardCharsets.UTF_8)) update(b);
            byte[] head = new byte[12];
            fill(in, head);
            int last = 0;
            for (byte b : head) last = decode(b);
            // 先頭 12 バイトの最後が、CRC (または更新時刻) の上位 1 バイトと合えばパスワードは正しい
            int want = (e.flags & 8) != 0 ? (e.dosTime >>> 8) & 255 : (int) (e.crc >>> 24) & 255;
            if (last != want) throw new PasswordException(true);
        }

        private void update(int b) {
            k0 = CRC_TABLE[(k0 ^ b) & 255] ^ (k0 >>> 8);
            k1 = (k1 + (k0 & 255)) * 134775813 + 1;
            k2 = CRC_TABLE[(k2 ^ (k1 >>> 24)) & 255] ^ (k2 >>> 8);
        }

        private int decode(int c) {
            int t = (k2 | 2) & 0xFFFF;
            int p = (c ^ ((t * (t ^ 1)) >>> 8)) & 255;
            update(p);
            return p;
        }

        @Override
        public int read() throws IOException {
            int c = in.read();
            return c < 0 ? -1 : decode(c);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            for (int i = 0; i < n; i++) b[off + i] = (byte) decode(b[off + i]);
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    // ------------------------------------------------------------------ 書く

    /**
     * sources (ファイルやフォルダ。フォルダは中身ごと) を、パスワード付きの zip (AES-256) にまとめる。
     * 中身は暗号化されるが、zip の決まりにより、ファイルの名前と大きさは暗号化されない。
     */
    static void create(List<File> sources, File out, char[] password) throws IOException {
        if (password == null || password.length == 0) throw new PasswordException(false);
        List<byte[]> central = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(out, "rw")) {
            raf.setLength(0);
            for (File f : sources) add(raf, f, f.getName(), out, password, central);
            long dirOffset = raf.getFilePointer();
            long dirSize = 0;
            for (byte[] c : central) {
                raf.write(c);
                dirSize += c.length;
            }
            if (central.size() > 0xFFFE || dirOffset >= MAX32 || dirSize >= MAX32) {
                throw new IOException("パスワード付きの zip は、合計 4GB・65534 個までです");
            }
            byte[] end = new byte[22];
            p32(end, 0, SIG_END);
            p16(end, 8, central.size());
            p16(end, 10, central.size());
            p32(end, 12, dirSize);
            p32(end, 16, dirOffset);
            raf.write(end);
        }
    }

    private static void add(RandomAccessFile raf, File f, String name, File out, char[] password,
                            List<byte[]> central) throws IOException {
        if (f.equals(out) || isSymlink(f)) return;
        if (central.size() >= Archive.MAX_ENTRIES) throw new IOException("ファイルの数が多すぎます");
        if (f.isDirectory()) {
            writeEntry(raf, f, name + "/", null, central);
            File[] kids = f.listFiles();
            if (kids == null) throw new IOException("読み込めませんでした: " + f.getName());
            Arrays.sort(kids);
            for (File k : kids) add(raf, k, name + "/" + k.getName(), out, password, central);
        } else if (f.isFile()) {
            writeEntry(raf, f, name, password, central);
        }
    }

    /** 1 項目を書く。password が null ならフォルダ (中身なし、暗号化なし) */
    private static void writeEntry(RandomAccessFile raf, File f, String name, char[] password,
                                   List<byte[]> central) throws IOException {
        boolean dir = password == null;
        byte[] nb = name.getBytes(StandardCharsets.UTF_8);
        if (nb.length > 0xFFFF) throw new IOException("名前が長すぎます: " + f.getName());
        byte[] extra = dir ? new byte[0] : new byte[11];
        if (!dir) {
            p16(extra, 0, AES_EXTRA);
            p16(extra, 2, 7);
            p16(extra, 4, 2);       // AE-2
            extra[6] = 'A';
            extra[7] = 'E';
            extra[8] = 3;           // 256 ビット
            p16(extra, 9, 8);       // 暗号の下は deflate
        }
        long offset = raf.getFilePointer();
        if (offset >= MAX32) throw new IOException("パスワード付きの zip は、合計 4GB までです");
        int flags = 0x800 | (dir ? 0 : 1); // 名前は UTF-8、(ファイルは) 暗号化あり
        int method = dir ? 0 : 99;
        int version = dir ? 20 : 51;
        int dos = dosTime(f.lastModified());
        byte[] h = new byte[30];
        p32(h, 0, SIG_LOCAL);
        p16(h, 4, version);
        p16(h, 6, flags);
        p16(h, 8, method);
        p32(h, 10, dos);
        p16(h, 26, nb.length);
        p16(h, 28, extra.length);
        raf.write(h);
        raf.write(nb);
        raf.write(extra);

        long csize = 0, usize = 0;
        if (!dir) {
            long start = raf.getFilePointer();
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            byte[] keys = deriveKeys(password, salt, 32);
            Ctr ctr = new Ctr(keys, 32);
            raf.write(salt);
            raf.write(keys, 64, 2);
            Deflater def = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
            byte[] in = new byte[65536], packed = new byte[65536];
            try (InputStream is = new FileInputStream(f)) {
                int n;
                while ((n = is.read(in)) > 0) {
                    usize += n;
                    def.setInput(in, 0, n);
                    while (!def.needsInput()) drain(def, packed, ctr, raf);
                }
                def.finish();
                while (!def.finished()) drain(def, packed, ctr, raf);
            } finally {
                def.end();
            }
            raf.write(ctr.tag());
            long end = raf.getFilePointer();
            csize = end - start;
            if (csize >= MAX32 || usize >= MAX32) {
                throw new IOException("パスワード付きの zip に入れられるのは、1 個 4GB までです: "
                        + f.getName());
            }
            // 大きさは書き終わってから分かるので、先頭へ戻って書き込む
            raf.seek(offset + 18);
            byte[] sizes = new byte[8];
            p32(sizes, 0, csize);
            p32(sizes, 4, usize);
            raf.write(sizes);
            raf.seek(end);
        }

        byte[] c = new byte[46 + nb.length + extra.length];
        p32(c, 0, SIG_CENTRAL);
        p16(c, 4, 0x0300 | 63);   // Unix で作成 (権限を残せる)
        p16(c, 6, version);
        p16(c, 8, flags);
        p16(c, 10, method);
        p32(c, 12, dos);
        p32(c, 20, csize);
        p32(c, 24, usize);
        p16(c, 28, nb.length);
        p16(c, 30, extra.length);
        int mode = dir ? 040755 : f.canExecute() ? 0100755 : 0100644;
        p32(c, 38, ((long) mode << 16) | (dir ? 0x10 : 0));
        p32(c, 42, offset);
        System.arraycopy(nb, 0, c, 46, nb.length);
        System.arraycopy(extra, 0, c, 46 + nb.length, extra.length);
        central.add(c);
    }

    private static void drain(Deflater def, byte[] packed, Ctr ctr, RandomAccessFile raf)
            throws IOException {
        int n = def.deflate(packed);
        if (n > 0) {
            ctr.encrypt(packed, 0, n);
            raf.write(packed, 0, n);
        }
    }

    // ---------------------------------------------------------------- 小道具

    private static boolean isSymlink(File f) {
        try {
            return java.nio.file.Files.isSymbolicLink(f.toPath());
        } catch (LinkageError | RuntimeException e) {
            return false; // Android 7 には java.nio.file が無い
        }
    }

    private static int dosTime(long millis) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(millis);
        int year = c.get(java.util.Calendar.YEAR);
        if (year < 1980) return (1 << 21) | (1 << 16); // zip が表せる最も古い日付
        return ((year - 1980) << 25) | ((c.get(java.util.Calendar.MONTH) + 1) << 21)
                | (c.get(java.util.Calendar.DAY_OF_MONTH) << 16)
                | (c.get(java.util.Calendar.HOUR_OF_DAY) << 11)
                | (c.get(java.util.Calendar.MINUTE) << 5) | (c.get(java.util.Calendar.SECOND) >> 1);
    }

    private static void fill(InputStream in, byte[] b) throws IOException {
        int got = 0;
        while (got < b.length) {
            int n = in.read(b, got, b.length - got);
            if (n < 0) throw new EOFException("zip が途中で切れています");
            got += n;
        }
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 255) | ((b[o + 1] & 255) << 8);
    }

    private static long u32(byte[] b, int o) {
        return (u16(b, o) | ((long) u16(b, o + 2) << 16)) & MAX32;
    }

    private static long u64(byte[] b, int o) {
        return u32(b, o) | (u32(b, o + 4) << 32);
    }

    private static void p16(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >>> 8);
    }

    private static void p32(byte[] b, int o, long v) {
        p16(b, o, (int) v);
        p16(b, o + 2, (int) (v >>> 16));
    }
}
