package site.ragdollp.copytool;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 大きなテキストを Android のクリップボードに安全に載せるためのヘルパー。
 *
 * ClipData.newPlainText() で長い文字列をそのままクリップボードに渡すと、
 * Android の Binder トランザクションバッファ上限（実質 1MB 前後、プロセス内で
 * 共有）を超えたときに TransactionTooLargeException で失敗する。これが
 * スマホで長いテキストのコピーに失敗する主因。
 *
 * ここでは一旦テキストをキャッシュファイルへ書き出し、FileProvider の
 * content:// URI だけをクリップボードに載せる（URI 自体は小さいので上限に
 * かからない）。貼り付け先が ClipData.Item#coerceToText() を使う標準的な
 * 実装（EditText/TextView 等）であれば、貼り付け時に ContentResolver 経由で
 * ファイルの中身をストリーム読み込みするため、25MB でも問題なく貼り付けられる。
 */
final class ClipHelper {

    static final long MAX_BYTES = 25L * 1024 * 1024;

    private ClipHelper() {}

    /** @return 結果メッセージ（Toast やステータス表示にそのまま使える） */
    static String copyLargeText(Context context, String text) {
        if (text == null || text.isEmpty()) {
            return "コピーする内容がありません";
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            return "25MBを超えているためコピーできません（" + formatBytes(bytes.length) + "）";
        }

        try {
            File dir = new File(context.getCacheDir(), "clip");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("キャッシュフォルダを作成できませんでした");
            }
            File file = new File(dir, "springcopy.txt");
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(bytes);
            }

            Uri uri = FileProvider.getUriForFile(
                    context, context.getPackageName() + ".fileprovider", file);

            ClipData clip = ClipData.newUri(context.getContentResolver(), "springコピー", uri);
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(clip);

            return "springコピーしました（" + formatBytes(bytes.length) + "）";
        } catch (IOException e) {
            return "コピーに失敗しました: " + e.getMessage();
        }
    }

    /**
     * 現在クリップボードにある内容をテキストとして読み出す（springペースト）。
     *
     * springコピーで載せた URI クリップならファイルから直接ストリーム読みする
     * （25MBまで対応）。それ以外（他アプリが載せた普通のテキストクリップ等）は
     * {@link ClipData.Item#coerceToText} にフォールバックする。
     *
     * @return 貼り付けるテキスト。クリップボードが空なら null。
     * @throws ClipTooLargeException 内容が {@link #MAX_BYTES} を超える場合
     */
    static String readClipboardText(Context context) {
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return null;
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return null;

        ClipData.Item item = clip.getItemAt(0);
        Uri uri = item.getUri();
        if (uri != null) {
            String fromFile = readUriAsText(context, uri);
            if (fromFile != null) return fromFile;
        }

        CharSequence text = item.coerceToText(context);
        return text != null && text.length() > 0 ? text.toString() : null;
    }

    /** URIの中身をUTF-8テキストとして読む。テキストとして読めなければ null。 */
    private static String readUriAsText(Context context, Uri uri) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(chunk)) != -1) {
                total += n;
                if (total > MAX_BYTES) {
                    throw new ClipTooLargeException(total);
                }
                buf.write(chunk, 0, n);
            }
            return buf.toString("UTF-8");
        } catch (IOException e) {
            return null;
        }
    }

    private static String formatBytes(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format("%.1f KB", n / 1024.0);
        return String.format("%.2f MB", n / 1024.0 / 1024.0);
    }

    static String formatBytesPublic(long n) {
        return formatBytes(n);
    }

    static final class ClipTooLargeException extends RuntimeException {
        final long bytes;
        ClipTooLargeException(long bytes) {
            super("clip content exceeds " + MAX_BYTES + " bytes: " + bytes);
            this.bytes = bytes;
        }
    }
}
