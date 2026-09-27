package site.ragdollp.soundtap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import androidx.core.app.ServiceCompat
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * 音を監視し、しきい値を超えた瞬間に TapService へタップを送るフォアグラウンドサービス。
 *
 * 精度のための工夫:
 * - 16bit PCM を端末ネイティブのサンプルレートで、小さなブロック (既定 64 フレーム ≒ 1.3ms) ずつ読む
 * - 音声スレッドは THREAD_PRIORITY_URGENT_AUDIO。判定はサンプル単位のピーク比較のみで軽量
 * - 検出した瞬間、音声スレッドから直接 dispatchGesture する (スレッド切り替えなし)
 * - AudioRecord.getTimestamp で「そのサンプルが実際に鳴った時刻」を推定し、
 *   遅延タップはその時刻を基準にスピン待ちで合わせる (入力遅延を差し引いて正確に)
 */
class DetectorService : Service() {

    companion object {
        const val ACTION_START = "site.ragdollp.soundtap.START"
        const val ACTION_STOP = "site.ragdollp.soundtap.STOP"
        const val ACTION_TOGGLE = "site.ragdollp.soundtap.TOGGLE"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "detector"
        private const val NOTIF_ID = 1

        @Volatile var instance: DetectorService? = null

        fun start(ctx: Context, projectionCode: Int = 0, projectionData: Intent? = null) {
            val i = Intent(ctx, DetectorService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_CODE, projectionCode)
            if (projectionData != null) i.putExtra(EXTRA_DATA, projectionData)
            ctx.startForegroundService(i)
        }
    }

    private var audioThread: Thread? = null
    @Volatile private var stopFlag = false
    private var projection: MediaProjection? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var scheduler: HandlerThread? = null
    private var schedHandler: Handler? = null
    private val main = Handler(Looper.getMainLooper())
    private val projectionCallback = object : MediaProjection.Callback() {
        // ユーザーが「共有を停止」したとき
        override fun onStop() { if (audioThread != null) stopDetection() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Config.load(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "音の監視", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopDetection(); return START_NOT_STICKY }
            ACTION_TOGGLE -> { Engine.setArmed(!Engine.armed.value); return START_NOT_STICKY }
            ACTION_START -> {}
            else -> { stopSelf(); return START_NOT_STICKY }
        }

        val internal = Config.source == Config.SOURCE_INTERNAL && Build.VERSION.SDK_INT >= 29
        val type = if (internal) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        } catch (e: Exception) {
            Engine.message.value = "監視を開始できませんでした: ${e.message}"
            stopSelf()
            return START_NOT_STICKY
        }
        if (audioThread != null) return START_NOT_STICKY

