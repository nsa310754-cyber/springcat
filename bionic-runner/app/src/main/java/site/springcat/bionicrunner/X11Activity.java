package site.springcat.bionicrunner;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * X11 (Xサーバー) の画面。
 *
 * 初めて開いたときは、アプリの中の Linux 環境 ({@link Linux}) を用意し (Alpine Linux を約 4MB)、
 * その中に X サーバー (TigerVNC の Xvnc)・端末 (xterm)・ウィンドウの枠 (twm) を apk で入れる。
 * そのあと X サーバーを起動して、画面に映す。X のアプリは、コマンドプロンプトで linux と打って
 * Linux 環境に入り、そこから起動する (DISPLAY=:1 は最初から設定してある)。
 *
 * X サーバーは、この画面を閉じても動き続ける (「X を終了」で止める)。
 */
public class X11Activity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private VncView vnc;
    private LinearLayout panel;
    private TextView status, log;
    private ScrollView logScroll;
    private Button retry, removeButton;
    private Button ctrlButton, altButton, rightButton;
    private volatile boolean destroyed;
    private boolean starting;

    // ------------------------------------------------------- X サーバー (画面を閉じても動き続ける)

    private static Process session;
    private static final StringBuilder sessionLog = new StringBuilder();

    /**
     * 前に起動して、このアプリが見失った X サーバーのプロセス番号 (0 なら無し)。
     * アプリの更新や、Android にアプリを止められたあとも、X サーバー (Xvnc) は裏で動き続けることがある
     */
    private static int orphanPid;

    static synchronized boolean running() {
        if (orphanPid > 0) {
            if (alive(orphanPid)) return true;
            orphanPid = 0;
        }
        if (session == null) return false;
        try {
            session.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    static synchronized void stopSession() {
        if (session != null) session.destroy();
        session = null;
        if (orphanPid > 0) kill(orphanPid);
        orphanPid = 0;
    }

    /** X サーバーを止める。見失った X サーバー (前のアプリが起動したもの) が残っていれば、それも止める */
    static synchronized void stopSession(android.content.Context c) {
        stopSession();
        int p = livePid(c);
        if (p > 0) kill(p);
    }

    private static boolean alive(int pid) {
        try {
            android.system.Os.kill(pid, 0);
            return true;
        } catch (android.system.ErrnoException e) {
            return false;
        }
    }

    private static void kill(int pid) {
        try {
            android.system.Os.kill(pid, android.system.OsConstants.SIGTERM);
        } catch (android.system.ErrnoException ignored) {
            // もう止まっている
        }
    }

    /**
     * Linux 環境の中で動いている X サーバー (画面番号 :1) のプロセス番号。印のファイル (/tmp/.X1-lock) の
     * 番号のプロセスが生きていて、それが Xvnc のときだけ返す (無ければ 0)
     */
    static int livePid(android.content.Context c) {
        File lock = new File(Linux.rootfs(c), "tmp/.X1-lock");
        int pid;
        try (java.io.FileInputStream in = new java.io.FileInputStream(lock)) {
            pid = Integer.parseInt(new String(Icons.readAll(in), StandardCharsets.US_ASCII).trim());
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
        if (pid <= 1 || !alive(pid)) return 0;
        try (java.io.FileInputStream in = new java.io.FileInputStream("/proc/" + pid + "/cmdline")) {
            String cmd = new String(Icons.readAll(in), StandardCharsets.UTF_8);
            return cmd.contains("Xvnc") ? pid : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * 起動する前に、前の X サーバーが残っていないか確かめる。残っていて画面の接続口もあれば、それにつなぐ
     * (true を返す)。接続口が無ければ止める。中身の無い印のファイルは消す
     */
    private static synchronized boolean adoptOrStale(android.content.Context c) {
        File root = Linux.rootfs(c);
        File sock = new File(root, Linux.VNC_SOCKET.substring(1));
        int p = livePid(c);
        if (p > 0 && sock.exists()) {
            orphanPid = p;
            return true;
        }
        if (p > 0) {
            kill(p);
            for (int i = 0; i < 30 && alive(p); i++) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        // 前の X サーバーの印 (ロックと接続口) が残っていると、新しい X サーバーが起動できない
        new File(root, "tmp/.X1-lock").delete();
        new File(root, "tmp/.X11-unix/X1").delete();
        return false;
    }

    private static synchronized void startSession(android.content.Context c, String geometry)
            throws IOException {
        if (running()) return;
        synchronized (sessionLog) {
            sessionLog.setLength(0);
        }
        // 前回の接続口が残っていると、新しい X サーバーの準備ができる前につなぎに行ってしまう
        new File(Linux.rootfs(c), Linux.VNC_SOCKET.substring(1)).delete();
        ProcessBuilder pb = new ProcessBuilder(Linux.argv(c,
                Arrays.asList("/bin/sh", "/usr/local/bin/br-x11-session", geometry)))
                .redirectErrorStream(true).directory(Linux.dir(c));
        Linux.env(c, pb.environment());
        final Process p = pb.start();
        p.getOutputStream().close();
        session = p;
        new Thread(() -> {
            byte[] buf = new byte[4096];
            try (InputStream in = p.getInputStream()) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    synchronized (sessionLog) {
                        sessionLog.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                        if (sessionLog.length() > 16000) sessionLog.delete(0, sessionLog.length() - 12000);
                    }
                }
            } catch (IOException ignored) {
                // 終了した
            }
        }, "x11-log").start();
    }

    /**
     * 画面を開かずに、裏で X サーバーを起動する (Windows のプログラムを動かすときに、ウィンドウが出るまでは
     * コマンドプロンプトに経過を出したままにするため)。もう動いていれば何もしない。
     * Linux 環境や X サーバーがまだ入っていない (初回の準備が要る) ときは false (呼んだ側が画面を開く)。
     * 時間がかかることがあるので UI スレッド以外で呼ぶ
     */
    static boolean startInBackground(android.content.Context c) {
        if (!Linux.supported(c) || !Linux.ready(c) || !Linux.hasX(c)) return false;
        try {
            if (!running()) {
                Linux.configure(c);
                if (!adoptOrStale(c)) startSession(c, backgroundGeometry(c));
            }
            KeepAliveService.ensureStarted(c);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** 裏で起動するときの画面の大きさ (画面を開いたときの geometry() と同じ考え方で、端末の画面全体から) */
    private static String backgroundGeometry(android.content.Context c) {
        android.util.DisplayMetrics m = c.getResources().getDisplayMetrics();
        float s = Math.max(1f, m.density / 1.6f);
        int w = Math.round(m.widthPixels / s), h = Math.round(m.heightPixels / s);
        if (w < 640) {
            h = h * 640 / Math.max(1, w);
            w = 640;
        }
        w = Math.min(4096, w / 8 * 8);
        h = Math.min(4096, Math.max(480, h / 8 * 8));
        return w + "x" + h;
    }

    private static String sessionLogText() {
        synchronized (sessionLog) {
            return sessionLog.toString();
        }
    }

    // ------------------------------------------------------------------ 画面

    /** 起動したら動かすアプリ (Linux の追加機能の id)。デスクトップのアイコンから */
    static final String EXTRA_LAUNCH = "launch";
    private volatile String pendingLaunch;

    /** id から起動のコマンドを決める (アプリが知っている追加機能だけ。任意のコマンドは受け付けない) */
    private static String commandOf(String id) {
        if (id == null) return null;
        for (LinuxAddons.Addon a : LinuxAddons.ALL) if (a.id.equals(id) && a.command != null) return a.command;
        return null;
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        String cmd = commandOf(intent.getStringExtra(EXTRA_LAUNCH));
        if (cmd == null) return;
        if (running() && !starting) launchNow(cmd);
        else pendingLaunch = cmd;
    }

    private void launchNow(String cmd) {
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            try {
                Linux.launch(app, cmd);
            } catch (IOException e) {
                post(() -> appendLog("起動できませんでした: " + e.getMessage() + "\n"));
            }
        }, "x11-launch").start();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pendingLaunch = commandOf(getIntent().getStringExtra(EXTRA_LAUNCH));
        StopAll.register(this); // 「すべて終了」で閉じる
        setTitle("X11");
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFF000000);

        FrameLayout stage = new FrameLayout(this);
        vnc = new VncView(this);
        stage.addView(vnc);
        stage.addView(buildPanel(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        col.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        col.addView(buildBar());
        setContentView(col);

        vnc.onModifiersUsed = this::refreshToggles;
        vnc.listener = new VncView.Listener() {
            @Override
            public void onConnected(int width, int height, String name) {
                panel.setVisibility(View.GONE);
                setTitle("X11 " + width + "×" + height);
            }

            @Override
            public void onDisconnected(String reason) {
                if (destroyed) return;
                showPanel("X の画面との接続が切れました: " + reason, true);
                appendLog(sessionLogText());
            }
        };
        // 画面の大きさが決まってから始める (X の画面の大きさをそれに合わせるため)
        vnc.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l > 0 && b - t > 0 && !starting && !vnc.connected() && panel.getVisibility() == View.VISIBLE
                    && retry.getVisibility() != View.VISIBLE) {
                begin();
            }
        });
    }

    private View buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xF0101826);
        int p = GameKeys.dp(this, 14);
        panel.setPadding(p, p, p, p);
        TextView head = new TextView(this);
        head.setText("X11 (Xサーバー)");
        head.setTextColor(0xFFFFFFFF);
        head.setTextSize(18);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        panel.addView(head);
        status = new TextView(this);
        status.setTextColor(0xFFBFDBFE);
        status.setTextSize(14);
        status.setPadding(0, GameKeys.dp(this, 6), 0, GameKeys.dp(this, 6));
        panel.addView(status);
        retry = GameKeys.button(this, "もう一度");
        retry.setVisibility(View.GONE);
        retry.setOnClickListener(v -> begin());
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(retry);
        removeButton = GameKeys.button(this, "Linux 環境を削除");
        removeButton.setVisibility(View.GONE);
        removeButton.setOnClickListener(v -> confirmRemove());
        actions.addView(removeButton);
        panel.addView(actions);
        logScroll = new ScrollView(this);
        log = new TextView(this);
        log.setTextColor(0xFFD1D5DB);
        log.setTextSize(11);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        logScroll.addView(log);
        panel.addView(logScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return panel;
    }

    private View buildBar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = GameKeys.dp(this, 6);
        row.setPadding(p, p, p, p);
        add(row, "アプリ", v -> chooseApp());
        add(row, "追加機能", v -> startActivity(new android.content.Intent(this, LinuxAddonsActivity.class)));
        add(row, "⌨", v -> vnc.showKeyboard());
        // Esc・F1〜F12・Ctrl・Alt などのある専用キーボードに切り替える (長押しで設定)
        Button kb = add(row, "⌨ 専用キーボード", v -> KeyboardSwitch.pick(this, () -> vnc.showKeyboard()));
        kb.setOnLongClickListener(v -> {
            startActivity(new android.content.Intent(this, KeyboardSettingsActivity.class));
            return true;
        });
        add(row, "Esc", v -> vnc.tapKey(VncView.XK_ESCAPE, false, false));
        add(row, "Tab", v -> vnc.tapKey(VncView.XK_TAB, false, false));
        ctrlButton = add(row, "Ctrl", v -> {
            vnc.ctrlNext = !vnc.ctrlNext;
            refreshToggles();
        });
        altButton = add(row, "Alt", v -> {
            vnc.altNext = !vnc.altNext;
            refreshToggles();
        });
        add(row, "←", v -> vnc.tapKey(VncView.XK_LEFT, false, false));
        add(row, "↑", v -> vnc.tapKey(VncView.XK_UP, false, false));
        add(row, "↓", v -> vnc.tapKey(VncView.XK_DOWN, false, false));
        add(row, "→", v -> vnc.tapKey(VncView.XK_RIGHT, false, false));
        rightButton = add(row, "右クリック", v -> {
            vnc.rightNext = !vnc.rightNext;
            refreshToggles();
        });
        add(row, "ホイール↑", v -> vnc.click(8));
        add(row, "ホイール↓", v -> vnc.click(16));
        add(row, "貼り付け", v -> paste());
        add(row, "全体", v -> vnc.resetZoom());
        add(row, "X を終了", v -> confirmStop());
        add(row, "✕ 閉じる", v -> finish());
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setBackgroundColor(0xFF111827);
        hs.addView(row);
        return hs;
    }

    private Button add(LinearLayout row, String label, View.OnClickListener l) {
        Button b = GameKeys.button(this, label);
        b.setOnClickListener(l);
        // ボタンを押してもキーボードの入力先 (X の画面) が変わらないようにする
        b.setFocusable(false);
        row.addView(b);
        return b;
    }

    private void refreshToggles() {
        tint(ctrlButton, vnc.ctrlNext);
        tint(altButton, vnc.altNext);
        tint(rightButton, vnc.rightNext);
    }

    private void tint(Button b, boolean on) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(on ? 0xFF2563EB : 0xFF374151);
        bg.setCornerRadius(GameKeys.dp(this, 8));
        b.setBackground(bg);
    }

    /** 入っている X のアプリを選んで起動する (X の背景を指でなぞって出るメニューと同じもの) */
    private void chooseApp() {
        final android.content.Context app = getApplicationContext();
        java.util.Set<String> pkgs = LinuxAddons.installedPackages(app);
        final java.util.List<String> labels = new java.util.ArrayList<>();
        final java.util.List<String> commands = new java.util.ArrayList<>();
        labels.add("端末 (xterm)");
        commands.add("xterm");
        for (LinuxAddons.Addon a : LinuxAddons.ALL) {
            if (a.command != null && LinuxAddons.installed(a, pkgs)) {
                labels.add(a.title);
                commands.add(a.command);
            }
        }
        labels.add("＋ アプリを追加する…");
        new AlertDialog.Builder(this)
                .setTitle("起動するアプリ")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which == commands.size()) {
                        startActivity(new android.content.Intent(this, LinuxAddonsActivity.class));
                        return;
                    }
                    if (!running()) {
                        android.widget.Toast.makeText(this, "X サーバーが動いていません", android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        Linux.launch(app, commands.get(which));
                        android.widget.Toast.makeText(this, labels.get(which) + " を起動しています…",
                                android.widget.Toast.LENGTH_SHORT).show();
                    } catch (IOException e) {
                        android.widget.Toast.makeText(this, "起動できませんでした: " + e.getMessage(),
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    /** Android でコピーした文字を X へ渡し、Shift+Insert で貼り付ける */
    private void paste() {
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        android.content.ClipData clip = cm == null ? null : cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0 || clip.getItemAt(0).getText() == null) return;
        String text = clip.getItemAt(0).getText().toString();
        boolean latin = true;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) > 0xff) latin = false;
        if (latin) {
            vnc.sendClipboard(text);
            vnc.key(VncView.XK_SHIFT_L, true);
            vnc.tapKey(VncView.XK_INSERT, false, false);
            vnc.key(VncView.XK_SHIFT_L, false);
        } else {
            // 日本語などは、キーの入力として 1 文字ずつ送る
            vnc.typeText(text);
        }
    }

    private void confirmStop() {
        new AlertDialog.Builder(this)
                .setTitle("X を終了")
                .setMessage("X サーバーと、その上で動いているアプリをすべて終了します。")
                .setPositiveButton("終了", (d, w) -> {
                    vnc.disconnect();
                    stopSession(getApplicationContext());
                    finish();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void showPanel(String message, boolean failed) {
        panel.setVisibility(View.VISIBLE);
        status.setText(message);
        retry.setVisibility(failed ? View.VISIBLE : View.GONE);
        removeButton.setVisibility(failed && Linux.ready(this) ? View.VISIBLE : View.GONE);
    }

    /** 中で入れたものごと Linux 環境を消す (空き容量を戻す)。次に開くと最初から用意し直す */
    private void confirmRemove() {
        final android.content.Context app = getApplicationContext();
        new AlertDialog.Builder(this)
                .setTitle("Linux 環境を削除")
                .setMessage("Linux 環境 (" + (Linux.size(app) / 1024 / 1024) + "MB) を、中で入れたアプリや"
                        + "ファイルごと削除します。")
                .setPositiveButton("削除", (d, w) -> {
                    vnc.disconnect();
                    stopSession(getApplicationContext());
                    status.setText("削除しています…");
                    removeButton.setVisibility(View.GONE);
                    new Thread(() -> {
                        Linux.remove(app);
                        post(() -> showPanel("削除しました。「もう一度」で用意し直します", true));
                    }, "linux-remove").start();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void appendLog(String text) {
        if (text == null || text.isEmpty()) return;
        log.append(text);
        CharSequence all = log.getText();
        if (all.length() > 20000) log.setText(all.subSequence(all.length() - 15000, all.length()));
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void post(Runnable r) {
        ui.post(() -> {
            if (!destroyed) r.run();
        });
    }

    /** X の画面の大きさ: 端末の画面に合わせ、文字が小さくなりすぎない程度にする */
    private String geometry() {
        float density = getResources().getDisplayMetrics().density;
        float s = Math.max(1f, density / 1.6f);
        int w = Math.round(vnc.getWidth() / s), h = Math.round(vnc.getHeight() / s);
        if (w < 640) {
            h = h * 640 / Math.max(1, w);
            w = 640;
        }
        w = Math.min(4096, w / 8 * 8);
        h = Math.min(4096, Math.max(480, h / 8 * 8));
        return w + "x" + h;
    }

    /** 準備 → X サーバーの起動 → 接続、を順に行う */
    private void begin() {
        if (starting) return;
        starting = true;
        showPanel("準備しています…", false);
        if (!Linux.supported(this)) {
            starting = false;
            showPanel("この端末の CPU には対応していません", true);
            return;
        }
        final String geometry = geometry();
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            try {
                if (!Linux.ready(app)) {
                    post(() -> appendLog("Linux 環境 (Alpine Linux) を用意します\n"));
                    Linux.prepare(app, (m, p) -> {
                        if (m != null) post(() -> status.setText(m));
                    });
                    post(() -> appendLog("Alpine Linux " + Linux.version(app) + " を展開しました\n"));
                }
                Linux.configure(app);
                if (!Linux.hasX(app)) {
                    post(() -> status.setText("X サーバーなどを入れています (初回だけ。100MB ほどダウンロードします)…"));
                    int code = runSetup(app);
                    if (code != 0 || !Linux.hasX(app)) {
                        throw new IOException("X サーバーを入れられませんでした (終了コード " + code
                                + ")。ネットワークにつながっているか確かめて「もう一度」を押してください");
                    }
                }
                if (!running()) {
                    if (adoptOrStale(app)) {
                        post(() -> status.setText("前に起動した X サーバーにつなぎます…"));
                    } else {
                        post(() -> status.setText("X サーバーを起動しています (" + geometry + ")…"));
                        startSession(app, geometry);
                    }
                }
                // 接続口ができるまで待つ (初回は字体の準備などで少しかかる)
                File sock = new File(Linux.rootfs(app), Linux.VNC_SOCKET.substring(1));
                long until = System.currentTimeMillis() + 60_000;
                while (!sock.exists()) {
                    if (!running()) {
                        throw new IOException("X サーバーが起動しませんでした");
                    }
                    if (System.currentTimeMillis() > until) {
                        throw new IOException("X サーバーの準備が終わりません");
                    }
                    Thread.sleep(200);
                }
                KeepAliveService.ensureStarted(app);
                // デスクトップのアイコンから開いたときは、そのアプリを起動する
                String launch = pendingLaunch;
                pendingLaunch = null;
                if (launch != null) Linux.launch(app, launch);
                post(() -> {
                    starting = false;
                    status.setText("接続しています…");
                    vnc.connect(sock.getAbsolutePath());
                });
            } catch (IOException | RuntimeException e) {
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                post(() -> {
                    starting = false;
                    showPanel(msg, true);
                    appendLog(sessionLogText());
                });
            } catch (InterruptedException e) {
                post(() -> starting = false);
            }
        }, "x11-start").start();
    }

    // ------------------------------------------- 初回の準備 (apk add)。画面を閉じても最後まで続ける

    private static Process setup;
    private static final StringBuilder setupLog = new StringBuilder();

    /**
     * 準備 (br-x11-setup) を始める。もう動いていれば (前に開いた画面が始めたもの)、新しく始めずに
     * それを使う。apk を 2 つ同時に動かすと、2 つ目が「Failed to open apk database」で失敗するため
     */
    private static synchronized Process startSetup(android.content.Context app) throws IOException {
        if (setup != null) {
            try {
                setup.exitValue();
            } catch (IllegalThreadStateException e) {
                return setup; // まだ動いている
            }
        }
        synchronized (setupLog) {
            setupLog.setLength(0);
        }
        ProcessBuilder pb = new ProcessBuilder(Linux.argv(app,
                Arrays.asList("/bin/sh", "/usr/local/bin/br-x11-setup"))).redirectErrorStream(true)
                .directory(Linux.dir(app));
        Linux.env(app, pb.environment());
        final Process p = pb.start();
        p.getOutputStream().close();
        setup = p;
        // 出力は画面とは別のスレッドで読み続ける (画面を閉じても apk が書き込みで止まらないように)
        new Thread(() -> {
            byte[] buf = new byte[4096];
            try (InputStream in = p.getInputStream()) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    synchronized (setupLog) {
                        setupLog.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                        if (setupLog.length() > 200_000) setupLog.delete(0, 100_000);
                    }
                }
            } catch (IOException ignored) {
                // 終了した
            }
        }, "x11-setup-log").start();
        return p;
    }

    /** 準備を動かし (または動いているものにつなぎ)、出力を画面のログに流す。終了コードを返す */
    private int runSetup(android.content.Context app) throws IOException, InterruptedException {
        Process p = startSetup(app);
        int shown = 0;
        while (true) {
            boolean done;
            try {
                p.exitValue();
                done = true;
            } catch (IllegalThreadStateException e) {
                done = false;
            }
            if (done) Thread.sleep(300); // 最後の出力を読み終えるのを待つ
            String all;
            synchronized (setupLog) {
                if (shown > setupLog.length()) shown = 0; // 古い部分を捨てた
                all = setupLog.substring(shown);
                shown = setupLog.length();
            }
            if (!all.isEmpty()) post(() -> appendLog(all));
            if (done) return p.exitValue();
            if (destroyed) return -1; // 準備はそのまま続く。開き直せば続きを表示する
            Thread.sleep(300);
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        // X サーバーは止めない (あとで開き直せば、そのまま続きが見える)
        if (vnc != null) vnc.disconnect();
        super.onDestroy();
    }
}
