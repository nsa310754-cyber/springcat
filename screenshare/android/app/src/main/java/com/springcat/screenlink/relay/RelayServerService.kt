package com.springcat.screenlink.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.springcat.screenlink.MainActivity
import com.springcat.screenlink.R
import java.net.NetworkInterface
import java.util.Collections

/** Runs the embedded relay server as long as this device is "the server". */
class RelayServerService : Service() {

    companion object {
        const val CHANNEL_ID = "screenlink_server"
        const val NOTIF_ID = 3001
        const val ACTION_STOP = "com.springcat.screenlink.action.STOP_SERVER"
        const val EXTRA_PORT = "port"
        const val DEFAULT_PORT = 8787

        @Volatile
        var isRunning = false
            private set

        fun localAddresses(): List<String> {
            return try {
                Collections.list(NetworkInterface.getNetworkInterfaces())
                    .filter { !it.isLoopback && it.isUp }
                    .flatMap { Collections.list(it.inetAddresses) }
                    .filter { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
                    .mapNotNull { it.hostAddress }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private var server: EmbeddedRelayServer? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer()
            return START_NOT_STICKY
        }
        val port = intent?.getIntExtra(EXTRA_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
        startForeground(NOTIF_ID, buildNotification(port))
        if (server == null) {
            server = EmbeddedRelayServer(port).also { it.start() }
            isRunning = true
        }
        return START_STICKY
    }

    private fun stopServer() {
        server?.stop(1000)
        server = null
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(port: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, RelayServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val addr = localAddresses().firstOrNull() ?: "?"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("中継サーバー稼働中")
            .setContentText("$addr:$port で待機中。他の端末はこのアドレスに接続できます。")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .addAction(0, "サーバーを停止", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "中継サーバー", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
