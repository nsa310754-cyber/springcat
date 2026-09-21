package site.ragdollp.copytool;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.Toast;

/**
 * どのアプリでも文字を選択すると「springコピー」ボタンを、テキスト入力欄に
 * フォーカスが当たると「springペースト」ボタンを、その場に浮かせて表示する
 * アクセシビリティサービス。
 *
 * ACTION_PROCESS_TEXT（SpringCopyActivity）は選択メニューにその項目を
 * 表示するかどうかをアプリ側の実装に依存するため、対応していないアプリ
 * では出てこない。アクセシビリティサービスはOSレベルでテキスト選択・
 * フォーカスの変化を検知できるため、より広いアプリで動作する。
 *
 * 有効化はAndroidの仕様上、設定画面でユーザーが手動でONにする必要があり
 * アプリ側からは操作できない（MainActivity の「設定を開く」ボタン参照）。
 *
 * 注意: 他アプリの入力欄へ文字を書き戻す処理（springペースト）は
 * AccessibilityNodeInfo#performAction を使うが、これもBinderのトランザクション
 * バッファ上限（実質1MB前後）の影響を受けるため、非常に大きいテキストは
 * 他アプリへの書き戻しに失敗することがある（アプリ内の「spring貼り付け」
 * ボタンはこの制限を受けず25MBまで確実に動く。ClipHelper参照）。
 */
public class SpringCopyAccessibilityService extends AccessibilityService {

    private WindowManager windowManager;
    private View copyOverlayView;
    private View pasteOverlayView;
    private AccessibilityNodeInfo pasteTargetNode;

    @Override
    public void onServiceConnected() {
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            AccessibilityNodeInfo source = event.getSource();
            if (source == null) {
                hideCopyOverlay();
                return;
            }
            try {
                int start = source.getTextSelectionStart();
                int end = source.getTextSelectionEnd();
                CharSequence full = source.getText();
                if (full != null && start >= 0 && end > start && end <= full.length()) {
                    showCopyOverlay(full.subSequence(start, end).toString(), source);
                } else {
                    hideCopyOverlay();
                }
            } finally {
                source.recycle();
            }
        } else if (type == AccessibilityEvent.TYPE_VIEW_FOCUSED
                || type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            AccessibilityNodeInfo source = event.getSource();
            if (source == null) return;
            if (source.isEditable()) {
                showPasteOverlay(source); // 所有権を渡す（source.recycle()はしない）
            } else {
                source.recycle();
                hidePasteOverlay();
            }
        } else if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // 画面遷移したら選択・フォーカスも失われるはずなので一旦隠す
            hideCopyOverlay();
            hidePasteOverlay();
        }
    }

    private void showCopyOverlay(final String selectedText, AccessibilityNodeInfo source) {
        hideCopyOverlay();
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
            hideCopyOverlay();
        });

        Rect bounds = new Rect();
        source.getBoundsInScreen(bounds);
        addOverlay(btn, bounds, -130);
        copyOverlayView = btn;
    }

    private void showPasteOverlay(AccessibilityNodeInfo node) {
        hidePasteOverlay(); // 前回分の pasteTargetNode を recycle
        pasteTargetNode = node;

        Button btn = new Button(this);
        btn.setText("springペースト");
        btn.setAllCaps(false);
        btn.setTextColor(0xFFFFFFFF);
        btn.setBackgroundColor(0xFF3B7CE8);
        btn.setPadding(28, 14, 28, 14);
        btn.setOnClickListener(v -> {
            String text;
            try {
                text = ClipHelper.readClipboardText(SpringCopyAccessibilityService.this);
            } catch (ClipHelper.ClipTooLargeException e) {
                Toast.makeText(SpringCopyAccessibilityService.this,
                        "他アプリへの貼り付けはOSの制限で約1MBまでです。このアプリ内の「spring貼り付け」なら25MBまで貼り付けできます。",
                        Toast.LENGTH_LONG).show();
                return;
            }
            if (text == null) {
                Toast.makeText(SpringCopyAccessibilityService.this, "貼り付けられる内容がありません", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean ok = false;
            if (pasteTargetNode != null) {
                try {
                    Bundle args = new Bundle();
                    args.putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
                    ok = pasteTargetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                } catch (RuntimeException e) {
                    // 転送量がOSの上限(実質1MB前後)を超えた場合など
                    Toast.makeText(SpringCopyAccessibilityService.this,
                            "貼り付けに失敗しました（データが大きすぎる可能性があります）: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                    hidePasteOverlay();
                    return;
                }
            }
            Toast.makeText(SpringCopyAccessibilityService.this,
                    ok ? "springペーストしました" : "貼り付けに失敗しました", Toast.LENGTH_SHORT).show();
            hidePasteOverlay();
        });

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        addOverlay(btn, bounds, -130);
        pasteOverlayView = btn;
    }

    private void addOverlay(View view, Rect anchorBounds, int yOffset) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = Math.max(0, anchorBounds.left);
        params.y = Math.max(0, anchorBounds.top + yOffset);

        try {
            windowManager.addView(view, params);
        } catch (Exception ignored) {
            // ウィンドウを追加できない状況（画面回転直後など）
        }
    }

    private void hideCopyOverlay() {
        removeOverlayView(copyOverlayView);
        copyOverlayView = null;
    }

    private void hidePasteOverlay() {
        removeOverlayView(pasteOverlayView);
        pasteOverlayView = null;
        if (pasteTargetNode != null) {
            pasteTargetNode.recycle();
            pasteTargetNode = null;
        }
    }

    private void removeOverlayView(View view) {
        if (view != null && windowManager != null) {
            try {
                windowManager.removeView(view);
            } catch (Exception ignored) {
                // ビューが既に外れている場合など
            }
        }
    }

    @Override
    public void onInterrupt() {
        hideCopyOverlay();
        hidePasteOverlay();
    }

    @Override
    public void onDestroy() {
        hideCopyOverlay();
        hidePasteOverlay();
        super.onDestroy();
    }
}
