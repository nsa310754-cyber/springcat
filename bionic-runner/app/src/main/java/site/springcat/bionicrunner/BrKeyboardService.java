package site.springcat.bionicrunner;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Bionic Runner の専用キーボード (Android の入力方法)。
 *
 * 普通の Android のキーボードに無い Esc・F1〜F12・Ctrl・Alt・Meta (Win)・矢印・Home/End・
 * PgUp/PgDn・Ins/Del を備える。ターミナル・X11 だけでなく、ほかのアプリでも使える。
 *
 *   ・Ctrl / Alt / Shift / Meta は、1 回押すと次の 1 キーだけ有効 (色が付く)、2 回押すと固定
 *     (濃い色)、もう一度押すと解除
 *   ・長押しでキーリピート (修飾キーと 🌐 は除く)
 *   ・文字は、修飾キーが無ければ文字として渡し、Ctrl・Alt・Meta があれば正しい keyCode と
 *     metaState (CTRL_ON など) のキーとして送る (Ctrl+C・Alt+Tab・Ctrl+Alt+Del などが届くように)
 *   ・🌐 で元のキーボードに戻す (長押しでキーボードの一覧)
 *
 * 打った内容は保存も送信もしない (パスワードも打つため)。ログにも出さない。
 */
public class BrKeyboardService extends InputMethodService {

    // ------------------------------------------------------------------ キーの定義

    /** 修飾キーの状態 */
    private static final int OFF = 0, ONCE = 1, LOCK = 2;
    private static final int MOD_SHIFT = 0, MOD_CTRL = 1, MOD_ALT = 2, MOD_META = 3;
    private final int[] mods = new int[4];
    private final List<TextView> modViews = new ArrayList<>();
    private final List<KeyView> charKeys = new ArrayList<>();

    /** 文字のキー: 普通の文字・Shift のときの文字・keyCode */
    private static final class CharKey {
        final char normal, shifted;
        final int keyCode;

        CharKey(char normal, char shifted, int keyCode) {
            this.normal = normal;
            this.shifted = shifted;
            this.keyCode = keyCode;
        }
    }

    private static CharKey c(char n, char s, int code) {
        return new CharKey(n, s, code);
    }

    private static CharKey letter(char n) {
        return new CharKey(n, Character.toUpperCase(n), KeyEvent.KEYCODE_A + (n - 'a'));
    }

    private static final CharKey[] ROW1 = {
            c('`', '~', KeyEvent.KEYCODE_GRAVE), c('1', '!', KeyEvent.KEYCODE_1), c('2', '@', KeyEvent.KEYCODE_2),
            c('3', '#', KeyEvent.KEYCODE_3), c('4', '$', KeyEvent.KEYCODE_4), c('5', '%', KeyEvent.KEYCODE_5),
            c('6', '^', KeyEvent.KEYCODE_6), c('7', '&', KeyEvent.KEYCODE_7), c('8', '*', KeyEvent.KEYCODE_8),
            c('9', '(', KeyEvent.KEYCODE_9), c('0', ')', KeyEvent.KEYCODE_0), c('-', '_', KeyEvent.KEYCODE_MINUS),
            c('=', '+', KeyEvent.KEYCODE_EQUALS),
    };
    private static final CharKey[] ROW2 = {
            letter('q'), letter('w'), letter('e'), letter('r'), letter('t'), letter('y'), letter('u'),
            letter('i'), letter('o'), letter('p'), c('[', '{', KeyEvent.KEYCODE_LEFT_BRACKET),
            c(']', '}', KeyEvent.KEYCODE_RIGHT_BRACKET), c('\\', '|', KeyEvent.KEYCODE_BACKSLASH),
    };
    private static final CharKey[] ROW3 = {
            letter('a'), letter('s'), letter('d'), letter('f'), letter('g'), letter('h'), letter('j'),
            letter('k'), letter('l'), c(';', ':', KeyEvent.KEYCODE_SEMICOLON),
            c('\'', '"', KeyEvent.KEYCODE_APOSTROPHE),
    };
    private static final CharKey[] ROW4 = {
            letter('z'), letter('x'), letter('c'), letter('v'), letter('b'), letter('n'), letter('m'),
            c(',', '<', KeyEvent.KEYCODE_COMMA), c('.', '>', KeyEvent.KEYCODE_PERIOD),
            c('/', '?', KeyEvent.KEYCODE_SLASH),
    };

    // ------------------------------------------------------------------ 画面

