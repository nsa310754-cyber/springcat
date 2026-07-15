package com.springcat.extractor;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.documentfile.provider.DocumentFile;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.compressors.CompressorInputStream;
import org.apache.commons.compress.compressors.CompressorStreamFactory;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;

import io.airlift.compress.zstd.ZstdInputStream;
import org.brotli.dec.BrotliInputStream;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Map;

/**
 * Streaming, low-memory multi-format archive extractor.
 *
 * Everything is streamed straight to the output tree so that multi-gigabyte
 * archives never need to be held in memory. 7z and RAR require random access,
 * so they are read through a seekable FileChannel obtained from the source Uri
 * (no full temporary copy is made).
 */
public final class ArchiveExtractor {

    /** ~1 MB copy buffer keeps throughput high without large allocations. */
    private static final int BUFFER = 1 << 20;

    public interface Callback {
        void log(String line);
        void progress(int percent, String message);
        boolean isCancelled();
    }

    private final Context context;
    private final Callback cb;

    public ArchiveExtractor(Context context, Callback cb) {
        this.context = context;
        this.cb = cb;
    }

    /** Extract {@code source} into {@code outRoot}. Returns number of entries written. */
    public int extract(Uri source, String sourceName, long sourceSize, DocumentFile outRoot)
            throws Exception {
        byte[] magic = readMagic(source, 16);

        if (isSevenZ(magic)) {
            cb.log("形式: 7z を検出");
            return extractSevenZ(source, outRoot);
        }
        if (isRar(magic)) {
            cb.log("形式: RAR を検出");
            return extractRar(source, outRoot);
        }
        if (isZstd(magic)) {
            cb.log("圧縮: zstd を検出");
            return handleDecompressed(
                    new ZstdInputStream(openCounting(source, sourceSize)), sourceName, outRoot);
        }
        // Everything else can be handled from a plain (seekable-free) stream.
        return extractStreaming(source, sourceName, sourceSize, outRoot);
    }

    // ---------------------------------------------------------------- streaming

    private int extractStreaming(Uri source, String sourceName, long sourceSize,
                                 DocumentFile outRoot) throws Exception {
        // markable stream needed for format auto-detection
        BufferedInputStream in = new BufferedInputStream(openCounting(source, sourceSize), BUFFER);

        String compressor = detectCompressor(in);
        if (compressor != null) {
            cb.log("圧縮: " + compressor);
            CompressorInputStream cin = new CompressorStreamFactory()
                    .createCompressorInputStream(compressor, in);
            return handleDecompressed(cin, sourceName, outRoot);
        }

        String archive = detectArchive(in);
        if (archive != null) {
            cb.log("形式: " + archive + " を検出");
            return extractArchiveStream(in, outRoot);
        }

        // Brotli has no magic number, so fall back to the file extension.
        String lower = sourceName.toLowerCase(java.util.Locale.US);
        if (lower.endsWith(".br") || lower.endsWith(".brotli")) {
            cb.log("圧縮: brotli (拡張子判定)");
            return handleDecompressed(new BrotliInputStream(in), sourceName, outRoot);
        }
        throw new IOException("対応していない、または壊れたファイル形式です");
    }

    /**
     * Take an already-decompressed stream and either treat it as a wrapped
     * archive (e.g. tar inside tar.gz / tar.zst) or write it out as a single
     * decompressed file (e.g. plain file.gz).
     */
    private int handleDecompressed(InputStream decompressed, String sourceName,
                                   DocumentFile outRoot) throws Exception {
        ContentResolver cr = context.getContentResolver();
        BufferedInputStream cbuf = new BufferedInputStream(decompressed, BUFFER);
        String inner = detectArchive(cbuf);
        if (inner != null) {
            cb.log("内部アーカイブ: " + inner);
            return extractArchiveStream(cbuf, outRoot);
        }
        String outName = strip(sourceName);
        cb.log("単一ファイルを展開: " + outName);
        DocumentFile target = createFile(outRoot, outName, new HashMap<>());
        if (target == null) throw new IOException("出力ファイルを作成できません");
        try (OutputStream os = cr.openOutputStream(target.getUri())) {
            copy(cbuf, os);
        }
        return 1;
    }

    /** Open the source wrapped in a progress-reporting counting stream. */
    private CountingInputStream openCounting(Uri source, long sourceSize) throws IOException {
        ContentResolver cr = context.getContentResolver();
        return new CountingInputStream(
                new BufferedInputStream(open(cr, source), BUFFER), sourceSize);
    }

