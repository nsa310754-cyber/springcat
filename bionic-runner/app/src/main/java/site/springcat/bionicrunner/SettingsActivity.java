package site.springcat.bionicrunner;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * パソコンの「設定 / コントロールパネル」にあたる画面。
 * システム情報 (CPU・メモリ・ストレージ)、タスクマネージャー、プログラムのインストールと
 * アンインストール、個人設定 (壁紙・検知の入切) をタブで切り替える。
 */
public class SettingsActivity extends Activity {

    /** 開くタブを指定する Intent の extra 名 */
    static final String EXTRA_TAB = "tab";
    static final int TAB_SYSTEM = 0;
    static final int TAB_TASKS = 1;
    static final int TAB_PROGRAMS = 2;
    static final int TAB_PERSONAL = 3;

    private static final int REFRESH_MS = 2500;
    private static final int TOP_PROCESSES = 25;

    /** タスクマネージャーの 1 行 */
    private static final class Task {
        int pid;
        String name;
        double cpu;
        long bytes;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final RootSession session = new RootSession();
    private volatile boolean sessionReady;
    private boolean resumed;
    private ProcStats.Sample lastSample; // 計測スレッドだけが触る
    private volatile long pageSize = 4096;

    private final View[] pages = new View[4];
    private final Button[] tabs = new Button[4];
    private int currentTab;

    private TextView infoText;
    private TextView cpuText;
    private ProgressBar cpuBar;
    private TextView memText;
    private ProgressBar memBar;
    private LinearLayout storageBox;
    private TextView tasksHint;
    private LinearLayout tasksBox;
    private LinearLayout programsBox;
    private LinearLayout yaraBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF111827);

        LinearLayout tabRow = new LinearLayout(this);
        String[] names = {"システム", "タスク", "プログラム", "個人設定"};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            tabs[i] = flatButton(names[i], v -> showTab(index));
            tabRow.addView(tabs[i]);
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setBackgroundColor(0xFF1F2937);
        hs.addView(tabRow);
        root.addView(hs);