    private final Handler ui = new Handler(Looper.getMainLooper());
    private int builtHeight = -1;
    private boolean builtLandscape;

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    private boolean landscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    /** キーの高さ (dp)。設定で縦画面・横画面それぞれ変えられる */
    static int keyHeight(Context c, boolean landscape) {
        SharedPreferences sp = c.getSharedPreferences("keyboard", MODE_PRIVATE);
        return landscape ? sp.getInt("height_land", 34) : sp.getInt("height_port", 46);
    }

    static void setKeyHeight(Context c, boolean landscape, int dp) {
        c.getSharedPreferences("keyboard", MODE_PRIVATE).edit()
                .putInt(landscape ? "height_land" : "height_port", dp).apply();
    }

    @Override
    public View onCreateInputView() {
        return build();
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        // 設定で高さを変えた・画面の向きが変わったときは作り直す
        boolean land = landscape();
        if (builtHeight != keyHeight(this, land) || builtLandscape != land) setInputView(build());
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false; // 横画面でも、入力欄を全画面に広げない
    }

    @Override
    public void onFinishInput() {
        super.onFinishInput();
        // 次の入力欄へ修飾キーを持ち越さない
        for (int i = 0; i < mods.length; i++) mods[i] = OFF;
        refreshMods();
    }

    private View build() {
        boolean land = landscape();
        builtLandscape = land;
        builtHeight = keyHeight(this, land);
        int h = dp(builtHeight);
        modViews.clear();
        charKeys.clear();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0B0F17);
        root.setPadding(dp(2), dp(3), dp(2), dp(3));

        // 1 段目: Esc F1〜F12 と編集キー (横に流せる)
        LinearLayout fn = new LinearLayout(this);
        fn.setOrientation(LinearLayout.HORIZONTAL);
        addSpecial(fn, "Esc", KeyEvent.KEYCODE_ESCAPE, 0, h);
        for (int i = 1; i <= 12; i++) addSpecial(fn, "F" + i, KeyEvent.KEYCODE_F1 + i - 1, 0, h);
        addSpecial(fn, "Ins", KeyEvent.KEYCODE_INSERT, 0, h);
        addSpecial(fn, "Del", KeyEvent.KEYCODE_FORWARD_DEL, 0, h);
        addSpecial(fn, "Home", KeyEvent.KEYCODE_MOVE_HOME, 0, h);
        addSpecial(fn, "End", KeyEvent.KEYCODE_MOVE_END, 0, h);
        addSpecial(fn, "PgUp", KeyEvent.KEYCODE_PAGE_UP, 0, h);
        addSpecial(fn, "PgDn", KeyEvent.KEYCODE_PAGE_DOWN, 0, h);
        KeyView settings = key("⚙", 0xFF374151);
        settings.setOnClickListener(() -> openSettings());
        fn.addView(settings, new LinearLayout.LayoutParams(dp(48), h));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(fn);
        root.addView(hs);

