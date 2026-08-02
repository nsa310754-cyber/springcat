package com.springcat.extractor;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * SpringCat "expanded" container (.sce): the opposite of compression.
 *
 * The original file is stored and then padded up to a chosen multiple of its
 * size, so the saved file is deliberately larger while staying fully
 * reversible ({@link ArchiveExtractor} restores it).
 *
 * The whole payload — the file bytes AND the original file name — is always
 * AES-CTR transformed so nothing is readable by eye in a hex/text editor:
 *   - with a user password: real AES-256 encryption (PBKDF2 key);
 *   - without one: obfuscated with a built-in key (reversible by this app,
 *     NOT secure against a determined reverse-engineer, but not plain text).
 * A 4-byte "SCOK" tag inside the encrypted payload detects a wrong password.
 *
 * Layout (format v2):
 *   "SPCXPND2"   8 bytes  magic
 *   version      1 byte   = 2
 *   flags        1 byte   bit0 = a user password is required
 *   salt        16 bytes  (PBKDF2)
 *   iv          16 bytes  (AES-CTR nonce)
 *   encLen       8 bytes  length of the encrypted payload
 *   payload    encLen bytes  AES-CTR of: "SCOK" + nameLen(2) + name + origSize(8) + fileBytes
 *   padding    fills the file up to the target size (ignored on restore)
 */
public final class ExpandedFormat {

    static final byte[] MAGIC = {'S', 'P', 'C', 'X', 'P', 'N', 'D', '2'};
    private static final byte[] TAG = {'S', 'C', 'O', 'K'};
    /** Built-in key used to obfuscate password-less files. Obfuscation, not security. */
    private static final String DEFAULT_KEY = "SpringCat/expanded/v2/default-obfuscation";
    private static final int BUFFER = 1 << 20;
    private static final int PBKDF2_ITERATIONS = 100_000;
    private static final int AES_KEY_BITS = 256;

    private ExpandedFormat() { }

    public static boolean isMagic(byte[] m) {
        if (m == null || m.length < MAGIC.length) return false;
        for (int i = 0; i < MAGIC.length; i++) if (m[i] != MAGIC[i]) return false;
        return true;
    }

    /** Receives the restored original name and hands back where to write it. */
    public interface OutputSink {
        OutputStream create(String name) throws IOException;
    }

    // ----------------------------------------------------------------- expand

    /** Expand {@code src} to {@code targetSize} bytes and write it to {@code out}. */
    public static void expand(Context ctx, Uri src, String name, long srcSize,
                              long targetSize, String password, Uri out,
                              ArchiveExtractor.Callback cb) throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        boolean userPw = password != null && !password.isEmpty();

