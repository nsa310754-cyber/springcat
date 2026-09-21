package site.ragdollp.copytool;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.Toast;

/**
 * どのアプリでも文字を選択すると「springコピー」ボタンを選択範囲の近くに
 * 浮かせて表示するアクセシビリティサービス。
 *
 * ACTION_PROCESS_TEXT（SpringCopyActivity）は選択メニューにその項目を
 * 表示するかどうかをアプリ側の実装に依存するため、対応していないアプリ
 * では出てこない。アクセシビリティサービスはOSレベルでテキスト選択の
 * 変化を検知できるため、より広いアプリで動作する。
 *
 * 有効化はAndroidの仕様上、設定画面でユーザーが手動でONにする必要があり
 * アプリ側からは操作できない（MainActivity の「設定を開く」ボタン参照）。
 */
public class SpringCopyAccessibilityService extends AccessibilityService {

    private WindowManager windowManager;
    private View overlayView;

    @Override
    public void onServiceConnected() {
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            AccessibilityNodeInfo source = event.getSource();
            if (source == null) {
                hideOverlay();
                return;
            }
            try {
                int start = source.getTextSelectionStart();
                int end = source.getTextSelectionEnd();
                CharSequence full = source.getText();
                if (full != null && start >= 0 && end > start && end <= full.length()) {
                    showOverlay(full.subSequence(start, end).toString(), source);
                } else {
                    hideOverlay();
                }
            } finally {
                source.recycle();
            }
        } else if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // 画面遷移したら選択も失われるはずなので一旦隠す
            hideOverlay();
        }
    }

    private void showOverlay(final String selectedText, AccessibilityNodeInfo source) {
        hideOverlay();
        if (selectedText.isEmpty()) return;

        Button btn = new Button(this);
        btn.setText("springコピー");
        btn.setAllCaps(false);
        btn.setTextColor(0xFFFFFFFF);
        btn.setBackgroundColor(0xFFE8743B);
        btn.setPadding(28, 14, 28, 14);
        btn.setOnClickListener(v -> {
            String result = ClipHelper.copyLargeText(SpringCopyAccessibilityService.this, selectedText);
            Toast.makeText(SpringCopyAccessibilityService.this, result, Toast.LENGTH_SHORT).show();
            hideOverlay();
        });

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;

        Rect bounds = new Rect();
        source.getBoundsInScreen(bounds);
        params.x = Math.max(0, bounds.left);
        params.y = Math.max(0, bounds.top - 130);

        overlayView = btn;
        try {
            windowManager.addView(overlayView, params);
        } catch (Exception e) {
            overlayView = null;
        }
    }

    private void hideOverlay() {
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) {
                // ビューが既に外れている場合など
            }
        }
        overlayView = null;
    }

    @Override
    public void onInterrupt() {
        hideOverlay();
    }

    @Override
    public void onDestroy() {
        hideOverlay();
        super.onDestroy();
    }
}
