package site.ragdollp.springkey.receiver;

import android.inputmethodservice.InputMethodService;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.widget.TextView;

/**
 * SpringKey 受信用の IME。
 *
 * この端末で「SpringKey（リモート入力）」をキーボードとして選び、
 * 何かのテキスト欄をタップしてフォーカスすると、送信側から来た文字を
 * そのテキスト欄へそのまま入力できる（任意のアプリに入力可能）。
 *
 * 画面（キー配列）は送信側に表示するので、こちら側の IME は
 * 「接続中」の小さなバーだけを出す最小構成。
 */
public class SpringKeyIme extends InputMethodService {

    private static SpringKeyIme instance;
    public static SpringKeyIme get() { return instance; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public View onCreateInputView() {
        TextView bar = new TextView(this);
        bar.setText("🐈 SpringKey：送信側のキーボードから入力できます");
        bar.setPadding(36, 28, 36, 28);
        bar.setTextSize(14);
        bar.setTextColor(0xFFFFFFFF);
        bar.setBackgroundColor(0xFF1C2229);
        return bar;
    }

    /** 文字列をフォーカス中のテキスト欄へ入力。成功したら true。 */
    public boolean commit(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return false;
        ic.commitText(s, 1);
        return true;
    }

    /** 特殊キー。成功したら true。 */
    public boolean sendKey(int keyCode) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return false;
        long t = System.currentTimeMillis();
        ic.sendKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0));
        ic.sendKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0));
        return true;
    }

    /** バックスペース（前方向の削除）。 */
    public boolean backspace() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return false;
        CharSequence sel = ic.getSelectedText(0);
        if (sel != null && sel.length() > 0) {
            ic.commitText("", 1);
        } else {
            ic.deleteSurroundingText(1, 0);
        }
        return true;
    }

    /** 修飾キー付きのキー送信（Ctrl+C など）。成功したら true。 */
    public boolean sendKeyWithMeta(int keyCode, int meta) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return false;
        long t = System.currentTimeMillis();
        ic.sendKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0, meta));
        ic.sendKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0, meta));
        return true;
    }
}
