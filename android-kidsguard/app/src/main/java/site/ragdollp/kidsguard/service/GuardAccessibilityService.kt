package site.ragdollp.kidsguard.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import site.ragdollp.kidsguard.data.Prefs
import site.ragdollp.kidsguard.ui.BlockActivity

/**
 * 画面に出てきたアプリの package 名を見張り、ブロックリストに入っていたら
 * ブロック画面を全面に出してホームへ戻す。
 *
 * 使用履歴 API ではなくアクセシビリティを使うのは、こちらの方が検知が速く、
 * バックグラウンドのポーリングを常駐させる必要がないため。
 */
class GuardAccessibilityService : AccessibilityService() {

    private var lastHandled: String? = null
    private var lastHandledAt: Long = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return

        // 自分自身・システムランチャ・設定・自分の BlockActivity は無視
        if (pkg == packageName) return
        if (pkg == "com.android.systemui") return

        val blocked = Prefs.getBlocked(this)
        if (pkg !in blocked) return

        // 連続イベントで多重起動しないよう軽いデバウンス
        val now = System.currentTimeMillis()
        if (pkg == lastHandled && now - lastHandledAt < 1500L) return
        lastHandled = pkg
        lastHandledAt = now

        // ホームへ戻す → ブロック画面を上に重ねる
        performGlobalAction(GLOBAL_ACTION_HOME)
        val i = Intent(this, BlockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(BlockActivity.EXTRA_PKG, pkg)
        }
        startActivity(i)
    }

    override fun onInterrupt() {}
}
