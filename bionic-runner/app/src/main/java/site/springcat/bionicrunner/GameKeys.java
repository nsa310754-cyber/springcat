package site.springcat.bionicrunner;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

/**
 * ゲームモードの画面の下に並べる操作ボタン (矢印・Space・Enter など)。
 * スマホにはキーボードが無いので、キーで操作するゲームをこれで遊べるようにする。
 * 押している間は、キーボードと同じように一定間隔で「押した」を繰り返す。
 */
final class GameKeys {

    /** ボタン 1 個ぶんのキー */
    static final class Key {
        final String label;
        /** ブラウザでのキーの名前 (KeyboardEvent.key / code) と昔ながらの番号 */
        final String key, code;
        final int keyCode;
        /** 端末へ送るバイト列 */
        final String bytes;

        Key(String label, String key, String code, int keyCode, String bytes) {
            this.label = label;
            this.key = key;
            this.code = code;
            this.keyCode = keyCode;
            this.bytes = bytes;
        }
    }

    interface Listener {
        /** down が真なら押した (押し続けている間も繰り返し呼ばれる)、偽なら離した */
        void onKey(Key key, boolean down);
    }

    static final Key[] KEYS = {
            new Key("◀", "ArrowLeft", "ArrowLeft", 37, "\u001B[D"),
            new Key("▼", "ArrowDown", "ArrowDown", 40, "\u001B[B"),
            new Key("▲", "ArrowUp", "ArrowUp", 38, "\u001B[A"),
            new Key("▶", "ArrowRight", "ArrowRight", 39, "\u001B[C"),
            new Key("Space", " ", "Space", 32, " "),
            new Key("Enter", "Enter", "Enter", 13, "\r"),
            new Key("Z", "z", "KeyZ", 90, "z"),
            new Key("X", "x", "KeyX", 88, "x"),
            new Key("Esc", "Escape", "Escape", 27, "\u001B"),
            new Key("P", "p", "KeyP", 80, "p"),
            new Key("Q", "q", "KeyQ", 81, "q"),
            new Key("R", "r", "KeyR", 82, "r"),
            new Key("W", "w", "KeyW", 87, "w"),
            new Key("A", "a", "KeyA", 65, "a"),
            new Key("S", "s", "KeyS", 83, "s"),
            new Key("D", "d", "KeyD", 68, "d"),
            new Key("Y", "y", "KeyY", 89, "y"),
            new Key("N", "n", "KeyN", 78, "n"),
    };

    private GameKeys() {
    }

    /** 横に流せるボタンの帯を作る。extra は帯の右端に足す追加のボタン (閉じるなど) */
    @SuppressLint("ClickableViewAccessibility")
    static View bar(Context c, final Listener listener, View... extra) {
        final Handler ui = new Handler(Looper.getMainLooper());
        LinearLayout row = new LinearLayout(c);
        row.setPadding(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4));
        for (final Key k : KEYS) {
            Button b = button(c, k.label);
            final Runnable[] repeat = new Runnable[1];
            repeat[0] = () -> {
                listener.onKey(k, true);
                ui.postDelayed(repeat[0], 90);
            };
            b.setOnTouchListener((v, e) -> {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    v.setPressed(true);
                    listener.onKey(k, true);
                    ui.postDelayed(repeat[0], 320);
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    v.setPressed(false);
                    ui.removeCallbacks(repeat[0]);
                    listener.onKey(k, false);
                }
                return true;
            });
            row.addView(b);
        }
        for (View v : extra) row.addView(v);
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setBackgroundColor(0xFF111827);
        hs.addView(row);
        return hs;
    }

    static Button button(Context c, String label) {
        Button b = new Button(c, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(Color.WHITE);
        b.setMinWidth(0);
        b.setMinimumWidth(dp(c, 52));
        b.setPadding(dp(c, 12), 0, dp(c, 12), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF374151);
        bg.setCornerRadius(dp(c, 8));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(c, 46));
        lp.rightMargin = dp(c, 6);
        b.setLayoutParams(lp);
        return b;
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }
}
