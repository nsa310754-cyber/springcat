package site.ragdollp.split4;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 4 つのアプリを画面の 2×2 に並べてフリーフォームウィンドウで起動する。
 *
 * root がある場合: am start --windowingMode 5 で起動し、am task resize で各タスクを
 * 正確な位置に合わせる (バックグラウンド起動制限や隠し API 制限を受けない)。
 * root が無い場合: ActivityOptions#setLaunchBounds で起動位置を指定する
 * (端末側でフリーフォームが有効になっている必要がある)。
 */
final class SplitLauncher {
    /** WindowConfiguration.WINDOWING_MODE_FREEFORM */
    private static final int WINDOWING_MODE_FREEFORM = 5;

    private static final Pattern ACTIVITY_RECORD =
            Pattern.compile("ActivityRecord\\{[0-9a-f]+ u\\d+ ([^/\\s]+)/\\S+ t(\\d+)");

    interface Callback {
        void onDone(String message);
    }

    private SplitLauncher() {}

    /** 有効なスロット (アプリ割り当て済み) と、その表示領域。 */
    private static final class Entry {
        final ComponentName cn;
        final Rect bounds;

        Entry(ComponentName cn, Rect bounds) {
            this.cn = cn;
            this.bounds = bounds;
        }
    }

    static void launch(Activity a, Callback cb) {
        Prefs prefs = new Prefs(a);
        Rect[] cells = computeCells(a, prefs.gap());
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < Prefs.SLOTS; i++) {
            ComponentName cn = prefs.getSlot(i);
            if (cn != null) entries.add(new Entry(cn, cells[i]));
        }
        if (entries.isEmpty()) {
            cb.onDone("アプリが1つも設定されていません");
            return;
        }
        boolean forceStop = prefs.forceStop();
        String self = a.getPackageName();
        Handler ui = new Handler(Looper.getMainLooper());