        if (internal) {
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
            }
            val code = intent.getIntExtra(EXTRA_CODE, 0)
            if (data == null) {
                Engine.message.value = "画面キャプチャの許可が必要です"
                stopDetection(); return START_NOT_STICKY
            }
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projection = try { mpm.getMediaProjection(code, data) } catch (e: Exception) { null }
            if (projection == null) {
                Engine.message.value = "端末内の音を取得できませんでした"
                stopDetection(); return START_NOT_STICKY
            }
            projection?.registerCallback(projectionCallback, main)
        }

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SoundTap:detector")
            .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        scheduler = HandlerThread("tap-scheduler", Process.THREAD_PRIORITY_URGENT_AUDIO).also {
            it.start(); schedHandler = Handler(it.looper)
        }

        stopFlag = false
        Engine.message.value = null
        Engine.setArmed(true)
        Engine.onRunningChanged(true)
        audioThread = Thread({ runLoop() }, "soundtap-audio").also { it.start() }
        return START_NOT_STICKY
    }

    fun stopDetection() {
        stopFlag = true
        audioThread?.let { t -> if (t !== Thread.currentThread()) t.join(500) }
        audioThread = null
        schedHandler?.removeCallbacksAndMessages(null)
        scheduler?.quitSafely()
        scheduler = null
        schedHandler = null
        projection?.let {
            it.unregisterCallback(projectionCallback)
            it.stop()
        }
        projection = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        Engine.onRunningChanged(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (audioThread != null) stopDetection()
        instance = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 通知

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val toggle = PendingIntent.getService(
            this, 1, Intent(this, DetectorService::class.java).setAction(ACTION_TOGGLE), flags
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, DetectorService::class.java).setAction(ACTION_STOP), flags
        )
        val armed = Engine.armed.value
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (armed) "音を監視中 — 鳴ったら即タップ" else "一時停止中")
            .setContentText("反応 ${Engine.triggerCount.value} 回")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, if (armed) "一時停止" else "再開", toggle).build())
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .build()
    }

    fun updateNotification() {
        if (audioThread == null) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    // ---------------------------------------------------------------- 音声

    private fun nativeSampleRate(): Int {
        val am = getSystemService(AudioManager::class.java)
        return am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
            ?.takeIf { it in 8000..192000 } ?: 48000
    }

    private fun createRecord(sr: Int, block: Int): AudioRecord? {
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sr)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufBytes = max(minBuf * 2, block * 2 * 8)
        return try {
            val proj = projection
            val b = AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(bufBytes)
            if (proj != null && Build.VERSION.SDK_INT >= 29) {
                val cfg = AudioPlaybackCaptureConfiguration.Builder(proj)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                b.setAudioPlaybackCaptureConfig(cfg)
            } else {
                // UNPROCESSED が使えればノイズ抑制/AGC を通らない生の音 (遅延も小さい)
                val am = getSystemService(AudioManager::class.java)
                val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
                b.setAudioSource(
                    if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED
                    else MediaRecorder.AudioSource.VOICE_RECOGNITION
                )
            }
            b.build().takeIf { it.state == AudioRecord.STATE_INITIALIZED }
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun runLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val sr = nativeSampleRate()
        val block = Config.blockFrames.coerceIn(16, 2048)
        val rec = createRecord(sr, block)
        if (rec == null) {
            Engine.message.value = "録音を開始できませんでした (マイク権限・他アプリの使用状況を確認)"
            main.post { stopDetection() }
            return
        }
        val buf = ShortArray(block)
        val ts = AudioTimestamp()
        val frameNs = 1_000_000_000.0 / sr

        var total = 0L               // これまでに読んだフレーム数
        var cooldownUntil = 0L       // このフレームまでは反応しない
        var hpPrevX = 0f
        var hpPrevY = 0f
        var floorPow = 1e-6          // 周囲音のパワー (ノイズ追従用)
        var peakHold = -120f

        try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Engine.message.value = "録音を開始できませんでした (他のアプリがマイクを使用中の可能性)"
                main.post { stopDetection() }
                return
            }
            while (!stopFlag) {
                val n = rec.read(buf, 0, block, AudioRecord.READ_BLOCKING)
                if (n <= 0) {
                    if (n < 0) {
                        Engine.message.value = "録音エラー ($n)"
                        main.post { stopDetection() }
                        break
                    }
                    continue
                }

                val hp = Config.highPassHz
                val a = if (hp > 0f) (1.0 / (1.0 + 2.0 * Math.PI * hp / sr)).toFloat() else 0f
                val thrDb = if (Config.adaptive) {
                    max(Config.thresholdDb, (10.0 * log10(floorPow + 1e-12)).toFloat() + Config.riseDb)
                } else Config.thresholdDb
                Engine.effectiveThresholdDb = thrDb
                val thrLin = 10.0.pow(thrDb / 20.0).toFloat()
                val canFire = Engine.armed.value

                var peak = 0f
                var sumSq = 0.0
                var hit = -1
                for (i in 0 until n) {
                    var x = buf[i] / 32768f
                    if (hp > 0f) {
                        val y = a * (hpPrevY + x - hpPrevX)
                        hpPrevX = x; hpPrevY = y; x = y
                    }
                    val ax = abs(x)
                    if (ax > peak) peak = ax
                    sumSq += (x * x).toDouble()
                    if (hit < 0 && canFire && ax >= thrLin && total + i >= cooldownUntil) hit = i
                }

                if (hit >= 0) {
                    val frame = total + hit
                    val soundNs = soundTimeNs(rec, ts, frame, total + n, frameNs)
                    fire(soundNs)
                    cooldownUntil = frame + Config.cooldownMs.toLong() * sr / 1000
                    Engine.lastTriggerDb.value = (20 * log10(peak + 1e-9f))
                    if (Config.oneShot) Engine.setArmed(false)
                }

                // 周囲音: 上がるときはゆっくり (1s)、下がるときは速く (0.2s) 追従
                val pow = sumSq / n
                val dt = n.toDouble() / sr
                val tau = if (pow > floorPow) 1.0 else 0.2
                if (hit < 0) floorPow += (1 - exp(-dt / tau)) * (pow - floorPow)

                val lvl = 20 * log10(peak + 1e-9f)
                peakHold = max(lvl, peakHold - (20.0 * dt).toFloat())
                Engine.levelDb = lvl
                Engine.peakHoldDb = peakHold
                total += n
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
    }

    /** trigger フレームが実際にマイク/ミキサーに入った時刻 (System.nanoTime 基準) を推定 */
    private fun soundTimeNs(rec: AudioRecord, ts: AudioTimestamp, frame: Long, readEnd: Long, frameNs: Double): Long {
        val now = System.nanoTime()
        if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
            val t = ts.nanoTime + ((frame - ts.framePosition) * frameNs).toLong()
            if (t <= now && now - t < 500_000_000L) return t
        }
        return now - ((readEnd - frame) * frameNs).toLong()
    }

    private fun fire(soundNs: Long) {
        Engine.triggerCount.value = Engine.triggerCount.value + 1
        main.post { updateNotification() }
        val svc = TapService.instance
        if (svc == null) {
            Engine.message.value = "ユーザー補助 (タップ操作) がオフです"
            return
        }
        val target = soundNs + Config.delayMs * 1_000_000L
        if (target - System.nanoTime() < 300_000L) {
            svc.tapNow()
            Engine.lastLatencyMs.value = (System.nanoTime() - soundNs) / 1e6f
        } else {
            schedHandler?.post {
                var r = target - System.nanoTime()
                while (r > 2_000_000L) {
                    LockSupport.parkNanos(r - 1_500_000L)
                    r = target - System.nanoTime()
                }
                while (System.nanoTime() < target) { /* 最後の 1〜2ms はスピンで正確に */ }
                TapService.instance?.tapNow()
                Engine.lastLatencyMs.value = (System.nanoTime() - soundNs) / 1e6f
            }
        }
    }
}
