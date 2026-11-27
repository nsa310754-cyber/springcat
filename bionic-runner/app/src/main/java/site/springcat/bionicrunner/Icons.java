package site.springcat.bionicrunner;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 登録したプログラムのアイコン。files/icons/&lt;登録名&gt;.png に、角を丸めた 192 画素の PNG で持つ。
 *
 * アイコンの出どころは 3 つ:
 *  - 圧縮ファイルから登録したとき、中に入っていたアイコンの画像 (icon.png、.DirIcon、
 *    .desktop ファイルの Icon= が指す画像、実行ファイルと同じ名前の画像 など)
 *  - Windows のインストーラーや自己解凍形式の exe から登録したとき、exe に埋め込まれたアイコン
 *  - デスクトップの長押しメニューから、利用者が選んだ画像
 * 無ければ、デスクトップはこれまで通り色付きの四角に頭文字を出す。
 */
final class Icons {

    private static final int SIZE = 192;
    /** これより大きい画像ファイルは、アイコンではないとみなして読まない */
    private static final long MAX_FILE = 8L * 1024 * 1024;

    /** 読み込んだアイコンの控え (登録名 → {更新時刻, 画像})。一覧を描くたびに読み直さないため */
    private static final Map<String, Object[]> cache = new HashMap<>();

    private Icons() {
    }

    static File file(Context c, String name) {
        return new File(new File(c.getFilesDir(), "icons"), name + ".png");
    }

    /** 登録名 name のアイコン。設定されていなければ null */
    static synchronized Bitmap load(Context c, String name) {
        File f = file(c, name);
        if (!f.isFile()) {
            cache.remove(name);
            return null;
        }
        Object[] hit = cache.get(name);
        if (hit != null && (Long) hit[0] == f.lastModified()) return (Bitmap) hit[1];
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (b != null) cache.put(name, new Object[]{f.lastModified(), b});
        return b;
    }

