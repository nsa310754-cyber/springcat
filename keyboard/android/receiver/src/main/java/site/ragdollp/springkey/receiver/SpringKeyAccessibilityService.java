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

    /**
     * カーソル位置でスクロール。dy は ±1 前後のティック（送信側で間引き済み）。
     * まずカーソル下のスクロール可能なノードにスクロール操作を試み、
     * ダメならスワイプのジェスチャで代替する。
     */
    public void scroll(final float dx, final float dy) {
        ui.post(() -> {
            // 1) スクロール可能なノードを直接操作（リスト/WebView などで確実）
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    AccessibilityNodeInfo sc = findScrollableAt(root, cx, cy);
                    if (sc != null) {
                        boolean fwd = (Math.abs(dy) >= Math.abs(dx)) ? dy > 0 : dx > 0;
                        int action = fwd ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
                        boolean ok = sc.performAction(action);
                        sc.recycle();
                        root.recycle();
                        if (ok) return;
                    } else {
                        root.recycle();
                    }
                }
            } catch (Exception ignored) {
            }
            // 2) 代替：スワイプ（同時に複数実行して打ち消し合わないよう直列化）
            swipeScroll(dx, dy);
        });
    }

    private volatile boolean gestureBusy = false;

    private void swipeScroll(float dx, float dy) {
        if (gestureBusy) return;
        try {
            float dist = Math.max(screenW, screenH) * 0.32f;
            // 下へスクロール（dy>0）＝指を上へ動かす
            float sx = clamp(cx, 4, screenW - 4);
            float sy = clamp(cy + (dy > 0 ? dist / 2 : -dist / 2), 4, screenH - 4);
            float ex = clamp(cx + (Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? -dist : dist) : 0), 4, screenW - 4);
            float ey = clamp(cy + (Math.abs(dy) >= Math.abs(dx) ? (dy > 0 ? -dist / 2 : dist / 2) : 0), 4, screenH - 4);
            Path p = new Path();
            p.moveTo(sx, sy);
            p.lineTo(ex, ey);
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(new GestureDescription.StrokeDescription(p, 0, 160));
            gestureBusy = true;
            dispatchGesture(b.build(), new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) { gestureBusy = false; }
                @Override public void onCancelled(GestureDescription g) { gestureBusy = false; }
            }, ui);
        } catch (Exception e) {
            gestureBusy = false;
        }
    }

    /** (x,y) を含む、スクロール可能なノードを探す（無ければ最初のスクロール可能ノード）。 */
    private AccessibilityNodeInfo findScrollableAt(AccessibilityNodeInfo root, float x, float y) {
        if (root == null) return null;
        AccessibilityNodeInfo anyScrollable = null;
        java.util.ArrayDeque<AccessibilityNodeInfo> q = new java.util.ArrayDeque<>();
        q.add(root);
        android.graphics.Rect r = new android.graphics.Rect();
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            if (n.isScrollable()) {
                n.getBoundsInScreen(r);
                if (r.contains((int) x, (int) y)) {
                    return AccessibilityNodeInfo.obtain(n);
                }
                if (anyScrollable == null) anyScrollable = AccessibilityNodeInfo.obtain(n);
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return anyScrollable;
    }

    /** 戻る／ホーム／履歴。 */
    public void nav(String k) {
        if ("back".equals(k)) performGlobalAction(GLOBAL_ACTION_BACK);
        else if ("home".equals(k)) performGlobalAction(GLOBAL_ACTION_HOME);
        else if ("recents".equals(k)) performGlobalAction(GLOBAL_ACTION_RECENTS);
    }

    /** IME が使えない時の代替入力：フォーカス中のテキスト欄のカーソル位置へ挿入。 */
    public boolean typeIntoFocused(String s) {
        AccessibilityNodeInfo node = findFocusedEditable();
        if (node == null) return false;
        String base = node.getText() == null ? "" : node.getText().toString();
        int st = node.getTextSelectionStart(), en = node.getTextSelectionEnd();
        if (st < 0 || en < 0 || st > base.length() || en > base.length()) { st = base.length(); en = base.length(); }
        int lo = Math.min(st, en), hi = Math.max(st, en);
        String out = base.substring(0, lo) + s + base.substring(hi);
        boolean ok = setText(node, out, lo + s.length());
        node.recycle();
        return ok;
    }

    /** 代替バックスペース（カーソル直前の1文字、選択があればその範囲を削除）。 */
    public boolean backspaceFocused() {
        AccessibilityNodeInfo node = findFocusedEditable();
        if (node == null) return false;
        String base = node.getText() == null ? "" : node.getText().toString();
        int st = node.getTextSelectionStart(), en = node.getTextSelectionEnd();
        if (st < 0 || en < 0 || st > base.length() || en > base.length()) { st = base.length(); en = base.length(); }
        int lo = Math.min(st, en), hi = Math.max(st, en);
        String out; int caret;
        if (lo != hi) { out = base.substring(0, lo) + base.substring(hi); caret = lo; }
        else if (lo > 0) { out = base.substring(0, lo - 1) + base.substring(lo); caret = lo - 1; }
        else { node.recycle(); return true; }
        boolean ok = setText(node, out, caret);
        node.recycle();
        return ok;
    }

    private boolean setText(AccessibilityNodeInfo node, String text, int caret) {
        Bundle a = new Bundle();
        a.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, a);
        try {
            Bundle sel = new Bundle();
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret);
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret);
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
        } catch (Exception ignored) {
        }
        return ok;
    }

    private AccessibilityNodeInfo findFocusedEditable() {
        try {
            AccessibilityNodeInfo n = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (n != null && n.isEditable()) return n;
            if (n != null) n.recycle();
        } catch (Exception ignored) {
        }
        // フォールバック：アクティブウィンドウを走査してフォーカス中の編集欄を探す
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                AccessibilityNodeInfo e = searchEditable(root, true);
                if (e == null) e = searchEditable(root, false);
                root.recycle();
                return e;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private AccessibilityNodeInfo searchEditable(AccessibilityNodeInfo root, boolean focusedOnly) {
        if (root == null) return null;
        java.util.ArrayDeque<AccessibilityNodeInfo> q = new java.util.ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            if (n.isEditable() && (!focusedOnly || n.isFocused())) {
                return AccessibilityNodeInfo.obtain(n);
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
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
