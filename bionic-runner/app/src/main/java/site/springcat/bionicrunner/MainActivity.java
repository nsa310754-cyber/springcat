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
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    /** デスクトップ画面から頼まれる操作 */
    static final String ACTION_RUN = "run";
    static final String ACTION_PICK_EXEC = "pickExec";
    static final String ACTION_PICK_APK = "pickApk";
    static final String ACTION_AI = "ai";

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
        if (p != null && ACTION_RUN.equals(p[0]) && !shellReady) return null;
        pendingRequest = null;
        return p;
    }

    private static final int REQ_PICK_EXEC = 1;
    private static final int REQ_PICK_APK = 2;
    private static final int REQ_STORAGE = 3;

    /** 画面に保持する出力の上限 (文字数)。超えたら古い方から捨てる */
    private static final int MAX_OUTPUT = 120_000;
    private static final int TRIM_TO = 80_000;

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]");

    private static final String CLEAR_SCREEN = "\u001B[2J";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder pending = new StringBuilder();
    private boolean flushScheduled;

    private TextView status;
    private TextView output;
    private ScrollView scroll;
    private EditText input;

    private RootShell shell;
    private int shellGeneration;

    private File binDir;
    private File homeDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binDir = new File(getFilesDir(), "bin");
        homeDir = new File(getFilesDir(), "home");
        binDir.mkdirs();
        homeDir.mkdirs();
        buildUi();
        startShell();
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
        String[] p = takePending(shell != null && shell.isAlive());
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
            if (shell != null) shell.interrupt();
        }));
        bar.addView(barButton("再起動", v -> startShell()));
        bar.addView(barButton("消去", v -> output.setText("")));
        bar.addView(barButton("権限", v -> requestStorageAccess()));
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
        root.addView(scroll, new LinearLayout.LayoutParams(
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
    private void note(String text) {
        appendOutput("» " + text + "\n");
    }

    /** どのスレッドからでも呼べる。出力が大量でも UI が詰まらないよう、まとめて反映する */
    private void appendOutput(String text) {
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

    private void flushOutput() {
        String text;
        synchronized (pending) {
            text = pending.toString();
            pending.setLength(0);
            flushScheduled = false;
        }
        // 画面消去 (cls / clear が出すエスケープシーケンス) が来たら、それより前を消す
        int clear = text.lastIndexOf(CLEAR_SCREEN);
        if (clear >= 0) {
            output.setText("", TextView.BufferType.EDITABLE);
            text = text.substring(clear + CLEAR_SCREEN.length());
        }
        text = ANSI.matcher(text).replaceAll("").replace("\r\n", "\n").replace("\r", "\n");
        output.append(text);
        Editable e = output.getEditableText();
        if (e != null && e.length() > MAX_OUTPUT) {
            e.delete(0, e.length() - TRIM_TO);
        }
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    // ---------------------------------------------------------------- シェル

    private void startShell() {
        final int gen = ++shellGeneration;
        if (shell != null) shell.close();
        setStatus("シェル起動中… su の許可ダイアログが出たら「許可」してください", 0xFFFACC15);

        final RootShell s = new RootShell(new RootShell.Listener() {
            @Override
            public void onOutput(String text) {
                if (gen == shellGeneration) appendOutput(text);
            }

            @Override
            public void onExit(int code) {
                ui.post(() -> {
                    if (gen != shellGeneration) return;
                    note("シェルが終了しました (終了コード " + code + ")。「再起動」で起動し直せます");
                    setStatus("シェル停止中", 0xFFF87171);
                });
            }
        }, ptyHelperPath());
        shell = s;

        final GameHost gameHost = GameHost.get(this);
        final File rc = writeShellRc();
        final String init = "export PATH=" + sq(binDir.getAbsolutePath()) + ":$PATH"
                + "; export HOME=" + sq(homeDir.getAbsolutePath())
                + "; export TMPDIR=" + sq(getCacheDir().getAbsolutePath())
                // 絵や音を出すプログラム (同梱の SDL2) がアプリの画面へつなぐための接続先
                + (gameHost == null ? "" : "; export BR_DISPLAY=" + sq(gameHost.address()))
                + "; cd \"$HOME\" 2>/dev/null"
                // 設定ファイルが読めれば読み込む。読めないのは「root なのにアプリの作業領域に
                // 入れない」異常なので、原因の手がかりを画面に出す。
                + (rc == null ? "" : "; if [ -r " + sq(rc.getAbsolutePath()) + " ]; then . "
                + sq(rc.getAbsolutePath()) + "; else " + accessDiagnosis() + "; fi");
        if (rc != null) s.setAccessProbe(rc.getAbsolutePath(), android.os.Process.myPid());
        new Thread(() -> {
            try {
                s.start(init);
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
                if (s.isRoot()) {
                    setStatus("root 取得済み (uid 0)  ·  " + Build.SUPPORTED_ABIS[0], 0xFF4ADE80);
                    note("root シェルを開始しました。対応 CPU: " + abis);
                } else {
                    setStatus("制限モード (root なし)  ·  " + Build.SUPPORTED_ABIS[0], 0xFFFACC15);
                    RootCheck.Result why = RootCheck.last();
                    note((why == null ? "root 権限を使えません" : RootUi.summary(why))
                            + "。root なしの制限モードで動作します。「root 確認」で原因と直し方を表示できます。");
                    note("制限モードでは APK は標準インストーラ経由、実行できるのは bionic 動的リンクの"
                            + "実行ファイルとスクリプトのみです。root 化 (Magisk 等) 後に「再起動」してください。");
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

    /** APK の lib/ に入れてある補助プログラム ptyrun の場所。入っていなければ null */
    private String ptyHelperPath() {
        File f = new File(getApplicationInfo().nativeLibraryDir, "libptyrun.so");
        return f.isFile() ? f.getAbsolutePath() : null;
    }

    private void submitInput() {
        String line = input.getText().toString();
        input.setText("");
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
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
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

    /** 受け取ったファイルを作業領域へ写し、中身を見て APK か実行ファイルかを振り分ける */
    private void openUri(final Uri uri) {
        final String name = safeName(displayName(uri));
        note("読み込み中: " + name);
        new Thread(() -> {
            final File tmp = new File(getCacheDir(), "incoming-" + System.nanoTime());
            final boolean zip;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("開けませんでした");
                byte[] buf = new byte[65536];
                byte[] magic = new byte[4];
                int magicLen = 0;
                try (OutputStream out = new FileOutputStream(tmp)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        for (int i = 0; i < n && magicLen < 4; i++) magic[magicLen++] = buf[i];
                        out.write(buf, 0, n);
                    }
                }
                zip = magicLen == 4 && magic[0] == 'P' && magic[1] == 'K'
                        && magic[2] == 3 && magic[3] == 4;
            } catch (IOException | SecurityException e) {
                tmp.delete();
                ui.post(() -> note("読み込みに失敗しました: " + e.getMessage()));
                return;
            }
            if (zip) {
                ui.post(() -> confirmApkInstall(uri, tmp, name));
                return;
            }
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
            }
            final ExeScanner.Report fr = report;
            ui.post(() -> {
                if (fr != null && fr.dangerous()) showDangerScreen(tmp, name, fi, fr);
                else showExecDialog(tmp, name, fi, fr != null);
            });
        }, "import").start();
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
        switch (info.kind) {
            case BIONIC_DYNAMIC:
            case STATIC:
                sb.append(!cpuOk ? "✖ CPU が合いません。この端末は "
                        + Arrays.toString(Build.SUPPORTED_ABIS) + " 用です。"
                        : !info.fitsPageSize(pageSize()) ? pageSizeProblem(info)
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
                    sb.append("✔ アプリ同梱の glibc 2.41 を使って実行できます。");
                } else {
                    sb.append("△ 同梱の glibc で実行する形式ですが、root が無いと Android の制限で"
                            + "起動できません。");
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
                sb.append("✖ Windows 用の .exe は Linux カーネルでは実行できません。"
                        + "Android 用 (bionic) にビルドされた ELF を選んでください。");
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

    /** scanned が真なら「検査して問題が見つからなかった」ことを緑の帯で知らせる */
    private void showExecDialog(final File tmp, String name, final ElfInfo info, boolean scanned) {
        final EditText nameField = dialogField("登録名", name);
        final EditText argsField = dialogField("引数 (省略可)", "");
        LinearLayout body = dialogBody(describe(info), nameField, argsField);
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
                .setPositiveButton("登録して実行", (d, w) -> register(tmp,
                        nameField.getText().toString(), info, argsField.getText().toString()))
                .setNeutralButton("登録のみ", (d, w) ->
                        register(tmp, nameField.getText().toString(), info, null))
                .setNegativeButton("キャンセル", (d, w) -> tmp.delete())
                .setOnCancelListener(d -> tmp.delete())
                .show();
    }

    /**
     * 危険な操作の記述が見つかったときの赤い警告画面。
     * そのままでは実行できず、「詳細」で見つかった内容を確認してからでないと実行ボタンが出ない。
     */
    private void showDangerScreen(final File tmp, final String name, final ElfInfo info,
                                  ExeScanner.Report report) {
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
            register(tmp, name, info, "");
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
                tmp.delete();
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
                          final String runArgs) {
        final String name = safeName(rawName);
        final File dst = new File(binDir, name);
        final boolean viaGlibc = Glibc.supports(info) && info.cpuMatches(Build.SUPPORTED_ABIS);
        if (viaGlibc) note("glibc を準備しています…");
        new Thread(() -> {
            try {
                File real = viaGlibc ? new File(glibcBinDir(), name) : dst;
                real.getParentFile().mkdirs();
                real.delete();
                if (!tmp.renameTo(real)) throw new IOException("ファイルを移動できません");
                // root シェル (別 uid) からも実行できるよう全員に実行・読み取りを許可する
                real.setReadable(true, false);
                real.setExecutable(true, false);
                if (viaGlibc) {
                    Glibc.ensureInstalled(this, info.abi());
                    Glibc.writeText(dst, Glibc.wrapperScript(this, info.abi(), real));
                    dst.setReadable(true, false);
                    dst.setExecutable(true, false);
                } else {
                    new File(glibcBinDir(), name).delete(); // 同名の古い glibc 版が残らないように
                }
            } catch (IOException e) {
                tmp.delete();
                ui.post(() -> note("登録に失敗しました (" + name + "): " + e.getMessage()));
                return;
            }
            ui.post(() -> {
                note("登録しました: " + name + (viaGlibc ? " (glibc 経由で起動)" : "")
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
                cmd = "sh " + cmd;
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
        ElfInfo info;
        try {
            info = ElfInfo.read(real.isFile() ? real : f);
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
