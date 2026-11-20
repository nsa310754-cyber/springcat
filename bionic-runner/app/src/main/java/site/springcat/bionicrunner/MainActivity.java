package site.springcat.bionicrunner;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends Activity {

    /** デスクトップ画面から頼まれる操作 */
    static final String ACTION_RUN = "run";
    static final String ACTION_PICK_EXEC = "pickExec";
    static final String ACTION_PICK_APK = "pickApk";
    static final String ACTION_AI = "ai";
    /** ソースのファイルを実行する (エクスプローラーの「実行」から)。名前 = ファイルの場所、引数 = モード */
    static final String ACTION_RUN_FILE = "runFile";
    /** Windows のプログラム (exe) を Wine で動かす (エクスプローラーの「Wine で実行」から)。名前 = ファイルの場所 */
    static final String ACTION_WINE = "wine";

    /**
     * デスクトップ画面からの依頼 {操作, 登録名, 引数}。Intent の extra ではなく同じプロセス内の
     * 変数で渡す。この画面は「アプリで開く」用に外部公開しているので、Intent で受けると
     * 他のアプリが root シェルに任意のコマンドを流せてしまうため。
     */
    private static String[] pendingRequest;

    static synchronized void setPending(String action, String name, String args) {
        pendingRequest = action == null ? null : new String[]{action, name, args};
    }

    private static synchronized String[] takePending(boolean shellReady) {
        String[] p = pendingRequest;
        // 実行の依頼はシェルが立ち上がるまで取っておく
        if (p != null && (ACTION_RUN.equals(p[0]) || ACTION_RUN_FILE.equals(p[0]) || ACTION_WINE.equals(p[0]))
                && !shellReady) {
            return null;
        }
        pendingRequest = null;
        return p;
    }

    private static final int REQ_PICK_EXEC = 1;
    private static final int REQ_PICK_APK = 2;
    private static final int REQ_PICK_YARA_TARGET = 4;

    /** 「ファイルをスキャン」で対象を選んでいる間の YARA ルール (一時ファイル) とその名前 */
    private File yaraRule;
    private String yaraRuleName;
    private static final int REQ_STORAGE = 3;

    /** 画面に保持する出力の上限 (文字数)。超えたら古い方から捨てる */
    private static final int MAX_OUTPUT = 120_000;
    private static final int TRIM_TO = 80_000;



    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder pending = new StringBuilder();
    private boolean flushScheduled;

    private TextView status;
    private TextView output;
    private ScrollView scroll;
    /** 全画面で描くプログラムを表示する文字端末の画面 (ふだんは隠れていて、ログ表示が見えている) */
    private View fullBox;
    private TermView fullTerm;
    /** 出力を読み解いて、ログ表示と全画面に振り分ける */
    private AnsiLog ansi;
    /** 全画面モードに入ったのが代替画面 (ESC[?1049h) か */
    private boolean fullAlt;
    /** 文字端末の画面がまだできていない (画面の大きさが決まる前) うちに届いた全画面の出力 */
    private final StringBuilder fullPending = new StringBuilder();
    /** 疑似端末の大きさ (ptyrun の既定)。全画面で描くプログラムは、この大きさの画面だと思って描く */
    private static final int PTY_COLS = 80, PTY_ROWS = 24;
    private EditText input;

    private RootShell shell;
    /** シェルの起動と初期設定が済み、コマンドを送ってよい状態か (UI スレッドだけが触る) */
    private boolean shellReady;
    private int shellGeneration;

    private File binDir;
    private File homeDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appContext = getApplicationContext();
        binDir = new File(getFilesDir(), "bin");
        homeDir = new File(getFilesDir(), "home");
        binDir.mkdirs();
        homeDir.mkdirs();
        // 前の版で登録した Linux の実行ファイルの名前に、種類の拡張子 (.Belf・.Gelf・.elf) を付け直す
        final int renamed = ElfName.migrate(this);
        // x86_64 などのエミュレータ用の起動スクリプトを、今の版の形 (Box64 対応) に作り直す
        // あわせて、Box64 が自分を起動し直すときのリンクと、wine コマンドの中身も今の版に合わせる
        new Thread(() -> {
            android.content.Context app = getApplicationContext();
            Emu.rewriteWrappers(app);
            Box64.linkLoader(app);
            Wine.writeScript(app);
        }, "emu-wrappers").start();
        if (renamed > 0) {
            ui.post(() -> note("登録済みの Linux の実行ファイル " + renamed + " 個の名前に、種類の拡張子"
                    + " (bionic 用は .Belf、glibc 用は .Gelf、そのほかは .elf) を付けました"));
        }
        // 前回、展開の途中で止められて残った仮の展開先を片付ける
        if (savedInstanceState == null) Archive.cleanStaging(getFilesDir());
        SevenZip.init(getApplicationInfo().nativeLibraryDir);
        buildUi();
        // glibc 用プログラムの起動スクリプトを今の版の作りに合わせて作り直してから、シェルを起動する
        Glibc.rewriteWrappers(this);
        // 前に「root のシェルでは作業領域に入れない」と分かった端末では、最初から root を使わない
        startShell(Prefs.shellNoRoot(this));
        handleViewIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleViewIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        consumePending();
    }

    private void consumePending() {
        // 「起動中」や「これから起動し直す」シェルには渡さない。渡すと、そのシェルと一緒に依頼が消える
        String[] p = takePending(shellReady && shell != null && shell.isAlive());
        if (p == null) return;
        switch (p[0]) {
            case ACTION_PICK_EXEC:
                pick(REQ_PICK_EXEC, "*/*");
                break;
            case ACTION_PICK_APK:
                pick(REQ_PICK_APK, "application/vnd.android.package-archive");
                break;
            case ACTION_AI:
                showAiPrompt();
                break;
            case ACTION_RUN_FILE:
                sendLine("__br_run " + sq(p[2] == null ? "" : p[2]) + " " + sq(p[1]));
                break;
            case ACTION_WINE:
                runWine(p[1]);
                break;
            case ACTION_RUN:
                File f = new File(binDir, safeName(p[1]));
                if (f.isFile()) {
                    run(f, p[2] == null ? "" : p[2]);
                } else {
                    note("見つかりません: " + p[1]);
                }
                break;
            default:
                break;
        }
    }

    @Override
    protected void onDestroy() {
        shellGeneration++;
        if (shell != null) shell.close();
        if (webRun != null) {
            // 実行モードで動かしていたページを止める
            ui.removeCallbacks(webRunTimeout);
            webRun.destroy();
            webRun = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 画面

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        status = new TextView(this);
        status.setPadding(dp(12), dp(8), dp(12), dp(8));
        status.setTextSize(13);
        status.setBackgroundColor(0xFF111418);
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(4), 0, dp(4), 0);
        bar.addView(barButton("デスクトップ", v ->
                startActivity(new Intent(this, DesktopActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))));
        bar.addView(barButton("ファイル", v ->
                startActivity(new Intent(this, FilesActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))));
        bar.addView(barButton("実行ファイル", v -> pick(REQ_PICK_EXEC, "*/*")));
        bar.addView(barButton("APKインストール",
                v -> pick(REQ_PICK_APK, "application/vnd.android.package-archive")));
        bar.addView(barButton("AIに作らせる", v -> showAiPrompt()));
        bar.addView(barButton("登録済み", v -> showRegistered()));
        bar.addView(barButton("中断 ^C", v -> {
            cancelPasted();
            if (webRun != null) stopWebRun(130, "中断しました");
            else if (shell != null) shell.interrupt();
        }));
        // Esc・F1〜F12・Ctrl・Alt などのある専用キーボードに切り替える (長押しで設定)
        Button kb = barButton("⌨ 専用キーボード", v -> KeyboardSwitch.pick(this, () -> {
            input.requestFocus();
        }));
        kb.setOnLongClickListener(v -> {
            startActivity(new Intent(this, KeyboardSettingsActivity.class));
            return true;
        });
        bar.addView(kb);
        bar.addView(barButton("再起動", v -> startShell()));
        bar.addView(barButton("消去", v -> output.setText("")));
        bar.addView(barButton("権限", v -> requestStorageAccess()));
        bar.addView(barButton("root で実行", v -> askRootCommand()));
        bar.addView(barButton("root 確認", v -> checkRoot()));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setBackgroundColor(0xFF111418);
        hs.addView(bar);
        root.addView(hs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        output = new TextView(this);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextSize(12);
        output.setTextColor(0xFFE5E7EB);
        output.setPadding(dp(8), dp(8), dp(8), dp(8));
        output.setTextIsSelectable(true);
        output.setText("", TextView.BufferType.EDITABLE);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(output, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // ログ表示と、全画面で描くプログラム用の文字端末の画面を同じ場所に重ねる。
        // 文字端末は隠しておくだけで、大きさは決めておく (画面の準備が切り替えに間に合うように)
        android.widget.FrameLayout outputArea = new android.widget.FrameLayout(this);
        outputArea.addView(scroll);
        fullTerm = new TermView(this, reply -> {
            // 端末への問い合わせ (カーソル位置など) の答えを、プログラムへ返す
            if (shell != null) shell.sendRaw(reply);
        }, false);
        fullTerm.setFixedSize(PTY_COLS, PTY_ROWS);
        fullTerm.onReady = () -> {
            if (fullPending.length() > 0) {
                fullTerm.screen.feed(fullPending.toString());
                fullPending.setLength(0);
                fullTerm.invalidate();
            }
        };
        android.widget.FrameLayout termWrap = new android.widget.FrameLayout(this);
        termWrap.setBackgroundColor(Color.BLACK);
        termWrap.addView(fullTerm);
        Button backToLog = new Button(this, null, android.R.attr.borderlessButtonStyle);
        backToLog.setText("ログに戻る");
        backToLog.setAllCaps(false);
        backToLog.setTextSize(12);
        backToLog.setTextColor(Color.WHITE);
        backToLog.setBackgroundColor(0x99374151);
        backToLog.setOnClickListener(v -> ansi.leaveFull());
        termWrap.addView(backToLog, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36), Gravity.BOTTOM | Gravity.END));
        termWrap.setVisibility(View.INVISIBLE);
        fullBox = termWrap;
        outputArea.addView(termWrap);
        ansi = new AnsiLog(new AnsiLog.Sink() {
            @Override
            public void text(String s, int fg, int bg, boolean bold) {
                appendLog(s, fg, bg, bold);
            }

            @Override
            public void clearLog() {
                output.setText("", TextView.BufferType.EDITABLE);
            }

            @Override
            public void enterFull(boolean altScreen) {
                showFull(altScreen);
            }

            @Override
            public void full(String raw) {
                feedFull(raw);
            }

            @Override
            public void exitFull() {
                hideFull();
            }

            @Override
            public void prompt() {
                // 前のコマンドが終わった: 貼り付けた複数行の、次の行を送る
                ui.post(MainActivity.this::sendNextPasted);
            }
        });
        root.addView(outputArea, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setBackgroundColor(0xFF111418);
        line.setPadding(dp(8), 0, dp(4), 0);
        input = new EditText(this);
        input.setTypeface(Typeface.MONOSPACE);
        input.setTextSize(14);
        input.setSingleLine(true);
        input.setHint("コマンド / プログラムへの入力");
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        // 複数行を貼り付けたら (Termux と同じように) 1 行ずつ実行する。1 行目が終わって
        // プロンプトに戻ってから 2 行目を送る (前のコマンドが 2 行目を入力として読んでしまわないように)
        input.setFilters(new android.text.InputFilter[]{(source, start, end, dest, dstart, dend) -> {
            String added = source.subSequence(start, end).toString();
            if (added.indexOf('\n') < 0 && added.indexOf('\r') < 0) return null;
            String all = dest.subSequence(0, dstart) + added + dest.subSequence(dend, dest.length());
            ui.post(() -> pasteLines(all));
            return dest.subSequence(dstart, dend); // 入力欄はいったんそのまま (すぐ空にする)
        }});
        input.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEND || enter) {
                submitInput();
                return true;
            }
            // ENTER の ACTION_UP も消費しておく (二重送信と改行挿入を防ぐ)
            return event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
        });
        line.addView(input, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button send = new Button(this, null, android.R.attr.borderlessButtonStyle);
        send.setText("送信");
        send.setTextColor(0xFF4ADE80);
        send.setOnClickListener(v -> submitInput());
        line.addView(send);
        root.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    /**
     * キーボード (専用キーボードや、つないだキーボード) のキーを、実行中のプログラムへ送る。
     * Ctrl+C は中断。F1〜F12・Esc・Tab・↑↓・PgUp/PgDn・Ctrl や Alt の組み合わせは、xterm と同じ
     * バイト列にして送る。入力欄の編集に使うキー (←→・Home・End・Backspace・Enter・Ctrl+V などの
     * 貼り付け) と普通の文字は、これまでどおり入力欄に入る。全画面の表示中で入力欄が空なら、
     * ←→・Backspace などもプログラムへ送る (vim などの操作のため)。打った内容はどこにも残さない
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (terminalKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    private boolean terminalKey(KeyEvent e) {
        if (shell == null || input == null || !input.hasFocus()) return false;
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || KeyEvent.isModifierKey(code)
                || code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false;
        }
        boolean ctrl = e.isCtrlPressed(), alt = e.isAltPressed(), meta = e.isMetaPressed();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean full = fullBox != null && fullBox.getVisibility() == View.VISIBLE;
        boolean lineEmpty = input.getText().length() == 0;
        // Ctrl+C: 実行中のコマンドを中断 (入力欄で文字を選んでいるときは、これまでどおりコピー)
        if (ctrl && !alt && !meta && code == KeyEvent.KEYCODE_C && (full || !input.hasSelection())) {
            if (down && e.getRepeatCount() == 0) {
                cancelPasted();
                if (webRun != null) stopWebRun(130, "中断しました");
                else shell.interrupt();
            }
            return true;
        }
        boolean plain = !ctrl && !alt && !meta;
        boolean editKey = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_MOVE_HOME || code == KeyEvent.KEYCODE_MOVE_END
                || code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_FORWARD_DEL
                || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (plain && editKey) {
            // 入力欄の編集に使う。全画面のときは、入力欄が空なら (Enter 以外は) プログラムへ送る
            boolean enter = code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
            if (!full || !lineEmpty || enter) return false;
        }
        // 入力欄の切り取り・コピー・貼り付け・全選択・元に戻す
        if (ctrl && !alt && !meta && (code == KeyEvent.KEYCODE_V || code == KeyEvent.KEYCODE_X
                || code == KeyEvent.KEYCODE_A || code == KeyEvent.KEYCODE_Z) && !full) {
            return false;
        }
        // 普通の文字 (Shift だけ付いたものも) は入力欄へ
        if (plain && code != KeyEvent.KEYCODE_TAB && code != KeyEvent.KEYCODE_ESCAPE
                && e.getUnicodeChar() != 0) {
            return false;
        }
        String seq = KeySeq.encode(e, fullTerm != null && fullTerm.screen != null && fullTerm.screen.appCursor);
        if (seq == null) return false;
        if (down) shell.sendRaw(seq);
        return true;
    }

    private Button barButton(String label, View.OnClickListener l) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(0xFFE5E7EB);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setOnClickListener(l);
        return b;
    }

    private void setStatus(String text, int color) {
        status.setText(text);
        status.setTextColor(color);
    }

    /** アプリ自身からのお知らせ (シェルの出力と区別するため » を付ける) */
    // 「inaccessible or not found」の原因を最後に調べた名前と時刻 (同じものを何度も調べない)
    private String lastNotFound;
    private long lastNotFoundAt;

    /**
     * シェルの出力に「inaccessible or not found」が出たら、名前の挙がったコマンドやファイルを調べて、
     * 本当の原因と直し方を表示する (この文言は、見つからないときも実行できないときも同じで、
     * 文言だけでは原因が分からないため)。
     */
    private void watchNotFound(String text) {
        if (!text.contains("not found")) return;
        final String[] hit = NotFoundDoctor.find(text);
        if (hit == null) return;
        long now = android.os.SystemClock.elapsedRealtime();
        String key = hit[0] + "|" + hit[2];
        synchronized (this) {
            if (key.equals(lastNotFound) && now - lastNotFoundAt < 10_000) return;
            lastNotFound = key;
            lastNotFoundAt = now;
        }
        final boolean root = shell != null && shell.isRoot();
        final String[] path = {binDir.getAbsolutePath(), Tools.dir(this).getAbsolutePath(),
                "/system/bin", "/system/xbin", "/vendor/bin", "/product/bin"};
        new Thread(() -> {
            String why = NotFoundDoctor.diagnose(this, hit[2], hit[0], path, root);
            note("[診断] " + why);
        }, "not-found").start();
    }

    private void note(String text) {
        appendOutput("» " + text + "\n");
    }

    /** どのスレッドからでも呼べる。出力が大量でも UI が詰まらないよう、まとめて反映する */
    private void appendOutput(String text) {
        text = takeRunRequests(text);
        if (text.isEmpty()) return;
        synchronized (pending) {
            pending.append(text);
            if (pending.length() > MAX_OUTPUT) {
                pending.delete(0, pending.length() - TRIM_TO);
            }
            if (flushScheduled) return;
            flushScheduled = true;
        }
        ui.postDelayed(this::flushOutput, 50);
    }

    // シェルの出力に混ざって届く「このファイルを実行して」の合図 (__br_run が出す) の途中経過
    private final StringBuilder runMarker = new StringBuilder();
    private boolean inRunMarker;

    /**
     * 出力から合図 (制御文字 RS と US で挟まれた "BRRUN|モード|場所") を抜き取り、残りを返す。
     * 合図は読み取りの切れ目で分かれて届くことがあるので、続きを覚えておく。
     */
    private synchronized String takeRunRequests(String text) {
        if (!inRunMarker && text.indexOf('\u001E') < 0) return text;
        StringBuilder rest = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inRunMarker) {
                if (c == '\u001F') {
                    inRunMarker = false;
                    final String m = runMarker.toString();
                    runMarker.setLength(0);
                    ui.post(() -> onRunMarker(m));
                } else if (runMarker.length() < 8192) {
                    runMarker.append(c);
                }
            } else if (c == '\u001E') {
                inRunMarker = true;
            } else {
                rest.append(c);
            }
        }
        return rest.toString();
    }

    private void onRunMarker(String marker) {
        if (marker.startsWith("BRWINE|")) {
            int gs = marker.indexOf('\u001D');
            if (gs > 0) runWineAsApp(marker.substring(7, gs), marker.substring(gs + 1));
            return;
        }
        if (marker.startsWith("BRX11|")) {
            if (marker.endsWith("|stop")) {
                X11Activity.stopSession();
            } else if (marker.endsWith("|addons")) {
                startActivity(new Intent(this, LinuxAddonsActivity.class));
            } else {
                startActivity(new Intent(this, X11Activity.class));
            }
            return;
        }
        if (!marker.startsWith("BRRUN|")) return;
        int bar = marker.indexOf('|', 6);
        if (bar < 0) return;
        chooseRunMode(marker.substring(6, bar), marker.substring(bar + 1));
    }

    private void flushOutput() {
        String text;
        synchronized (pending) {
            text = pending.toString();
            pending.setLength(0);
            flushScheduled = false;
        }
        // 色の指定はログに反映し、全画面で描くプログラムの出力は文字端末の画面へ回す
        ansi.feed(text);
    }

    /** ログに文字を足す。fg / bg は色 (-1 は既定の色のまま) */
    private void appendLog(String text, int fg, int bg, boolean bold) {
        if (fg == -1 && bg == -1 && !bold) {
            output.append(text);
        } else {
            android.text.SpannableString span = new android.text.SpannableString(text);
            int flags = android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
            if (fg != -1) span.setSpan(new android.text.style.ForegroundColorSpan(fg), 0, text.length(), flags);
            if (bg != -1) span.setSpan(new android.text.style.BackgroundColorSpan(bg), 0, text.length(), flags);
            if (bold) span.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, text.length(), flags);
            output.append(span);
        }
        Editable e = output.getEditableText();
        if (e != null && e.length() > MAX_OUTPUT) {
            e.delete(0, e.length() - TRIM_TO);
        }
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    /** 全画面モードに切り替える。前のプログラムが描いたものは消して、まっさらな画面から始める */
    private void showFull(boolean altScreen) {
        fullAlt = altScreen;
        fullPending.setLength(0);
        if (fullTerm.screen != null) {
            fullTerm.screen.feed("\u001Bc"); // 端末の初期化 (画面を消し、色やカーソルを元に戻す)
        }
        fullBox.setVisibility(View.VISIBLE);
    }

    private void feedFull(String raw) {
        if (fullTerm.screen == null) {
            fullPending.append(raw);
            return;
        }
        fullTerm.screen.feed(raw);
        fullTerm.invalidate();
    }

    /**
     * 全画面モードを終えて、ログ表示に戻す。それまでのログはそのまま。
     * 代替画面を使わないプログラムは、普通の端末と同じく、最後に描いたものをログに残す。
     * 何も描かずに画面を消しただけ (clear コマンドなど) なら、これまでどおりログを消す。
     */
    private void hideFull() {
        fullBox.setVisibility(View.INVISIBLE);
        if (fullAlt || fullTerm.screen == null) return;
        String shown = fullTerm.screen.text().replaceAll("[ \t]+\n", "\n").replaceAll("\n+$", "");
        if (shown.trim().isEmpty()) {
            output.setText("", TextView.BufferType.EDITABLE);
        } else {
            appendLog(shown + "\n", -1, -1, false);
        }
    }

    // ---------------------------------------------------------------- シェル

    private void startShell() {
        startShell(false);
    }

    /**
     * noRoot が真なら、root が使えても使わずに起動する。root のシェルがアプリの作業領域に
     * 入れない端末では、root で起動しても登録したプログラムを実行できないため。
     */
    /**
     * コマンドプロンプトのモードを切り替える。/su は root モード、/exsu は普通のモード (アプリの権限)。
     * シェルを起動し直すので、今いるフォルダや実行中のコマンドは引き継がない。選んだモードは次回も使う。
     */
    private void switchMode(boolean root) {
        appendOutput("> " + (root ? "/su" : "/exsu") + "\n");
        boolean nowRoot = shell != null && shell.isAlive() && shell.isRoot();
        if (nowRoot == root) {
            note(root ? "すでに root モードです (uid 0)。普通のモードに戻るには /exsu"
                    : "すでに普通のモードです。root モードにするには /su");
            return;
        }
        Prefs.setShellNoRoot(this, !root);
        note(root ? "root モードに切り替えます… (root の許可を求められたら「許可」してください)"
                : "普通のモード (アプリの権限) に切り替えます…");
        startShell(!root);
    }

    private void startShell(final boolean noRoot) {
        final int gen = ++shellGeneration;
        shellReady = false;
        if (shell != null) shell.close();
        setStatus("シェル起動中… su の許可ダイアログが出たら「許可」してください", 0xFFFACC15);

        final RootShell s = new RootShell(new RootShell.Listener() {
            @Override
            public void onOutput(String text) {
                if (gen == shellGeneration) {
                    appendOutput(text);
                    watchNotFound(text);
                }
            }

            @Override
            public void onExit(int code) {
                ui.post(() -> {
                    if (gen != shellGeneration) return;
                    shellReady = false;
                    note("シェルが終了しました (終了コード " + code + ")。「再起動」で起動し直せます");
                    setStatus("シェル停止中", 0xFFF87171);
                });
            }
        }, ptyHelperPath());
        shell = s;

        s.setNoRoot(noRoot);
        final GameHost gameHost = GameHost.get(this);
        final Control control = Control.get(this);
        final File rc = writeShellRc();
        // 同梱のコマンド (br・7z・yara) は files/tools に置いたリンクから呼ぶ (スクリプトからも使えるように)
        final File tools = Tools.install(this);
        final String env = "export PATH=" + sq(binDir.getAbsolutePath()) + ":"
                + sq(tools.getAbsolutePath()) + ":$PATH"
                + "; export HOME=" + sq(homeDir.getAbsolutePath())
                + "; export TMPDIR=" + sq(getCacheDir().getAbsolutePath())
                // root なしでも実行できる補助プログラム (glibc のローダなど) の置き場所
                + "; export BR_NATIVE=" + sq(getApplicationInfo().nativeLibraryDir)
                + "; export BR_YARA_RULES=" + sq(Yara.rulesDir(this).getAbsolutePath())
                // Linux 環境 (linux・x11 の命令) の場所と、proot が使う設定。root のシェルから
                // アプリの権限に戻すときのために、アプリの利用者番号も渡す
                + "; " + Linux.shellExports(this)
                // C のソースから実行ファイルを作るコマンド mkexe の本体と、コンパイラの補助一式
                + (Mkexe.install(this) ? "; export BR_MKEXE=" + sq(Mkexe.script(this).getAbsolutePath())
                        + "; export BR_MKEXE_SUPPORT=" + sq(Mkexe.support(this).getAbsolutePath()) : "")
                + "; export BR_APP_UID=" + android.os.Process.myUid()
                // Windows のプログラムを動かすコマンド wine の本体 (Wine は「追加機能」から入れる)
                + "; export BR_WINE=" + sq(Wine.script(this).getAbsolutePath())
                // 絵や音を出すプログラム (同梱の SDL2) がアプリの画面へつなぐための接続先
                + (gameHost == null ? "" : "; export BR_DISPLAY=" + sq(gameHost.address()))
                // 画面の上への表示や自動操作をアプリに頼む窓口 (br コマンドが使う)
                + (control == null ? "" : "; export BR_API=" + sq(control.address()));
        // sudo / asroot が root 側のシェルに同じ環境 (PATH や dir などの命令) を作るための前置き
        // (シェルへは 1 行ずつ送るので、途中に改行を入れない)
        final String sudoPre = env + (rc == null ? "" : "; [ -r " + sq(rc.getAbsolutePath())
                + " ] && . " + sq(rc.getAbsolutePath()));
        // 準備完了の合図より前に、短い行に分けて送る初期設定 (長い 1 行にまとめてはいけない。
        // 理由は RootShell.start の説明を参照)
        final java.util.List<String> setup = new java.util.ArrayList<>();
        setup.add(env);
        setup.add("export BR_SU=" + sq(RootCheck.su()) + "; export BR_SUDO_PRE=" + sq(sudoPre));
        setup.add("cd \"$HOME\" 2>/dev/null");
        setup.add("__br_diag() { " + accessDiagnosis() + "; }");
        // 設定ファイルが読めれば読み込む。読めないのは「root なのにアプリの作業領域に
        // 入れない」異常なので、原因の手がかりを画面に出す。
        final String init = rc == null ? "" : "if [ -r " + sq(rc.getAbsolutePath()) + " ]; then . "
                + sq(rc.getAbsolutePath()) + "; else __br_diag; fi";
        if (rc != null) s.setAccessProbe(rc.getAbsolutePath(), android.os.Process.myPid());
        new Thread(() -> {
            try {
                s.start(setup, init);
            } catch (IOException e) {
                ui.post(() -> {
                    if (gen != shellGeneration) return;
                    note("シェルを起動できませんでした: " + e.getMessage());
                    setStatus("シェル停止中", 0xFFF87171);
                });
                return;
            }
            ui.post(() -> {
                if (gen != shellGeneration) return;
                String abis = Arrays.toString(Build.SUPPORTED_ABIS);
                if (s.isRoot() && !s.filesReadable()) {
                    // root は取れたのに、そのシェルからアプリの作業領域が読めない。
                    // このままでは何も実行できないので、アプリ自身の権限で起動し直す。
                    note("root のシェルからアプリの作業領域に入れないため、root を使わずに起動し直します。"
                            + "bionic 用のプログラムやスクリプト、/run はこのまま使えます。"
                            + "上の [診断] に直し方が出ていなければ、その内容を開発者に送ってください。");
                    Prefs.setShellNoRoot(this, true);
                    // [診断] の出力が届ききるまで少し待ってから切り替える (すぐ閉じると途中で切れる)
                    ui.postDelayed(() -> {
                        if (gen == shellGeneration) startShell(true);
                    }, 1200);
                    return;
                }
                // root のシェルで問題なく読めたなら、次回も root から試す
                if (s.isRoot()) Prefs.setShellNoRoot(this, false);
                if (noRoot) {
                    setStatus("普通のモード (root なし)  ·  " + Build.SUPPORTED_ABIS[0], 0xFFFACC15);
                    note("普通のモード (アプリ自身の権限) でシェルを開始しました。登録したプログラムやゲームは"
                            + "実行できます。APK のインストールは標準インストーラ経由になります。"
                            + "root モードにするには /su と入力してください。");
                } else if (s.isRoot()) {
                    setStatus("root モード (uid 0)  ·  " + Build.SUPPORTED_ABIS[0], 0xFF4ADE80);
                    note("root シェルを開始しました。対応 CPU: " + abis + "。普通のモードに戻すには /exsu");
                } else {
                    setStatus("制限モード (root なし)  ·  " + Build.SUPPORTED_ABIS[0], 0xFFFACC15);
                    RootCheck.Result why = RootCheck.last();
                    note((why == null ? "root 権限を使えません" : RootUi.summary(why))
                            + "。root なしの制限モードで動作します。「root 確認」で原因と直し方を表示できます。");
                    note("制限モードでも、登録したプログラムやゲームは実行できます (静的リンクの ELF を除く)。"
                            + "APK のインストールは標準インストーラ経由になります。");
                }
                note("「実行ファイル」で bionic 用の ELF を選ぶか、下の欄にコマンドを入力してください。"
                        + " dir・cd・type・copy・del・cls なども使えます (help で一覧)。");
                // 端末かどうかはシェルの最初の応答で分かるので、少し待ってから確かめる
                ui.postDelayed(() -> {
                    if (gen == shellGeneration && s.isAlive() && !s.isTty()) {
                        String why = s.ptyError();
                        note("疑似ターミナル (プログラムの文字を画面に届けるアプリ内部の仕組み) を"
                                + "使えなかったため、簡易接続で動作しています。プログラムによっては、"
                                + "文字が終了時までまとめて表示されません。"
                                + (why == null ? "" : " 原因: " + why));
                    }
                }, 3000);
                shellReady = true;
                consumePending(); // 起動待ちの間にデスクトップから頼まれていた実行を始める
            });
        }, "shell-start").start();
    }

    /**
     * Windows のコマンドプロンプトと同じ名前の命令を定義したファイルを書き出す。
     * シェルの起動時に読み込ませる。書けなければ null (命令が増えないだけで他は動く)。
     */
    private File writeShellRc() {
        File f = new File(getFilesDir(), "shellrc");
        try {
            Glibc.writeText(f, ShellRc.text());
            f.setReadable(true, false);
            return f;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * シェルからアプリの作業領域が読めないときに実行させる診断コマンド。
     * 誰として動いているか、保護機能の状態、ファイルの見え方がアプリと同じか、を表示する。
     */
    private String accessDiagnosis() {
        int pid = android.os.Process.myPid();
        return "echo '[診断] シェルからアプリの作業領域を読めません。登録したプログラムは実行できません。'"
                + "; echo \"[診断] id: $(id 2>&1)\""
                + "; echo \"[診断] selinux: $(getenforce 2>&1) / $(cat /proc/self/attr/current 2>&1)\""
                + "; echo \"[診断] $(grep CapEff /proc/self/status 2>&1)\""
                // 権限 (ケーパビリティ) が 1 つも無い root は、名前だけの root。アプリ専用の
                // フォルダにも入れない。Magisk の「root の権限を制限する」設定でこうなる
                + "; case \"$(grep CapEff /proc/self/status 2>/dev/null)\" in *0000000000000000) "
                + "echo '[診断] root の権限がすべて外されています (制限付きの root)。"
                + "Magisk アプリの「スーパーユーザー」で Bionic Runner の設定を開き、"
                + "root の権限を制限する項目をオフにすると、root のまま使えるようになります。';; esac"
                + "; echo \"[診断] ns: shell=$(readlink /proc/self/ns/mnt 2>&1) app=$(readlink /proc/"
                + pid + "/ns/mnt 2>&1)\""
                + "; echo \"[診断] dir: $(ls -ldZ " + sq(getFilesDir().getAbsolutePath()) + " 2>&1)\""
                + "; echo '[診断] この画面のスクリーンショットを送ってください。'";
    }

    /** root が使えるか確かめ直して結果を表示する。使えるようになっていたらシェルを root で起動し直す */
    private void checkRoot() {
        note("root 権限を確認しています…");
        new Thread(() -> {
            final RootCheck.Result r = RootCheck.check();
            ui.post(() -> {
                boolean nowRoot = r.state == RootCheck.State.GRANTED;
                if (nowRoot && (shell == null || !shell.isRoot())) {
                    note("root 権限を使えるようになりました。シェルを起動し直します。");
                    startShell();
                } else {
                    RootUi.show(this, r, this::checkRoot);
                }
            });
        }, "root-check").start();
    }

    /**
     * コマンドを 1 つ尋ねて、root 権限で実行する。シェルが root でないとき (制限モードや、
     * root を使わずに起動し直したとき) でも、そのコマンドだけ su 経由で root として動かす。
     */
    private void askRootCommand() {
        final EditText field = dialogField("例: pm list packages", "");
        boolean already = shell != null && shell.isAlive() && shell.isRoot();
        new AlertDialog.Builder(this)
                .setTitle("root で実行")
                .setView(dialogBody(already
                        ? "このシェルはすでに root 権限 (uid 0) で動いているので、下の入力欄に打つコマンドは"
                        + "すべて root で実行されます。ここから実行しても同じです。"
                        : "入力したコマンドを root 権限 (su) で実行します。root の許可を求める画面が出たら"
                        + "「許可」してください。root 化されていない端末では実行できません。\n"
                        + "入力欄で「sudo コマンド」と打っても同じことができます。", field))
                .setPositiveButton("実行", (d, w) -> {
                    String cmd = field.getText().toString().trim();
                    if (!cmd.isEmpty()) sendLine("asroot " + sq(cmd));
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    /** APK の lib/ に入れてある補助プログラム ptyrun の場所。入っていなければ null */
    private String ptyHelperPath() {
        File f = new File(getApplicationInfo().nativeLibraryDir, "libptyrun.so");
        return f.isFile() ? f.getAbsolutePath() : null;
    }

    /** 貼り付けた複数行のうち、まだ送っていない行 */
    private final java.util.ArrayDeque<String> pasted = new java.util.ArrayDeque<>();
    /** 貼り付けた行を送って、プロンプトに戻るのを待っているところか */
    private boolean pasteWaiting;

    /** 改行を含む文字が入力欄に入った: 行に分けて、順に実行する */
    private void pasteLines(String text) {
        input.setText("");
        for (String l : text.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            if (!l.trim().isEmpty()) pasted.add(l);
        }
        if (pasted.size() > 1) note(pasted.size() + " 行を順に実行します (中断 ^C で残りを取り消します)");
        // 端末モードでなければ (プロンプトの印が出ないので) 待たずに全部送る。シェルが 1 行ずつ実行する
        if (shell != null && !shell.isTty()) {
            while (!pasted.isEmpty()) submitLine(pasted.poll());
            return;
        }
        if (!pasteWaiting) sendNextPasted();
    }

    /** 貼り付けた行を 1 行送る (前の行が終わってプロンプトに戻ったとき) */
    private void sendNextPasted() {
        String line = pasted.poll();
        if (line == null) {
            pasteWaiting = false;
            return;
        }
        pasteWaiting = true;
        submitLine(line);
    }

    /** 貼り付けた行の残りを取り消す (中断したとき) */
    private void cancelPasted() {
        if (!pasted.isEmpty()) note("貼り付けた残りの " + pasted.size() + " 行は実行しません");
        pasted.clear();
        pasteWaiting = false;
    }

    private void submitInput() {
        String line = input.getText().toString();
        input.setText("");
        submitLine(line);
    }

    private void submitLine(String line) {
        if (line.trim().equals("/run") || line.trim().startsWith("/run ")) {
            runCommand(line.trim().substring(4).trim());
            return;
        }
        if (line.trim().equals("/su")) {
            switchMode(true);
            return;
        }
        if (line.trim().equals("/exsu")) {
            switchMode(false);
            return;
        }
        sendLine(line);
    }

    private void sendLine(String line) {
        if (shell == null || !shell.isAlive()) {
            Toast.makeText(this, "シェルが動いていません。「再起動」を押してください",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        // 端末モードでは入力した行を端末がエコーしてくれる。パイプモードでは自分で表示する
        if (!shell.isTty()) appendOutput("$ " + line + "\n");
        if (!shell.send(line)) {
            note("シェルに送信できませんでした。「再起動」を押してください");
        }
    }

    /** シェルの単一引用符で安全に囲む */
    static String sq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    // ------------------------------------------------------- ファイルの受け取り

    private void pick(int req, String mime) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        try {
            startActivityForResult(i, req);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "ファイル選択アプリが見つかりません", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        boolean picked = resultCode == RESULT_OK && data != null && data.getData() != null;
        if (requestCode == REQ_PICK_YARA_TARGET) {
            final File rule = yaraRule;
            final String ruleName = yaraRuleName;
            yaraRule = null;
            if (rule == null) return;
            if (picked) scanWithRule(rule, ruleName, data.getData());
            else showYaraDialog(rule, ruleName); // 選ぶのをやめた: ルールの画面へ戻る
            return;
        }
        if (!picked) return;
        if (requestCode == REQ_PICK_EXEC || requestCode == REQ_PICK_APK) {
            openUri(data.getData());
        }
    }

    private void handleViewIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        Uri uri = intent.getData();
        if (uri != null) openUri(uri);
        intent.setAction(Intent.ACTION_MAIN); // 画面の作り直しで二重に処理しない
    }

    /** 受け取ったファイルを作業領域へ写し、中身を見て APK・圧縮ファイル・実行ファイルに振り分ける */
    private void openUri(final Uri uri) {
        final String name = safeName(displayName(uri));
        note("読み込み中: " + name);
        new Thread(() -> {
            final File tmp = new File(getCacheDir(), "incoming-" + System.nanoTime());
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("開けませんでした");
                byte[] buf = new byte[65536];
                try (OutputStream out = new FileOutputStream(tmp)) {
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            } catch (IOException | SecurityException e) {
                tmp.delete();
                ui.post(() -> note("読み込みに失敗しました: " + e.getMessage()));
                return;
            }
            if (Yara.isRuleName(name)) {
                openYaraRule(tmp, name);
                return;
            }
            Archive.Format format;
            try {
                format = Archive.detect(tmp);
            } catch (IOException e) {
                format = Archive.Format.NONE;
            }
            if (format == Archive.Format.ZIP && Archive.isApk(tmp)) {
                ui.post(() -> confirmApkInstall(uri, tmp, name));
            } else if (format == Archive.Format.UNSUPPORTED) {
                tmp.delete();
                ui.post(() -> note("この圧縮形式 (7z / rar / zstd) は展開できません: " + name
                        + "\ntar.gz か zip にしてから選んでください"));
            } else if (format != Archive.Format.NONE) {
                openArchive(tmp, name, format);
            } else {
                inspectFile(tmp, name);
            }
        }, "import").start();
    }

    /** 実行ファイル 1 個を調べて、登録の確認 (危険な記述があれば警告) を出す。読み込み用スレッドで呼ぶ */
    private void inspectFile(final File tmp, final String name) {
        ElfInfo info;
        try {
            info = ElfInfo.read(tmp);
        } catch (IOException e) {
            info = new ElfInfo();
        }
        final ElfInfo fi = info;
        // 危険な操作の記述が無いか検査する (設定で切っていれば省く)
        ExeScanner.Report report = null;
        if (Prefs.scanEnabled(this)) {
            try {
                report = ExeScanner.scan(tmp);
            } catch (IOException e) {
                report = null; // 読めなかった: 検査なしとして扱う
            }
            // 追加してある YARA ルールでも調べる
            if (report != null) Yara.addFindings(this, report, tmp, null);
            // ウイルスの定義 (セキュリティの「定義の更新」で取ったもの) とも照合する
            if (report != null) addDefinitionHit(report, tmp, null);
        }
        final ExeScanner.Report fr = report;
        ui.post(() -> {
            if (fr != null && fr.dangerous()) showDangerScreen(tmp, name, fi, fr, null);
            else showExecDialog(tmp, name, fi, fr != null, null);
        });
    }

    // ------------------------------------------------------ ソースをそのまま実行

    /** 実行モードで動かしている HTML / JavaScript / TypeScript (画面には出さないブラウザ部品) */
    private android.webkit.WebView webRun;
    private Runnable webRunTimeout;
    /** 実行モードの制限時間。終わらないプログラム (ゲームなど) を動かしたままにしないため */
    private static final int WEB_RUN_LIMIT_MS = 30_000;

    /** 入力欄の「/run ファイル [game|run]」を処理する */
    private void runCommand(String args) {
        if (!shell_ok()) return;
        appendOutput("> /run " + args + "\n");
        if (args.isEmpty()) {
            note("使い方: /run ファイル [game|run]\n  game = ゲームモード (画面で動かす)、run = 実行モード"
                    + " (出力を表示して終わる)。省くと選べます。\n  実行できる種類: " + Lang.supported());
            return;
        }
        String mode = "";
        String[] words = {" game", " -g", " ゲーム", " run", " -r", " 実行"};
        for (int i = 0; i < words.length; i++) {
            if (args.endsWith(words[i])) {
                mode = i < 3 ? "game" : "run";
                args = args.substring(0, args.length() - words[i].length()).trim();
                break;
            }
        }
        // 引用符で囲んであれば外す (名前に空白があるファイル用)
        if (args.length() >= 2 && (args.charAt(0) == '"' || args.charAt(0) == '\'')
                && args.charAt(args.length() - 1) == args.charAt(0)) {
            args = args.substring(1, args.length() - 1);
        }
        shell.send("__br_run " + sq(mode) + " " + sq(args));
    }

    private boolean shell_ok() {
        if (shell != null && shell.isAlive()) return true;
        Toast.makeText(this, "シェルが動いていません。「再起動」を押してください",
                Toast.LENGTH_SHORT).show();
        return false;
    }

    /** モードが決まっていなければ選んでもらってから実行する。path は元のファイルの場所 */
    private void chooseRunMode(String mode, final String path) {
        final Lang lang = Lang.of(path);
        final String name = new File(path).getName();
        if (lang == null) {
            note("この種類のファイルは実行できません: " + name + "\n実行できる種類: " + Lang.supported()
                    + "\nC / C++ / Rust / Java などは、コンパイルして実行ファイルにしてから"
                    + "「実行ファイル」で選んでください。");
            return;
        }
        if (mode.equals("game") || mode.equals("run")) {
            runSource(lang, mode, path);
            return;
        }
        String gameHelp = lang.type == Lang.Type.WEB
                ? "画面いっぱいに表示して動かします。キャンバスに描くゲームなどはこちら。"
                : "色やカーソル移動を使える端末の画面で動かします。文字で描くゲームなどはこちら。";
        new AlertDialog.Builder(this)
                .setTitle(name + "  (" + lang.label + ")")
                .setMessage("ゲームモード: " + gameHelp + "誤りがあれば、画面にエラーとコードを表示します。\n\n"
                        + "実行モード: コマンドプロンプトで動かし、出力と終了コード (exit 0 など) を表示します。")
                .setPositiveButton("ゲームモード", (d, w) -> runSource(lang, "game", path))
                .setNeutralButton("実行モード", (d, w) -> runSource(lang, "run", path))
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void runSource(Lang lang, String mode, String path) {
        String lib = getApplicationInfo().nativeLibraryDir;
        if (!lang.available(lib)) {
            note(lang.label + " の処理系は、この端末の CPU 用には入っていません");
            return;
        }
        // アプリが元のファイルを読めなければ (root でしか読めない場所など)、シェルが作った写しを使う
        File staged = new File(getCacheDir(), "br-run/src");
        String readable = new File(path).canRead() ? path : staged.getAbsolutePath();
        boolean game = mode.equals("game");
        if (lang.type == Lang.Type.WEB) {
            if (game) {
                startActivity(new Intent(this, WebGameActivity.class)
                        .putExtra(WebGameActivity.EXTRA_FILE, readable)
                        .putExtra(WebGameActivity.EXTRA_ORIGIN, path));
            } else {
                startWebRun(lang, readable, path);
            }
        } else if (game) {
            startActivity(new Intent(this, TermGameActivity.class)
                    .putExtra(TermGameActivity.EXTRA_FILE, readable)
                    .putExtra(TermGameActivity.EXTRA_ORIGIN, path));
        } else {
            // シェルの中で動かす (今いるフォルダや root 権限はシェルのまま)。終わったら終了コードを出す
            StringBuilder cmd = new StringBuilder();
            for (String a : lang.argv(lib, path)) cmd.append(sq(a)).append(' ');
            sendLine(cmd + "; echo \"exit $?\"");
        }
    }

    /** 実行モード (HTML / JS / TS): 画面に出さないブラウザ部品で動かし、出力をここへ流す */
    private void startWebRun(Lang lang, String readable, String origin) {
        if (webRun != null) stopWebRun(130, "前の実行を止めました");
        String source;
        try {
            source = WebGameActivity.readText(new File(readable));
        } catch (IOException e) {
            note("読み込めませんでした: " + e.getMessage());
            return;
        }
        final android.webkit.WebView web;
        try {
            web = new android.webkit.WebView(this);
        } catch (RuntimeException e) {
            note("この端末ではブラウザ部品 (WebView) を使えません: " + e.getMessage());
            return;
        }
        webRun = web;
        web.setWebViewClient(new android.webkit.WebViewClient());
        RunBridge bridge = new RunBridge(lang.id, "run", new File(origin).getName(), source,
                new RunBridge.Listener() {
                    @Override
                    public void onOutput(String text, boolean error) {
                        if (webRun == web) appendOutput(text);
                    }

                    @Override
                    public void onExit(int code) {
                        ui.post(() -> {
                            if (webRun == web) stopWebRun(code, null);
                        });
                    }
                });
        RunBridge.attach(web, bridge);
        webRunTimeout = () -> stopWebRun(124, "30 秒たっても終わらないので止めました"
                + " (動き続けるプログラムはゲームモードで実行してください)");
        ui.postDelayed(webRunTimeout, WEB_RUN_LIMIT_MS);
        RunBridge.load(web, bridge, new File(origin).getParentFile());
    }

    private void stopWebRun(int code, String why) {
        android.webkit.WebView web = webRun;
        if (web == null) return;
        webRun = null;
        ui.removeCallbacks(webRunTimeout);
        web.stopLoading();
        web.destroy();
        // 出力の最後が改行で終わっていなくても、終了コードは行の頭に出す
        appendOutput((why == null ? "" : "\n" + why) + "\nexit " + code + "\n");
    }

    // ------------------------------------------------------------ YARA ルール

    /** 受け取った YARA ルールの書き方を確かめ、使い道を選んでもらう。読み込み用スレッドで呼ぶ */
    private void openYaraRule(final File tmp, final String name) {
        String problem;
        try {
            problem = Yara.check(this, tmp);
        } catch (IOException e) {
            problem = e.getMessage();
        }
        if (problem != null) {
            tmp.delete();
            final String why = problem;
            ui.post(() -> note("YARA ルールを読み込めませんでした (" + name + "):\n" + why));
            return;
        }
        ui.post(() -> showYaraDialog(tmp, name));
    }

    private void showYaraDialog(final File tmp, final String name) {
        final boolean[] keep = {false};
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setMessage("YARA ルール (書き方の確認: 問題なし)\n\n"
                        + "「検知に追加」: 実行ファイルや圧縮ファイルを開くときの検査で、このルールも使います。"
                        + "一致すると赤い警告画面で実行を止めます。設定の「個人設定」から外せます。\n\n"
                        + "「ファイルをスキャン」: 選んだファイルを、このルールで今すぐ調べます。")
                .setPositiveButton("検知に追加", (d, w) -> {
                    keep[0] = true;
                    try {
                        note("YARA ルールを検知に追加しました: " + Yara.install(this, tmp, name));
                    } catch (IOException e) {
                        tmp.delete();
                        note("追加できませんでした (" + name + "): " + e.getMessage());
                    }
                })
                .setNeutralButton("ファイルをスキャン", (d, w) -> {
                    keep[0] = true;
                    yaraRule = tmp;
                    yaraRuleName = name;
                    pick(REQ_PICK_YARA_TARGET, "*/*");
                })
                .setNegativeButton("キャンセル", null)
                .setOnDismissListener(d -> {
                    if (!keep[0]) tmp.delete();
                })
                .show();
    }

    /** 選ばれたファイルをルールで調べ、結果を画面に出す。終わったらルールの画面へ戻る */
    private void scanWithRule(final File rule, final String ruleName, final Uri target) {
        final String targetName = safeName(displayName(target));
        note("YARA で検査中: " + targetName);
        new Thread(() -> {
            final File tmp = new File(getCacheDir(), "yara-target-" + System.nanoTime());
            final StringBuilder sb = new StringBuilder();
            try (InputStream in = getContentResolver().openInputStream(target)) {
                if (in == null) throw new IOException("開けませんでした");
                byte[] buf = new byte[65536];
                try (OutputStream out = new FileOutputStream(tmp)) {
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                List<Yara.Match> matches = Yara.scan(this, rule, tmp);
                sb.append("YARA の検査結果: ").append(targetName).append(" (ルール: ")
                        .append(ruleName).append(")\n");
                if (matches.isEmpty()) sb.append("  一致するルールはありませんでした");
                for (Yara.Match m : matches) {
                    sb.append("  ● 一致: ").append(m.rule);
                    if (!m.detail.isEmpty()) sb.append("   ").append(m.detail);
                    sb.append('\n');
                }
            } catch (IOException | SecurityException e) {
                sb.setLength(0);
                sb.append("YARA で検査できませんでした (").append(targetName).append("): ")
                        .append(e.getMessage());
            } finally {
                tmp.delete();
            }
            ui.post(() -> {
                note(sb.toString().trim());
                showYaraDialog(rule, ruleName);
            });
        }, "yara-scan").start();
    }

    // ------------------------------------------------------------ 圧縮ファイル

    /**
     * 圧縮ファイル (tar.gz・zip など) を展開し、中の実行ファイルを登録できるようにする。
     * 読み込み用スレッドで呼ぶ。tmp は展開が済んだら消す。
     */
    private void openArchive(final File tmp, final String name, Archive.Format format) {
        ui.post(() -> note("展開中: " + name));
        File staging = null;
        final Archive.Result result;
        try {
            staging = Archive.newStaging(getFilesDir());
            result = Archive.extract(tmp, format, new File(staging, "root"),
                    safeName(Archive.stripExtension(name)));
        } catch (IOException | RuntimeException e) {
            if (staging != null) Archive.deleteTree(staging);
            tmp.delete();
            final String why = e instanceof ZipCrypt.PasswordException
                    ? "パスワード付きの圧縮ファイルです。zip.exe かエクスプローラーで解凍してから、中の実行ファイルを選んでください"
                    : e instanceof java.io.EOFException
                    || e instanceof java.util.zip.ZipException
                    ? "圧縮ファイルが壊れているか、途中で切れています" : String.valueOf(e.getMessage());
            ui.post(() -> note("展開に失敗しました (" + name + "): " + why));
            return;
        }
        // Windows の exe (インストーラーや自己解凍形式) を開いたのか。結果の説明に使う
        final boolean fromExe = Archive.isWindowsExe(tmp);
        // exe に埋め込まれたアイコンがあれば、消す前に取り出しておく
        final File exeIcon = fromExe && result.tree
                ? Icons.fromWindowsExe(tmp, new File(staging, "exe-icon")) : null;
        tmp.delete();
        if (!result.tree) {
            // 圧縮されていただけの 1 個のファイル (prog.gz など)。取り出して、ふつうの実行ファイルとして扱う
            Archive.Entry only = result.files.get(0);
            File one = new File(Archive.pkgRoot(getFilesDir()), ".incoming-file-" + System.nanoTime());
            boolean moved = only.file.renameTo(one);
            Archive.deleteTree(staging);
            if (moved) inspectFile(one, only.path);
            else ui.post(() -> note("展開に失敗しました (" + name + ")"));
            return;
        }
        final List<Archive.Entry> candidates =
                Archive.candidates(result, name, Build.SUPPORTED_ABIS);
        if (candidates.isEmpty()) {
            Archive.deleteTree(staging);
            ui.post(() -> note(fromExe
                    ? "この exe はインストーラー (または自己解凍形式) で、中身を展開できましたが、"
                    + "Linux / Android 用の実行ファイルは入っていませんでした (" + result.files.size()
                    + " 個)。Windows 用のプログラムを Linux 用に変換することはできません。"
                    + "中身を取り出すだけなら、エクスプローラーで exe を長押し →「解凍」を使ってください。"
                    : "実行できるファイルが見つかりませんでした: " + name + " (中のファイル "
                    + result.files.size() + " 個に、ELF の実行ファイルもスクリプトもありません)"));
            return;
        }
        final ExeScanner.Report report = Prefs.scanEnabled(this) ? scanAll(result) : null;
        if (report != null) {
            File root = new File(staging, "root");
            Yara.addFindings(this, report, root, root);
        }
        final Archive.Pkg pkg = new Archive.Pkg();
        pkg.staging = staging;
        pkg.result = result;
        pkg.icon = exeIcon;
        ui.post(() -> chooseArchiveExe(pkg, candidates, name, report));
    }

    /** 定義との照合に使う (static の検査処理から使うため) */
    private static volatile android.content.Context appContext;

    /** ウイルスの定義のハッシュ値と一致したら、検査結果に加える (定義が無ければ何もしない) */
    private static void addDefinitionHit(ExeScanner.Report report, File f, String where) {
        android.content.Context c = appContext;
        if (c == null || !AvDefs.ready(c) || f.length() > 200L * 1024 * 1024) return;
        try {
            String hit = AvDefs.lookup(c, AvDefs.hashes(f), f.length());
            if (hit != null) {
                report.findings.add(0, new ExeScanner.Finding("ウイルスの定義 (ClamAV) に一致: " + hit,
                        (where == null ? "" : where + ": ") + "既知の脅威と同じファイルです"));
            }
        } catch (IOException ignored) {
            // 読めない
        }
    }

    /** 展開した全ファイルを検査し、見つかった記述をどのファイルのものか分かる形でまとめる */
    private static ExeScanner.Report scanAll(Archive.Result result) {
        ExeScanner.Report all = new ExeScanner.Report();
        java.util.Set<String> seen = new java.util.HashSet<>();
        long left = 256L * 1024 * 1024; // 検査に時間をかけすぎないための上限
        for (Archive.Entry e : result.files) {
            if (left <= 0) {
                all.truncated = true;
                break;
            }
            left -= e.file.length();
            try {
                ExeScanner.Report one = ExeScanner.scan(e.file);
                addDefinitionHit(one, e.file, null);
                all.truncated |= one.truncated;
                for (ExeScanner.Finding f : one.findings) {
                    // 同じ種類は最初に見つかった 1 件だけ報告する
                    if (seen.add(f.title)) {
                        all.findings.add(new ExeScanner.Finding(f.title, e.path + ": " + f.evidence));
                    }
                }
            } catch (IOException ignored) {
                // 読めなかったファイルは検査なしとして扱う
            }
        }
        return all;
    }

    /** 起動できそうなファイルが複数あれば選んでもらう。1 個ならそのまま進む */
    private void chooseArchiveExe(final Archive.Pkg pkg, final List<Archive.Entry> candidates,
                                  String archiveName, final ExeScanner.Report report) {
        if (candidates.size() == 1) {
            confirmArchiveExe(pkg, candidates.get(0), report);
            return;
        }
        final boolean[] picked = {false};
        new AlertDialog.Builder(this)
                .setTitle("実行するファイルを選択 (" + archiveName + ")")
                .setItems(Archive.paths(candidates).toArray(new String[0]), (d, which) -> {
                    picked[0] = true;
                    confirmArchiveExe(pkg, candidates.get(which), report);
                })
                .setNegativeButton("キャンセル", null)
                .setOnDismissListener(d -> {
                    if (!picked[0]) discard(null, pkg);
                })
                .show();
    }

    private void confirmArchiveExe(Archive.Pkg pkg, Archive.Entry exe, ExeScanner.Report report) {
        pkg.exe = exe;
        // 一式の中にアイコンらしい画像があれば、それをデスクトップのアイコンにする
        File inside = Icons.findInTree(pkg.result.files, exe.path);
        if (inside != null) pkg.icon = inside;
        // ほかのファイルも入っているなら、本体がそれを読めるよう展開したフォルダの中で実行する
        pkg.cd = pkg.result.files.size() > 1;
        ElfInfo info;
        try {
            info = ElfInfo.read(exe.file);
        } catch (IOException e) {
            info = new ElfInfo();
        }
        String name = safeName(exe.path);
        if (report != null && report.dangerous()) {
            showDangerScreen(exe.file, name, info, report, pkg);
        } else {
            showExecDialog(exe.file, name, info, report != null, pkg);
        }
    }

    /** 登録をやめたときの後始末。圧縮ファイルから展開したものは一式ごと消す */
    private static void discard(File tmp, Archive.Pkg pkg) {
        if (pkg != null) Archive.deleteTree(pkg.staging);
        else if (tmp != null) tmp.delete();
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) name = c.getString(0);
        } catch (RuntimeException ignored) {
            // 名前を教えてくれない提供元もある。下の代替を使う
        }
        if (name == null) name = uri.getLastPathSegment();
        return name == null ? "program" : name;
    }

    /** bin に置くファイル名として安全な文字だけにする */
    static String safeName(String raw) {
        String s = raw;
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf(':'));
        if (slash >= 0) s = s.substring(slash + 1);
        s = s.replaceAll("[^A-Za-z0-9._+-]", "_");
        while (s.startsWith(".") || s.startsWith("-")) s = s.substring(1);
        if (s.isEmpty()) s = "program";
        if (s.length() > 64) s = s.substring(0, 64);
        return s;
    }

    // ------------------------------------------------------------ 実行ファイル

    private String describe(ElfInfo info) {
        StringBuilder sb = new StringBuilder();
        boolean cpuOk = info.cpuMatches(Build.SUPPORTED_ABIS);
        switch (info.kind) {
            case BIONIC_DYNAMIC:
                sb.append("形式: ELF / bionic 動的リンク (Android 用)\n");
                break;
            case STATIC:
                sb.append("形式: ELF / 静的リンク\n");
                break;
            case FOREIGN_LIBC:
                sb.append(Glibc.supports(info) ? "形式: ELF / glibc 動的リンク (一般の Linux 用)\n"
                        : "形式: ELF / Android 以外の Linux 用 (musl など)\n");
                break;
            case SHARED_OBJECT:
                sb.append("形式: ELF / 共有ライブラリ (.so) の可能性\n");
                break;
            case SCRIPT:
                sb.append("形式: スクリプト (#!").append(info.shebang).append(")\n");
                break;
            case WINDOWS_PE:
                sb.append("形式: Windows の実行ファイル (PE / .exe)\n");
                break;
            default:
                sb.append("形式: 不明 (ELF でもスクリプトでもありません)\n");
        }
        if (info.isElf()) {
            sb.append("CPU: ").append(info.machineName())
                    .append(info.is64 ? " (64bit)" : " (32bit)").append('\n');
            if (info.interpreter != null) {
                sb.append("ローダ: ").append(info.interpreter).append('\n');
            }
        }
        sb.append('\n');
        if (Emu.isForeign(info, Build.SUPPORTED_ABIS) && Emu.available(this, info)) {
            // 端末の CPU では直接動かない (32bit の ARM 用・PC 用の x86_64)。エミュレータなら動くものがある
            String cpu = Emu.cpuLabel(info);
            if (Emu.supports(this, info, Build.SUPPORTED_ABIS) && Box64.isX8664(info) && Box64.available(this)
                    && !Box64.QEMU.equals(Box64.mode(this))) {
                sb.append("✔ " + cpu + " 用です。この端末の CPU では直接動かないので、同梱の Box64 (x86_64 の命令を"
                        + " ARM の命令に変換する仕組み) で実行します。Box64 が使えないときは CPU エミュレータ (QEMU) で"
                        + "実行します。設定 → 個人設定 →「x86_64 の実行方法」で切り替えられます。");
            } else if (Emu.supports(this, info, Build.SUPPORTED_ABIS)) {
                sb.append("✔ " + cpu + " 用です。この端末の CPU では直接動かないので、同梱の CPU エミュレータ"
                        + " (QEMU) で実行します。直接動かすより遅くなります。");
            } else if (info.kind == ElfInfo.Kind.BIONIC_DYNAMIC) {
                sb.append("✖ " + cpu + " の Android 用 (bionic 動的リンク) です。これが読み込む、その CPU 用の"
                        + "システムライブラリがこの端末に入っていないため、エミュレータでも実行できません。"
                        + "静的リンク (-static) か、普通の Linux 用 (glibc) でビルドしたものなら実行できます。");
            } else {
                String loader = Emu.glibcLoader(info);
                sb.append("✖ " + cpu + " 用ですが、このローダ (" + info.interpreter + ") は同梱していない"
                        + "ため実行できません。" + (loader != null
                        ? "静的リンク版か、glibc (" + loader + ") 用のものならエミュレータで実行できます。"
                        : "この CPU 用の glibc は同梱していないので、静的リンク (-static) 版を使ってください。"));
            }
            return sb.toString();
        }
        switch (info.kind) {
            case BIONIC_DYNAMIC:
            case STATIC:
                sb.append(!cpuOk ? "✖ CPU が合いません。この端末は "
                        + Arrays.toString(Build.SUPPORTED_ABIS) + " 用です。"
                        : !info.fitsPageSize(pageSize()) ? pageSizeProblem(info)
                        : info.kind == ElfInfo.Kind.STATIC && (shell == null || !shell.isRoot())
                        ? "△ 静的リンクの ELF は root のシェルでしか起動できません "
                        + "(Android は、アプリのデータ領域にあるファイルの直接実行を禁じています)。"
                        : "✔ この端末で実行できる形式です。");
                break;
            case FOREIGN_LIBC:
                if (!Glibc.supports(info)) {
                    sb.append("✖ このローダは Android にもアプリ同梱の glibc にも無いため起動できません。"
                            + "静的リンク版を使ってください。");
                } else if (!cpuOk) {
                    sb.append("✖ CPU が合いません。この端末は "
                            + Arrays.toString(Build.SUPPORTED_ABIS) + " 用です。");
                } else if (!info.fitsPageSize(pageSize())) {
                    sb.append(pageSizeProblem(info));
                } else if (shell != null && shell.isRoot()) {
                    sb.append("✔ アプリ同梱の glibc 2.44 を使って実行できます。");
                } else {
                    sb.append("✔ アプリ同梱の glibc 2.44 を使って実行できます (root なしでは、"
                            + "Android の制限に触れる一部のプログラムは動かないことがあります)。");
                }
                break;
            case SHARED_OBJECT:
                sb.append("✖ 共有ライブラリのようです。通常は単体では実行できません"
                        + " (古いリンカで作った静的 PIE の場合は実行できます)。");
                break;
            case SCRIPT:
                sb.append("✔ シェルスクリプトとして実行できます。");
                break;
            case WINDOWS_PE:
                sb.append("✖ Windows 用の .exe は、そのままでは実行できません。64bit の exe なら Wine で動かせます"
                        + " (エクスプローラーで長押しして「Wine で実行」、またはコマンドプロンプトで wine 名前.exe)。");
                break;
            default:
                sb.append("✖ 実行できる形式ではなさそうです。");
        }
        return sb.toString();
    }

    /** この端末のメモリの 1 ページの大きさ (バイト)。古い端末は 4096、新しい端末は 16384 のことがある */
    static long pageSize() {
        try {
            long n = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE);
            return n > 0 ? n : 4096;
        } catch (RuntimeException e) {
            return 4096;
        }
    }

    private static String pageSizeProblem(ElfInfo info) {
        return "✖ この端末はメモリを " + (pageSize() / 1024) + "KB 単位で扱いますが、このファイルは "
                + (info.loadAlign / 1024) + "KB 単位で作られているため起動できません。"
                + "リンク時に -Wl,-z,max-page-size=16384 を付けてビルドし直してください。";
    }

    private EditText dialogField(String hint, String value) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setText(value);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return e;
    }

    private LinearLayout dialogBody(String message, View... fields) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(12), dp(24), 0);
        TextView msg = new TextView(this);
        msg.setText(message);
        msg.setTextSize(14);
        box.addView(msg);
        for (View f : fields) box.addView(f);
        return box;
    }

    /** 登録名のファイル (bin の中) に対応する本体。glibc 用・32bit 用・圧縮ファイルからのものは別の場所にある */
    private File realFileOf(File registered) {
        String name = registered.getName();
        File packed = Archive.packageExe(getFilesDir(), name);
        if (packed != null) return packed;
        File emulated = new File(Emu.binDir(this), name);
        if (emulated.isFile()) return emulated;
        File glibc = new File(glibcBinDir(), name);
        return glibc.isFile() ? glibc : registered;
    }

    /** 2 つのファイルの中身が同じか。大きさが違えば読まずに「違う」と分かる */
    private static boolean sameContent(File a, File b) {
        if (a.length() != b.length()) return false;
        try (InputStream x = new java.io.BufferedInputStream(new java.io.FileInputStream(a), 65536);
             InputStream y = new java.io.BufferedInputStream(new java.io.FileInputStream(b), 65536)) {
            int c;
            while ((c = x.read()) >= 0) if (c != y.read()) return false;
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** scanned が真なら「検査して問題が見つからなかった」ことを緑の帯で知らせる */
    private void showExecDialog(final File tmp, String name, final ElfInfo info, boolean scanned,
                                final Archive.Pkg pkg) {
        if (info.kind == ElfInfo.Kind.WINDOWS_PE && pkg == null) {
            showWineDialog(tmp, name);
            return;
        }
        // 同じアプリがもう登録されていれば、アップデートとして扱う (同じ登録名で置き換える)。
        // 名前が版の番号などで少し違っていても、同じアプリとみなせるものを探す
        final File existing = AppNames.findRegistered(binDir, name);
        final boolean same = existing != null && pkg == null && sameContent(tmp, realFileOf(existing));
        // Linux の実行ファイル (ELF) は、種類が分かる拡張子 (.Belf・.Gelf・.elf) の名前にする
        final EditText nameField = dialogField("登録名", existing != null ? existing.getName()
                : pkg == null ? ElfName.apply(safeName(name), info) : name);
        final EditText argsField = dialogField("引数 (省略可)", "");
        String about = describe(info);
        if (pkg != null) {
            about = "圧縮ファイルから " + pkg.result.files.size() + " 個のファイルを展開しました"
                    + (pkg.result.skipped > 0 ? " (展開先の外を指すなど、安全でない項目 "
                    + pkg.result.skipped + " 個は取り出していません)" : "")
                    + "\n実行するファイル: " + pkg.exe.path + "\n\n" + about;
        }
        LinearLayout body = dialogBody(about, nameField, argsField);
        if (existing != null) {
            File old = realFileOf(existing);
            CharSequence when = android.text.format.DateFormat.format("yyyy/MM/dd HH:mm", old.lastModified());
            TextView update = new TextView(this);
            update.setText(same
                    ? "＝ 登録済みの「" + existing.getName() + "」と同じ内容です。登録し直す必要はありません"
                    : "🔄 アップデート: 登録済みの「" + existing.getName() + "」を新しい内容に置き換えます\n"
                    + "登録済み: " + (old.length() / 1024) + " KB (" + when + ")  →  新しいもの: "
                    + (tmp.length() / 1024) + " KB\n別のアプリとして残したいときは、登録名を変えてください");
            update.setTextColor(Color.WHITE);
            update.setTextSize(13);
            update.setBackgroundColor(same ? 0xFF475569 : 0xFF1D4ED8);
            update.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ulp.bottomMargin = dp(10);
            body.addView(update, 0, ulp);
        }
        if (pkg != null && pkg.icon != null) {
            TextView icon = new TextView(this);
            icon.setText("🖼 アイコンが見つかりました。デスクトップに表示します");
            icon.setTextSize(13);
            body.addView(icon);
        }
        if (pkg != null && pkg.result.files.size() > 1) {
            final android.widget.CheckBox cd = new android.widget.CheckBox(this);
            cd.setText("展開したフォルダの中で実行する (同梱のデータを読むプログラム向け)");
            cd.setTextSize(13);
            cd.setChecked(pkg.cd);
            cd.setOnCheckedChangeListener((b, on) -> pkg.cd = on);
            body.addView(cd);
        }
        if (scanned) {
            TextView ok = new TextView(this);
            ok.setText("✔ スマホが保護されました\n危険な操作の記述は見つかりませんでした"
                    + " (簡易検査のため、安全を保証するものではありません)");
            ok.setTextColor(Color.WHITE);
            ok.setTextSize(13);
            ok.setBackgroundColor(0xFF15803D);
            ok.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(10);
            body.addView(ok, 0, lp);
        }
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setView(body)
                .setPositiveButton(same ? "そのまま実行" : existing != null ? "更新して実行"
                        : "登録して実行", (d, w) -> {
                    String chosen = nameField.getText().toString();
                    if (same && safeName(chosen).equals(existing.getName())) {
                        // 中身が同じなので置き換えず、登録済みのものを実行する
                        discard(tmp, pkg);
                        run(existing, argsField.getText().toString());
                    } else {
                        register(tmp, chosen, info, argsField.getText().toString(), pkg);
                    }
                })
                .setNeutralButton(same ? "登録し直す" : existing != null ? "更新のみ" : "登録のみ",
                        (d, w) -> register(tmp, nameField.getText().toString(), info, null, pkg))
                .setNegativeButton("キャンセル", (d, w) -> discard(tmp, pkg))
                .setOnCancelListener(d -> discard(tmp, pkg))
                .show();
    }

    /** Windows のプログラム (exe) を選んだとき: Wine で動かすかを聞く。動かすなら D: ドライブ (共有フォルダ) に写す */
    private void showWineDialog(final File tmp, String name) {
        final String why = Wine.checkPe(tmp);
        final String file = safeName(name);
        String msg = "形式: Windows の実行ファイル (PE / .exe)\n\n";
        if (!Wine.usable(this)) {
            msg += "✖ Wine は使えません (この端末では Box64 が使えないためです)。";
        } else if (why != null) {
            msg += "✖ " + why;
        } else {
            msg += "✔ Wine で動かせます。D: ドライブ (共有フォルダ) に " + file + " として写してから動かします。"
                    + (Wine.installed(this) ? "" : "\n(Wine がまだ入っていません。先に「追加機能」から入れてください)");
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(name)
                .setMessage(msg)
                .setNegativeButton("キャンセル", (d, w) -> tmp.delete())
                .setOnCancelListener(d -> tmp.delete());
        if (Wine.usable(this) && why == null) {
            b.setPositiveButton("Wine で実行", (d, w) -> {
                File dest = new File(Wine.shared(this), file);
                try {
                    Wine.shared(this).mkdirs();
                    Archive.copyFile(tmp, dest);
                } catch (IOException e) {
                    note("写せませんでした: " + e.getMessage());
                    return;
                } finally {
                    tmp.delete();
                }
                runWine(dest.getAbsolutePath());
            });
        }
        b.show();
    }

    /**
     * root モードの wine コマンドから: アプリ自身 (普通の権限) で wine.sh を動かし、出力をここに出す。
     * args はシェルが単一引用符で囲んだ引数の並び。root のシェルからは入力を渡せないので、
     * 初回の確認はここで出す
     */
    private void runWineAsApp(final String cwd, final String args) {
        if (!Wine.agreed(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Windows のプログラムを動かします")
                    .setMessage("Windows のプログラムはアプリの権限で動きます。信頼できないものは動かさないでください。")
                    .setPositiveButton("動かす", (d, w) -> {
                        Wine.setAgreed(this);
                        runWineAsApp(cwd, args);
                    })
                    .setNegativeButton("やめる", null)
                    .show();
            return;
        }
        final File script = Wine.script(this);
        final File shared = Wine.shared(this);
        new Thread(() -> {
            int code;
            try {
                shared.mkdirs();
                Process p = new ProcessBuilder("/system/bin/sh", "-c",
                        "cd " + sq(cwd) + " 2>/dev/null || cd " + sq(shared.getAbsolutePath()) + "; exec /system/bin/sh "
                                + sq(script.getAbsolutePath()) + " " + args)
                        .redirectErrorStream(true).start();
                p.getOutputStream().close();
                try (java.io.Reader r = new java.io.InputStreamReader(p.getInputStream(),
                        java.nio.charset.StandardCharsets.UTF_8)) {
                    char[] buf = new char[4096];
                    int n;
                    while ((n = r.read(buf)) > 0) appendOutput(new String(buf, 0, n));
                }
                code = p.waitFor();
            } catch (IOException | InterruptedException e) {
                appendOutput("» Wine を起動できませんでした: " + e.getMessage() + "\n");
                return;
            }
            appendOutput("» Wine が終わりました (終了コード " + code + ")\n");
        }, "wine-app").start();
    }

    /** Windows のプログラムを、コマンドプロンプトで wine コマンドを使って動かす (出力はここに出る) */
    private void runWine(final String path) {
        if (!Wine.usable(this)) {
            note("Wine は使えません (この端末では Box64 が使えないためです)");
            return;
        }
        if (!Wine.installed(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Wine が入っていません")
                    .setMessage("Windows のプログラムを動かすには、先に Wine を入れてください (約 "
                            + Wine.downloadMegabytes() + "MB のダウンロード)。追加機能の画面を開きますか？")
                    .setPositiveButton("開く", (d, w) -> startActivity(new Intent(this, LinuxAddonsActivity.class)))
                    .setNegativeButton("キャンセル", null)
                    .show();
            return;
        }
        File f = new File(path);
        if (f.canRead()) {
            String why = Wine.checkPe(f);
            if (why != null) {
                note(why + ": " + path);
                return;
            }
        }
        if (!Wine.agreed(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Windows のプログラムを動かします")
                    .setMessage("Windows のプログラムはアプリの権限で動きます。信頼できないものは動かさないでください。")
                    .setPositiveButton("動かす", (d, w) -> {
                        Wine.setAgreed(this);
                        sendLine("wine " + sq(path));
                    })
                    .setNegativeButton("やめる", null)
                    .show();
            return;
        }
        sendLine("wine " + sq(path));
    }

    /**
     * 危険な操作の記述が見つかったときの赤い警告画面。
     * そのままでは実行できず、「詳細」で見つかった内容を確認してからでないと実行ボタンが出ない。
     */
    private void showDangerScreen(final File tmp, final String name, final ElfInfo info,
                                  ExeScanner.Report report, final Archive.Pkg pkg) {
        final android.app.Dialog dialog =
                new android.app.Dialog(this, android.R.style.Theme_Material_NoActionBar);
        final boolean[] proceed = {false};

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFFB91C1C);
        col.setPadding(dp(24), dp(40), dp(24), dp(24));

        TextView mark = new TextView(this);
        mark.setText("⚠");
        mark.setTextSize(64);
        mark.setTextColor(Color.WHITE);
        mark.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(mark);

        TextView title = new TextView(this);
        title.setText("この実行可能ファイルはスマホを破損させる可能性があります");
        title.setTextSize(21);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.WHITE);
        title.setPadding(0, dp(12), 0, dp(8));
        col.addView(title);

        TextView file = new TextView(this);
        file.setText(name + "\n実行は止めてあります。信頼できるファイルでなければ実行しないでください。");
        file.setTextSize(14);
        file.setTextColor(0xFFFEE2E2);
        col.addView(file);

        StringBuilder sb = new StringBuilder("見つかった危険な操作:\n");
        for (ExeScanner.Finding f : report.findings) {
            sb.append("\n● ").append(f.title).append("\n   記述: ").append(f.evidence).append('\n');
        }
        sb.append("\nroot 権限で実行すると、これらの操作は確認なしに行われます。");
        if (report.truncated) sb.append("\nファイルが大きいため、検査したのは先頭の一部です。");
        final TextView detail = new TextView(this);
        detail.setText(sb);
        detail.setTextSize(13);
        detail.setTextColor(Color.WHITE);
        detail.setTypeface(Typeface.MONOSPACE);
        detail.setTextIsSelectable(true);
        detail.setBackgroundColor(0x33000000);
        detail.setPadding(dp(12), dp(10), dp(12), dp(10));
        detail.setVisibility(View.GONE);
        ScrollView sv = new ScrollView(this);
        sv.addView(detail);
        LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        svp.topMargin = dp(16);
        col.addView(sv, svp);

        final Button cancel = dangerButton("実行しない (おすすめ)", 0xFFFFFFFF, 0xFFB91C1C);
        cancel.setOnClickListener(v -> dialog.dismiss());
        col.addView(cancel);

        final Button runAnyway = dangerButton("それでも実行", 0x33000000, 0xFFFFFFFF);
        runAnyway.setVisibility(View.GONE);
        runAnyway.setOnClickListener(v -> {
            proceed[0] = true;
            dialog.dismiss();
            register(tmp, name, info, "", pkg);
        });

        final Button more = dangerButton("詳細", 0x33000000, 0xFFFFFFFF);
        more.setOnClickListener(v -> {
            detail.setVisibility(View.VISIBLE);
            more.setVisibility(View.GONE);
            runAnyway.setVisibility(View.VISIBLE);
        });
        col.addView(more);
        col.addView(runAnyway);

        dialog.setContentView(col);
        // 戻るボタンや画面外タップで閉じた場合も「実行しない」と同じ扱いにする
        dialog.setOnDismissListener(d -> {
            if (!proceed[0]) {
                discard(tmp, pkg);
                note("実行を中止しました: " + name);
            }
        });
        dialog.show();
    }

    private Button dangerButton(String label, int background, int textColor) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(textColor);
        b.setBackgroundColor(background);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    /**
     * bin へ登録する。登録名はそのままコマンド名として使える。runArgs が null でなければ続けて実行する。
     * glibc 用のものは本体を glibc-bin に置き、bin には同梱ローダ経由で起動するスクリプトを置く。
     */
    private void register(final File tmp, String rawName, final ElfInfo info,
                          final String runArgs, final Archive.Pkg pkg) {
        // Linux の実行ファイル (ELF) は、元の拡張子に関係なく種類の拡張子を付ける:
        // bionic 用は .Belf、glibc 用は .Gelf、そのほか (静的リンクなど) は .elf。
        // 圧縮ファイルから展開したもの・スクリプトはそのまま
        final String name = pkg == null ? ElfName.apply(safeName(rawName), info) : safeName(rawName);
        final File dst = new File(binDir, name);
        final boolean updating = dst.isFile(); // 同じ登録名がもうある: 置き換える
        final boolean viaGlibc = Glibc.supports(info) && info.cpuMatches(Build.SUPPORTED_ABIS);
        // 32bit の ARM 用を 64bit 専用の端末で: エミュレータ経由で起動する
        final boolean viaEmu = Emu.supports(this, info, Build.SUPPORTED_ABIS);
        if (viaGlibc) note("glibc を準備しています…");
        if (viaEmu) note("エミュレータを準備しています…");
        new Thread(() -> {
            try {
                if (pkg != null) {
                    // アイコンは、一式を移す前 (ファイルの場所が変わる前) に読んでおく
                    android.graphics.Bitmap icon = Icons.decode(pkg.icon);
                    if (icon != null) Icons.save(this, name, icon);
                    // 圧縮ファイルから展開した一式: files/pkg/<登録名> へ移し、bin には中の本体を
                    // 起動するスクリプトを置く
                    File exe = Archive.install(getFilesDir(), pkg, name);
                    // スクリプトなら、Android で動く形に直す (#!/bin/bash → #!/system/bin/sh など)
                    ElfInfo launch = info;
                    if (info.kind == ElfInfo.Kind.SCRIPT) {
                        String fixed = Scripts.normalize(this, exe);
                        if (fixed != null) {
                            ui.post(() -> note(fixed));
                            launch = ElfInfo.read(exe);
                        }
                    }
                    new File(glibcBinDir(), name).delete();
                    new File(Emu.binDir(this), name).delete();
                    if (viaGlibc) Glibc.ensureInstalled(this, info.abi());
                    if (viaEmu) Emu.ensureInstalled(this, info);
                    dst.delete();
                    Glibc.writeText(dst, Archive.launcherScript(launch, exe, pkg.cd,
                            viaEmu ? Emu.launchLines(this, info, exe)
                            : viaGlibc ? Glibc.launchLines(this, info.abi(), exe) : null));
                    dst.setReadable(true, false);
                    dst.setExecutable(true, false);
                } else {
                    File real = viaEmu ? new File(Emu.binDir(this), name)
                            : viaGlibc ? new File(glibcBinDir(), name) : dst;
                    real.getParentFile().mkdirs();
                    real.delete();
                    if (!tmp.renameTo(real)) throw new IOException("ファイルを移動できません");
                    // root シェル (別 uid) からも実行できるよう全員に実行・読み取りを許可する
                    real.setReadable(true, false);
                    real.setExecutable(true, false);
                    // スクリプトなら、Android で動く形に直す (#!/bin/bash → #!/system/bin/sh、改行など)
                    if (info.kind == ElfInfo.Kind.SCRIPT) {
                        String fixed = Scripts.normalize(this, real);
                        if (fixed != null) ui.post(() -> note(fixed));
                    }
                    Archive.deletePackage(getFilesDir(), name); // 同名の古い一式が残らないように
                    if (!viaEmu) new File(Emu.binDir(this), name).delete(); // 同名の古いものを残さない
                    if (viaEmu) {
                        new File(glibcBinDir(), name).delete();
                        Emu.ensureInstalled(this, info);
                        Glibc.writeText(dst, Emu.wrapperScript(this, info, real));
                        dst.setReadable(true, false);
                        dst.setExecutable(true, false);
                    } else if (viaGlibc) {
                        Glibc.ensureInstalled(this, info.abi());
                        Glibc.writeText(dst, Glibc.wrapperScript(this, info.abi(), real));
                        dst.setReadable(true, false);
                        dst.setExecutable(true, false);
                    } else {
                        new File(glibcBinDir(), name).delete(); // 同名の古い glibc 版が残らないように
                    }
                }
            } catch (IOException e) {
                discard(tmp, pkg);
                ui.post(() -> note("登録に失敗しました (" + name + "): " + e.getMessage()));
                return;
            }
            ui.post(() -> {
                note((updating ? "アップデートしました: " : "登録しました: ") + name + (viaEmu ? " (エミュレータ経由で起動)"
                        : viaGlibc ? " (glibc 経由で起動)" : "")
                        + "。コマンド名としてそのまま入力できます");
                if (runArgs != null) run(dst, runArgs);
            });
        }, "register").start();
    }

    private File glibcBinDir() {
        return new File(getFilesDir(), "glibc-bin");
    }

    private void run(File f, String args) {
        ElfInfo info;
        try {
            info = ElfInfo.read(f);
        } catch (IOException e) {
            info = new ElfInfo();
        }
        boolean root = shell != null && shell.isRoot();
        String cmd = sq(f.getAbsolutePath());
        if (!root) {
            // root が無いと、アプリのデータ領域にあるファイルは直接 exec できない
            // (Android 10 以降の制限)。システムのローダに読み込ませる形なら起動できる。
            if (info.kind == ElfInfo.Kind.SCRIPT) {
                // 1 行目 (#!) の処理系に読み込ませる (sh とは限らない: python3 や lua のこともある)
                cmd = Scripts.runCommand(this, f, info.shebang);
            } else if (info.kind == ElfInfo.Kind.BIONIC_DYNAMIC) {
                cmd = (info.is64 ? "/system/bin/linker64 " : "/system/bin/linker ") + cmd;
            }
        }
        String a = args.trim();
        sendLine(a.isEmpty() ? cmd : cmd + " " + a);
    }

    private void showRegistered() {
        final File[] files = binDir.listFiles();
        if (files == null || files.length == 0) {
            Toast.makeText(this, "登録済みの実行ファイルはありません", Toast.LENGTH_SHORT).show();
            return;
        }
        Arrays.sort(files);
        String[] names = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            names[i] = files[i].getName() + "  (" + (files[i].length() / 1024) + " KB)";
        }
        new AlertDialog.Builder(this)
                .setTitle("登録済みの実行ファイル")
                .setItems(names, (d, which) -> showRegisteredItem(files[which]))
                .setNegativeButton("閉じる", null)
                .show();
    }

    private void showRegisteredItem(final File f) {
        // glibc 用は bin 側が起動スクリプトなので、説明には本体の方を使う
        final File real = new File(glibcBinDir(), f.getName());
        // 圧縮ファイルから展開したものも bin 側は起動スクリプトで、本体は展開先にある
        final File packed = Archive.packageExe(getFilesDir(), f.getName());
        ElfInfo info;
        try {
            File emulated = new File(Emu.binDir(this), f.getName());
            info = ElfInfo.read(packed != null ? packed : emulated.isFile() ? emulated
                    : real.isFile() ? real : f);
        } catch (IOException e) {
            info = new ElfInfo();
        }
        final EditText argsField = dialogField("引数 (省略可)", "");
        new AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setView(dialogBody(describe(info), argsField))
                .setPositiveButton("実行", (d, w) -> run(f, argsField.getText().toString()))
                .setNeutralButton("削除", (d, w) -> {
                    real.delete();
                    new File(Emu.binDir(this), f.getName()).delete();
                    Archive.deletePackage(getFilesDir(), f.getName());
                    Icons.delete(this, f.getName());
                    if (f.delete()) note("削除しました: " + f.getName());
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    // ------------------------------------------------------------ AI への指示

    /** このアプリで動くプログラムを AI に作ってもらうための指示文を、コピーして貼れる形で渡す */
    private void showAiPrompt() {
        final EditText want = new EditText(this);
        want.setHint("例: 指定フォルダの重複ファイルを探して一覧にする");
        want.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        want.setMinLines(3);
        want.setGravity(Gravity.TOP | Gravity.START);
        new AlertDialog.Builder(this)
                .setTitle("AI にプログラムを作らせる")
                .setView(dialogBody("作りたいものを書いて「指示をコピー」を押し、ChatGPT や Claude などの "
                        + "AI に貼り付けてください。この端末で動く形式で作るための条件が自動で付きます。"
                        + "\n返ってきたファイルを保存し、「実行ファイル」から選べば実行できます。", want))
                .setPositiveButton("指示をコピー", (d, w) -> {
                    String text = aiPrompt(want.getText().toString());
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("AI への指示", text));
                    Toast.makeText(this, "指示をコピーしました。AI に貼り付けてください",
                            Toast.LENGTH_LONG).show();
                })
                .setNeutralButton("AI アプリへ送る", (d, w) -> {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_TEXT, aiPrompt(want.getText().toString()));
                    try {
                        startActivity(Intent.createChooser(i, "指示を送るアプリ"));
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(this, "送れるアプリがありません", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private String aiPrompt(String request) {
        return AiPrompt.build(request, Build.SUPPORTED_ABIS[0],
                Build.MANUFACTURER + " " + Build.MODEL, Build.VERSION.RELEASE,
                Build.VERSION.SDK_INT, pageSize(), shell == null || !shell.isAlive() || shell.isRoot());
    }

    // -------------------------------------------------------------- APK 導入

    private void confirmApkInstall(final Uri source, final File tmp, final String name) {
        final boolean root = shell != null && shell.isAlive() && shell.isRoot();
        String how = root
                ? "root 権限の pm install で、確認画面なしにインストールします (同じアプリがあれば上書き更新)。"
                : "root が無いため、Android 標準のインストール画面を開きます。";
        new AlertDialog.Builder(this)
                .setTitle("APK をインストール")
                .setMessage(name + "\n" + (tmp.length() / 1024) + " KB\n\n" + how
                        + "\n\n信頼できる入手元の APK だけをインストールしてください。")
                .setPositiveButton("インストール", (d, w) -> {
                    if (root) {
                        // pm (system_server) はアプリのデータ領域を直接読めないことがあるため、
                        // root 側で cat してパイプで渡す。-S はパイプ入力時に必須のサイズ指定。
                        String p = sq(tmp.getAbsolutePath());
                        sendLine("cat " + p + " | pm install -r -S " + tmp.length()
                                + "; rm -f " + p);
                    } else {
                        tmp.delete();
                        installWithSystemInstaller(source);
                    }
                })
                .setNegativeButton("キャンセル", (d, w) -> tmp.delete())
                .setOnCancelListener(d -> tmp.delete())
                .show();
    }

    private void installWithSystemInstaller(Uri source) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(source, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        // 自分自身も APK を開けるアプリなので、選択肢に出て堂々巡りにならないよう除外する
        Intent chooser = Intent.createChooser(i, "インストーラを選択");
        chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS,
                new android.content.ComponentName[]{getComponentName()});
        try {
            startActivity(chooser);
        } catch (ActivityNotFoundException | SecurityException e) {
            note("標準インストーラを開けませんでした: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 権限

    private void requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                Toast.makeText(this, "全ファイルへのアクセスは許可済みです",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }
    }
}
