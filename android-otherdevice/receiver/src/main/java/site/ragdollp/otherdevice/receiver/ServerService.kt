package site.ragdollp.otherdevice.receiver

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import java.io.File

/**
 * フォアグラウンドで HTTP サーバを常駐。
 * 保存先は /sdcard/otherdevice/
 */
class ServerService : Service() {
    private var server: HttpServer? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val baseDir = File(Environment.getExternalStorageDirectory(), "otherdevice")
        if (server == null) {
            server = HttpServer(PORT, baseDir) { msg ->
                lastLog = (lastLog + "\n" + msg).takeLast(4000)
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID, buildNotif(msg))
                listener?.invoke(msg)
            }
            server!!.start()
        }
        startForeground(NOTIF_ID, buildNotif("待受中 :$PORT"))
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop(); server = null
        super.onDestroy()
    }

    private fun buildNotif(text: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CH, "受信サーバ",
                NotificationManager.IMPORTANCE_LOW))
        }
        val tap = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("OtherDevice 受信中")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(tap)
            .build()
    }

    companion object {
        const val PORT = 8787
        const val CH = "otherdevice-recv"
        const val NOTIF_ID = 1
        var lastLog: String = ""
        var listener: ((String) -> Unit)? = null

        fun start(ctx: Context) =
            ctx.startForegroundService(Intent(ctx, ServerService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, ServerService::class.java))
    }
}
