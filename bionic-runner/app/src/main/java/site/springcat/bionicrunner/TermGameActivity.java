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
        term = new TermView(this);
        stage.addView(term);
        col.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button keyboard = GameKeys.button(this, "⌨");
        keyboard.setOnClickListener(v -> showKeyboard());
        Button close = GameKeys.button(this, "✕ 閉じる");
        close.setOnClickListener(v -> finish());
        col.addView(GameKeys.bar(this, (k, down) -> {
            if (down) send(k.bytes);
        }, keyboard, close));
        setContentView(col);

        // 画面の大きさ (何文字ぶんか) が決まってからプログラムを起動する
        term.onReady = this::start;
    }

    private void showKeyboard() {
        term.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(term, 0);
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

    /** 文字端末の画面を描く。大きさは、横に入る文字数が読みやすい範囲になるように決める */
    private final class TermView extends View {
        VtScreen screen;
        Runnable onReady;
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint();
        private float cellW, cellH, baseline;
        private final char[] one = new char[2];

        TermView(Context c) {
            super(c);
            text.setTypeface(Typeface.MONOSPACE);
            setFocusable(true);
            setFocusableInTouchMode(true);
            setOnClickListener(v -> showKeyboard());
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            if (screen != null || w <= 0 || h <= 0) return;
            // 1 文字の幅を 9dp くらいにする。狭い画面でも 40 文字、広い画面でも 100 文字まで
            int cols = Math.max(40, Math.min(100, w / GameKeys.dp(getContext(), 9)));
            cellW = (float) w / cols;
            text.setTextSize(cellW / 0.6f); // 等幅フォントの文字幅は、文字の大きさのおよそ 0.6 倍
            float measured = text.measureText("M");
            if (measured > 0) text.setTextSize(text.getTextSize() * cellW / measured);
            Paint.FontMetrics fm = text.getFontMetrics();
            cellH = (float) Math.ceil(fm.descent - fm.ascent);
            baseline = -fm.ascent;
            int rows = Math.max(10, (int) (h / cellH));
            screen = new VtScreen(cols, rows, TermGameActivity.this::send);
            if (onReady != null) onReady.run();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (screen == null) return;
            synchronized (screen) {
                for (int y = 0; y < screen.rows; y++) {
                    float top = y * cellH;
                    for (int x = 0; x < screen.cols; x++) {
                        int at = y * screen.cols + x, c = screen.ch[at];
                        if (c == VtScreen.WIDE_TAIL) continue;
                        boolean wide = x + 1 < screen.cols && screen.ch[at + 1] == VtScreen.WIDE_TAIL;
                        float left = x * cellW, right = left + cellW * (wide ? 2 : 1);
                        int bg = screen.bg[at], fg = screen.fg[at];
                        if (bg != VtScreen.DEFAULT_BG) {
                            fill.setColor(bg);
                            canvas.drawRect(left, top, right, top + cellH, fill);
                        }
                        if (c == ' ') continue;
                        if (c >= 0x2580 && c <= 0x2593 && block(canvas, c, left, top, right, fg)) continue;
                        text.setColor(fg);
                        int len = Character.toChars(c, one, 0);
                        canvas.drawText(one, 0, len, left, top + baseline, text);
                    }
                }
                if (screen.cursorVisible) {
                    fill.setColor(0x99FFFFFF);
                    float left = screen.curX * cellW, top = screen.curY * cellH;
                    canvas.drawRect(left, top + cellH - cellH / 6, left + cellW, top + cellH, fill);
                }
            }
        }

        /**
         * ブロック状の記号 (█ ▀ ▄ ▌ ▐ ░ ▒ ▓) は、文字として描くとマスの間に隙間ができるので、
         * 四角形として塗る。描いたら真を返す
         */
        private boolean block(Canvas canvas, int c, float l, float t, float r, int color) {
            float b = t + cellH, mx = (l + r) / 2, my = t + cellH / 2;
            fill.setColor(color);
            switch (c) {
                case 0x2588: canvas.drawRect(l, t, r, b, fill); return true;
                case 0x2580: canvas.drawRect(l, t, r, my, fill); return true;
                case 0x2584: canvas.drawRect(l, my, r, b, fill); return true;
                case 0x258C: canvas.drawRect(l, t, mx, b, fill); return true;
                case 0x2590: canvas.drawRect(mx, t, r, b, fill); return true;
                case 0x2591: fill.setAlpha(64); break;
                case 0x2592: fill.setAlpha(128); break;
                case 0x2593: fill.setAlpha(192); break;
                default: return false;
            }
            canvas.drawRect(l, t, r, b, fill);
            fill.setAlpha(255);
            return true;
        }

        // ---- 画面キーボードからの入力

        @Override
        public boolean onCheckIsTextEditor() {
            return true;
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo info) {
            // 変換や予測をさせず、打った文字がそのまま届くようにする
            info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN;
            return new BaseInputConnection(this, false) {
                @Override
                public boolean commitText(CharSequence t, int newCursorPosition) {
                    send(t.toString().replace('\n', '\r'));
                    return true;
                }

                @Override
                public boolean deleteSurroundingText(int before, int after) {
                    for (int i = 0; i < before; i++) send("\u007F");
                    return true;
                }
            };
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent e) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_ENTER: send("\r"); return true;
                case KeyEvent.KEYCODE_DEL: send("\u007F"); return true;
                case KeyEvent.KEYCODE_ESCAPE: send("\u001B"); return true;
                case KeyEvent.KEYCODE_TAB: send("\t"); return true;
                case KeyEvent.KEYCODE_DPAD_UP: send("\u001B[A"); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: send("\u001B[B"); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: send("\u001B[C"); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: send("\u001B[D"); return true;
                default:
            }
            int c = e.getUnicodeChar();
            if (c != 0 && keyCode != KeyEvent.KEYCODE_BACK) {
                // Ctrl と一緒に押した英字は制御文字 (Ctrl-C = 3 など) にする
                if (e.isCtrlPressed() && c >= 'a' && c <= 'z') c = c - 'a' + 1;
                send(new String(Character.toChars(c)));
                return true;
            }
            return super.onKeyDown(keyCode, e);
        }
    }
}