    /** 画像を 192 画素の正方形に収め、角を丸めて、登録名 name のアイコンとして保存する */
    static synchronized boolean save(Context c, String name, Bitmap src) {
        if (src == null || src.getWidth() <= 0 || src.getHeight() <= 0) return false;
        Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        // 縦横比を保ったまま、正方形いっぱいに広げる (はみ出す分は切り落とす)
        float scale = Math.max((float) SIZE / src.getWidth(), (float) SIZE / src.getHeight());
        // 小さなドット絵のアイコン (16〜48 画素) は、ぼかさずにくっきり拡大する
        boolean pixelArt = src.getWidth() <= 64 && src.getHeight() <= 64;
        Paint paint = new Paint(pixelArt ? 0 : Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setAntiAlias(true);
        BitmapShader shader = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(scale, scale);
        m.postTranslate((SIZE - src.getWidth() * scale) / 2, (SIZE - src.getHeight() * scale) / 2);
        shader.setLocalMatrix(m);
        paint.setShader(shader);
        float r = SIZE * 0.22f;
        canvas.drawRoundRect(new RectF(0, 0, SIZE, SIZE), r, r, paint);
        File f = file(c, name);
        File dir = f.getParentFile();
        if (dir != null) dir.mkdirs();
        try (OutputStream os = new FileOutputStream(f)) {
            out.compress(Bitmap.CompressFormat.PNG, 100, os);
        } catch (IOException e) {
            f.delete();
            return false;
        }
        cache.remove(name);
        return true;
    }

    static synchronized void delete(Context c, String name) {
        file(c, name).delete();
        cache.remove(name);
    }

    // ------------------------------------------------------------------ 読み込み

    /** 画像ファイル (PNG・JPEG・WebP・GIF・BMP・ICO) を読む。読めなければ null */
    static Bitmap decode(File f) {
        if (f == null || !f.isFile() || f.length() > MAX_FILE) return null;
        try (InputStream in = new FileInputStream(f)) {
            return decode(readAll(in));
        } catch (IOException e) {
            return null;
        }
    }

    static Bitmap decode(byte[] data) {
        if (data == null || data.length == 0 || data.length > MAX_FILE) return null;
        try {
            if (IcoReader.isIco(data)) {
                IcoReader.Image img = IcoReader.read(data);
                if (img == null) return null;
                if (img.png != null) data = img.png;
                else return Bitmap.createBitmap(img.argb, img.width, img.height, Bitmap.Config.ARGB_8888);
            }
            // 大きな写真を選ばれても memory を食いつぶさないよう、先に大きさだけ調べて縮めて読む
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            o.inJustDecodeBounds = false;
            o.inSampleSize = 1;
            while (Math.min(o.outWidth, o.outHeight) / (o.inSampleSize * 2) >= SIZE) o.inSampleSize *= 2;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (bo.size() + n > MAX_FILE) throw new IOException("画像が大きすぎます");
            bo.write(buf, 0, n);
        }
        return bo.toByteArray();
    }

    // -------------------------------------------------- 圧縮ファイルの中から探す

    /**
     * 展開した一式の中から、アイコンらしい画像を探す。exePath は起動するファイルの相対パス。
     * 「たまたま入っていた画像」を拾わないよう、アイコンだと分かる名前のものだけを選ぶ。無ければ null。
     */
    static File findInTree(List<Archive.Entry> files, String exePath) {
        String exeLeaf = leaf(exePath).toLowerCase(Locale.ROOT);
        String exeStem = stem(exeLeaf);
        String exeDir = exePath.contains("/") ? exePath.substring(0, exePath.lastIndexOf('/') + 1) : "";
        // Linux のアプリの説明ファイル (.desktop) に「Icon=名前」とあれば、その名前の画像が本命
        String declared = null;
        for (Archive.Entry e : files) {
            if (!e.path.toLowerCase(Locale.ROOT).endsWith(".desktop") || e.file.length() > 65536) continue;
            try (InputStream in = new FileInputStream(e.file)) {
                for (String line : new String(readAll(in), StandardCharsets.UTF_8).split("\n")) {
                    if (line.startsWith("Icon=")) declared = stem(leaf(line.substring(5).trim()))
                            .toLowerCase(Locale.ROOT);
                }
            } catch (IOException ignored) {
                // 読めなければ使わない
            }
        }
        File best = null;
        long bestScore = 0;
        for (Archive.Entry e : files) {
            String name = leaf(e.path).toLowerCase(Locale.ROOT);
            boolean dirIcon = name.equals(".diricon"); // AppImage が使う、拡張子の無いアイコン
            if (!dirIcon && !name.matches(".*\\.(png|jpg|jpeg|webp|ico|bmp|gif)")) continue;
            if (e.file.length() < 64 || e.file.length() > MAX_FILE) continue;
            String s = stem(name);
            long score = 0;
            if (dirIcon) score = 90;
            else if (declared != null && s.equals(declared)) score = 100;
            else if (s.equals(exeStem) || s.equals(exeLeaf)) score = 80;
            else if (s.equals("icon") || s.equals("logo") || s.equals("appicon") || s.equals("app_icon")
                    || s.equals("ic_launcher") || s.equals("favicon")) score = 70;
            else if (name.endsWith(".ico")) score = 60; // .ico はアイコン専用の形式
            else if (s.contains("icon") || s.contains("logo")) score = 50;
            if (score == 0) continue;
            if (e.path.startsWith(exeDir)) score += 5;            // 起動するファイルの近く
            if (name.endsWith(".png")) score += 3;                // 透明を持てる形式を優先
            // 同じ名前で大きさ違いが並んでいるとき (icons/48x48、256x256 など) は大きい方
            score = score * 1_000_000 + Math.min(e.file.length(), 999_999);
            if (score > bestScore) {
                bestScore = score;
                best = e.file;
            }
        }
        return best;
    }

    /**
     * Windows の exe に埋め込まれたアイコンを、同梱の 7-Zip で取り出す。work は空の作業用フォルダ。
     * いちばん大きい (= たいてい画質の良い) 1 個を返す。無ければ null。
     */
    static File fromWindowsExe(File exe, File work) {
        try {
            if (!SevenZip.extractPeIcons(exe, work)) return null;
        } catch (IOException e) {
            return null;
        }
        File best = null;
        java.util.ArrayDeque<File> todo = new java.util.ArrayDeque<>();
        todo.add(work);
        while (!todo.isEmpty()) {
            File[] kids = todo.poll().listFiles();
            if (kids == null) continue;
            for (File k : kids) {
                if (k.isDirectory()) todo.add(k);
                else if (k.getName().toLowerCase(Locale.ROOT).endsWith(".ico")
                        && (best == null || k.length() > best.length())) best = k;
            }
        }
        return best;
    }

    private static String leaf(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
