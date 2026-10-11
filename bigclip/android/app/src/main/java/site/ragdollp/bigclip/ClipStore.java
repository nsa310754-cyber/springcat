package site.ragdollp.bigclip;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 大きなテキストをファイル経由でクリップボードに載せる。 */
final class ClipStore {
    /** コピー/貼り付けできるテキストの上限 (UTF-8 で 1MB)。 */
    static final int MAX_BYTES = 1024 * 1024;

    /**
     * これ以下の文字数なら本文をそのままクリップボードに載せる (互換性が最も高い)。
     * Parcel では 1 文字 2 バイトになるため、Binder の約1MB上限に十分な余裕を持たせる。
     */
    static final int INLINE_MAX_CHARS = 100_000;

    static final String AUTHORITY = "site.ragdollp.bigclip.clip";

    private ClipStore() {}

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "clip");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /** text をクリップボードへ。上限超過なら IllegalArgumentException。戻り値は URI 方式を使ったか。 */
    static boolean copy(Context c, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("上限 1MB を超えています (" + Fmt.bytes(bytes.length) + ")");
        }
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        if (text.length() <= INLINE_MAX_CHARS) {
            cm.setPrimaryClip(ClipData.newPlainText("BigClip", text));
            return false;
        }
        // 古いファイルを掃除してから、毎回別名で保存 (前の URI を読んでいるアプリと衝突しない)
        File d = dir(c);
        File[] old = d.listFiles();
        if (old != null) for (File f : old) f.delete();
        String name = "clip-" + System.currentTimeMillis() + ".txt";
        try (FileOutputStream out = new FileOutputStream(new File(d, name))) {
            out.write(bytes);
        }
        Uri uri = Uri.parse("content://" + AUTHORITY + "/" + name);
        ClipDescription desc = new ClipDescription("BigClip " + Fmt.bytes(bytes.length),
                new String[]{ClipDescription.MIMETYPE_TEXT_PLAIN});
        cm.setPrimaryClip(new ClipData(desc, new ClipData.Item(uri)));
        return true;
    }

    /** 現在のクリップボードを全文取得 (他アプリの content:// URI も読む)。空なら null。 */
    static String readPrimary(Context c) {
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < clip.getItemCount(); i++) {
            CharSequence t = clip.getItemAt(i).coerceToText(c);
            if (t != null) sb.append(t);
        }
        return sb.toString();
    }

    /** ストリームから最大 MAX_BYTES まで読む。超過なら IllegalArgumentException。 */
    static String readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[64 * 1024];
        int n;
        while ((n = in.read(b)) > 0) {
            buf.write(b, 0, n);
            if (buf.size() > MAX_BYTES) {
                throw new IllegalArgumentException("ファイルが 1MB を超えています");
            }
        }
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }
}
