package com.springcat.extractor;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.documentfile.provider.DocumentFile;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;

import io.airlift.compress.zstd.ZstdOutputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * Streaming, low-memory multi-format compressor.
 *
 * Input files are read one at a time and streamed straight into the output
 * archive, so packing multi-gigabyte content never needs it all in memory.
 * 7z requires random access on the target, so it is written through a seekable
 * FileChannel opened from the output Uri.
 */
public final class Compressor {

    private static final int BUFFER = 1 << 20;

    public enum Format {
        ZIP("zip", "application/zip"),
        SEVENZ("7z", "application/x-7z-compressed"),
        TAR_GZ("tar.gz", "application/gzip"),
        TAR_XZ("tar.xz", "application/x-xz"),
        TAR_ZST("tar.zst", "application/zstd"),
        TAR_BZ2("tar.bz2", "application/x-bzip2");

        public final String ext;
        public final String mime;
        Format(String ext, String mime) { this.ext = ext; this.mime = mime; }
    }

    /** One file to be packed, with its path inside the archive. */
    public static final class Item {
        final String path;   // archive-relative path, '/'-separated
        final Uri uri;
        final long size;
        Item(String path, Uri uri, long size) { this.path = path; this.uri = uri; this.size = size; }
    }

    public interface Callback {
        void log(String line);
        void progress(int percent, String message);
        boolean isCancelled();
    }

    private final Context context;
    private final Callback cb;
    private long totalBytes;
    private long doneBytes;
    private int lastPercent = -1;

    public Compressor(Context context, Callback cb) {
        this.context = context;
        this.cb = cb;
    }

    /** Collect the files selected directly by the user (flat list). */
    public List<Item> fromFiles(List<Uri> uris) {
        List<Item> items = new ArrayList<>();
        for (Uri uri : uris) {
            DocumentFile f = DocumentFile.fromSingleUri(context, uri);
            if (f == null) continue;
            String name = f.getName() != null ? f.getName() : "file";
            items.add(new Item(name, uri, Math.max(0, f.length())));
        }
        return items;
    }

    /** Recursively collect every file under a picked folder, keeping the tree. */
    public List<Item> fromFolder(Uri treeUri) {
        List<Item> items = new ArrayList<>();
        DocumentFile root = DocumentFile.fromTreeUri(context, treeUri);
        if (root == null) return items;
        String base = root.getName() != null ? root.getName() : "folder";
        walk(root, base, items);
        return items;
    }

    private void walk(DocumentFile dir, String prefix, List<Item> out) {
        DocumentFile[] children = dir.listFiles();
        for (DocumentFile c : children) {
            if (cb.isCancelled()) return;
            String name = c.getName();
            if (name == null) continue;
            String path = prefix + "/" + name;
            if (c.isDirectory()) {
                walk(c, path, out);
            } else {
                out.add(new Item(path, c.getUri(), Math.max(0, c.length())));
            }
        }
    }

    public int compress(List<Item> items, Format format, Uri output) throws Exception {
        totalBytes = 0;
        doneBytes = 0;
        lastPercent = -1;
        for (Item i : items) totalBytes += i.size;
        cb.log("入力: " + items.size() + " ファイル / " + totalBytes + " バイト");
        cb.log("形式: " + format.ext);

        switch (format) {
            case ZIP:      return writeZip(items, output);
            case SEVENZ:   return writeSevenZ(items, output);
            default:       return writeTar(items, format, output);
        }
    }

    // ------------------------------------------------------------------- zip

    private int writeZip(List<Item> items, Uri output) throws Exception {
        ContentResolver cr = context.getContentResolver();
        int count = 0;
        try (OutputStream os = new BufferedOutputStream(openOut(cr, output), BUFFER);
             ZipArchiveOutputStream zos = new ZipArchiveOutputStream(os)) {
            for (Item item : items) {
                if (cb.isCancelled()) throw new InterruptedException("中止しました");
                ZipArchiveEntry entry = new ZipArchiveEntry(item.path);
                entry.setSize(item.size);
                zos.putArchiveEntry(entry);
                copyInto(cr, item, zos);
                zos.closeArchiveEntry();
                count++;
            }
            zos.finish();
        }
        return count;
    }

