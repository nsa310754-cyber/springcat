package site.ragdollp.springkey.receiver;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * SpringKey 受信のアクセシビリティサービス。
 *
 * ・画面上に「マウスカーソル」をオーバーレイ表示（TYPE_ACCESSIBILITY_OVERLAY なので
 *   「他アプリの上に表示」権限は不要）。送信側のトラックパッドで動かす。
 * ・タップ／長押し／スクロールは dispatchGesture でカーソル位置に実行。
 * ・戻る／ホーム／履歴はグローバル操作で実行。
 * ・IME が使えない場面向けに、フォーカス中テキスト欄への文字入力も代替提供。
 */
public class SpringKeyAccessibilityService extends AccessibilityService {

    private static SpringKeyAccessibilityService instance;
    public static SpringKeyAccessibilityService get() { return instance; }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private View cursor;
    private WindowManager.LayoutParams lp;
    private int screenW = 1080, screenH = 1920;
    private float cx = 540, cy = 960;
    private int curSize = 46;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        measureScreen();
        ui.post(this::addCursor);
    }

    private void measureScreen() {
        try {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;
            curSize = Math.max(36, Math.min(64, screenW / 24));
        } catch (Exception ignored) {
        }
        cx = screenW / 2f;
        cy = screenH / 2f;
    }

    private void addCursor() {
        if (cursor != null || wm == null) return;
        View v = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0x66E8743B);
        g.setStroke(Math.max(3, curSize / 12), 0xFFE8743B);
        v.setBackground(g);
        cursor = v;

        lp = new WindowManager.LayoutParams(
                curSize, curSize,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = (int) cx;
        lp.y = (int) cy;
        try {
            wm.addView(cursor, lp);
        } catch (Exception e) {
            cursor = null;
        }
    }

    public void setCursorVisible(final boolean vis) {
        ui.post(() -> {
            if (cursor != null) cursor.setVisibility(vis ? View.VISIBLE : View.GONE);
        });
    }

    /** カーソル相対移動。 */
    public void moveCursor(final float dx, final float dy) {
        ui.post(() -> {
            cx = clamp(cx + dx, 0, screenW - 1);
            cy = clamp(cy + dy, 0, screenH - 1);
            if (cursor != null && lp != null) {
                lp.x = (int) (cx - curSize / 2f);
                lp.y = (int) (cy - curSize / 2f);
                try { wm.updateViewLayout(cursor, lp); } catch (Exception ignored) {}
            }
        });
    }

    /** カーソル位置でクリック。button: left/right/middle */
    public void click(final String button) {
        ui.post(() -> {
            long dur = "right".equals(button) ? 600 : 40; // 右クリック=長押し
            tapAt(cx, cy, dur);
        });
    }

    private void tapAt(float x, float y, long durationMs) {
        try {
            Path p = new Path();
            p.moveTo(clamp(x, 1, screenW - 1), clamp(y, 1, screenH - 1));
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(new GestureDescription.StrokeDescription(p, 0, durationMs));
            dispatchGesture(b.build(), null, null);
        } catch (Exception ignored) {
        }
    }

    /** カーソル位置でスクロール（縦横のスワイプ）。 */
    public void scroll(final float dx, final float dy) {
        ui.post(() -> {
            try {
                float x1 = clamp(cx, 2, screenW - 2), y1 = clamp(cy, 2, screenH - 2);
                float x2 = clamp(cx + dx * 2.2f, 2, screenW - 2);
                float y2 = clamp(cy + dy * 2.2f, 2, screenH - 2);
                Path p = new Path();
                p.moveTo(x1, y1);
                p.lineTo(x2, y2);
                GestureDescription.Builder b = new GestureDescription.Builder();
                b.addStroke(new GestureDescription.StrokeDescription(p, 0, 120));
                dispatchGesture(b.build(), null, null);
            } catch (Exception ignored) {
            }
        });
    }

    /** 戻る／ホーム／履歴。 */
    public void nav(String k) {
        if ("back".equals(k)) performGlobalAction(GLOBAL_ACTION_BACK);
        else if ("home".equals(k)) performGlobalAction(GLOBAL_ACTION_HOME);
        else if ("recents".equals(k)) performGlobalAction(GLOBAL_ACTION_RECENTS);
    }

    /** IME が使えない時の代替入力：フォーカス中のテキスト欄に追記。 */
    public boolean typeIntoFocused(String s) {
        AccessibilityNodeInfo node = findFocusedEditable();
        if (node == null) return false;
        CharSequence cur = node.getText();
        String base = cur == null ? "" : cur.toString();
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, base + s);
        boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        node.recycle();
        return ok;
    }

    /** 代替バックスペース。 */
    public boolean backspaceFocused() {
        AccessibilityNodeInfo node = findFocusedEditable();
        if (node == null) return false;
        CharSequence cur = node.getText();
        String base = cur == null ? "" : cur.toString();
        if (base.length() > 0) base = base.substring(0, base.length() - 1);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, base);
        boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        node.recycle();
        return ok;
    }

    private AccessibilityNodeInfo findFocusedEditable() {
        try {
            AccessibilityNodeInfo n = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (n != null && n.isEditable()) return n;
            if (n != null) n.recycle();
        } catch (Exception ignored) {
        }
        return null;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { /* 使用しない */ }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        if (cursor != null && wm != null) {
            try { wm.removeView(cursor); } catch (Exception ignored) {}
            cursor = null;
        }
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