        root.addView(charRow(ROW1, null, 0, null, 0, h));
        root.addView(charRow(ROW2, null, 0, null, 0, h));
        // 3 段目: Tab + a〜' 、4 段目: Shift + z〜/ + ⌫
        LinearLayout r3 = charRow(ROW3, "Tab", KeyEvent.KEYCODE_TAB, null, 0, h);
        root.addView(r3);
        LinearLayout r4 = new LinearLayout(this);
        r4.setOrientation(LinearLayout.HORIZONTAL);
        addMod(r4, "Shift", MOD_SHIFT, 1.5f, h);
        for (CharKey k : ROW4) addChar(r4, k, 1f, h);
        addSpecial(r4, "⌫", KeyEvent.KEYCODE_DEL, 1.5f, h);
        root.addView(r4);
        // 5 段目: Ctrl Alt Win 🌐 Space ← ↑ ↓ → Enter
        LinearLayout r5 = new LinearLayout(this);
        r5.setOrientation(LinearLayout.HORIZONTAL);
        addMod(r5, "Ctrl", MOD_CTRL, 1.2f, h);
        addMod(r5, "Alt", MOD_ALT, 1.1f, h);
        addMod(r5, "Win", MOD_META, 1.1f, h);
        KeyView globe = key("🌐", 0xFF374151);
        globe.setOnClickListener(this::switchBack);
        globe.setOnLongClickListener(this::showPicker);
        r5.addView(globe, weight(1f, h));
        addSpecial(r5, "Space", KeyEvent.KEYCODE_SPACE, 3.2f, h);
        addSpecial(r5, "←", KeyEvent.KEYCODE_DPAD_LEFT, 1f, h);
        addSpecial(r5, "↑", KeyEvent.KEYCODE_DPAD_UP, 1f, h);
        addSpecial(r5, "↓", KeyEvent.KEYCODE_DPAD_DOWN, 1f, h);
        addSpecial(r5, "→", KeyEvent.KEYCODE_DPAD_RIGHT, 1f, h);
        addSpecial(r5, "Enter", KeyEvent.KEYCODE_ENTER, 1.6f, h);
        root.addView(r5);
        refreshMods();
        return root;
    }

    private LinearLayout.LayoutParams weight(float w, int h) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, h, w);
        return lp;
    }

    private LinearLayout charRow(CharKey[] keys, String leftLabel, int leftCode, String rightLabel,
                                 int rightCode, int h) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        if (leftLabel != null) addSpecial(row, leftLabel, leftCode, 1.5f, h);
        for (CharKey k : keys) addChar(row, k, 1f, h);
        if (rightLabel != null) addSpecial(row, rightLabel, rightCode, 1.5f, h);
        return row;
    }

    private KeyView key(String label, int color) {
        KeyView v = new KeyView(this, color);
        v.setText(label);
        return v;
    }

    private void addChar(LinearLayout row, final CharKey k, float w, int h) {
        final KeyView v = key(String.valueOf(k.normal), 0xFF1F2937);
        v.charKey = k;
        v.repeat = true;
        v.setOnClickListener(() -> typeChar(k));
        charKeys.add(v);
        row.addView(v, weight(w, h));
    }

    /** 文字ではないキー (Esc・F1・矢印など)。w が 0 なら決まった幅 (横に流す段) */
    private void addSpecial(LinearLayout row, String label, final int keyCode, float w, int h) {
        KeyView v = key(label, 0xFF2B3442);
        v.repeat = keyCode != KeyEvent.KEYCODE_ESCAPE;
        v.setOnClickListener(() -> pressSpecial(keyCode));
        if (w == 0) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(label.length() > 2 ? 54 : 46), h);
            row.addView(v, lp);
        } else {
            row.addView(v, weight(w, h));
        }
    }

    private void addMod(LinearLayout row, String label, final int which, float w, int h) {
        KeyView v = key(label, 0xFF2B3442);
        v.setTag(which);
        v.setOnClickListener(() -> {
            mods[which] = mods[which] == OFF ? ONCE : mods[which] == ONCE ? LOCK : OFF;
            refreshMods();
        });
        modViews.add(v);
        row.addView(v, weight(w, h));
    }

    /** 修飾キーの色と、文字のキーの表示 (Shift なら大文字・記号) を今の状態に合わせる */
    private void refreshMods() {
        for (TextView v : modViews) {
            int which = (Integer) v.getTag();
            ((KeyView) v).setColor(mods[which] == OFF ? 0xFF2B3442 : mods[which] == ONCE ? 0xFF2563EB : 0xFF1E3A8A);
            v.setTextColor(mods[which] == LOCK ? 0xFFFDE68A : Color.WHITE);
        }
        boolean shift = mods[MOD_SHIFT] != OFF;
        for (KeyView v : charKeys) v.setText(String.valueOf(shift ? v.charKey.shifted : v.charKey.normal));
    }

    /** 「1 回だけ」の修飾キーを、キーを 1 つ打ったあとに外す */
    private void releaseOnce() {
        boolean changed = false;
        for (int i = 0; i < mods.length; i++) {
            if (mods[i] == ONCE) {
                mods[i] = OFF;
                changed = true;
            }
        }
        if (changed) refreshMods();
    }

    private int metaState() {
        int m = 0;
        if (mods[MOD_SHIFT] != OFF) m |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
        if (mods[MOD_CTRL] != OFF) m |= KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
        if (mods[MOD_ALT] != OFF) m |= KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
        if (mods[MOD_META] != OFF) m |= KeyEvent.META_META_ON | KeyEvent.META_META_LEFT_ON;
        return m;
    }

    // ------------------------------------------------------------------ 送る

    private void typeChar(CharKey k) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        boolean shift = mods[MOD_SHIFT] != OFF;
        boolean special = mods[MOD_CTRL] != OFF || mods[MOD_ALT] != OFF || mods[MOD_META] != OFF;
        if (special) {
            // Ctrl・Alt・Meta と一緒: 正しい keyCode と metaState のキーとして送る (Ctrl+C など)
            sendKey(ic, k.keyCode, metaState());
        } else {
            // 普通の文字は、文字として渡す (どのアプリの入力欄でも確実に入る)
            ic.commitText(String.valueOf(shift ? k.shifted : k.normal), 1);
        }
        releaseOnce();
    }

    private void pressSpecial(int keyCode) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        int meta = metaState();
        if (keyCode == KeyEvent.KEYCODE_SPACE && meta == 0) {
            ic.commitText(" ", 1);
        } else if (keyCode == KeyEvent.KEYCODE_ENTER && meta == 0 && performEditorAction(ic)) {
            // 1 行の入力欄 (送信・検索など) は、その欄の決まった動作をさせる
        } else {
            sendKey(ic, keyCode, meta);
        }
        releaseOnce();
    }

    /** 入力欄に「送信」などの動作が決まっていれば、それを行う。行ったら真 */
    private boolean performEditorAction(InputConnection ic) {
        EditorInfo info = getCurrentInputEditorInfo();
        if (info == null) return false;
        if ((info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return false;
        int action = info.imeOptions & EditorInfo.IME_MASK_ACTION;
        if (action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) return false;
        return ic.performEditorAction(action);
    }

    /** キーを押して離す (KeyEvent を 2 つ)。端末の中だけで受け渡し、どこにも残さない */
    private void sendKey(InputConnection ic, int keyCode, int meta) {
        long now = SystemClock.uptimeMillis();
        int flags = KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE;
        ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags));
        ic.sendKeyEvent(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0, meta,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags));
    }

    // ------------------------------------------------------------------ キーボードの切り替え

    /** 🌐: 元のキーボードに戻す */
    private void switchBack() {
        boolean ok = false;
        if (Build.VERSION.SDK_INT >= 28) {
            ok = switchToPreviousInputMethod();
        } else {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && getWindow() != null && getWindow().getWindow() != null) {
                //noinspection deprecation
                ok = imm.switchToLastInputMethod(getWindow().getWindow().getAttributes().token);
            }
        }
        if (!ok) showPicker();
    }

    /** 🌐 の長押し: キーボードの一覧 */
    private boolean showPicker() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showInputMethodPicker();
        return true;
    }

    private void openSettings() {
        try {
            startActivity(new android.content.Intent(this, KeyboardSettingsActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
            // 開けない
        }
    }

    // ------------------------------------------------------------------ キー 1 個

    /** キー 1 個。押している間は色を変え、長押しで繰り返す */
    private final class KeyView extends TextView {
        private final GradientDrawable bg = new GradientDrawable();
        private int color;
        CharKey charKey;
        boolean repeat;
        private Runnable click;
        private java.util.function.BooleanSupplier longClick;
        private final Runnable repeater = new Runnable() {
            @Override
            public void run() {
                if (click != null) click.run();
                ui.postDelayed(this, 55);
            }
        };
        private final Runnable longPress = () -> {
            if (longClick != null && longClick.getAsBoolean()) {
                longFired = true;
            } else if (repeat) {
                repeating = true;
                ui.post(repeater);
            }
        };
        private boolean longFired, repeating;

        KeyView(Context c, int color) {
            super(c);
            this.color = color;
            setGravity(Gravity.CENTER);
            setTextColor(Color.WHITE);
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            setTypeface(Typeface.DEFAULT_BOLD);
            setSingleLine(true);
            bg.setCornerRadius(dp(6));
            bg.setColor(color);
            setBackground(bg);
            ViewGroup.MarginLayoutParams mp = new ViewGroup.MarginLayoutParams(0, 0);
            setLayoutParams(mp);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            ViewGroup.LayoutParams lp = getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ((ViewGroup.MarginLayoutParams) lp).setMargins(dp(1.5f), dp(1.5f), dp(1.5f), dp(1.5f));
            }
        }

        void setColor(int c) {
            color = c;
            bg.setColor(c);
        }

        void setOnClickListener(Runnable r) {
            click = r;
        }

        void setOnLongClickListener(java.util.function.BooleanSupplier r) {
            longClick = r;
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    longFired = repeating = false;
                    bg.setColor(0xFF4B5563);
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    ui.postDelayed(longPress, 420);
                    return true;
                case MotionEvent.ACTION_UP:
                    ui.removeCallbacks(longPress);
                    ui.removeCallbacks(repeater);
                    bg.setColor(color);
                    // 長押しで繰り返したときは、離したときにもう 1 回は打たない
                    if (!longFired && !repeating && click != null) click.run();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    ui.removeCallbacks(longPress);
                    ui.removeCallbacks(repeater);
                    bg.setColor(color);
                    return true;
                default:
                    return true;
            }
        }
    }
}
