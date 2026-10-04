package site.ragdollp.soundtap

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.SoundPool
import android.net.Uri
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * コースごとの曲 (ユーザーが端末から選んだ音声ファイル) の保存と再生。
 * 「タップ音つき」なら譜面のタイル時刻にクリック音を重ね、譜面と曲が合っているか耳で確かめられる。
 */
object MusicPlayer {
    val playing = MutableStateFlow(false)
    val positionMs = MutableStateFlow(0)
    val durationMs = MutableStateFlow(0)
    val error = MutableStateFlow<String?>(null)

    private var mp: MediaPlayer? = null
    private var loadedFile: File? = null
    private var tickThread: Thread? = null
    @Volatile private var tickStop = false
    private var pool: SoundPool? = null
    private var clickId = 0

    // ---------------------------------------------------------------- 保存

    private fun dir(ctx: Context) = File(ctx.filesDir, "music").apply { mkdirs() }

    /** コースに紐づいた曲ファイル (無ければ null) */
    fun fileFor(ctx: Context, courseKey: String): File? {
        if (courseKey.isEmpty()) return null
        val base = courseKey.removeSuffix(".adofai")
        return dir(ctx).listFiles { f -> f.nameWithoutExtension == base }?.firstOrNull()
    }

    /** 端末の音声ファイルをコースの曲としてコピーする */
    fun import(ctx: Context, courseKey: String, uri: Uri, displayName: String?) {
        stop()
        fileFor(ctx, courseKey)?.delete()
        val ext = displayName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.length in 2..5 } ?: "audio"
        val f = File(dir(ctx), courseKey.removeSuffix(".adofai") + "." + ext)
        ctx.contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
    }

    fun delete(ctx: Context, courseKey: String) {
        stop()
        fileFor(ctx, courseKey)?.delete()
    }

    // ---------------------------------------------------------------- 再生

    /**
     * 再生 / 一時停止。speed は曲の再生速度 (譜面の pitch × スピードトライアル)。
     * clicks が true ならタイル時刻にクリック音を鳴らす。
     */
    fun toggle(ctx: Context, file: File, chart: AdofaiChart?, speed: Double, clicks: Boolean) {
        val p = mp
        if (p != null && loadedFile == file) {
            if (p.isPlaying) { p.pause(); stopTicks(); playing.value = false; return }
            start(p, speed, chart, clicks, ctx)
            return
        }
        stop()
        try {
            val np = MediaPlayer()
            np.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            np.setDataSource(file.absolutePath)
            np.prepare()
            np.setOnCompletionListener { stopTicks(); playing.value = false; positionMs.value = 0 }
            mp = np
            loadedFile = file
            durationMs.value = np.duration
            error.value = null
            start(np, speed, chart, clicks, ctx)
        } catch (e: Exception) {
            error.value = "この曲ファイルは再生できません: ${e.message}"
            stop()
        }
    }

    fun seekTo(ms: Int) {
        mp?.seekTo(ms.coerceAtLeast(0))
        positionMs.value = ms
    }

    fun stop() {
        stopTicks()
        try { mp?.stop() } catch (_: Exception) {}
        mp?.release()
        mp = null
        loadedFile = null
        playing.value = false
        positionMs.value = 0
    }

    private fun start(p: MediaPlayer, speed: Double, chart: AdofaiChart?, clicks: Boolean, ctx: Context) {
        try {
            p.playbackParams = PlaybackParams().setSpeed(speed.toFloat().coerceIn(0.25f, 4f))
        } catch (_: Exception) {}
        p.start()
        playing.value = true
        startTicks(p, chart, clicks, ctx)
    }

    private fun stopTicks() {
        tickStop = true
        tickThread?.join(200)
        tickThread = null
    }

    /** 再生位置を見張り、位置表示の更新とクリック音を出す */
    private fun startTicks(p: MediaPlayer, chart: AdofaiChart?, clicks: Boolean, ctx: Context) {
        stopTicks()
        if (clicks && pool == null) {
            pool = SoundPool.Builder().setMaxStreams(4)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
                .build()
            clickId = pool!!.load(clickWav(ctx).absolutePath, 1)
        }
        tickStop = false
        // 曲の中での時刻 (ms)。1 枚目のタイルは offset の位置
        val tileMs = chart?.let { c -> DoubleArray(c.times.size) { c.offsetMs + c.times[it] * 1000 } } ?: DoubleArray(0)
        tickThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            var k = 0
            var lastPos = -1
            var lastPosNs = 0L
            val sp = try { p.playbackParams.speed.toDouble() } catch (_: Exception) { 1.0 }
            while (!tickStop) {
                val pos = try { p.currentPosition } catch (_: Exception) { break }
                val now = System.nanoTime()
                if (pos != lastPos) { lastPos = pos; lastPosNs = now }
                // currentPosition は粗いので、前回の値からの経過時間で補う
                val songMs = lastPos + (now - lastPosNs) / 1e6 * sp
                positionMs.value = songMs.toInt()
                if (clicks) {
                    while (k < tileMs.size && tileMs[k] < songMs - 50) k++ // シーク後の追い付き
                    if (k < tileMs.size && songMs >= tileMs[k]) {
                        pool?.play(clickId, 1f, 1f, 1, 0, 1f)
                        k++
                    }
                }
                try { Thread.sleep(2) } catch (_: InterruptedException) { break }
            }
        }, "music-ticks").also { it.start() }
    }

    /** 短いクリック音 (WAV) を作ってキャッシュに置く */
    private fun clickWav(ctx: Context): File {
        val f = File(ctx.cacheDir, "click.wav")
        if (f.exists()) return f
        val rate = 44100
        val n = rate * 30 / 1000
        val pcm = ShortArray(n) { i ->
            val t = i.toDouble() / rate
            (sin(2 * PI * 1800 * t) * exp(-t * 180) * 0.8 * Short.MAX_VALUE).toInt().toShort()
        }
        RandomAccessFile(f, "rw").use { r ->
            fun le32(v: Int) = r.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun le16(v: Int) = r.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            r.setLength(0)
            r.writeBytes("RIFF"); le32(36 + n * 2); r.writeBytes("WAVE")
            r.writeBytes("fmt "); le32(16); le16(1); le16(1); le32(rate); le32(rate * 2); le16(2); le16(16)
            r.writeBytes("data"); le32(n * 2)
            val b = ByteArray(n * 2)
            for (i in 0 until n) { b[2 * i] = pcm[i].toInt().toByte(); b[2 * i + 1] = (pcm[i].toInt() shr 8).toByte() }
            r.write(b)
        }
        return f
    }
}
