package site.springcat.bionicrunner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;

/**
 * Linux の追加機能の画面。一覧から選んで「ダウンロード」を押すと、Linux 環境に入る
 * (Alpine Linux の公式の配布元から apk で)。入っているものは「削除」で消せる。
 *
 * 入れる作業は、この画面を閉じても最後まで続く (apk を途中で止めると、中の記録が壊れるため)。
 * 一度に動かす作業は 1 つだけ。
 */
public class LinuxAddonsActivity extends Activity {

    // ------------------------------------------- 作業 (画面を閉じても続ける。アプリ全体で 1 つ)

    private static final StringBuilder jobLog = new StringBuilder();
    private static volatile boolean jobRunning;
    private static volatile String jobTitle = "";
    private static volatile String jobResult = "";

    private static void log(String s) {
        synchronized (jobLog) {
            jobLog.append(s);
            if (jobLog.length() > 200_000) jobLog.delete(0, 100_000);
        }
    }

    /** 作業の中身。終わったときに出す 1 行を返す */
    private interface Job {
        String run(Context app) throws IOException;
    }

    /** Linux 環境の中でスクリプトを動かす作業を始める。もう動いていれば false */
    private static boolean startJob(final Context app, final String title, final String script) {
        return startJob(app, title, a -> {
            if (!Linux.ready(a)) {
                log("Linux 環境 (Alpine Linux) を用意します\n");
                final int[] last = {-1};
                Linux.prepare(a, (m, p) -> {
                    if (p / 10 != last[0]) {
                        last[0] = p / 10;
                        log((m != null ? m : p + "%") + "\n");
                    }
                });
                log("Alpine Linux " + Linux.version(a) + " を展開しました\n");
            }
            Linux.configure(a);
            int code = run(a, script);
            return code == 0 ? "完了しました: " + title
                    : "失敗しました (終了コード " + code + ")。ネットワークにつながっているか確かめてください";
        });
    }

    /** 作業を始める。もう動いていれば false */
    private static synchronized boolean startJob(final Context app, final String title, final Job job) {
        if (jobRunning) return false;
        jobRunning = true;
        jobTitle = title;
        jobResult = "";
        synchronized (jobLog) {
            jobLog.setLength(0);
        }
        new Thread(() -> {
            String result;
            try {
                result = job.run(app);
            } catch (IOException | RuntimeException e) {
                result = "失敗しました: " + (e.getMessage() == null ? e.toString() : e.getMessage());
            }
            log(result + "\n");
            jobResult = result;
            jobRunning = false;
        }, "linux-addon").start();
        return true;
    }

    private static int run(Context app, String script) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(Linux.argv(app, Arrays.asList("/bin/sh", "-c", script)))
                .redirectErrorStream(true).directory(Linux.dir(app));
        Linux.env(app, pb.environment());
        Process p = pb.start();
        p.getOutputStream().close();
        byte[] buf = new byte[4096];
        try (InputStream in = p.getInputStream()) {
            int n;
            while ((n = in.read(buf)) > 0) log(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        try {
            return p.waitFor();
        } catch (InterruptedException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ 画面

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private TextView summary, status, logView;
    private ScrollView logScroll;
    private int shown;
    private boolean wasRunning;
    private boolean destroyed;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            String add;
            synchronized (jobLog) {
                if (shown > jobLog.length()) {
                    shown = 0;
                    logView.setText("");
                }
                add = jobLog.substring(shown);
                shown = jobLog.length();
            }
            if (!add.isEmpty()) {
                logView.append(add);
                CharSequence all = logView.getText();
                if (all.length() > 30000) logView.setText(all.subSequence(all.length() - 20000, all.length()));
                logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
            }
            boolean running = jobRunning;
            if (running) {
                status.setText("作業中: " + jobTitle + " (この画面を閉じても続きます)");
                logScroll.setVisibility(View.VISIBLE);
            } else if (!jobResult.isEmpty()) {
                status.setText(jobResult);
            }
            if (running != wasRunning) {
                wasRunning = running;
                refresh();
            }
            ui.postDelayed(this, 400);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Linux の追加機能");
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFF0F172A);
        int p = GameKeys.dp(this, 12);
        col.setPadding(p, p, p, p);

        TextView head = new TextView(this);
        head.setText("Linux の追加機能");
        head.setTextColor(0xFFFFFFFF);
        head.setTextSize(20);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(head);
        TextView about = new TextView(this);
        about.setText("使いたいものの「ダウンロード」を押すと、Linux 環境 (Alpine Linux) に入ります。"
                + "入れるものは Alpine Linux の公式の配布元から取ってきます (アプリには入っていません)。\n"
                + "画面を持つアプリは、X11 の画面の「アプリ」ボタンから起動できます。"
                + "コマンドプロンプトからは linux コマンド名 & でも起動できます。\n"
                + "いちばん上の「Windows のプログラム (Wine)」だけは Linux 環境ではなく、アプリの中に入ります。");
        about.setTextColor(0xFFCBD5E1);
        about.setTextSize(13);
        about.setPadding(0, GameKeys.dp(this, 4), 0, GameKeys.dp(this, 4));
        col.addView(about);
        summary = new TextView(this);
        summary.setTextColor(0xFF93C5FD);
        summary.setTextSize(12);
        col.addView(summary);
        status = new TextView(this);
        status.setTextColor(0xFFFDE68A);
        status.setTextSize(13);
        status.setPadding(0, GameKeys.dp(this, 4), 0, GameKeys.dp(this, 4));
        col.addView(status);

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 3f));

        logScroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextColor(0xFFD1D5DB);
        logView.setTextSize(11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setBackgroundColor(0xFF020617);
        logView.setPadding(p / 2, p / 2, p / 2, p / 2);
        logScroll.addView(logView);
        logScroll.setVisibility(View.GONE);
        col.addView(logScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 2f));
        setContentView(col);
        refresh();
        ui.post(poll);
    }

    /** 一覧を作り直す (入っているかどうかを読み直す) */
    private void refresh() {
        final Context app = getApplicationContext();
        new Thread(() -> {
            final boolean ready = Linux.ready(app);
            final Set<String> pkgs = LinuxAddons.installedPackages(app);
            final long size = ready ? Linux.size(app) : 0;
            final boolean wine = Wine.installed(app);
            final long wineSize = wine ? Wine.size(app) : 0;
            ui.post(() -> {
                if (destroyed) return;
                summary.setText(ready ? "Linux 環境: Alpine Linux " + Linux.version(app) + " (使用中 "
                        + (size / 1024 / 1024) + "MB)"
                        : "Linux 環境はまだありません (最初のダウンロードのときに、先に約 4MB を取ってきます)");
                list.removeAllViews();
                list.addView(wineRow(wine, wineSize));
                for (LinuxAddons.Addon a : LinuxAddons.ALL) list.addView(row(a, LinuxAddons.installed(a, pkgs)));
            });
        }, "linux-addons-list").start();
    }

    private View row(final LinuxAddons.Addon a, final boolean installed) {
        return card(a.title, a.description,
                (installed ? "✓ 入っています" : "ダウンロード 約 " + a.megabytes + "MB")
                        + (a.command != null ? "   起動: linux " + a.command + " &" : ""),
                installed, true, v -> {
                    if (installed) confirmRemove(a);
                    else confirmInstall(a);
                });
    }

    /** Wine (Windows のプログラム)。Linux 環境ではなく、アプリのフォルダ (files/wine) に入れる */
    private View wineRow(final boolean installed, long size) {
        boolean usable = Wine.usable(this);
        String desc = "Windows のプログラム (64bit の exe) を動かす Wine " + Wine.VERSION + "。Box64 の上で動き、"
                + "ウィンドウは X11 の画面に出ます。Debian の公式の配布物を取ってきて、1 つずつ SHA-256 で照合します。\n"
                + "使い方: exe を長押しして「Wine で実行」、またはコマンドプロンプトで wine 名前.exe "
                + "(例: wine cmd /c echo hello、wine notepad)。\n"
                + "ドライブは C: (Windows の環境) と D: (共有フォルダ " + Wine.shared(this).getAbsolutePath()
                + ") だけです。32bit の exe はまだ動きません。";
        String meta = !usable ? "Wine は使えません (この端末では Box64 が使えないためです)"
                : installed ? "✓ 入っています (使用中 " + (size / 1024 / 1024) + "MB)"
                : "ダウンロード 約 " + Wine.downloadMegabytes() + "MB (入れたあと約 750MB)";
        return card("Windows のプログラム (Wine)", desc, meta, installed, usable, v -> {
            if (installed) confirmWineRemove();
            else confirmWineInstall();
        });
    }

    private View card(String titleText, String description, String metaText, boolean installed,
                      boolean enabled, View.OnClickListener onClick) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = GameKeys.dp(this, 10);
        card.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1E293B);
        bg.setCornerRadius(GameKeys.dp(this, 10));
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = GameKeys.dp(this, 8);
        card.setLayoutParams(lp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button b = GameKeys.button(this, installed ? "削除" : "ダウンロード");
        boolean can = enabled && !jobRunning;
        b.setEnabled(can);
        GradientDrawable bb = new GradientDrawable();
        bb.setColor(!can ? 0xFF4B5563 : installed ? 0xFF7F1D1D : 0xFF2563EB);
        bb.setCornerRadius(GameKeys.dp(this, 8));
        b.setBackground(bb);
        b.setOnClickListener(onClick);
        top.addView(b);
        card.addView(top);

        TextView desc = new TextView(this);
        desc.setText(description);
        desc.setTextColor(0xFFCBD5E1);
        desc.setTextSize(13);
        card.addView(desc);
        TextView meta = new TextView(this);
        meta.setText(metaText);
        meta.setTextColor(installed ? 0xFF86EFAC : 0xFF94A3B8);
        meta.setTextSize(12);
        card.addView(meta);
        return card;
    }

    private void confirmWineInstall() {
        new AlertDialog.Builder(this)
                .setTitle("Windows のプログラム (Wine)")
                .setMessage("Debian の公式の配布元 (deb.debian.org) から、Wine " + Wine.VERSION + " と、それが使う"
                        + "ライブラリ (合わせて約 " + Wine.downloadMegabytes() + "MB) をダウンロードして入れます。"
                        + "入れたあとの大きさは約 750MB です。Wi-Fi で行うのがおすすめです。\n\n"
                        + "Wine は LGPL 2.1 以降のオープンソースのソフトウェアです "
                        + "(ライセンスの全文とソースの場所は、設定 → オープンソースのライセンス)。")
                .setPositiveButton("ダウンロード", (d, w) -> beginJob("Wine を入れる", app -> {
                    Wine.install(app, line -> log(line + "\n"));
                    return "完了しました: Wine を入れました。コマンドプロンプトで wine notepad と打つと試せます";
                }))
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void confirmWineRemove() {
        new AlertDialog.Builder(this)
                .setTitle("Windows のプログラム (Wine)")
                .setMessage("Wine と Windows の環境 (C: ドライブ) を削除します。D: ドライブ (共有フォルダ) の"
                        + "ファイルは残します。")
                .setPositiveButton("削除", (d, w) -> beginJob("Wine を削除", app -> {
                    Wine.remove(app);
                    return "完了しました: Wine を削除しました";
                }))
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void confirmInstall(final LinuxAddons.Addon a) {
        new AlertDialog.Builder(this)
                .setTitle(a.title)
                .setMessage("約 " + a.megabytes + "MB をダウンロードして入れます (入れたあとの大きさはその 2〜3 倍)。"
                        + "Wi-Fi で行うのがおすすめです。")
                .setPositiveButton("ダウンロード", (d, w) -> begin(a.title + " を入れる", LinuxAddons.installScript(a)))
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void confirmRemove(final LinuxAddons.Addon a) {
        new AlertDialog.Builder(this)
                .setTitle(a.title)
                .setMessage("Linux 環境から削除します。")
                .setPositiveButton("削除", (d, w) -> begin(a.title + " を削除", LinuxAddons.removeScript(a)))
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void begin(String title, String script) {
        if (!startJob(getApplicationContext(), title, script)) {
            status.setText("ほかの作業が終わるまで待ってください: " + jobTitle);
            return;
        }
        started();
    }

    private void beginJob(String title, Job job) {
        if (!startJob(getApplicationContext(), title, job)) {
            status.setText("ほかの作業が終わるまで待ってください: " + jobTitle);
            return;
        }
        started();
    }

    private void started() {
        shown = 0;
        logView.setText("");
        logScroll.setVisibility(View.VISIBLE);
        KeepAliveService.ensureStarted(getApplicationContext());
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacks(poll);
        super.onDestroy();
    }
}
