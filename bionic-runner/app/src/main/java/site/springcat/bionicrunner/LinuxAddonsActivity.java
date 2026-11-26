package site.springcat.bionicrunner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
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
            final boolean wineUpdate = Wine.needsUpdate(app);
            final long wineSize = wine || wineUpdate ? Wine.size(app) : 0;
            final boolean wow64 = wine && Wine.wow64Installed(app);
            final long wow64Size = wow64 ? Wine.wow64Size(app) : 0;
            final String wv2Version = wine ? WineWebView2.version(app) : null;
            final long wv2Size = wv2Version != null ? WineWebView2.size(app) : 0;
            final boolean mono = wine && Wine.monoInstalled(app);
            final long monoSize = mono ? Wine.monoSize(app) : 0;
            ui.post(() -> {
                if (destroyed) return;
                summary.setText(ready ? "Linux 環境: Alpine Linux " + Linux.version(app) + " (使用中 "
                        + (size / 1024 / 1024) + "MB)"
                        : "Linux 環境はまだありません (最初のダウンロードのときに、先に約 4MB を取ってきます)");
                list.removeAllViews();
                list.addView(wineRow(wine, wineUpdate, wineSize));
                if (wine) list.addView(wow64Row(wow64, wow64Size));
                if (wine) list.addView(monoRow(mono, monoSize));
                if (wine) list.addView(webView2Row(wv2Version, wv2Size));
                for (LinuxAddons.Addon a : LinuxAddons.ALL) {
                    if (!a.language) list.addView(row(a, LinuxAddons.installed(a, pkgs)));
                }
                // プログラミング言語は見出しの下にまとめる
                list.addView(sectionTitle("プログラミング言語",
                        "コマンドプロンプトで「linux コマンド」の形で使います (例: linux python3 hello.py)。"
                                + "ファイルは /sdcard (端末の内部ストレージ) に置くと、Android 側と受け渡しできます。"));
                for (LinuxAddons.Addon a : LinuxAddons.ALL) {
                    if (a.language) list.addView(row(a, LinuxAddons.installed(a, pkgs)));
                }
            });
        }, "linux-addons-list").start();
    }

    /** 一覧の中の見出し */
    private View sectionTitle(String title, String note) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Math.round(getResources().getDisplayMetrics().density * 12);
        box.setPadding(p / 3, p * 2, p / 3, p / 2);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(0xFF93C5FD);
        t.setTextSize(17);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        box.addView(t);
        TextView n = new TextView(this);
        n.setText(note);
        n.setTextColor(0x99FFFFFF);
        n.setTextSize(12);
        box.addView(n);
        return box;
    }

    private View row(final LinuxAddons.Addon a, final boolean installed) {
        return card(a.title, a.description,
                (installed ? "✓ 入っています" : "ダウンロード 約 " + a.megabytes + "MB")
                        + (a.command != null ? "   起動: linux " + a.command + " &" : "")
                        + (a.usage != null ? "\n例: " + a.usage : ""),
                installed, true, v -> {
                    if (installed) confirmRemove(a);
                    else confirmInstall(a);
                });
    }

    /** Wine (Windows のプログラム)。Linux 環境ではなく、アプリのフォルダ (files/wine) に入れる */
    private View wineRow(final boolean installed, final boolean update, long size) {
        boolean usable = Wine.usable(this);
        String desc = "Windows のプログラム (64bit の exe) を動かす Wine " + Wine.VERSION + "。Box64 の上で動き、"
                + "ウィンドウは X11 の画面に出ます。Debian の公式の配布物を取ってきて、1 つずつ SHA-256 で照合します。\n"
                + "使い方: exe を長押しして「Wine で実行」、またはコマンドプロンプトで wine 名前.exe "
                + "(例: wine cmd /c echo hello、wine notepad)。\n"
                + "ドライブは C: (Windows の環境) と D: (共有フォルダ " + Wine.shared(this).getAbsolutePath()
                + ") だけです。32bit の exe はまだ動きません。";
        String meta = !usable ? "Wine は使えません (この端末では Box64 が使えないためです)"
                : update ? "更新があります: 足りない部品 (字体の設定 fontconfig、約 0.5MB) を足します"
                : installed ? "✓ 入っています (使用中 " + (size / 1024 / 1024) + "MB)"
                : "ダウンロード 約 " + Wine.downloadMegabytes() + "MB (入れたあと約 750MB)";
        View v0 = card("Windows のプログラム (Wine)", desc, meta, installed, usable, v -> {
            if (update) confirmWineUpdate();
            else if (installed) confirmWineRemove();
            else confirmWineInstall();
        });
        if (update) relabel(v0, "更新");
        if (installed && !update) addJapaneseOptions((LinearLayout) v0);
        return v0;
    }

    /** Wine の日本語の設定: 日本語フォントの有無の表示と、「日本語の環境で動かす」の切り替え */
    private void addJapaneseOptions(LinearLayout card) {
        TextView font = new TextView(this);
        String notice = WineFonts.notice(this);
        font.setText(notice == null ? "✓ 日本語フォント (Noto CJK) を Wine でも使います" : "⚠ " + notice);
        font.setTextColor(notice == null ? 0xFF86EFAC : 0xFFFCD34D);
        font.setTextSize(12);
        font.setPadding(0, GameKeys.dp(this, 6), 0, 0);
        card.addView(font);

        final android.widget.CheckBox ja = new android.widget.CheckBox(this);
        ja.setText("日本語の環境で動かす (LANG=ja_JP.UTF-8)");
        ja.setTextColor(0xFFFFFFFF);
        ja.setTextSize(13);
        ja.setChecked(WineFonts.japaneseLocale(this));
        card.addView(ja);
        TextView help = new TextView(this);
        help.setText("日本語版 Windows を前提にしたプログラム (Shift_JIS の文字を使うもの) 向けです。"
                + "Windows の文字コードが日本語 (932) になり、メモ帳などの Wine の画面も日本語になります。"
                + "ふだんは切ったままで大丈夫です。切り替えは次に動かしたときから効きます。");
        help.setTextColor(0xFF94A3B8);
        help.setTextSize(12);
        card.addView(help);
        ja.setOnCheckedChangeListener((b, on) -> {
            try {
                WineFonts.setJapaneseLocale(this, on);
            } catch (IOException e) {
                b.setChecked(!on);
                android.widget.Toast.makeText(this, "設定を変えられませんでした: " + e.getMessage(),
                        android.widget.Toast.LENGTH_LONG).show();
            }
        });
    }

    /** 32bit の exe のための部品 (試験中)。Wine 本体の中 (files/wine/dist) に足す */
    private View wow64Row(final boolean installed, long size) {
        String desc = "32bit の Windows のプログラム (古いソフト・ゲームなどに多い) も Wine で動かせるようにします。"
                + "Debian の 32bit 用の Wine の部品 (libwine:i386) から、Windows の DLL だけを取ってきて "
                + "SHA-256 で照合して使います (64bit のプログラムの中で 32bit のプログラムを動かす「WoW64」の方式)。\n"
                + "⚠ 試験中です。ときどき落ちることがあり、64bit のプログラムより遅くなります。"
                + "落ちたときは wine --diag で原因を調べられます。";
        String meta = installed ? "✓ 入っています (使用中 " + (size / 1024 / 1024) + "MB)。削除すると容量が戻ります"
                : "ダウンロード 約 " + Wine.wow64DownloadMegabytes() + "MB (入れたあと約 600MB 増えます)";
        return card("32bit の exe も動かす (試験中)", desc, meta, installed, true, v -> {
            if (installed) confirmWow64Remove();
            else confirmWow64Install();
        });
    }

    /** .NET のプログラムのための部品 (Wine Mono)。Wine 本体の中 (files/wine/dist) に足す */
    private View monoRow(final boolean installed, long size) {
        String desc = ".NET (C# など) で作られた Windows のプログラムを動かせるようにします。Wine が使う .NET の部品 "
                + "Wine Mono 9.4.0 を WineHQ の公式の配布元から取ってきて、SHA-256 で照合して使います。"
                + "どの CPU 向けでも動く .NET のプログラム (AnyCPU) は 64bit で動くので、32bit の部品は要りません。\n"
                + "Box64 の上なので、ふつうの Windows より起動に時間がかかります。";
        String meta = installed ? "✓ 入っています (使用中 " + (size / 1024 / 1024) + "MB)。削除すると容量が戻ります"
                : "ダウンロード 約 " + Wine.monoDownloadMegabytes() + "MB (入れたあと約 230MB 増えます)";
        return card(".NET のプログラムも動かす (Wine Mono)", desc, meta, installed, true, v -> {
            if (installed) confirmMonoRemove();
            else confirmMonoInstall();
        });
    }

    // ------------------------------------------------------------------ WebView2 (試験中)

    private static final int REQ_WEBVIEW2_ZIP = 41;

    /** Microsoft Edge WebView2 ランタイム (試験中)。使う人が自分の PC から取り出した zip を取り込む */
    private View webView2Row(final String version, long size) {
        String desc = "画面の表示に WebView2 (Microsoft Edge) を使う Windows のプログラムを試すためのものです。"
                + "WebView2 ランタイムは Microsoft のソフトウェアで再配布の条件があるので、このアプリには入っていません。"
                + "自分の Windows の PC から取り出した x64 版のフォルダ (例: 157.0.4341.60) を zip にして、"
                + "スマホのダウンロードなどに置き、ここで取り込みます。\n"
                + "⚠ 試験中です。Wine 10 では表示されないことが多く、メモリも 2GB 以上使います。";
        String meta = version != null ? "✓ 取り込み済み: WebView2 " + version + " (使用中 " + (size / 1024 / 1024) + "MB)"
                : "取り込んでいません";
        View v0 = card("WebView2 を取り込む (試験中)", desc, meta, version != null, true, v -> {
            if (version != null) confirmWebView2Remove();
            else chooseWebView2Zip();
        });
        if (version == null) relabel(v0, "取り込む");
        if (version != null && v0 instanceof LinearLayout) {
            LinearLayout card = (LinearLayout) v0;
            TextView info = new TextView(this);
            info.setText(WineWebView2.info(this));
            info.setTextColor(0xFFCBD5E1);
            info.setTextSize(11);
            info.setTextIsSelectable(true);
            info.setPadding(0, GameKeys.dp(this, 6), 0, 0);
            card.addView(info);
            Button args = GameKeys.button(this, "起動の設定: " + WineWebView2.args(this));
            args.setAllCaps(false);
            args.setOnClickListener(x -> editWebView2Args());
            card.addView(args);
        }
        return v0;
    }

    /** 取り込む zip を選ぶ: ダウンロードの webview2*.zip を候補に出し、ほかのファイルも選べる */
    private void chooseWebView2Zip() {
        final List<File> cands = WineWebView2.candidates();
        List<String> items = new java.util.ArrayList<>();
        for (File f : cands) items.add(f.getName() + "  (" + (f.length() / 1024 / 1024) + "MB・ダウンロード)");
        items.add("ほかのファイルを選ぶ…");
        new AlertDialog.Builder(this)
                .setTitle("WebView2 の zip を選択")
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which < cands.size()) {
                        confirmWebView2Import(cands.get(which), cands.get(which).getName(), false);
                    } else {
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");
                        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed",
                                "application/octet-stream"});
                        try {
                            startActivityForResult(i, REQ_WEBVIEW2_ZIP);
                        } catch (RuntimeException e) {
                            status.setText("ファイルを選ぶ画面を開けません");
                        }
                    }
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_WEBVIEW2_ZIP || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final android.net.Uri uri = data.getData();
        String name = "webview2.zip";
        try (android.database.Cursor cur = getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) name = cur.getString(0);
        } catch (RuntimeException ignored) {
            // 名前が分からなくても続ける
        }
        final String shown = name;
        // 選んだファイルをいったんアプリの中に写してから取り込む (取り込みが終われば消す)
        beginJob("WebView2 を取り込む", app -> {
            File tmp = new File(app.getCacheDir(), "webview2-import.zip");
            log("選んだファイルを読み込んでいます…\n");
            try (InputStream in = app.getContentResolver().openInputStream(uri);
                 java.io.OutputStream os = new java.io.FileOutputStream(tmp)) {
                if (in == null) throw new IOException("開けませんでした");
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            try {
                return importResult(WineWebView2.importZip(app, tmp, shown, line -> log(line + "\n")));
            } finally {
                tmp.delete();
            }
        });
    }

    private void confirmWebView2Import(final File zip, final String shown, boolean unused) {
        new AlertDialog.Builder(this)
                .setTitle("WebView2 を取り込む (試験中)")
                .setMessage(shown + " (" + (zip.length() / 1024 / 1024) + "MB) を取り込みます。中の msedgewebview2.exe が "
                        + "x64 用か、Microsoft の正しい署名があるかを確かめてから、Wine から使えるように置きます。"
                        + "元の zip は消しません。展開すると zip の大きさくらいの容量を使います。")
                .setPositiveButton("取り込む", (d, w) -> beginJob("WebView2 を取り込む",
                        app -> importResult(WineWebView2.importZip(app, zip, shown, line -> log(line + "\n")))))
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private static String importResult(String record) {
        log("\n" + record + "\n");
        return "完了しました: WebView2 を取り込みました (試験中)。WebView2 を使う exe を wine で動かすと、これを使います。"
                + "wine --diag で、段階ごとに動くかを調べられます";
    }

    private void confirmWebView2Remove() {
        new AlertDialog.Builder(this)
                .setTitle("WebView2 を取り込む (試験中)")
                .setMessage("取り込んだ WebView2 を削除して、容量を戻します。Windows の環境の中のリンクとレジストリの印は、"
                        + "次に wine を動かしたときに消えます。")
                .setPositiveButton("削除", (d, w) -> beginJob("WebView2 を削除", app -> {
                    WineWebView2.remove(app);
                    return "完了しました: WebView2 を削除しました";
                }))
                .setNegativeButton("キャンセル", null)
                .show();
    }

    /** WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS を変える (よく使う組み合わせを選ぶか、自分で書く) */
    private void editWebView2Args() {
        final String[] presets = {
                WineWebView2.DEFAULT_ARGS,
                WineWebView2.DEFAULT_ARGS + " --single-process",
                WineWebView2.DEFAULT_ARGS + " --in-process-gpu",
                WineWebView2.DEFAULT_ARGS + " --disable-gpu-compositing --disable-direct-composition",
                "--no-sandbox --in-process-gpu --use-angle=swiftshader --enable-unsafe-swiftshader",
        };
        final android.widget.EditText edit = new android.widget.EditText(this);
        edit.setText(WineWebView2.args(this));
        edit.setTextSize(13);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = GameKeys.dp(this, 16);
        box.setPadding(p, p / 2, p, 0);
        TextView help = new TextView(this);
        help.setText("WebView2 (Chromium) に渡す起動の設定 (WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS)。"
                + "下から選ぶか、直接書いてください。次に wine で動かしたときから効きます。");
        help.setTextSize(12);
        box.addView(help);
        box.addView(edit);
        for (final String ps : presets) {
            Button b = GameKeys.button(this, ps);
            b.setAllCaps(false);
            b.setTextSize(11);
            b.setOnClickListener(x -> edit.setText(ps));
            box.addView(b);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("WebView2 の起動の設定")
                .setView(sv)
                .setPositiveButton("保存", (d, w) -> {
                    try {
                        WineWebView2.setArgs(this, edit.getText().toString());
                    } catch (IOException e) {
                        status.setText("保存できませんでした: " + e.getMessage());
                    }
                    refresh();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void confirmMonoInstall() {
        new AlertDialog.Builder(this)
                .setTitle(".NET のプログラムも動かす (Wine Mono)")
                .setMessage("WineHQ の公式の配布元 (dl.winehq.org) から、Wine Mono 9.4.0 (約 " + Wine.monoDownloadMegabytes()
                        + "MB) をダウンロードして入れます。入れたあと約 230MB 増えます。\n\n"
                        + "いらなくなったら、この画面の「削除」で容量を戻せます。")
                .setPositiveButton("ダウンロード", (d, w) -> beginJob(".NET の部品を入れる", app -> {
                    Wine.installMono(app, line -> log(line + "\n"));
                    return "完了しました: .NET のプログラムも動かせるようになりました。"
                            + "コマンドプロンプトで wine 名前.exe と打つと試せます";
                }))
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void confirmMonoRemove() {
        new AlertDialog.Builder(this)
                .setTitle(".NET のプログラムも動かす (Wine Mono)")
                .setMessage("Wine Mono を削除して、容量を戻します。.NET 以外の Windows のプログラムはそのまま使えます。")
                .setPositiveButton("削除", (d, w) -> beginJob(".NET の部品を削除", app -> {
                    Wine.removeMono(app);
                    return "完了しました: .NET の部品を削除しました";
                }))
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void confirmWow64Install() {
        new AlertDialog.Builder(this)
                .setTitle("32bit の exe も動かす (試験中)")
                .setMessage("Debian の公式の配布元 (deb.debian.org) から、Wine " + Wine.VERSION + " の 32bit 用の部品 "
                        + "(約 " + Wine.wow64DownloadMegabytes() + "MB) をダウンロードして入れます。入れたあと約 600MB "
                        + "増えます。\n\n32bit の exe の実行は試験中で、ときどき落ちることがあります。"
                        + "いらなくなったら、この画面の「削除」で容量を戻せます。")
                .setPositiveButton("ダウンロード", (d, w) -> beginJob("32bit の部品を入れる", app -> {
                    Wine.installWow64(app, line -> log(line + "\n"));
                    return "完了しました: 32bit の exe も動かせるようになりました (試験中)。"
                            + "コマンドプロンプトで wine 名前.exe と打つと試せます";
                }))
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void confirmWow64Remove() {
        new AlertDialog.Builder(this)
                .setTitle("32bit の exe も動かす (試験中)")
                .setMessage("32bit の部品を削除して、容量を戻します。Wine 本体と 64bit の exe はそのまま使えます。")
                .setPositiveButton("削除", (d, w) -> beginJob("32bit の部品を削除", app -> {
                    Wine.removeWow64(app);
                    return "完了しました: 32bit の部品を削除しました";
                }))
                .setNegativeButton("キャンセル", null)
                .show();
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

    private void confirmWineUpdate() {
        new AlertDialog.Builder(this)
                .setTitle("Wine の更新")
                .setMessage("Wine が画面を使うプログラムで止まらないよう、足りない部品 (字体の設定 fontconfig と expat、"
                        + "約 0.5MB) を Debian の公式の配布元から取ってきて足します (SHA-256 で照合します)。")
                .setPositiveButton("更新", (d, w) -> beginJob("Wine を更新", app -> {
                    Wine.install(app, line -> log(line + "\n"));
                    return "完了しました: Wine を更新しました。もう一度 wine で試してください";
                }))
                .setNegativeButton("キャンセル", null)
                .show();
    }

    /** カードのボタンの文字を変える */
    private static void relabel(View card, String label) {
        if (!(card instanceof ViewGroup)) return;
        View top = ((ViewGroup) card).getChildAt(0);
        if (!(top instanceof ViewGroup)) return;
        ViewGroup row = (ViewGroup) top;
        View b = row.getChildAt(row.getChildCount() - 1);
        if (b instanceof Button) ((Button) b).setText(label);
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
