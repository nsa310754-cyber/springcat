package site.springcat.bionicrunner;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * ゲームモード (Go・Python・Lua・シェルスクリプト): プログラムを、画面制御のできる文字端末の画面で
 * 動かす。カーソル移動や色を使って文字で描くゲーム (テトリスなど) が、そのまま遊べる。
 * 下に操作ボタンを並べ、矢印キーなどを送れるようにする。画面をタップすると文字も打てる。
 *
 * 起動の前に文法の誤りを調べ、誤りがあればプログラムは動かさずに、エラーとコードを画面に表示する。
 */
public class TermGameActivity extends Activity {

    /** 実行するファイル (アプリが読める場所にあるもの) */
    static final String EXTRA_FILE = "file";
    /** 元のファイルの場所 (言語の判定と表示用) */
    static final String EXTRA_ORIGIN = "origin";
    /** ファイルではなく、このコマンドをそのまま動かす (仮想マシンなど)。文法の確認はしない */
    static final String EXTRA_ARGV = "argv";
    /** EXTRA_ARGV と一緒に: 足す環境変数 ("名前=値") と、画面の題名 */
    static final String EXTRA_ENV = "env";
    static final String EXTRA_TITLE = "title";

    private final Handler ui = new Handler(Looper.getMainLooper());
    /** キー入力を順番どおりに送るための 1 本のスレッド */
    private final java.util.concurrent.ExecutorService sender =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private FrameLayout stage;
    private TermView term;
    private Process process;
    private OutputStream toProgram;
    private volatile boolean closed;
    private boolean started;
    private String file, origin;
    private Lang lang;
    private String[] argv, extraEnv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        StopAll.register(this); // 「すべて終了」で閉じる
        file = getIntent().getStringExtra(EXTRA_FILE);
        origin = getIntent().getStringExtra(EXTRA_ORIGIN);
        argv = getIntent().getStringArrayExtra(EXTRA_ARGV);
        extraEnv = getIntent().getStringArrayExtra(EXTRA_ENV);
        if (argv != null) {
            String title = getIntent().getStringExtra(EXTRA_TITLE);
            setTitle(title != null ? title : new File(argv[0]).getName());
        } else {
            if (origin == null) origin = file;
            lang = file == null ? null : Lang.of(origin);
            if (lang == null || lang.type != Lang.Type.TERM) {
                finish();
                return;
            }
            setTitle(new File(origin).getName());
        }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFF000000);
        stage = new FrameLayout(this);
        term = new TermView(this, this::send, true);
        stage.addView(term);
        col.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button keyboard = GameKeys.button(this, "⌨");
        keyboard.setOnClickListener(v -> showKeyboard());
        // Esc・F1〜F12・Ctrl・Alt などのある専用キーボードに切り替える (長押しで設定)
        Button pro = GameKeys.button(this, "⌨ 専用キーボード");
        pro.setOnClickListener(v -> KeyboardSwitch.pick(this, this::showKeyboard));
        pro.setOnLongClickListener(v -> {
            startActivity(new android.content.Intent(this, KeyboardSettingsActivity.class));
            return true;
        });
        Button close = GameKeys.button(this, "✕ 閉じる");
        close.setOnClickListener(v -> finish());
        col.addView(GameKeys.bar(this, (k, down) -> {
            if (down) send(k.bytes);
        }, keyboard, pro, close));
        setContentView(col);

        // 画面の大きさ (何文字ぶんか) が決まってからプログラムを起動する
        term.onReady = this::start;
    }

    private void showKeyboard() {
        term.showKeyboard();
    }

    /** プログラムへ文字を送る (キー入力) */
    private void send(final String text) {
        final OutputStream out = toProgram;
        if (out == null || closed) return;
        // パイプが詰まっていても画面が固まらないよう、別スレッドで送る
        try {
            sender.execute(() -> {
                try {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException ignored) {
                    // プログラムが終了している
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // 画面を閉じたあと
        }
    }

    private void start() {
        if (started) return;
        started = true;
        final String lib = getApplicationInfo().nativeLibraryDir;
        final int cols = term.screen.cols, rows = term.screen.rows;
        if (argv != null) {
            new Thread(() -> run(lib, cols, rows), "term-run").start();
            return;
        }
        new Thread(() -> {
            if (!lang.available(lib)) {
                fail(lang.label + " の処理系は、この端末の CPU 用には入っていません", 0);
                return;
            }
            // 先に文法だけを調べる。誤りがあれば動かさずに、エラーとコードを見せる
            try {
                Process check = new ProcessBuilder(lang.checkArgv(lib, file))
                        .redirectErrorStream(true).start();
                check.getOutputStream().close();
                String out = new String(readAll(check.getInputStream()), StandardCharsets.UTF_8);
                if (check.waitFor() != 0) {
                    String msg = out.replace(file, new File(origin).getName()).trim();
                    fail(msg.isEmpty() ? "文法エラー" : msg, Lang.errorLine(msg));
                    return;
                }
            } catch (IOException e) {
                fail("起動できませんでした: " + e.getMessage(), 0);
                return;
            } catch (InterruptedException e) {
                return;
            }
            run(lib, cols, rows);
        }, "term-run").start();
    }

    private void run(String lib, int cols, int rows) {
        List<String> cmd = argv != null ? new java.util.ArrayList<>(java.util.Arrays.asList(argv))
                : lang.argv(lib, file);
        // 疑似端末を挟む。これが無いと、1 文字ずつのキー入力や画面の大きさの取得ができない
        File pty = new File(lib, "libptyrun.so");
        if (pty.isFile()) cmd.add(0, pty.getAbsolutePath());
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("BR_COLS", String.valueOf(cols));
        env.put("BR_ROWS", String.valueOf(rows));
        env.put("BR_TERM", "xterm-256color");
        env.put("TERM", "xterm-256color");
        env.put("COLUMNS", String.valueOf(cols));
        env.put("LINES", String.valueOf(rows));
        env.put("HOME", new File(getFilesDir(), "home").getAbsolutePath());
        env.put("TMPDIR", getCacheDir().getAbsolutePath());
        env.put("BR_NATIVE", lib);
        // コマンドプロンプトと同じく、登録したプログラムと同梱のコマンド (br・7z・yara) を使えるように
        String path = env.get("PATH");
        env.put("PATH", new File(getFilesDir(), "bin").getAbsolutePath() + ":"
                + Tools.install(this).getAbsolutePath() + (path == null ? "" : ":" + path));
        Control control = Control.get(this);
        if (control != null) env.put("BR_API", control.address());
        GameHost game = GameHost.get(this);
        if (game != null) env.put("BR_DISPLAY", game.address());
        if (extraEnv != null) {
            for (String kv : extraEnv) {
                int eq = kv.indexOf('=');
                if (eq > 0) env.put(kv.substring(0, eq), kv.substring(eq + 1));
            }
        }
        File dir = origin == null ? null : new File(origin).getParentFile();
        pb.directory(dir != null && dir.canRead() ? dir : new File(getFilesDir(), "home"));
        final Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            fail("起動できませんでした: " + e.getMessage(), 0);
            return;
        }
        process = p;
        toProgram = p.getOutputStream();
        if (closed) {
            p.destroy();
            return;
        }
        byte[] buf = new byte[8192];
        try (InputStream in = p.getInputStream()) {
            int n;
            while ((n = in.read(buf)) > 0) {
                term.screen.feed(buf, n);
                term.postInvalidate();
            }
        } catch (IOException ignored) {
            // 画面を閉じた
        }
        int code;
        try {
            code = p.waitFor();
        } catch (InterruptedException e) {
            return;
        }
        if (closed) return;
        term.screen.feed("\u001B[0m\u001B[?25l\r\n\u001B[7m プログラムが終了しました (exit " + code
                + ")。「閉じる」で戻ります \u001B[0m");
        term.postInvalidate();
    }

    /** プログラムを動かせなかった: エラーの内容と、(分かれば) その行に印を付けたコードを画面に出す */
    private void fail(final String message, final int line) {
        String source;
        try {
            source = file == null ? "" : WebGameActivity.readText(new File(file));
        } catch (IOException e) {
            source = "";
        }
        final String code = source;
        ui.post(() -> {
            if (closed) return;
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackgroundColor(0xFF1A0B10);
            TextView head = new TextView(this);
            head.setText("⚠ " + message);
            head.setTextColor(0xFFFFFFFF);
            head.setTextSize(13);
            head.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            head.setBackgroundColor(0xFFB91C1C);
            head.setTextIsSelectable(true);
            head.setPadding(GameKeys.dp(this, 12), GameKeys.dp(this, 10), GameKeys.dp(this, 12),
                    GameKeys.dp(this, 10));
            box.addView(head);
            final ScrollView sv = new ScrollView(this);
            LinearLayout lines = new LinearLayout(this);
            lines.setOrientation(LinearLayout.VERTICAL);
            String[] rows = code.split("\n", -1);
            View target = null;
            for (int i = 0; i < rows.length && i < 3000; i++) {
                TextView t = new TextView(this);
                t.setText(String.format(java.util.Locale.ROOT, "%4d  %s", i + 1,
                        rows[i].replace("\t", "    ")));
                t.setTypeface(Typeface.MONOSPACE);
                t.setTextSize(12);
                t.setTextColor(i + 1 == line ? 0xFFFFFFFF : 0xFFD1D5DB);
                if (i + 1 == line) {
                    t.setBackgroundColor(0xFF7F1D1D);
                    target = t;
                }
                t.setPadding(GameKeys.dp(this, 8), 0, GameKeys.dp(this, 8), 0);
                lines.addView(t);
            }
            sv.addView(lines);
            box.addView(sv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            stage.removeAllViews();
            stage.addView(box);
            final View hit = target;
            if (hit != null) sv.post(() -> sv.scrollTo(0, Math.max(0, hit.getTop() - sv.getHeight() / 3)));
        });
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0 && bo.size() < 256 * 1024) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    @Override
    protected void onDestroy() {
        closed = true;
        sender.shutdownNow();
        final Process p = process;
        if (p != null) {
            // 入力を閉じると、間に挟んだ疑似端末がプログラムへ「端末が閉じた」と伝えて終わらせる
            new Thread(() -> {
                try {
                    p.getOutputStream().close();
                } catch (IOException ignored) {
                    // 既に閉じている
                }
                p.destroy();
            }, "term-close").start();
        }
        super.onDestroy();
    }
}
