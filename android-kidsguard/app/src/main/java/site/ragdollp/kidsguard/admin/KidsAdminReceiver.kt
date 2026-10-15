package site.ragdollp.kidsguard.admin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context

/**
 * 保護者がうっかり/子どもが勝手にアンインストールするのを防ぐためだけに
 * デバイス管理者を使う。ワイプや画面ロック変更などの破壊的ポリシーは一切要求しない
 * (device_admin.xml で force-lock のみ)。
 */
class KidsAdminReceiver : DeviceAdminReceiver() {
    companion object {
        fun component(ctx: Context): ComponentName =
            ComponentName(ctx, KidsAdminReceiver::class.java)
    }
}
