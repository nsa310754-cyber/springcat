package com.springcat.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Foreground service that samples device metrics once per second and keeps running when the
 * Activity is closed or the screen is off. It publishes each sample to [MonitorState] and shows
 * a persistent notification with the latest CPU / memory figures.
 *
 * A partial [PowerManager.WakeLock] keeps the CPU awake so sampling continues with the screen
 * off. This increases battery use, so the notification offers a one-tap stop action.
 */
class MonitorService : Service() {

    private lateinit var stats: SystemStats
    private var samplerThread: HandlerThread? = null
    private var samplerHandler: Handler? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val ticker = object : Runnable {
        override fun run() {
            val snapshot = stats.sample()
            MonitorState.update(snapshot)
            notificationManager().notify(NOTIF_ID, buildNotification(snapshot))
            samplerHandler?.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        stats = SystemStats(applicationContext)
        createChannel()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Promote to a foreground service (typed as special-use on Android 14+).
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(MonitorState.latest), type)

        if (wakeLock?.isHeld != true) {
            wakeLock?.acquire()
        }
        MonitorState.running = true

        if (samplerThread == null) {
            val thread = HandlerThread("springcat-service").also { it.start() }
            samplerThread = thread
            samplerHandler = Handler(thread.looper).also { it.post(ticker) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        samplerHandler?.removeCallbacks(ticker)
        samplerThread?.quitSafely()
        samplerThread = null
        samplerHandler = null
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        MonitorState.running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "システム計測",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "バックグラウンドでの CPU / メモリ計測の通知"
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun buildNotification(snapshot: Snapshot?): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val text = if (snapshot == null) {
            "計測を開始しています…"
        } else {
            val cpu = snapshot.cpuPercent?.let { String.format("%.0f%%", it) } ?: "–"
            val mem = String.format("%.0f%%", snapshot.memPercent)
            val suffix = when (snapshot.cpuSource) {
                CpuSource.ROOT -> " (root)"
                CpuSource.PROCESS -> " (アプリ)"
                else -> ""
            }
            "CPU $cpu$suffix · メモリ $mem"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("springcat モニター")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_monitor)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "停止", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val CHANNEL_ID = "monitor"
        private const val NOTIF_ID = 1001
        private const val INTERVAL_MS = 1000L
        private const val WAKELOCK_TAG = "springcat:monitor"
        const val ACTION_STOP = "com.springcat.monitor.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, MonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, MonitorService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