        FrameLayout area = new FrameLayout(this);
        pages[TAB_SYSTEM] = buildSystemPage();
        pages[TAB_TASKS] = buildTasksPage();
        pages[TAB_PROGRAMS] = buildProgramsPage();
        pages[TAB_PERSONAL] = buildPersonalPage();
        for (View p : pages) area.addView(p);
        root.addView(area, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        showTab(getIntent().getIntExtra(EXTRA_TAB, TAB_SYSTEM));

        worker.execute(() -> {
            try {
                session.start();
                RootSession.Result r = session.exec("getconf PAGESIZE 2>/dev/null");
                try {
                    pageSize = Long.parseLong(r.output.trim());
                } catch (NumberFormatException ignored) {
                    // 取れなければ一般的な 4096 のまま
                }
            } catch (IOException e) {
                return; // CPU とタスクは「取得できません」のままになる
            }
            sessionReady = true;
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        showTab(intent.getIntExtra(EXTRA_TAB, currentTab));
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        refreshPrograms();
        refreshYara();
        tick.run();
    }

    @Override
    protected void onPause() {
        resumed = false;
        ui.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        worker.execute(session::close);
        worker.shutdown();
        super.onDestroy();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private void showTab(int index) {
        currentTab = Math.max(0, Math.min(pages.length - 1, index));
        for (int i = 0; i < pages.length; i++) {
            pages[i].setVisibility(i == currentTab ? View.VISIBLE : View.GONE);
            tabs[i].setTextColor(i == currentTab ? 0xFF60A5FA : Color.WHITE);
        }
    }

    // ------------------------------------------------------------- 部品

    private Button flatButton(String label, View.OnClickListener l) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(Color.WHITE);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setOnClickListener(l);
        return b;
    }

    /** 枠付きのはっきりしたボタン (ページ内の操作用) */
    private Button boxButton(String label, View.OnClickListener l) {
        Button b = flatButton(label, l);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1F2937);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0x33FFFFFF);
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView heading(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFF60A5FA);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(18), 0, dp(6));
        return t;
    }

    private TextView body(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFFE5E7EB);
        t.setTextSize(14);
        t.setLineSpacing(0, 1.2f);
        return t;
    }

    private ProgressBar bar() {
        ProgressBar p = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        p.setMax(100);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(10));
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(6);
        p.setLayoutParams(lp);
        return p;
    }

    private LinearLayout column() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), 0, dp(16), dp(24));
        return col;
    }

    private View scrolling(View content) {
        ScrollView sv = new ScrollView(this);
        sv.addView(content);
        return sv;
    }

    private String size(long bytes) {
        return Formatter.formatFileSize(this, bytes);
    }

    // ------------------------------------------------------- システムのページ

    private View buildSystemPage() {
        LinearLayout col = column();
        col.addView(heading("この端末"));
        infoText = body("");
        infoText.setTextIsSelectable(true);
        col.addView(infoText);

        col.addView(heading("CPU"));
        cpuText = body("計測中…");
        col.addView(cpuText);
        cpuBar = bar();
        col.addView(cpuBar);

        col.addView(heading("メモリ"));
        memText = body("");
        col.addView(memText);
        memBar = bar();
        col.addView(memBar);

        col.addView(heading("ストレージ"));
        storageBox = new LinearLayout(this);
        storageBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(storageBox);
        col.addView(boxButton("エクスプローラーで開く", v -> startActivity(
                new Intent(this, FilesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))));
        return scrolling(col);
    }

    private void updateStaticInfo() {
        long up = SystemClock.elapsedRealtime() / 1000;
        infoText.setText("機種: " + Build.MANUFACTURER + " " + Build.MODEL
                + "\nAndroid: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                + "\nCPU の種類: " + Arrays.toString(Build.SUPPORTED_ABIS)
                + "\nチップ: " + Build.HARDWARE
                + "\nカーネル: " + System.getProperty("os.version")
                + "\nroot: " + (!sessionReady ? "確認中" : session.isRoot() ? "あり" : "なし")
                + "\n起動してから: " + (up / 3600) + " 時間 " + (up % 3600 / 60) + " 分");

        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
        long used = mi.totalMem - mi.availMem;
        memText.setText("使用中 " + size(used) + " / 全体 " + size(mi.totalMem)
                + "  (空き " + size(mi.availMem) + ")");
        memBar.setProgress(mi.totalMem > 0 ? (int) (used * 100 / mi.totalMem) : 0);

        storageBox.removeAllViews();
        addStorage("内部ストレージ", Environment.getDataDirectory());
        File[] ext = getExternalFilesDirs(null);
        for (int i = 1; i < ext.length; i++) {
            if (ext[i] != null) addStorage("SD カード", ext[i]);
        }
    }

    private void addStorage(String label, File path) {
        try {
            StatFs fs = new StatFs(path.getPath());
            long total = fs.getTotalBytes(), free = fs.getAvailableBytes();
            if (total <= 0) return;
            storageBox.addView(body(label + ": 使用中 " + size(total - free) + " / 全体 " + size(total)
                    + "  (空き " + size(free) + ")"));
            ProgressBar b = bar();
            b.setProgress((int) ((total - free) * 100 / total));
            storageBox.addView(b);
        } catch (IllegalArgumentException ignored) {
            // 取り外されたなどで調べられない
        }
    }

    // ----------------------------------------------- タスクマネージャーのページ

    private View buildTasksPage() {
        LinearLayout col = column();
        col.addView(heading("実行中のプロセス (CPU を使っている順)"));
        tasksHint = body("計測中…");
        tasksHint.setTextColor(0x99FFFFFF);
        tasksHint.setTextSize(12);
        col.addView(tasksHint);
        tasksBox = new LinearLayout(this);
        tasksBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(tasksBox);
        return scrolling(col);
    }

    /** 一定間隔で CPU とプロセスを測り直す。画面が見えている間だけ動く */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            updateStaticInfo();
            if (sessionReady) worker.execute(SettingsActivity.this::measure);
            ui.postDelayed(this, REFRESH_MS);
        }
    };

    /** 別スレッドで実行: /proc を読み、前回との差から使用率を求めて画面に反映する */
    private void measure() {
        final ProcStats.Sample now;
        final List<Task> top = new ArrayList<>();
        final int cpu;
        try {
            now = ProcStats.parse(session.exec(ProcStats.COMMAND).output);
            ProcStats.Sample before = lastSample;
            lastSample = now;
            cpu = ProcStats.cpuPercent(before, now);
            double[] pct = ProcStats.procPercent(before, now);
            for (int i = 0; i < now.procs.size(); i++) {
                ProcStats.Proc p = now.procs.get(i);
                Task t = new Task();
                t.pid = p.pid;
                t.name = p.name;
                t.cpu = pct[i];
                t.bytes = p.rssPages * pageSize;
                top.add(t);
            }
            Collections.sort(top, (a, b) -> a.cpu != b.cpu ? Double.compare(b.cpu, a.cpu)
                    : Long.compare(b.bytes, a.bytes));
            while (top.size() > TOP_PROCESSES) top.remove(top.size() - 1);
            fillNames(top);
        } catch (IOException e) {
            return;
        }
        ui.post(() -> showMeasurement(cpu, now.procs.size(), top));
    }

    /** 上位のプロセスだけ、短い名前の代わりにコマンドライン (アプリならパッケージ名) を調べる */
    private void fillNames(List<Task> top) throws IOException {
        if (top.isEmpty()) return;
        StringBuilder pids = new StringBuilder();
        for (Task t : top) pids.append(' ').append(t.pid);
        RootSession.Result r = session.exec("for p in" + pids + "; do tr '\\0' ' ' "
                + "< /proc/$p/cmdline 2>/dev/null | head -c 70; echo; done");
        String[] lines = r.output.split("\n", -1);
        for (int i = 0; i < top.size() && i < lines.length; i++) {
            String name = lines[i].trim();
            if (!name.isEmpty()) top.get(i).name = name;
        }
    }

    private void showMeasurement(int cpu, int processCount, List<Task> top) {
        int cores = Runtime.getRuntime().availableProcessors();
        if (cpu < 0) {
            cpuText.setText("コア数: " + cores + "\n使用率: "
                    + (session.isRoot() ? "計測中…" : "取得できません (root が必要です)"));
            cpuBar.setProgress(0);
        } else {
            cpuText.setText("コア数: " + cores + "\n使用率: " + cpu + " %");
            cpuBar.setProgress(cpu);
        }
        tasksHint.setText("プロセス数: " + processCount + "  ·  タップで終了できます"
                + (session.isRoot() ? "" : "  ·  root が無いためこのアプリの分だけ表示"));
        tasksBox.removeAllViews();
        for (final Task t : top) {
            TextView row = body(String.format(Locale.US, "%5.1f %%  %8s   %s  (PID %d)",
                    t.cpu, size(t.bytes), t.name, t.pid));
            row.setTypeface(Typeface.MONOSPACE);
            row.setTextSize(12);
            row.setPadding(0, dp(9), 0, dp(9));
            row.setOnClickListener(v -> confirmKill(t));
            tasksBox.addView(row);
        }
    }

    private void confirmKill(final Task t) {
        new AlertDialog.Builder(this)
                .setTitle("プロセスを終了")
                .setMessage(t.name + "\nPID " + t.pid + "\n\nシステムのプロセスを終了すると、"
                        + "画面が固まったり端末が再起動したりすることがあります。")
                .setPositiveButton("終了", (d, w) -> kill(t, false))
                .setNeutralButton("強制終了", (d, w) -> kill(t, true))
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void kill(final Task t, final boolean force) {
        worker.execute(() -> {
            String msg;
            try {
                RootSession.Result r = session.exec("kill " + (force ? "-9 " : "") + t.pid);
                msg = r.ok() ? "終了を送りました: " + t.name : "終了できませんでした: " + r.output;
            } catch (IOException e) {
                msg = e.getMessage();
            }
            final String m = msg;
            ui.post(() -> toast(m));
        });
    }

    // ----------------------------------------------------- プログラムのページ

    private View buildProgramsPage() {
        LinearLayout col = column();
        col.addView(heading("インストール"));
        col.addView(boxButton("実行ファイル (exe) をインストール",
                v -> openTerminal(MainActivity.ACTION_PICK_EXEC)));
        col.addView(boxButton("Android アプリ (APK) をインストール",
                v -> openTerminal(MainActivity.ACTION_PICK_APK)));
        col.addView(heading("インストール済みの実行ファイル"));
        programsBox = new LinearLayout(this);
        programsBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(programsBox);
        col.addView(heading("Android アプリ"));
        col.addView(boxButton("Android アプリをアンインストール…", v -> chooseAppToUninstall()));
        return scrolling(col);
    }

    private void openTerminal(String action) {
        MainActivity.setPending(action, null, null);
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** 検知に追加してある YARA ルールの一覧 */
    private void refreshYara() {
        if (yaraBox == null) return;
        yaraBox.removeAllViews();
        for (final File f : Yara.rules(this)) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.addView(body(f.getName() + "\n" + size(f.length())),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Button remove = flatButton("外す", v -> {
                f.delete();
                refreshYara();
            });
            remove.setTextColor(0xFFF87171);
            row.addView(remove);
            yaraBox.addView(row);
        }
    }

    private void refreshPrograms() {
        programsBox.removeAllViews();
        File[] files = new File(getFilesDir(), "bin").listFiles();
        if (files == null || files.length == 0) {
            programsBox.addView(body("まだありません。上のボタンからインストールできます。"));
            return;
        }
        Arrays.sort(files);
        for (final File f : files) {
            // glibc 用は bin 側が小さな起動スクリプトなので、本体の大きさを表示する
            final File real = new File(new File(getFilesDir(), "glibc-bin"), f.getName());
            // 圧縮ファイルから展開したものは、展開先の一式の大きさを表示する
            final File pkg = Archive.pkgDir(getFilesDir(), f.getName());
            long bytes = pkg.isDirectory() ? Archive.treeSize(pkg)
                    : real.isFile() ? real.length() : f.length();
            LinearLayout row = new LinearLayout(this);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            TextView name = body(f.getName() + "\n" + size(bytes) + (pkg.isDirectory() ? "  ·  圧縮ファイルから展開"
                    : real.isFile() ? "  ·  glibc 用" : ""));
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Button remove = flatButton("アンインストール", v -> new AlertDialog.Builder(this)
                    .setTitle("アンインストール")
                    .setMessage(f.getName() + " を削除します。デスクトップのアイコンも消えます。")
                    .setPositiveButton("アンインストール", (d, w) -> {
                        real.delete();
                        new File(Emu.binDir(this), f.getName()).delete();
                        Icons.delete(this, f.getName());
                        Archive.deletePackage(getFilesDir(), f.getName());
                        f.delete();
                        refreshPrograms();
                    })
                    .setNegativeButton("キャンセル", null)
                    .show());
            remove.setTextColor(0xFFF87171);
            row.addView(remove);
            row.setPadding(0, dp(6), 0, dp(6));
            programsBox.addView(row);
        }
    }

    /** 利用者が入れた Android アプリ (システムアプリ以外) の一覧から選んで削除する */
    private void chooseAppToUninstall() {
        final PackageManager pm = getPackageManager();
        final List<String[]> apps = new ArrayList<>(); // {表示名, パッケージ名}
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            if (ai.packageName.equals(getPackageName())) continue;
            apps.add(new String[]{String.valueOf(ai.loadLabel(pm)), ai.packageName});
        }
        if (apps.isEmpty()) {
            toast("アンインストールできるアプリが見つかりません");
            return;
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        String[] labels = new String[apps.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = apps.get(i)[0] + "\n" + apps.get(i)[1];
        new AlertDialog.Builder(this)
                .setTitle("アンインストールするアプリ")
                .setItems(labels, (d, which) -> uninstallApp(apps.get(which)[0], apps.get(which)[1]))
                .setNegativeButton("閉じる", null)
                .show();
    }

    private void uninstallApp(final String label, final String pkg) {
        if (!sessionReady || !session.isRoot()) {
            // root が無ければ Android 標準の確認画面に任せる
            try {
                startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)));
            } catch (ActivityNotFoundException e) {
                toast("アンインストール画面を開けませんでした");
            }
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("アンインストール")
                .setMessage(label + "\n" + pkg + "\n\nアプリとそのデータを削除します。元に戻せません。")
                .setPositiveButton("アンインストール", (d, w) -> worker.execute(() -> {
                    String msg;
                    try {
                        RootSession.Result r = session.exec("pm uninstall " + RootSession.sq(pkg));
                        msg = r.ok() && r.output.contains("Success")
                                ? "アンインストールしました: " + label
                                : "失敗しました: " + r.output;
                    } catch (IOException e) {
                        msg = e.getMessage();
                    }
                    final String m = msg;
                    ui.post(() -> toast(m));
                }))
                .setNegativeButton("キャンセル", null)
                .show();
    }

    // ------------------------------------------------------- 個人設定のページ

    private View buildPersonalPage() {
        LinearLayout col = column();
        col.addView(heading("デスクトップの壁紙"));
        LinearLayout colors = new LinearLayout(this);
        for (int i = 0; i < Prefs.WALLPAPERS.length; i++) {
            final int index = i;
            Object[] w = Prefs.WALLPAPERS[i];
            Button b = flatButton((String) w[0], v -> {
                Prefs.setWallpaper(this, index);
                toast("壁紙を「" + Prefs.WALLPAPERS[index][0] + "」にしました");
            });
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{(Integer) w[1], (Integer) w[2], (Integer) w[3]});
            bg.setCornerRadius(dp(8));
            b.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
            lp.rightMargin = dp(8);
            colors.addView(b, lp);
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(colors);
        col.addView(hs);

        col.addView(heading("セキュリティ"));
        Switch scan = new Switch(this);
        scan.setText("実行ファイルを開くときに検査する");
        scan.setTextColor(0xFFE5E7EB);
        scan.setTextSize(14);
        scan.setChecked(Prefs.scanEnabled(this));
        scan.setOnCheckedChangeListener((v, on) -> Prefs.setScanEnabled(this, on));
        col.addView(scan);
        TextView note = body("端末を壊したりデータを消したりする操作の記述がないか調べ、見つかれば赤い警告画面で"
                + "実行を止めます。記述を探すだけの簡易検査なので、すべての危険を見つけられるわけではなく、"
                + "安全なツールを危険と判定することもあります。");
        note.setTextSize(12);
        note.setTextColor(0x99FFFFFF);
        col.addView(note);
        TextView yaraNote = body("YARA ルール (.yar) を追加すると、上の検査でそのルールも使います。"
                + "ルールに一致したファイルは、同じ赤い警告画面で実行を止めます。");
        yaraNote.setTextSize(12);
        yaraNote.setTextColor(0x99FFFFFF);
        col.addView(yaraNote);
        yaraBox = new LinearLayout(this);
        yaraBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(yaraBox);
        col.addView(boxButton("YARA ルール (.yar) を追加",
                v -> openTerminal(MainActivity.ACTION_PICK_EXEC)));

        col.addView(heading("Android の設定"));
        col.addView(boxButton("ホームアプリの切り替え", v -> openSystem(Settings.ACTION_HOME_SETTINGS)));
        col.addView(boxButton("Android の設定を開く", v -> openSystem(Settings.ACTION_SETTINGS)));

        col.addView(heading("このアプリについて"));
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            // 自分自身なので起こらない
        }
        col.addView(body("Bionic Runner " + version));
        return scrolling(col);
    }

    private void openSystem(String action) {
        try {
            startActivity(new Intent(action));
        } catch (ActivityNotFoundException e) {
            toast("開けませんでした");
        }
    }
}
