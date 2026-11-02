package site.ragdollp.precisebattery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import site.ragdollp.precisebattery.Prefs.autoStart

/** 「起動時に自動開始」が ON かつ オーバーレイ権限がある時だけ常駐サービスを立ち上げる。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!context.autoStart) return
        if (!Settings.canDrawOverlays(context)) return
        context.startForegroundService(Intent(context, BatteryService::class.java))
    }
}