    private int extractArchiveStream(InputStream in, DocumentFile outRoot) throws Exception {
        ContentResolver cr = context.getContentResolver();
        Map<String, DocumentFile> dirCache = new HashMap<>();
        int count = 0;
        try (ArchiveInputStream ain =
                     new ArchiveStreamFactory().createArchiveInputStream(in)) {
            ArchiveEntry entry;
            while ((entry = ain.getNextEntry()) != null) {
                if (cb.isCancelled()) throw new InterruptedException("中止しました");
                if (!ain.canReadEntryData(entry)) {
                    cb.log("スキップ(読取不可): " + entry.getName());
                    continue;
                }
                String name = entry.getName();
                if (entry.isDirectory()) {
                    ensureDir(outRoot, name, dirCache);
                    continue;
                }
                DocumentFile target = createFile(outRoot, name, dirCache);
                if (target == null) { cb.log("スキップ(不正パス): " + name); continue; }
                try (OutputStream os = cr.openOutputStream(target.getUri())) {
                    copy(ain, os);
                }
                count++;
                if ((count & 15) == 0) cb.progress(-1, count + " 個展開");
            }
        }
        return count;
    }

    // ---------------------------------------------------------------------- 7z

    private int extractSevenZ(Uri source, DocumentFile outRoot) throws Exception {
        ContentResolver cr = context.getContentResolver();
        Map<String, DocumentFile> dirCache = new HashMap<>();
        int count = 0;
        try (ParcelFileDescriptor pfd = cr.openFileDescriptor(source, "r")) {
            FileChannel channel = new FileInputStream(pfd.getFileDescriptor()).getChannel();
            try (SevenZFile sevenZ = new SevenZFile.Builder()
                    .setSeekableByteChannel(channel).get()) {
                SevenZArchiveEntry entry;
                while ((entry = sevenZ.getNextEntry()) != null) {
                    if (cb.isCancelled()) throw new InterruptedException("中止しました");
                    String name = entry.getName();
                    if (entry.isDirectory()) {
                        ensureDir(outRoot, name, dirCache);
                        continue;
                    }
                    DocumentFile target = createFile(outRoot, name, dirCache);
                    if (target == null) { cb.log("スキップ(不正パス): " + name); continue; }
                    try (OutputStream os = cr.openOutputStream(target.getUri())) {
                        byte[] buf = new byte[BUFFER];
                        int n;
                        while ((n = sevenZ.read(buf)) != -1) {
                            if (cb.isCancelled()) throw new InterruptedException("中止しました");
                            os.write(buf, 0, n);
                        }
                    }
                    count++;
                    if ((count & 15) == 0) cb.progress(-1, count + " 個展開");
                }
            }
        }
        return count;
    }

    // -------------------------------------------------------------------- RAR

    private int extractRar(Uri source, DocumentFile outRoot) throws Exception {
        ContentResolver cr = context.getContentResolver();
        Map<String, DocumentFile> dirCache = new HashMap<>();
        int count = 0;
        try (InputStream in = new BufferedInputStream(open(cr, source), BUFFER);
             Archive archive = new Archive(in)) {
            FileHeader header;
            while ((header = archive.nextFileHeader()) != null) {
                if (cb.isCancelled()) throw new InterruptedException("中止しました");
                String name = header.getFileName().replace('\\', '/');
                if (header.isDirectory()) {
                    ensureDir(outRoot, name, dirCache);
                    continue;
                }
                DocumentFile target = createFile(outRoot, name, dirCache);
                if (target == null) { cb.log("スキップ(不正パス): " + name); continue; }
                try (OutputStream os = cr.openOutputStream(target.getUri())) {
                    archive.extractFile(header, os);
                }
                count++;
                if ((count & 7) == 0) cb.progress(-1, count + " 個展開");
            }
        }
        return count;
    }

    // ------------------------------------------------------------- detection

    private byte[] readMagic(Uri source, int len) throws IOException {
        ContentResolver cr = context.getContentResolver();
        byte[] buf = new byte[len];
        try (InputStream in = open(cr, source)) {
            int off = 0, n;
            while (off < len && (n = in.read(buf, off, len - off)) != -1) off += n;
        }
        return buf;
    }

    private static boolean isSevenZ(byte[] m) {
        return m.length >= 6 && (m[0] & 0xFF) == 0x37 && (m[1] & 0xFF) == 0x7A
                && (m[2] & 0xFF) == 0xBC && (m[3] & 0xFF) == 0xAF
                && (m[4] & 0xFF) == 0x27 && (m[5] & 0xFF) == 0x1C;
    }

