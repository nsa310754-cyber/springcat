package site.springcat.bionicrunner;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

/**
 * 文字端末の画面 ({@link VtScreen}) を描く部品。ゲームモード ({@link TermGameActivity}) と、
 * コマンドプロンプトの全画面モード ({@link MainActivity}) で使う。
 *
 * 大きさの決め方は 2 通り:
 *  - 自動 (ゲームモード): 1 文字の幅が読みやすい大きさになるよう、横に入る文字数を決める
 *  - 固定 ({@link #setFixedSize}。コマンドプロンプト): プログラムが思っている端末の大きさ
 *    (疑似端末の 80×24 など) に合わせ、その文字数がちょうど収まるように文字の大きさを決める
 */
final class TermView extends View {

    VtScreen screen;
    /** 画面の大きさが決まって {@link #screen} ができたときに呼ぶ */
    Runnable onReady;
    private final VtScreen.Reply sender;
    /** 画面キーボードや外付けキーボードの入力を、この部品が受け取るか */
    private final boolean keyboardInput;
    private int fixedCols, fixedRows;
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint();
    private float cellW, cellH, baseline;
    private final char[] one = new char[2];

    /**
     * sender は、プログラムへ送る文字 (キー入力や、端末への問い合わせの答え) の送り先。
     * keyboardInput が真なら、タップで画面キーボードを出し、打った文字を sender へ送る。
     */
    TermView(Context c, VtScreen.Reply sender, boolean keyboardInput) {
        super(c);
        this.sender = sender;
        this.keyboardInput = keyboardInput;
        text.setTypeface(Typeface.MONOSPACE);
        if (keyboardInput) {
            setFocusable(true);
            setFocusableInTouchMode(true);
            setOnClickListener(v -> showKeyboard());
        }
    }

    /** 端末の大きさを固定する。画面に置く前に呼ぶ */
    void setFixedSize(int cols, int rows) {
        fixedCols = cols;
        fixedRows = rows;
    }

    void showKeyboard() {
        requestFocus();
        InputMethodManager imm = (InputMethodManager) getContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(this, 0);
    }

    private void send(String s) {
        sender.send(s);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        if (w <= 0 || h <= 0) return;
        if (fixedCols > 0) {
            // 決まった文字数が収まるように文字の大きさを決める (向きを変えたときも合わせ直す)
            fit(w, h, fixedCols, fixedRows);
            if (screen == null) {
                screen = new VtScreen(fixedCols, fixedRows, sender);
                if (onReady != null) onReady.run();
            }
            return;
        }
        if (screen != null) return;
        // 1 文字の幅を 9dp くらいにする。狭い画面でも 40 文字、広い画面でも 100 文字まで
        int cols = Math.max(40, Math.min(100, w / GameKeys.dp(getContext(), 9)));
        setCellWidth((float) w / cols);
        int rows = Math.max(10, (int) (h / cellH));
        screen = new VtScreen(cols, rows, sender);
        if (onReady != null) onReady.run();
    }

    /** cols × rows の文字が w × h に収まる、いちばん大きい文字の大きさにする */
    private void fit(int w, int h, int cols, int rows) {
        setCellWidth((float) w / cols);
        if (cellH * rows > h) {
            // 縦に収まらない: 縦に合わせて小さくする (横には余白ができる)
            setCellWidth(cellW * h / (cellH * rows));
        }
    }

    private void setCellWidth(float width) {
        cellW = width;
        text.setTextSize(cellW / 0.6f); // 等幅フォントの文字幅は、文字の大きさのおよそ 0.6 倍
        float measured = text.measureText("M");
        if (measured > 0) text.setTextSize(text.getTextSize() * cellW / measured);
        Paint.FontMetrics fm = text.getFontMetrics();
        cellH = (float) Math.ceil(fm.descent - fm.ascent);
        baseline = -fm.ascent;
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

    // ---- 画面キーボードからの入力 (keyboardInput が真のときだけ)

    @Override
    public boolean onCheckIsTextEditor() {
        return keyboardInput;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo info) {
        if (!keyboardInput) return null;
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
        if (!keyboardInput || keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, e);
        // キーを xterm と同じバイト列にして送る (F1〜F12・矢印・Home/End・Ctrl/Alt の組み合わせなど。
        // 専用キーボードやつないだキーボードから届く)。Ctrl と一緒の英字は制御文字 (Ctrl-C = 3 など)
        String seq = KeySeq.encode(e, screen != null && screen.appCursor);
        if (seq != null) {
            send(seq);
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }
}
