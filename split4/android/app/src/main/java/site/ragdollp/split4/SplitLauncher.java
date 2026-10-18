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

    /**
     * @param split  4 分割スロットのアプリを起動する
     * @param hidden 透明 (バックグラウンド) スロットのアプリを透明ディスプレイに起動する
     */
    static void launch(Activity a, boolean split, boolean hidden, Callback cb) {
        Prefs prefs = new Prefs(a);
        Rect[] cells = computeCells(a, prefs.gap());
        List<Entry> entries = new ArrayList<>();
        if (split) {
            for (int i = 0; i < Prefs.SLOTS; i++) {
                ComponentName cn = prefs.getSlot(i);
                if (cn != null) entries.add(new Entry(cn, cells[i]));
            }
        }
        List<ComponentName> hiddenApps = new ArrayList<>();
        if (hidden) {
            for (int i = 0; i < Prefs.HIDDEN_SLOTS; i++) {
                ComponentName cn = prefs.getHidden(i);
                if (cn != null) hiddenApps.add(cn);
            }
        }
        if (entries.isEmpty() && hiddenApps.isEmpty()) {
            cb.onDone("アプリが1つも設定されていません");
            return;
        }
        int hiddenDisplay = -1;
        if (!hiddenApps.isEmpty()) {
            // 透明ディスプレイはこのプロセスが生きている間だけ存在するので常駐サービスで保持する
            hiddenDisplay = HiddenDisplay.ensure(a);
            SplitService.start(a);
        }
        final int displayId = hiddenDisplay;
        boolean forceStop = prefs.forceStop();
        String self = a.getPackageName();
        Handler ui = new Handler(Looper.getMainLooper());

        new Thread(() -> {
            if (Root.isAvailable()) {
                List<String> msgs = new ArrayList<>();
                if (!entries.isEmpty()) msgs.add(launchWithRoot(entries, forceStop, self));
                if (!hiddenApps.isEmpty()) msgs.add(launchHiddenWithRoot(hiddenApps, displayId, forceStop, self));
                String msg = String.join("\n", msgs);
                ui.post(() -> cb.onDone(msg));
            } else {
                ui.post(() -> {
                    String msg = entries.isEmpty() ? "" : launchWithApi(a, entries);
                    if (!hiddenApps.isEmpty()) {
                        msg += (msg.isEmpty() ? "" : "\n") + "透明起動には root 権限が必要です";
                    }
                    cb.onDone(msg);
                });
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

    // ------------------------------------------------------------------ hidden (透明)

    /** 透明ディスプレイ上にアプリを起動する (root の uid 0 は他アプリを非公開ディスプレイへ起動できる)。 */
    private static String launchHiddenWithRoot(List<ComponentName> apps, int displayId,
                                               boolean forceStop, String self) {
        List<String> cmds = new ArrayList<>();
        for (ComponentName cn : apps) {
            if (forceStop && !cn.getPackageName().equals(self)) {
                cmds.add("am force-stop " + Root.q(cn.getPackageName()));
            }
        }
        for (ComponentName cn : apps) {
            cmds.add("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
                    + " -f 0x10000000 --display " + displayId
                    + " -n " + Root.q(cn.flattenToShortString()));
            cmds.add("sleep 0.4");
        }
        Root.run(cmds.toArray(new String[0]));
        sleep(1000);

        int n = 0;
        for (TaskRef t : listTasks()) {
            if (t.display != displayId) continue;
            for (ComponentName cn : apps) {
                if (cn.getPackageName().equals(t.pkg)) {
                    n++;
                    break;
                }
            }
        }
        if (n == 0) {
            return "透明起動に失敗しました (この端末では仮想ディスプレイへの起動が許可されていない可能性があります)";
        }
        return n + "個のアプリを透明 (バックグラウンド) で起動しました";
    }

    /** 透明ディスプレイで動いているアプリをメイン画面へ移し、透明ディスプレイを閉じる。 */
    static void restoreHidden(Callback cb) {
        int displayId = HiddenDisplay.id();
        Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            int n = 0;
            if (displayId >= 0 && Root.isAvailable()) {
                List<String> cmds = new ArrayList<>();
                for (TaskRef t : listTasks()) {
                    if (t.display != displayId) continue;
                    String c = "am display move-stack " + t.rootTask + " 0";
                    if (!cmds.contains(c)) {
                        cmds.add(c);
                        n++;
                    }
                }
                if (!cmds.isEmpty()) Root.run(cmds.toArray(new String[0]));
                sleep(500);
            }
            HiddenDisplay.release();
            int moved = n;
            ui.post(() -> cb.onDone(moved > 0 ? moved + "個のアプリを画面に戻しました" : "透明アプリはありません"));
        }, "split4-restore").start();
    }

    /** 透明ディスプレイを閉じる (上で動いているアプリは終了する)。 */
    static void closeHidden() {
        HiddenDisplay.release();
    }

    /** dumpsys activity activities から読み取った、アプリのタスク 1 つ分の情報。 */
    private static final class TaskRef {
        final String pkg;
        final int task;
        final int rootTask;
        final int display;

        TaskRef(String pkg, int task, int rootTask, int display) {
            this.pkg = pkg;
            this.task = task;
            this.rootTask = rootTask;
            this.display = display;
        }
    }

    private static final Pattern DISPLAY_HEADER = Pattern.compile("^\\s*Display #(\\d+)");
    private static final Pattern ROOT_TASK_HEADER = Pattern.compile("^\\s*(?:RootTask|Stack) #(\\d+)");

    /** どのディスプレイのどのタスクでどのアプリが動いているかの一覧 (前面のものから順)。 */
    private static List<TaskRef> listTasks() {
        List<TaskRef> out = new ArrayList<>();
        String dump = Root.run("dumpsys activity activities");
        if (dump == null) return out;
        int display = -1, root = -1;
        for (String line : dump.split("\n")) {
            Matcher d = DISPLAY_HEADER.matcher(line);
            if (d.find()) {
                display = Integer.parseInt(d.group(1));
                root = -1;
                continue;
            }
            Matcher r = ROOT_TASK_HEADER.matcher(line);
            if (r.find()) {
                root = Integer.parseInt(r.group(1));
                continue;
            }
            Matcher m = ACTIVITY_RECORD.matcher(line);
            if (m.find()) {
                int task = Integer.parseInt(m.group(2));
                out.add(new TaskRef(m.group(1), task, root >= 0 ? root : task, display));
            }
        }
        return out;
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
