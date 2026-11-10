package site.ragdollp.stayawake

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * 他のアプリに切り替えても画面を点けたままにするためのフォアグラウンドサービス。
 *
 * - SCREEN_DIM_WAKE_LOCK を取得(画面が暗転しない)
 * - FGS 化で通知バーに常駐、OS からの kill 耐性を持たせる
 *
 * MainActivity が前面にある時は Activity 側の FLAG_KEEP_SCREEN_ON だけで十分だが、
 * 「他アプリを開いても消えない」を達成するには WakeLock が必要。
 */
class KeepAwakeService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "StayAwake:keep"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { wakeLock?.release() } catch (_: Throwable) {}
        wakeLock = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CH, "画面オン維持",
                NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            })
        }
        val tap = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, KeepAwakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("画面オン維持中")
            .setContentText("タップしてアプリを開く / 下のボタンで停止")
            .setContentIntent(tap)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "停止",
                PendingIntent.getBroadcast(this, 2,
                    Intent(this, StopReceiver::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
    }

    companion object {
        const val CH = "stayawake"
        const val NOTIF_ID = 42
        const val ACTION_STOP = "site.ragdollp.stayawake.STOP"

        fun start(ctx: Context) =
            ctx.startForegroundService(Intent(ctx, KeepAwakeService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, KeepAwakeService::class.java))
    }
}
