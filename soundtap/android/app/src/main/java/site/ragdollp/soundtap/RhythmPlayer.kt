package site.ragdollp.soundtap

import android.content.Context
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.concurrent.locks.LockSupport
import kotlin.math.max
import kotlin.math.min

/**
 * ADOFAI の自動プレイ。
 * ▶ を押すと「ゲーム開始タップ」を自分で送り、その瞬間を基準に譜面の全タイルを時刻どおりに叩く。
 * 待機は parkNanos + 最後の 1.5ms をスピンで合わせ、各タップは dispatchGesture で即送信する。
 */
object RhythmPlayer {
    @Volatile var chart: AdofaiChart? = null
    @Volatile var courseKey: String = ""
    /** ゲーム画面にコントロールバーを出すか */
    @Volatile var barVisible = false

    val playing = MutableStateFlow(false)
    @Volatile var index = 0
    @Volatile var lastError: String? = null

    private var thread: Thread? = null
    @Volatile private var stopFlag = false

    // ---------------------------------------------------------------- コース (譜面ファイル) 管理

    fun coursesDir(ctx: Context) = File(ctx.filesDir, "courses").apply { mkdirs() }

    fun listCourses(ctx: Context): List<File> =
        coursesDir(ctx).listFiles { f -> f.name.endsWith(".adofai") }?.sortedBy { it.name.lowercase() } ?: emptyList()

    /** 選択中のコースを読み込む。失敗時は null とエラー文 */
    fun loadSelected(ctx: Context): Pair<AdofaiChart?, String?> {
        val key = Config.selectedCourse
        if (key.isEmpty()) { chart = null; courseKey = ""; return null to null }
        val f = File(coursesDir(ctx), key)
        if (!f.exists()) { chart = null; courseKey = ""; return null to "コースファイルが見つかりません" }
        if (courseKey == key && chart != null) return chart to null
        return try {
            val c = AdofaiChart.parse(f.readText())
            chart = c; courseKey = key
            c to null
        } catch (e: Exception) {
            chart = null; courseKey = ""
            null to (e.message ?: "読み込みに失敗しました")
        }
    }

    // ---------------------------------------------------------------- 再生

    /** 実際の再生速度 (1.0 = 100%) */
    fun speed(c: AdofaiChart): Double = (if (Config.rhythmSpeed > 0) Config.rhythmSpeed.toDouble() else c.pitch) / 100.0

    fun totalOffsetMs(): Int = Config.rhythmCalibMs + Config.courseAdjustMs(courseKey)

    fun start() {
        stop()
        val c = chart ?: return
        stopFlag = false
        index = 0
        lastError = null
        playing.value = true
        thread = Thread({ run(c) }, "adofai-player").also { it.start() }
    }

    fun stop() {
        stopFlag = true
        thread?.let { if (it !== Thread.currentThread()) it.join(300) }
        thread = null
        playing.value = false
    }

    /** target (System.nanoTime) まで待つ。停止されたら false */
    private fun waitUntil(target: Long): Boolean {
        while (true) {
            if (stopFlag) return false
            val r = target - System.nanoTime()
            if (r <= 1_500_000L) break
            LockSupport.parkNanos(min(r - 1_500_000L, 5_000_000L))
        }
        while (System.nanoTime() < target) { if (stopFlag) return false }
        return true
    }

    private fun run(c: AdofaiChart) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        try {
            val svc = TapService.instance ?: run { lastError = "ユーザー補助がオフです"; return }
            val (x, y) = svc.rhythmPoint()
            val sp = speed(c)
            val press = Config.rhythmPressMs.coerceIn(1, 200).toDouble()

            // 1) ゲーム開始タップ (▶ を押した指が離れるのを少し待つ)
            val t0 = System.nanoTime() + 250_000_000L
            if (!waitUntil(t0)) return
            svc.press(x, y, press.toLong())

            // 2) 1 枚目のタイルは開始から leadSec 後。以降は譜面どおり
            val base = t0 + (c.leadSec / sp * 1e9).toLong()
            val n = c.times.size
            for (k in 0 until n) {
                index = k
                val tSec = c.times[k] / sp
                val target = base + (tSec * 1e9).toLong() + totalOffsetMs() * 1_000_000L
                if (!waitUntil(target)) return
                val nextMs = if (k + 1 < n) (c.times[k + 1] / sp - tSec) * 1000 else 1000.0
                val durMs = if (c.releases[k] >= 0) {
                    // ホールド: 終点まで押し続け、次のタップより前に離す
                    val holdMs = (c.releases[k] / sp - tSec) * 1000
                    min(holdMs + min(30.0, (nextMs - holdMs) * 0.5), nextMs * 0.95)
                } else {
                    min(press, nextMs * 0.45)
                }
                if (TapService.instance?.press(x, y, max(1.0, durMs).toLong()) != true) {
                    lastError = "タップを送れませんでした"
                }
            }
            index = n
        } finally {
            playing.value = false
        }
    }
}
