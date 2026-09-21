package site.ragdollp.copytool;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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

    private static String formatBytes(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format("%.1f KB", n / 1024.0);
        return String.format("%.2f MB", n / 1024.0 / 1024.0);
    }
}
