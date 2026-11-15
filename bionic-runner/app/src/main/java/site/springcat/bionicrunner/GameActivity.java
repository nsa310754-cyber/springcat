package site.springcat.bionicrunner;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 絵や音を出すプログラムの画面。プログラム (同梱の SDL2) がウィンドウを作ると自動で開き、
 * プログラムが終了すると閉じる。画面へのタッチはマウス操作として、画面上のボタンや
 * キーボードはキー入力としてプログラムへ送る。
 */
public class GameActivity extends Activity {

    private GameHost host;
    private GameView view;
    private View pad;
    private long lastBack;
    /** プログラム側の終了で閉じるときは、こちらから切断し直さない */
    private boolean closedByProgram;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        host = GameHost.get(this);
        if (host == null || !host.isWindowOpen()) {
            finish(); // 表示するプログラムがいない (アプリの再起動後に復元された場合など)
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        view = new GameView(this);
        root.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        pad = buildPad();
        root.addView(pad, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
        root.addView(buildTopBar(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END));
        setContentView(root);
        setTitle(host.title());
        view.requestFocus();
        host.attach(this);
    }

    @Override
    protected void onDestroy() {
        if (host != null) {
            host.detach(this);
            // 利用者が画面を閉じた: 接続を切り、プログラムに「ウィンドウが閉じられた」と伝える
            if (isFinishing() && !closedByProgram) host.disconnect();
        }
        super.onDestroy();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    // ------------------------------------------------- GameHost から (通信スレッド)

    void onFrame() {
        view.postInvalidate();
    }

    void onModeChanged() {
        runOnUiThread(() -> {
            setTitle(host.title());
            view.invalidate();
        });
    }

    void onProgramExit() {
        runOnUiThread(() -> {
            closedByProgram = true;
            finish();
        });
    }

    // ------------------------------------------------------------- 画面上の操作

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(0, dp(4), dp(4), 0);
        bar.addView(smallButton("⌨", v -> toggleKeyboard()), sized(44, 36));
        bar.addView(smallButton("🎮", v -> pad.setVisibility(
                pad.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)), sized(44, 36));
        bar.addView(smallButton("✕", v -> requestClose()), sized(44, 36));
        return bar;
    }

    /** 左に十字キー、右によく使うキーを並べた仮想パッド */
    private View buildPad() {
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(12), 0, dp(12), dp(12));

        // 十字キー: 3x3 の升目の上下左右に置く
        FrameLayout cross = new FrameLayout(this);
        int cell = dp(52);
        addAt(cross, keyButton("▲", KeyMap.UP), cell, 1, 0);
        addAt(cross, keyButton("◀", KeyMap.LEFT), cell, 0, 1);
        addAt(cross, keyButton("▶", KeyMap.RIGHT), cell, 2, 1);
        addAt(cross, keyButton("▼", KeyMap.DOWN), cell, 1, 2);
        box.addView(cross, new FrameLayout.LayoutParams(cell * 3, cell * 3,
                Gravity.BOTTOM | Gravity.START));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        LinearLayout row1 = new LinearLayout(this);
        row1.addView(keyButton("Esc", KeyMap.ESCAPE), sized(52, 44));
        row1.addView(keyButton("Enter", KeyMap.ENTER), sized(64, 44));
        LinearLayout row2 = new LinearLayout(this);
        row2.addView(keyButton("Space", KeyMap.SPACE), sized(64, 52));
        row2.addView(keyButton("Z", KeyMap.Z), sized(52, 52));
        row2.addView(keyButton("X", KeyMap.X), sized(52, 52));
        right.addView(row1);
        right.addView(row2);
        box.addView(right, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END));
        return box;
    }

    private void addAt(FrameLayout parent, View child, int cell, int col, int row) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(cell, cell);
        lp.leftMargin = col * cell;
        lp.topMargin = row * cell;
        parent.addView(child, lp);
    }

    private LinearLayout.LayoutParams sized(int wDp, int hDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(wDp), dp(hDp));
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        return lp;
    }

    private TextView baseButton(String label) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x66374151); // 下のゲーム画面が透けて見える濃さ
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), 0x66FFFFFF);
        b.setBackground(bg);
        return b;
    }

    private TextView smallButton(String label, View.OnClickListener l) {
        TextView b = baseButton(label);
        b.setOnClickListener(l);
        return b;
    }

    /** 押している間だけキーが押された状態になるボタン */
    @SuppressLint("ClickableViewAccessibility")
    private TextView keyButton(String label, final int scancode) {
        final TextView b = baseButton(label);
        b.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    b.setAlpha(0.5f);
                    host.sendEvent(DisplayServer.EV_KEY, scancode, 1, 0);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    b.setAlpha(1f);
                    host.sendEvent(DisplayServer.EV_KEY, scancode, 0, 0);
                    return true;
                default:
                    return true;
            }
        });
        return b;
    }

    private void toggleKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm == null) return;
        view.requestFocus();
        imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
    }

    /** 1 回目はプログラムに終了を頼み、続けてもう 1 回で強制的に閉じる */
    private void requestClose() {
        long now = System.currentTimeMillis();
        if (now - lastBack < 2500) {
            finish();
            return;
        }
        lastBack = now;
        host.sendEvent(DisplayServer.EV_QUIT, 0, 0, 0);
        Toast.makeText(this, "終了を送りました。閉じない場合はもう一度押してください",
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        requestClose();
    }

    // -------------------------------------------------------------- キー入力

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_VOLUME_UP
                || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(event); // 戻る・音量は Android に任せる
        }
        int scancode = KeyMap.scancode(code);
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (scancode != 0 && (down || event.getAction() == KeyEvent.ACTION_UP)) {
            // 押しっぱなしで繰り返し届く DOWN は送らない (SDL 側は押下状態を自分で持っている)
            if (!down || event.getRepeatCount() == 0) {
                host.sendEvent(DisplayServer.EV_KEY, scancode, down ? 1 : 0, 0);
            }
        }
        if (down) {
            // 文字入力を受け付けるプログラム (名前入力など) のために、文字としても送る
            int ch = event.getUnicodeChar();
            if (ch >= 0x20 && ch != 0x7f) host.sendEvent(DisplayServer.EV_TEXT, ch, 0, 0);
        }
        return scancode != 0 || super.dispatchKeyEvent(event);
    }

    // ------------------------------------------------------------ ゲームの表示

    private final class GameView extends View {
        private final Rect dst = new Rect();

        GameView(Context c) {
            super(c);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        /** 縦横比を保ったまま、画面に収まる最大の大きさで表示する範囲を求める */
        private boolean layoutImage() {
            int[] size = host.size();
            if (size == null || getWidth() == 0 || getHeight() == 0) return false;
            float scale = Math.min(getWidth() / (float) size[0], getHeight() / (float) size[1]);
            int w = Math.round(size[0] * scale), h = Math.round(size[1] * scale);
            int left = (getWidth() - w) / 2;
            // 縦持ちでは下に操作ボタンがあるので、絵は上に寄せる
            int top = getHeight() > getWidth() ? 0 : (getHeight() - h) / 2;
            dst.set(left, top, left + w, top + h);
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (layoutImage()) host.draw(canvas, dst);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int[] size = host.size();
            if (size == null || !layoutImage() || dst.width() == 0 || dst.height() == 0) return true;
            // 画面上の位置を、プログラムのウィンドウ内の座標に直す (範囲外は端に丸める)
            int x = Math.round((e.getX() - dst.left) * size[0] / dst.width());
            int y = Math.round((e.getY() - dst.top) * size[1] / dst.height());
            x = Math.max(0, Math.min(size[0] - 1, x));
            y = Math.max(0, Math.min(size[1] - 1, y));
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    host.sendEvent(DisplayServer.EV_MOUSE_BUTTON, x, y, 1);
                    break;
                case MotionEvent.ACTION_MOVE:
                    host.sendEvent(DisplayServer.EV_MOUSE_MOTION, x, y, 0);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    host.sendEvent(DisplayServer.EV_MOUSE_BUTTON, x, y, 0);
                    break;
                default:
                    break;
            }
            return true;
        }

        @Override
        public boolean onCheckIsTextEditor() {
            return true;
        }

        /**
         * 画面キーボードからの入力を受ける。入力欄の種類を「なし」にしておくと、
         * キーボードは変換をせず 1 文字ずつキー入力として送ってくる (dispatchKeyEvent に届く)。
         */
        @Override
        public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            outAttrs.inputType = InputType.TYPE_NULL;
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
            return new BaseInputConnection(this, false);
        }
    }
}
