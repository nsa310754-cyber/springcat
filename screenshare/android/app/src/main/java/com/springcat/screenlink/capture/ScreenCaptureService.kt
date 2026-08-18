package com.springcat.screenlink.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import androidx.core.app.NotificationCompat
import com.springcat.screenlink.MainActivity
import com.springcat.screenlink.R
import com.springcat.screenlink.net.RelayClient
import java.io.ByteArrayOutputStream

/**
 * Captures this device's own screen via MediaProjection and streams throttled
 * JPEG frames to the relay. View-only: this service never reads input events
 * or accepts remote commands, it only produces outgoing frames.
 *
 * MediaProjection requires an explicit system consent dialog every time
 * capture starts, and Android enforces a persistent, non-hideable
 * notification for the whole capture session - both are left completely
 * intact here on purpose, since bypassing either would turn this into covert
 * spyware instead of a support tool the device's user can see and stop.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "screenlink_capture"
        const val NOTIF_ID = 2001
        const val ACTION_STOP = "com.springcat.screenlink.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val TARGET_FPS = 3
        private const val JPEG_QUALITY = 55
        private const val MAX_DIMENSION = 960
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var lastFrameAt = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        thread = HandlerThread("ScreenCapture").also { it.start() }
        handler = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultData == null || resultCode == 0) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, buildNotification())

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val projection = mpm.getMediaProjection(resultCode, resultData)
        mediaProjection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopCapture()
            }
        }, handler)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(metrics)
        val scale = MAX_DIMENSION.toFloat() / maxOf(metrics.widthPixels, metrics.heightPixels, 1)
        val width = (metrics.widthPixels * minOf(scale, 1f)).toInt().coerceAtLeast(1)
        val height = (metrics.heightPixels * minOf(scale, 1f)).toInt().coerceAtLeast(1)

        val reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ onImageAvailable(it) }, handler)

        virtualDisplay = projection.createVirtualDisplay(
            "ScreenLinkCapture",
            width, height, metrics.densityDpi,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )

        return START_NOT_STICKY
    }

    private fun onImageAvailable(reader: ImageReader) {
        val now = System.currentTimeMillis()
        val minIntervalMs = 1000L / TARGET_FPS
        val image = reader.acquireLatestImage() ?: return
        try {
            if (now - lastFrameAt < minIntervalMs) return
            lastFrameAt = now

            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val paddedBitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
            )
            paddedBitmap.copyPixelsFromBuffer(buffer)
            val bitmap = if (rowPadding == 0) paddedBitmap else
                Bitmap.createBitmap(paddedBitmap, 0, 0, image.width, image.height).also { paddedBitmap.recycle() }

            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            bitmap.recycle()

            RelayClient.sendFrame(out.toByteArray())
        } finally {
            image.close()
        }
    }

    private fun stopCapture() {
        RelayClient.stopShare()
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopCapture()
        thread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("画面を共有しています")
            .setContentText("許可した端末から画面が見られています。タップで停止できます。")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .addAction(0, "共有を停止", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "画面共有中", NotificationManager.IMPORTANCE_HIGH
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