        new Thread(() -> {
            if (Root.isAvailable()) {
                String msg = launchWithRoot(entries, forceStop, self);
                ui.post(() -> cb.onDone(msg));
            } else {
                ui.post(() -> cb.onDone(launchWithApi(a, entries)));
            }
        }, "split4-launch").start();
    }

    // ------------------------------------------------------------------ root

    private static String launchWithRoot(List<Entry> entries, boolean forceStop, String self) {
        List<String> cmds = new ArrayList<>();
        if (forceStop) {
            for (Entry e : entries) {
                if (!e.cn.getPackageName().equals(self)) {
                    cmds.add("am force-stop " + Root.q(e.cn.getPackageName()));
                }
            }
        }
        for (Entry e : entries) {
            // 0x10000000 = FLAG_ACTIVITY_NEW_TASK
            cmds.add("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
                    + " -f 0x10000000 --windowingMode " + WINDOWING_MODE_FREEFORM
                    + " -n " + Root.q(e.cn.flattenToShortString()));
            cmds.add("sleep 0.4");
        }
        Root.run(cmds.toArray(new String[0]));

        // 起動直後はタスクがまだ出来ていないことがあるので、少し待ってから 2 回合わせる。
        int placed = 0;
        for (int round = 0; round < 2; round++) {
            sleep(round == 0 ? 1200 : 1500);
            placed = resizeTasks(entries);
        }
        if (placed == entries.size()) {
            return entries.size() + "個のアプリを4分割で起動しました";
        }
        return "起動しました (" + placed + "/" + entries.size()
                + " 個を配置)。うまく並ばない場合は「フリーフォームを有効化」後に再起動してください";
    }

    /** dumpsys からパッケージ → タスク ID を調べ、各タスクを所定の位置にリサイズする。 */
    private static int resizeTasks(List<Entry> entries) {
        String dump = Root.run("dumpsys activity activities");
        if (dump == null) return 0;
        Map<String, String> taskOf = new HashMap<>();
        Matcher m = ACTIVITY_RECORD.matcher(dump);
        while (m.find()) {
            // 先に出てくるもの (= より前面) を優先する
            if (!taskOf.containsKey(m.group(1))) taskOf.put(m.group(1), m.group(2));
        }
        List<String> cmds = new ArrayList<>();
        int n = 0;
        for (Entry e : entries) {
            String id = taskOf.get(e.cn.getPackageName());
            if (id == null) continue;
            Rect b = e.bounds;
            cmds.add("am task resize " + id + " " + b.left + " " + b.top + " " + b.right + " " + b.bottom);
            n++;
        }
        if (!cmds.isEmpty()) Root.run(cmds.toArray(new String[0]));
        return n;
    }

    // ------------------------------------------------------------------ non-root

    private static String launchWithApi(Activity a, List<Entry> entries) {
        int ok = 0;
        for (Entry e : entries) {
            ActivityOptions o = ActivityOptions.makeBasic();
            o.setLaunchBounds(e.bounds);
            try {
                // 隠し API。使えない端末でも setLaunchBounds だけでフリーフォームになることが多い。
                Method m = ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class);
                m.invoke(o, WINDOWING_MODE_FREEFORM);
            } catch (Throwable ignored) {
            }
            Intent i = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(e.cn)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                a.startActivity(i, o.toBundle());
                ok++;
            } catch (Exception ex) {
                // 次のアプリへ
            }
        }
        return ok + "個のアプリを起動しました (root なしモード)";
    }

    // ------------------------------------------------------------------ layout

    /** 画面 (ステータスバー・ナビバーを除く) を 2×2 に分けた 4 つの矩形。順番は 左上, 右上, 左下, 右下。 */
    static Rect[] computeCells(Activity a, int gap) {
        Rect area = usableArea(a);
        int w = (area.width() - gap) / 2;
        int h = (area.height() - gap) / 2;
        int x0 = area.left, x1 = area.left + w + gap;
        int y0 = area.top, y1 = area.top + h + gap;
        return new Rect[] {
                new Rect(x0, y0, x0 + w, y0 + h),
                new Rect(x1, y0, area.right, y0 + h),
                new Rect(x0, y1, x0 + w, area.bottom),
                new Rect(x1, y1, area.right, area.bottom),
        };
    }

    private static Rect usableArea(Activity a) {
        WindowManager wm = a.getWindowManager();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowMetrics wmx = wm.getMaximumWindowMetrics();
            Rect b = new Rect(wmx.getBounds());
            Insets in = wmx.getWindowInsets().getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            b.left += in.left;
            b.top += in.top;
            b.right -= in.right;
            b.bottom -= in.bottom;
            return b;
        }
        Point p = new Point();
        wm.getDefaultDisplay().getRealSize(p);
        Rect b = new Rect(0, 0, p.x, p.y);
        b.top += dimen(a.getResources(), "status_bar_height");
        if (a.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
            b.bottom -= dimen(a.getResources(), "navigation_bar_height");
        } else {
            b.right -= dimen(a.getResources(), "navigation_bar_width");
        }
        return b;
    }

    private static int dimen(Resources r, String name) {
        int id = r.getIdentifier(name, "dimen", "android");
        return id > 0 ? r.getDimensionPixelSize(id) : 0;
    }

    // ------------------------------------------------------------------ setup

    /**
     * root でフリーフォームウィンドウ機能を強制的に有効化する。
     * @return 状態を表すメッセージ。初めて有効化した場合は再起動が必要な旨を含む。
     */
    static String enableFreeform() {
        if (!Root.isAvailable()) {
            return "root 権限を取得できませんでした (Magisk 等で許可してください)";
        }
        String before = Root.run("settings get global enable_freeform_support");
        boolean wasOn = before != null && before.trim().equals("1");
        Root.run(
                "settings put global enable_freeform_support 1",
                "settings put global force_resizable_activities 1",
                "settings put global enable_non_resizable_multi_window 1",
                "settings put global hidden_api_policy 1");
        if (wasOn) {
            return "フリーフォームは有効です";
        }
        return "フリーフォームを有効化しました。端末を再起動すると確実に反映されます";
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
