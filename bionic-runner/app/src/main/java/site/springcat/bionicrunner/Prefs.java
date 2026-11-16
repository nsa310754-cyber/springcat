package site.springcat.bionicrunner;

import android.content.Context;
import android.content.SharedPreferences;

/** 設定画面で変えられる項目の保存先 */
final class Prefs {

    /** 壁紙の候補: {名前, 左上の色, 中央の色, 右下の色} */
    static final Object[][] WALLPAPERS = {
            {"ブルー", 0xFF0B3A6E, 0xFF14639B, 0xFF0A2A4F},
            {"グリーン", 0xFF064E3B, 0xFF0F766E, 0xFF022C22},
            {"パープル", 0xFF3B0764, 0xFF6D28D9, 0xFF1E1B4B},
            {"サンセット", 0xFF7C2D12, 0xFFBE185D, 0xFF312E81},
            {"ダーク", 0xFF111827, 0xFF1F2937, 0xFF030712},
    };

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /** 実行ファイルを開くときに検査するか (既定は検査する) */
    static boolean scanEnabled(Context c) {
        return sp(c).getBoolean("scan", true);
    }

    static void setScanEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean("scan", on).apply();
    }

    /** root が使えない理由の説明を、起動時に自動で出したことがあるか */
    static boolean rootExplained(Context c) {
        return sp(c).getBoolean("rootExplained", false);
    }

    static void setRootExplained(Context c) {
        sp(c).edit().putBoolean("rootExplained", true).apply();
    }

    /** root のシェルがアプリの作業領域に入れない端末だと分かっているか (次回から root を試さずに起動する) */
    static boolean shellNoRoot(Context c) {
        return sp(c).getBoolean("shellNoRoot", false);
    }

    static void setShellNoRoot(Context c, boolean on) {
        sp(c).edit().putBoolean("shellNoRoot", on).apply();
    }

    /** ゲーム画面の操作ボタン (⌨ 🎮 ✕) の置き場所。0 = ゲーム画面の下、1 = 右上、2 = 左上 */
    static int gameBarPos(Context c) {
        int p = sp(c).getInt("gameBarPos", 0);
        return p >= 0 && p <= 2 ? p : 0;
    }

    static void setGameBarPos(Context c, int pos) {
        sp(c).edit().putInt("gameBarPos", pos).apply();
    }

    /** デスクトップのアイコンの並びとフォルダ分け ({@link DesktopLayout} の形式)。未保存なら null */
    static String desktopLayout(Context c) {
        return sp(c).getString("desktopLayout", null);
    }

    static void setDesktopLayout(Context c, String layout) {
        sp(c).edit().putString("desktopLayout", layout).apply();
    }

    /** アイコンの動かし方の案内を、もう出したか */
    static boolean desktopHintShown(Context c) {
        return sp(c).getBoolean("desktopHint", false);
    }

    static void setDesktopHintShown(Context c) {
        sp(c).edit().putBoolean("desktopHint", true).apply();
    }

    static int wallpaper(Context c) {
        int i = sp(c).getInt("wallpaper", 0);
        return i >= 0 && i < WALLPAPERS.length ? i : 0;
    }

    static void setWallpaper(Context c, int index) {
        sp(c).edit().putInt("wallpaper", index).apply();
    }

    static int[] wallpaperColors(Context c) {
        Object[] w = WALLPAPERS[wallpaper(c)];
        return new int[]{(Integer) w[1], (Integer) w[2], (Integer) w[3]};
    }
}
