package com.springcat.extractor;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.nio.charset.StandardCharsets;

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
 * The original file is stored verbatim (optionally AES-encrypted) and then
 * padded up to a chosen multiple of its size, so the saved file is
 * deliberately larger. A small header records the original name and size, so
 * the process is fully reversible ({@link ArchiveExtractor} restores it).
 *
 * Layout:
 *   "SPCXPND1"        8 bytes  magic
 *   version           1 byte
 *   flags             1 byte   bit0 = encrypted
 *   nameLen           2 bytes  (unsigned, big-endian)
 *   name              nameLen bytes (UTF-8)
 *   origSize          8 bytes  original file size
 *   dataSize          8 bytes  size of the data section (ciphertext length if encrypted)
 *   [salt 16][iv 16]  only when encrypted
 *   data              dataSize bytes  (original, optionally AES/CBC encrypted)
 *   padding           fills the file up to the target size (ignored on restore)
 */
public final class ExpandedFormat {

    static final byte[] MAGIC = {'S', 'P', 'C', 'X', 'P', 'N', 'D', '1'};
    private static final int BUFFER = 1 << 20;
    private static final int PBKDF2_ITERATIONS = 100_000;
    private static final int AES_KEY_BITS = 256;

    private ExpandedFormat() { }

    public static boolean isMagic(byte[] m) {
        if (m == null || m.length < MAGIC.length) return false;
        for (int i = 0; i < MAGIC.length; i++) if (m[i] != MAGIC[i]) return false;
        return true;
    }

    static final class Header {
        boolean encrypted;
        String name;
        long origSize;
        long dataSize;
        byte[] salt;
        byte[] iv;
    }

    // ----------------------------------------------------------------- expand

    /** Expand {@code src} to {@code targetSize} bytes and write it to {@code out}. */
    public static void expand(Context ctx, Uri src, String name, long srcSize,
                              long targetSize, String password, Uri out,
                              ArchiveExtractor.Callback cb) throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        boolean enc = password != null && !password.isEmpty();
        byte[] nameB = name.getBytes(StandardCharsets.UTF_8);
        // AES/CBC/PKCS5 always appends a full padding block when size % 16 == 0.
        long dataSize = enc ? ((srcSize / 16) + 1) * 16 : srcSize;

        byte[] salt = null, iv = null;
        Cipher cipher = null;
        if (enc) {
            salt = randomBytes(16);
            iv = randomBytes(16);
            cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password.toCharArray(), salt),
                    new IvParameterSpec(iv));
        }

        int headerSize = 8 + 1 + 1 + 2 + nameB.length + 8 + 8 + (enc ? 32 : 0);
        long total = Math.max(targetSize, (long) headerSize + dataSize);

        OutputStream rawOut = cr.openOutputStream(out, "wt");
        if (rawOut == null) throw new IOException("出力ファイルを開けませんでした");
        Counting counting = new Counting(new BufferedOutputStream(rawOut, BUFFER), total, cb);
        try (DataOutputStream dos = new DataOutputStream(counting)) {
            dos.write(MAGIC);
            dos.writeByte(1);
            dos.writeByte(enc ? 1 : 0);
            dos.writeShort(nameB.length);
            dos.write(nameB);
            dos.writeLong(srcSize);
            dos.writeLong(dataSize);
            if (enc) { dos.write(salt); dos.write(iv); }

            cb.log("元ファイル: " + name + " (" + srcSize + " バイト)");
            cb.log("拡張後サイズ: " + total + " バイト" + (enc ? " / AES-256暗号化" : ""));

            // data section
            try (InputStream in = new BufferedInputStream(openIn(cr, src), BUFFER)) {
                InputStream data = enc ? new CipherInputStream(in, cipher) : in;
                byte[] buf = new byte[BUFFER];
                int n;
                while ((n = data.read(buf)) != -1) {
                    if (cb.isCancelled()) throw new InterruptedException("中止しました");
                    dos.write(buf, 0, n);
                }
            }
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

    static Header readHeader(DataInputStream dis) throws IOException {
        byte[] magic = new byte[8];
        dis.readFully(magic);
        if (!isMagic(magic)) throw new IOException("SpringCat拡張ファイルではありません");
        dis.readUnsignedByte(); // version (only 1 exists)
        int flags = dis.readUnsignedByte();
        Header h = new Header();
        h.encrypted = (flags & 1) != 0;
        int nameLen = dis.readUnsignedShort();
        byte[] nb = new byte[nameLen];
        dis.readFully(nb);
        h.name = new String(nb, StandardCharsets.UTF_8);
        h.origSize = dis.readLong();
        h.dataSize = dis.readLong();
        if (h.encrypted) {
            h.salt = new byte[16]; dis.readFully(h.salt);
            h.iv = new byte[16]; dis.readFully(h.iv);
        }
        return h;
    }

    /** Wrap the data section so reading it yields the original (decrypted) bytes. */
    static InputStream openData(DataInputStream dis, Header h, char[] password) throws Exception {
        InputStream bounded = new Bounded(dis, h.dataSize);
        if (!h.encrypted) return bounded;
        if (password == null || password.length == 0) {
            throw new IOException("このファイルはパスワードで暗号化されています");
        }
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, h.salt), new IvParameterSpec(h.iv));
        return new CipherInputStream(bounded, cipher);
    }

    // --------------------------------------------------------------- crypto

    private static SecretKey deriveKey(char[] password, byte[] salt) throws Exception {
        // PBKDF2WithHmacSHA1 is available from API 10 (SHA256 variant only from 26).
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