    // ------------------------------------------------------------------- tar.*

    private int writeTar(List<Item> items, Format format, Uri output) throws Exception {
        ContentResolver cr = context.getContentResolver();
        int count = 0;
        OutputStream raw = new BufferedOutputStream(openOut(cr, output), BUFFER);
        OutputStream comp;
        switch (format) {
            case TAR_GZ:  comp = new GzipCompressorOutputStream(raw); break;
            case TAR_XZ:  comp = new XZCompressorOutputStream(raw); break;
            case TAR_BZ2: comp = new BZip2CompressorOutputStream(raw); break;
            case TAR_ZST: comp = new ZstdOutputStream(raw); break;
            default: throw new IOException("未対応の形式");
        }
        try (TarArchiveOutputStream tos = new TarArchiveOutputStream(comp)) {
            // Handle long names and >8GB entries.
            tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tos.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            for (Item item : items) {
                if (cb.isCancelled()) throw new InterruptedException("中止しました");
                TarArchiveEntry entry = new TarArchiveEntry(item.path);
                entry.setSize(item.size);
                tos.putArchiveEntry(entry);
                copyInto(cr, item, tos);
                tos.closeArchiveEntry();
                count++;
            }
            tos.finish();
        }
        return count;
    }

    // ------------------------------------------------------------------- 7z

    private int writeSevenZ(List<Item> items, Uri output) throws Exception {
        ContentResolver cr = context.getContentResolver();
        int count = 0;
        try (ParcelFileDescriptor pfd = cr.openFileDescriptor(output, "rw")) {
            FileChannel channel = new FileOutputStream(pfd.getFileDescriptor()).getChannel();
            channel.truncate(0);
            try (SevenZOutputFile out = new SevenZOutputFile(channel)) {
                for (Item item : items) {
                    if (cb.isCancelled()) throw new InterruptedException("中止しました");
                    SevenZArchiveEntry entry = new SevenZArchiveEntry();
                    entry.setName(item.path);
                    entry.setSize(item.size);
                    out.putArchiveEntry(entry);
                    try (InputStream in = new BufferedInputStream(open(cr, item.uri), BUFFER)) {
                        byte[] buf = new byte[BUFFER];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            if (cb.isCancelled()) throw new InterruptedException("中止しました");
                            out.write(buf, 0, n);
                            advance(n);
                        }
                    }
                    out.closeArchiveEntry();
                    count++;
                }
                out.finish();
            }
        }
        return count;
    }

    // --------------------------------------------------------------- plumbing

    private void copyInto(ContentResolver cr, Item item, OutputStream out)
            throws IOException, InterruptedException {
        try (InputStream in = new BufferedInputStream(open(cr, item.uri), BUFFER)) {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (cb.isCancelled()) throw new InterruptedException("中止しました");
                out.write(buf, 0, n);
                advance(n);
            }
        }
    }

    private void advance(int n) {
        doneBytes += n;
        if (totalBytes > 0) {
            int pct = (int) Math.min(100, doneBytes * 100 / totalBytes);
            if (pct != lastPercent) {
                lastPercent = pct;
                cb.progress(pct, pct + "%");
            }
        }
    }

    private static InputStream open(ContentResolver cr, Uri uri) throws IOException {
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new IOException("ファイルを開けませんでした");
        return in;
    }

    private static OutputStream openOut(ContentResolver cr, Uri uri) throws IOException {
        OutputStream os = cr.openOutputStream(uri, "wt");
        if (os == null) throw new IOException("出力ファイルを開けませんでした");
        return os;
    }
}