        byte[] salt = randomBytes(16);
        byte[] iv = randomBytes(16);
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,
                deriveKey((userPw ? password : DEFAULT_KEY).toCharArray(), salt),
                new IvParameterSpec(iv));

        byte[] nameB = name.getBytes(StandardCharsets.UTF_8);
        // CTR is a stream cipher: encrypted length == plaintext length.
        long encLen = TAG.length + 2 + nameB.length + 8 + srcSize;
        int headerSize = 8 + 1 + 1 + 16 + 16 + 8;
        long total = Math.max(targetSize, (long) headerSize + encLen);

        OutputStream rawOut = cr.openOutputStream(out, "wt");
        if (rawOut == null) throw new IOException("出力ファイルを開けませんでした");
        Counting counting = new Counting(new BufferedOutputStream(rawOut, BUFFER), total, cb);
        try (DataOutputStream dos = new DataOutputStream(counting)) {
            dos.write(MAGIC);
            dos.writeByte(2);
            dos.writeByte(userPw ? 1 : 0);
            dos.write(salt);
            dos.write(iv);
            dos.writeLong(encLen);

            cb.log("元ファイル: " + name + " (" + srcSize + " バイト)");
            cb.log("拡張後サイズ: " + total + " バイト"
                    + (userPw ? " / AES-256暗号化" : " / 難読化(内蔵キー)"));

            // encrypted metadata: tag + nameLen + name + origSize
            ByteArrayOutputStream metaBuf = new ByteArrayOutputStream();
            DataOutputStream meta = new DataOutputStream(metaBuf);
            meta.write(TAG);
            meta.writeShort(nameB.length);
            meta.write(nameB);
            meta.writeLong(srcSize);
            byte[] encMeta = cipher.update(metaBuf.toByteArray());
            if (encMeta != null) dos.write(encMeta);

            // encrypted file body (same keystream continues)
            try (InputStream in = new BufferedInputStream(openIn(cr, src), BUFFER)) {
                byte[] buf = new byte[BUFFER];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cb.isCancelled()) throw new InterruptedException("中止しました");
                    byte[] enc = cipher.update(buf, 0, n);
                    if (enc != null && enc.length > 0) dos.write(enc);
                }
            }
            byte[] fin = cipher.doFinal();
            if (fin != null && fin.length > 0) dos.write(fin);

            // padding up to the target size
            long pad = total - counting.count();
            byte[] padBuf = new byte[BUFFER];
            while (pad > 0) {
                if (cb.isCancelled()) throw new InterruptedException("中止しました");
                int w = (int) Math.min(pad, padBuf.length);
                dos.write(padBuf, 0, w);
                pad -= w;
            }
            dos.flush();
        }
    }

    // ---------------------------------------------------------------- restore

    /** Restore an .sce stream, writing the original file via {@code sink}. */
    public static int restore(Context ctx, Uri src, char[] password,
                              OutputSink sink, ArchiveExtractor.Callback cb) throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        try (InputStream raw = new BufferedInputStream(openIn(cr, src), BUFFER)) {
            DataInputStream dis = new DataInputStream(raw);
            byte[] magic = new byte[8];
            dis.readFully(magic);
            if (!isMagic(magic)) throw new IOException("SpringCat拡張ファイルではありません");
            dis.readUnsignedByte(); // version
            int flags = dis.readUnsignedByte();
            boolean userPw = (flags & 1) != 0;
            byte[] salt = new byte[16]; dis.readFully(salt);
            byte[] iv = new byte[16]; dis.readFully(iv);
            long encLen = dis.readLong();

            if (userPw && (password == null || password.length == 0)) {
                throw new IOException("このファイルはパスワードで暗号化されています");
            }
            char[] keyChars = userPw ? password : DEFAULT_KEY.toCharArray();
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(keyChars, salt), new IvParameterSpec(iv));

            CipherInputStream cis = new CipherInputStream(new Bounded(dis, encLen), cipher);
            DataInputStream plain = new DataInputStream(cis);

            byte[] tag = new byte[TAG.length];
            plain.readFully(tag);
            if (!Arrays.equals(tag, TAG)) {
                throw new IOException(userPw
                        ? "パスワードが違います (wrong password)"
                        : "ファイルが壊れています");
            }
            int nameLen = plain.readUnsignedShort();
            byte[] nb = new byte[nameLen];
            plain.readFully(nb);
            String name = new String(nb, StandardCharsets.UTF_8);
            long origSize = plain.readLong();
            cb.log("復元: " + name + " (" + origSize + " バイト)"
                    + (userPw ? " / 暗号化" : ""));

            try (OutputStream os = sink.create(name)) {
                byte[] buf = new byte[BUFFER];
                long remaining = origSize;
                while (remaining > 0) {
                    if (cb.isCancelled()) throw new InterruptedException("中止しました");
                    int want = (int) Math.min(buf.length, remaining);
                    int n = plain.read(buf, 0, want);
                    if (n == -1) break;
                    os.write(buf, 0, n);
                    remaining -= n;
                }
            }
            cb.progress(100, "完了");
        }
        return 1;
    }

    // --------------------------------------------------------------- crypto

    private static SecretKey deriveKey(char[] password, byte[] salt) throws Exception {
        // PBKDF2WithHmacSHA1 is available from API 10 (the SHA256 variant only from 26).
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        KeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, AES_KEY_BITS);
        return new SecretKeySpec(f.generateSecret(spec).getEncoded(), "AES");
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    private static InputStream openIn(ContentResolver cr, Uri uri) throws IOException {
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new IOException("ファイルを開けませんでした");
        return in;
    }

    // --------------------------------------------------------------- helpers

    /** Limits reads to a fixed number of bytes (skips trailing padding). */
    private static final class Bounded extends InputStream {
        private final InputStream in;
        private long remaining;
        Bounded(InputStream in, long limit) { this.in = in; this.remaining = limit; }

        @Override public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = in.read();
            if (b != -1) remaining--;
            return b;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }
    }

    /** Counts bytes written and reports percentage progress. */
    private static final class Counting extends OutputStream {
        private final OutputStream out;
        private final long total;
        private final ArchiveExtractor.Callback cb;
        private long count;
        private int lastPercent = -1;

        Counting(OutputStream out, long total, ArchiveExtractor.Callback cb) {
            this.out = out; this.total = total; this.cb = cb;
        }
        long count() { return count; }

        private void advance(int n) {
            count += n;
            if (total > 0) {
                int pct = (int) Math.min(100, count * 100 / total);
                if (pct != lastPercent) { lastPercent = pct; cb.progress(pct, pct + "%"); }
            }
        }
        @Override public void write(int b) throws IOException { out.write(b); advance(1); }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len); advance(len);
        }
        @Override public void flush() throws IOException { out.flush(); }
        @Override public void close() throws IOException { out.close(); }
    }
}
