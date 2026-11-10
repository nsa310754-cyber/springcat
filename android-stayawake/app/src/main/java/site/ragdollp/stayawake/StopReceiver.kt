package site.ragdollp.stayawake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 通知アクション「停止」から呼ばれてサービスを止めるだけの受け皿。 */
class StopReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        KeepAwakeService.stop(ctx)
    }
}
