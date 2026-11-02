package site.ragdollp.precisebattery

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import site.ragdollp.precisebattery.Prefs.useRoot

/**
 * フォアグラウンドで常駐し、
 *  - 画面右上(ユーザー調整可能)の浮動オーバーレイに 0..1000 の数値を描画
 *  - 通知領域にも同じ数値を表示
 * を 5 秒おきに更新する。
 *
 * ドラッグで位置を動かせる(位置は Prefs に保存)。
 */
class BatteryService : Service() {

    private lateinit var wm: WindowManager
    private var overlay: TextView? = null
    private lateinit var layoutParams: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())

    private val tickRunnable = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 5_000L)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refresh() }
    }

    override fun onCreate() {
        super.onCreate()
        BatteryReader.allowRoot = this.useRoot
        startForeground(NOTIF_ID, buildNotification("…"))
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showOverlay()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        handler.post(tickRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        BatteryReader.allowRoot = this.useRoot
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        runCatching { unregisterReceiver(batteryReceiver) }
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showOverlay() {
        val tv = TextView(this).apply {
            text = "---"
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(28, 14, 28, 14)
            background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(0xCC000000.toInt())
                setStroke(2, 0x66FFFFFF)
            }
        }

        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE

        val (px, py) = Prefs.pos(this)
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = px; y = py
        }

        var downX = 0; var downY = 0
        var touchX = 0f; var touchY = 0f
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = layoutParams.x; downY = layoutParams.y
                    touchX = e.rawX; touchY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = (downX + (e.rawX - touchX)).toInt()
                    layoutParams.y = (downY + (e.rawY - touchY)).toInt()
                    wm.updateViewLayout(tv, layoutParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    Prefs.setPos(this, layoutParams.x, layoutParams.y); true
                }
                else -> false
            }
        }

        wm.addView(tv, layoutParams)
        overlay = tv
    }

    private fun refresh() {
        val s = BatteryReader.read(this)
        val label = buildString {
            append(s.milliPercent)
            append(if (s.charging) " ⚡" else "")
        }
        overlay?.text = label

        val detail = buildString {
            append("0〜1000 : ${s.milliPercent}")
            s.voltageMv?.let { append("  ${it}mV") }
            s.temperatureC?.let { append("  ${"%.1f".format(it)}°C") }
            append("  [${s.source}]")
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(detail))
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CH_ID, getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CH_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(tap)
        return builder.build()
    }

    companion object {
        const val CH_ID = "precisebattery"
        const val NOTIF_ID = 1
    }
}
