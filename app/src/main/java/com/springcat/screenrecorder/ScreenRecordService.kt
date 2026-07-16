package com.springcat.screenrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service that captures the screen via [MediaProjection] and writes it to an MP4 file.
 * Recording is started by an intent from [MainActivity] and can be stopped either from the app or
 * from the "Stop" action on the ongoing notification.
 */
class ScreenRecordService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var mediaRecorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null

    // Where the finished recording lives, so we can un-mark it as pending / notify the user.
    private var outputUri: Uri? = null
    private var outputFile: File? = null
    private var pfd: ParcelFileDescriptor? = null

    // Stops the projection cleanly if the system tears it down out from under us.
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopRecording()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = intentDataCompat(intent)
                if (data != null) {
                    startRecording(resultCode, data)
                } else {
                    stopSelf()
                }
            }
            ACTION_STOP -> stopRecording()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(resultCode: Int, data: Intent) {
        if (isRecording) return

        // A foreground notification must be posted before the media-projection session begins.
        startForegroundWithNotification()

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        if (mediaProjection == null) {
            toast(getString(R.string.error_generic))
            cleanup()
            stopSelf()
            return
        }
        mediaProjection?.registerCallback(projectionCallback, null)

        val metrics = screenMetrics()
        try {
            prepareRecorder(metrics)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare recorder", e)
            toast(getString(R.string.error_generic))
            cleanup()
            stopSelf()
            return
        }

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "SpringCatDisplay",
            metrics.width, metrics.height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder?.surface, null, null
        )

        try {
            mediaRecorder?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recorder", e)
            toast(getString(R.string.error_generic))
            cleanup()
            stopSelf()
            return
        }

        isRecording = true
        broadcastState()
        toast(getString(R.string.status_recording))
    }

    private fun prepareRecorder(metrics: ScreenMetrics) {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

        setupOutput(recorder)

        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        recorder.setVideoSize(metrics.width, metrics.height)
        recorder.setVideoFrameRate(30)
        recorder.setVideoEncodingBitRate(computeBitrate(metrics.width, metrics.height))
        recorder.prepare()
        mediaRecorder = recorder
    }

    /** Routes the recorder output to MediaStore (API 29+) or the public Movies dir (older). */
    private fun setupOutput(recorder: MediaRecorder) {
        val name = "SpringCat_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SpringCat")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            ) ?: throw IllegalStateException("MediaStore insert returned null")
            outputUri = uri
            val descriptor = contentResolver.openFileDescriptor(uri, "w")
                ?: throw IllegalStateException("Could not open output descriptor")
            pfd = descriptor
            recorder.setOutputFile(descriptor.fileDescriptor)
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "SpringCat"
            )
            if (!dir.exists()) dir.mkdirs()
            val target = if (dir.canWrite()) dir else File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "").apply { mkdirs() }
            val file = File(target, name)
            outputFile = file
            @Suppress("DEPRECATION")
            recorder.setOutputFile(file.absolutePath)
        }
    }

    private fun stopRecording() {
        if (!isRecording) {
            cleanup()
            stopForegroundCompat()
            stopSelf()
            return
        }
        isRecording = false

        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Recorder stop failed (likely too short)", e)
        }

        finalizeOutput()
        cleanup()
        broadcastState()
        toast(getString(R.string.status_saved))
        stopForegroundCompat()
        stopSelf()
    }

    private fun finalizeOutput() {
        runCatching { pfd?.close() }
        pfd = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            outputUri?.let { uri ->
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                runCatching { contentResolver.update(uri, values, null, null) }
            }
        } else {
            // Nudge the media scanner so the file shows up in the gallery.
            outputFile?.let { file ->
                runCatching {
                    sendBroadcast(
                        Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file))
                    )
                }
            }
        }
    }

    private fun cleanup() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { mediaRecorder?.reset(); mediaRecorder?.release() }
        mediaRecorder = null
        runCatching { mediaProjection?.unregisterCallback(projectionCallback) }
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
    }

    // --- Notification -------------------------------------------------------

    private fun startForegroundWithNotification() {
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingFlags()
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenRecordService::class.java).apply { action = ACTION_STOP },
            pendingFlags()
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_record)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_stop, getString(R.string.stop_recording), stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    // --- Helpers ------------------------------------------------------------

    private data class ScreenMetrics(val width: Int, val height: Int, val densityDpi: Int)

    private fun screenMetrics(): ScreenMetrics {
        val metrics = DisplayMetrics()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        // Encoders require even dimensions; round down to be safe.
        val w = metrics.widthPixels and 1.inv()
        val h = metrics.heightPixels and 1.inv()
        return ScreenMetrics(w, h, metrics.densityDpi)
    }

    private fun computeBitrate(width: Int, height: Int): Int {
        // ~0.2 bits per pixel per frame at 30fps, clamped to a sane range.
        val bitrate = (width.toLong() * height * 30 * 0.2).toLong()
        return bitrate.coerceIn(2_000_000L, 20_000_000L).toInt()
    }

    private fun pendingFlags(): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return flags
    }

    private fun broadcastState() {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    @Suppress("DEPRECATION")
    private fun intentDataCompat(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) {
            isRecording = false
            cleanup()
            broadcastState()
        }
    }

    companion object {
        private const val TAG = "ScreenRecordService"
        const val ACTION_START = "com.springcat.screenrecorder.START"
        const val ACTION_STOP = "com.springcat.screenrecorder.STOP"
        const val ACTION_STATE_CHANGED = "com.springcat.screenrecorder.STATE_CHANGED"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL_ID = "screen_record_channel"
        private const val NOTIFICATION_ID = 1001

        // Read by MainActivity to reflect the current recording state in the UI.
        @Volatile
        var isRecording: Boolean = false
            private set
    }
}
