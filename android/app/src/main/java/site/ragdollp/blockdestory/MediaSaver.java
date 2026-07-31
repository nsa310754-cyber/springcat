package site.ragdollp.blockdestory;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * スクリーンショット(画像)や録画(動画)、エクスポートファイルを端末に保存する。
 *
 *  - Android 10 (API 29) 以上: MediaStore の公開コレクションへ保存
 *    (画像→Pictures/BlockDestroy, 動画→Movies/BlockDestroy, その他→Download/BlockDestroy)
 *    権限は不要でギャラリー等からも見える。
 *  - Android 9 以下: 権限不要なアプリ専用外部ディレクトリへ保存。
 */
final class MediaSaver {

    private MediaSaver() { }

    static Uri save(Context ctx, String name, String mime, byte[] data) {
        try {
            return saveStream(ctx, name, mime, new java.io.ByteArrayInputStream(data));
        } catch (Exception e) {
            return null;
        }
    }

    static Uri saveStream(Context ctx, String name, String mime, InputStream in) throws Exception {
        if (mime == null) mime = "application/octet-stream";
        boolean isImage = mime.startsWith("image/");
        boolean isVideo = mime.startsWith("video/");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver cr = ctx.getContentResolver();
            Uri collection;
            String relDir;
            if (isImage) {
                collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                relDir = Environment.DIRECTORY_PICTURES + "/BlockDestroy";
            } else if (isVideo) {
                collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                relDir = Environment.DIRECTORY_MOVIES + "/BlockDestroy";
            } else {
                collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                relDir = Environment.DIRECTORY_DOWNLOADS + "/BlockDestroy";
            }
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, relDir);
            cv.put(MediaStore.MediaColumns.IS_PENDING, 1);

            Uri item = cr.insert(collection, cv);
            if (item == null) return null;
            OutputStream out = cr.openOutputStream(item);
            copy(in, out);
            cv.clear();
            cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(item, cv, null, null);
            return item;
        } else {
            // API 24-28: アプリ専用外部ディレクトリ (権限不要)
            String sub = isImage ? Environment.DIRECTORY_PICTURES
                    : isVideo ? Environment.DIRECTORY_MOVIES
                    : Environment.DIRECTORY_DOWNLOADS;
            File dir = ctx.getExternalFilesDir(sub);
            if (dir == null) dir = ctx.getFilesDir();
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, name);
            OutputStream out = new FileOutputStream(f);
            copy(in, out);
            return Uri.fromFile(f);
        }
    }

    /** 録画一時ファイルを保存先へ移す。 */
    static Uri saveFile(Context ctx, File src, String name, String mime) {
        try {
            InputStream in = new FileInputStream(src);
            Uri u = saveStream(ctx, name, mime, in);
            //noinspection ResultOfMethodCallIgnored
            src.delete();
            return u;
        } catch (Exception e) {
            return null;
        }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        } finally {
            try { in.close(); } catch (Exception ignore) { }
            try { if (out != null) out.close(); } catch (Exception ignore) { }
        }
    }
}
