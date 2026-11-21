package site.springcat.bionicrunner;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;

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
    /** ホーム画面の並び。表示の切り替え (filter) ごとに別に覚える */
    static String desktopLayout(Context c, int filter) {
        return sp(c).getString(filter == 0 ? "desktopLayout" : "desktopLayout" + filter, null);
    }

    static void setDesktopLayout(Context c, int filter, String layout) {
        sp(c).edit().putString(filter == 0 ? "desktopLayout" : "desktopLayout" + filter, layout).apply();
    }

    /**
     * 「すべて登録」でまとめて登録したアイコンを、次にホーム画面を開いたときフォルダにまとめる予約。
     * 1 行 1 件で「済んだ表示のビット\tフォルダ名\tid\tid…」(表示ごとに並びが別なので、表示ごとに行う)
     */
    static String pendingFolders(Context c) {
        return sp(c).getString("pendingFolders", "");
    }

    static void setPendingFolders(Context c, String v) {
        sp(c).edit().putString("pendingFolders", v).apply();
    }

    /** 1 回だけ行う処理の済み印 */
    static boolean flag(Context c, String key) {
        return sp(c).getBoolean("flag." + key, false);
    }

    static void setFlag(Context c, String key) {
        sp(c).edit().putBoolean("flag." + key, true).apply();
    }

    static synchronized void addPendingFolder(Context c, String name, List<String> ids) {
        StringBuilder sb = new StringBuilder(pendingFolders(c));
        sb.append("0\t").append(DesktopLayout.clean(name));
        for (String id : ids) sb.append('\t').append(DesktopLayout.clean(id));
        sb.append('\n');
        setPendingFolders(c, sb.toString());
    }

    /** ホーム画面の表示の切り替え ({@link ProgramKind#FILTER_ALL} など) */
    static int desktopFilter(Context c) {
        int f = sp(c).getInt("desktopFilter", 0);
        return f < 0 || f > 3 ? 0 : f;
    }

    static void setDesktopFilter(Context c, int f) {
        sp(c).edit().putInt("desktopFilter", f).apply();
    }

    static boolean desktopHintShown(Context c) {
        return sp(c).getBoolean("desktopHint", false);
    }

    static void setDesktopHintShown(Context c) {
        sp(c).edit().putBoolean("desktopHint", true).apply();
    }

    /** プログラムからの自動操作と画面上の表示 (br コマンド) を許すか。既定はオフ */
    static boolean controlEnabled(Context c) {
        return sp(c).getBoolean("control", false);
    }

    static void setControlEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean("control", on).apply();
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

    // ---------------------------------------------------------------- セキュリティ

    /** リアルタイム保護 (既定はオフ。オンにすると常駐して通知を出すため) */
    static boolean avRealtime(Context c) {
        return sp(c).getBoolean("av_realtime", false);
    }

    static void setAvRealtime(Context c, boolean on) {
        sp(c).edit().putBoolean("av_realtime", on).apply();
    }

    /** リアルタイム保護で見つけたときの処置 */
    static final int AV_QUARANTINE = 0, AV_DELETE = 1, AV_NOTIFY = 2;

    /** 処置 (既定は隔離。前の版で「自動で隔離する」を切っていたら通知だけ) */
    static int avAction(Context c) {
        int def = sp(c).getBoolean("av_autoq", true) ? AV_QUARANTINE : AV_NOTIFY;
        return sp(c).getInt("av_action", def);
    }

    static void setAvAction(Context c, int action) {
        sp(c).edit().putInt("av_action", action).apply();
    }

    /** 「疑わしい」もの (危険な操作の記述) にも処置をするか (既定はする) */
    static boolean avActOnSuspicious(Context c) {
        return sp(c).getBoolean("av_act_suspicious", true);
    }

    static void setAvActOnSuspicious(Context c, boolean on) {
        sp(c).edit().putBoolean("av_act_suspicious", on).apply();
    }

    /** 定義を最後に確認した時刻 (0 は未取得) */
    static long avUpdated(Context c) {
        return sp(c).getLong("av_updated", 0);
    }

    static void setAvUpdated(Context c, long t) {
        sp(c).edit().putLong("av_updated", t).apply();
    }

    /** 最後のスキャン ("種類\t時刻\t数\t見つかった数")。無ければ空 */
    static String avLastScan(Context c) {
        return sp(c).getString("av_last_scan", "");
    }

    static void setAvLastScan(Context c, String v) {
        sp(c).edit().putString("av_last_scan", v).apply();
    }

    /** 利用者が「許可」したファイル (SHA-256)。検出しない */
    static boolean avAllowed(Context c, String sha256) {
        return sp(c).getStringSet("av_allowed", java.util.Collections.<String>emptySet()).contains(sha256);
    }

    static void avAllow(Context c, String sha256) {
        java.util.Set<String> s = new java.util.HashSet<>(
                sp(c).getStringSet("av_allowed", java.util.Collections.<String>emptySet()));
        s.add(sha256);
        sp(c).edit().putStringSet("av_allowed", s).apply();
    }

    static int avAllowedCount(Context c) {
        return sp(c).getStringSet("av_allowed", java.util.Collections.<String>emptySet()).size();
    }

    static void avClearAllowed(Context c) {
        sp(c).edit().remove("av_allowed").apply();
    }
}