    private static boolean isZstd(byte[] m) {
        // Zstandard frame magic: 0x28 0xB5 0x2F 0xFD (little-endian on disk)
        return m.length >= 4 && (m[0] & 0xFF) == 0x28 && (m[1] & 0xFF) == 0xB5
                && (m[2] & 0xFF) == 0x2F && (m[3] & 0xFF) == 0xFD;
    }

    private static boolean isRar(byte[] m) {
        // "Rar!\x1A\x07"
        return m.length >= 6 && (m[0] & 0xFF) == 0x52 && (m[1] & 0xFF) == 0x61
                && (m[2] & 0xFF) == 0x72 && (m[3] & 0xFF) == 0x21
                && (m[4] & 0xFF) == 0x1A && (m[5] & 0xFF) == 0x07;
    }

    private static String detectCompressor(BufferedInputStream in) {
        try {
            return CompressorStreamFactory.detect(in);
        } catch (Exception e) {
            return null;
        }
    }

    private static String detectArchive(BufferedInputStream in) {
        try {
            return ArchiveStreamFactory.detect(in);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------- output tree

    /** Ensure a directory chain exists; returns the leaf directory. */
    private DocumentFile ensureDir(DocumentFile root, String relPath,
                                   Map<String, DocumentFile> cache) {
        String norm = normalize(relPath);
        if (norm == null || norm.isEmpty()) return root;
        DocumentFile current = root;
        StringBuilder key = new StringBuilder();
        for (String part : norm.split("/")) {
            if (part.isEmpty()) continue;
            key.append('/').append(part);
            DocumentFile cached = cache.get(key.toString());
            if (cached != null) { current = cached; continue; }
            DocumentFile existing = current.findFile(part);
            DocumentFile dir = (existing != null && existing.isDirectory())
                    ? existing : current.createDirectory(part);
            if (dir == null) return current; // fall back
            cache.put(key.toString(), dir);
            current = dir;
        }
        return current;
    }

    /** Create (or replace) a file for the given archive-relative path. */
    private DocumentFile createFile(DocumentFile root, String relPath,
                                    Map<String, DocumentFile> cache) {
        String norm = normalize(relPath);
        if (norm == null || norm.isEmpty()) return null;
        int slash = norm.lastIndexOf('/');
        String fileName = slash >= 0 ? norm.substring(slash + 1) : norm;
        if (fileName.isEmpty()) return null;
        DocumentFile parent = slash >= 0
                ? ensureDir(root, norm.substring(0, slash), cache) : root;
        DocumentFile existing = parent.findFile(fileName);
        if (existing != null) existing.delete();
        return parent.createFile("application/octet-stream", fileName);
    }

    /** Reject path traversal; return a clean relative path or null if unsafe. */
    private static String normalize(String p) {
        if (p == null) return null;
        String s = p.replace('\\', '/');
        while (s.startsWith("/")) s = s.substring(1);
        for (String part : s.split("/")) {
            if (part.equals("..")) return null; // zip-slip guard
        }
        return s;
    }

    private static String strip(String name) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base.isEmpty() ? "output" : base;
    }

    // --------------------------------------------------------------- plumbing

    private static InputStream open(ContentResolver cr, Uri uri) throws IOException {
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new IOException("ファイルを開けませんでした");
        return in;
    }

    private void copy(InputStream in, OutputStream out) throws IOException, InterruptedException {
        byte[] buf = new byte[BUFFER];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (cb.isCancelled()) throw new InterruptedException("中止しました");
            out.write(buf, 0, n);
        }
    }

    /** Counts bytes read from the source and emits percentage progress. */
    private final class CountingInputStream extends InputStream {
        private final InputStream delegate;
        private final long total;
        private long read;
        private int lastPercent = -1;

        CountingInputStream(InputStream delegate, long total) {
            this.delegate = delegate;
            this.total = total;
        }

        private void advance(long n) {
            if (n <= 0) return;
            read += n;
            if (total > 0) {
                int pct = (int) Math.min(100, read * 100 / total);
                if (pct != lastPercent) {
                    lastPercent = pct;
                    cb.progress(pct, pct + "%");
                }
            }
        }

        @Override public int read() throws IOException {
            int b = delegate.read();
            if (b != -1) advance(1);
            return b;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = delegate.read(b, off, len);
            advance(n);
            return n;
        }

        @Override public long skip(long n) throws IOException {
            long s = delegate.skip(n);
            advance(s);
            return s;
        }

        @Override public int available() throws IOException { return delegate.available(); }
        @Override public void close() throws IOException { delegate.close(); }
        @Override public synchronized void mark(int limit) { delegate.mark(limit); }
        @Override public synchronized void reset() throws IOException { delegate.reset(); }
        @Override public boolean markSupported() { return delegate.markSupported(); }
    }
}
