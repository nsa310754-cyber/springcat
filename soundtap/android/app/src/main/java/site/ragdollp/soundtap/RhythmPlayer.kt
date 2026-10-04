package site.ragdollp.soundtap

import android.content.Context
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.concurrent.locks.LockSupport
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ADOFAI の自動プレイ。
 *
 * 開始のしかた:
 * - 自動 (既定): バーの ▶ の 0.25 秒後にアプリが「タップしてスタート」を押し、そこから最後まで自動。
 * - 自分でタップ: ▶ で待機 → ゲームの「タップしてスタート」を自分で押す。
 *   その指が触れた時刻 (タッチの eventTime) を基準にする。タッチ自体はそのままゲームに届く。
 *
 * ステージによっては開始タップから曲が始まるまで間がある。その間はコース×速度ごとの
 * 「開始の間・補正」に入れる。「1枚目を自分で押して測る」をオンにすると、1 枚目だけ自分で押した
 * 時刻から自動で求めて保存し、2 枚目から自動で続ける (次回からは完全自動)。
 *
 * 待機は parkNanos + 最後の 1.5ms をスピンで合わせ、各タップは dispatchGesture で即送信する。
 */
object RhythmPlayer {
    enum class Phase { IDLE, WAIT_START, WAIT_FIRST, PLAYING }

    @Volatile var chart: AdofaiChart? = null
    @Volatile var courseKey: String = ""
    /** ゲーム画面にコントロールバーを出すか */
    @Volatile var barVisible = false

    val phase = MutableStateFlow(Phase.IDLE)
    /** 待機〜再生中なら true (画面表示用) */
    val playing = MutableStateFlow(false)
    @Volatile var index = 0
    @Volatile var lastError: String? = null
    @Volatile var lastMeasuredMs: Int? = null

    private var thread: Thread? = null
    @Volatile private var stopFlag = false
    @Volatile private var startTouchNs = 0L
    /** WAIT_FIRST が「1 タイル目を自分で押す」モードのとき (測定・保存はしない) */
    @Volatile private var anchorOnly = false

    private fun setPhase(p: Phase) {
        phase.value = p
        playing.value = p != Phase.IDLE
    }

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

    // ---------------------------------------------------------------- 速度・補正

    /** スピードトライアル倍率 (1.0〜3.0) */
    fun trial(): Double = Config.rhythmTrial.coerceIn(10, 30) / 10.0

    /** 実際の再生速度 (1.0 = 100%)。譜面の pitch × スピードトライアル */
    fun speed(c: AdofaiChart): Double = c.pitch / 100.0 * trial()

    /** 開始の間・補正の保存キー。速度ごとに別の値を持つ */
    fun adjKey(): String = if (courseKey.isEmpty()) "" else "$courseKey@x${Config.rhythmTrial.coerceIn(10, 30)}"

    fun courseOffsetMs(): Int {
        val k = adjKey()
        if (k.isEmpty()) return 0
        // 1.1 以前は速度なしのキーで保存していたので ×1.0 のときだけ引き継ぐ
        return Config.courseAdjustOrNull(k)
            ?: if (Config.rhythmTrial == 10) Config.courseAdjustMs(courseKey) else 0
    }

    fun setCourseOffsetMs(ctx: Context, v: Int) = Config.setCourseAdjustMs(ctx, adjKey(), v)

    fun totalOffsetMs(): Int = Config.rhythmCalibMs + courseOffsetMs()

    // ---------------------------------------------------------------- 開始

    /** バーの ▶。開始のしかたに応じて待機 or 自動開始 */
    fun arm() {
        stop()
        anchorOnly = false
        if (chart == null) { lastError = "コースが選ばれていません"; return }
        lastError = null
        index = 0
        if (Config.rhythmStartMode == Config.START_AUTO) {
            // アプリが「タップしてスタート」を押す (▶ の指が離れるのを 0.25 秒待つ)
            val t0 = System.nanoTime() + 250_000_000L
            if (Config.rhythmMeasure) {
                // 開始タップだけ送り、1 枚目はユーザーに押してもらって間を測る
                stopFlag = false
                setPhase(Phase.PLAYING)
                thread = Thread({
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                    if (!waitUntil(t0)) return@Thread
                    val svc = TapService.instance ?: run { lastError = "ユーザー補助がオフです"; setPhase(Phase.IDLE); return@Thread }
                    val (x, y) = svc.rhythmPoint()
                    svc.press(x, y, Config.rhythmPressMs.coerceIn(1, 200).toLong())
                    startTouchNs = t0
                    setPhase(Phase.WAIT_FIRST)
                    svc.setTouchWatcher(true)
                }, "adofai-start").also { it.start() }
            } else {
                launch(t0, injectStart = true, fromIndex = 0)
            }
        } else if (Config.rhythmStartMode == Config.START_FIRST_TILE) {
            // 次に画面に触れたタップを 1 枚目とみなす
            anchorOnly = true
            setPhase(Phase.WAIT_FIRST)
            TapService.instance?.setTouchWatcher(true)
        } else {
            setPhase(Phase.WAIT_START)
            TapService.instance?.setTouchWatcher(true)
        }
    }

    /** 画面のどこかに指が触れた (TapService の監視窓から)。tNs は System.nanoTime 基準 */
    fun onUserTouch(ctx: Context, tNs: Long) {
        val c = chart ?: return
        when (phase.value) {
            Phase.WAIT_START -> {
                startTouchNs = tNs
                if (Config.rhythmMeasure) {
                    setPhase(Phase.WAIT_FIRST)
                } else {
                    TapService.instance?.setTouchWatcher(false)
                    launch(tNs, injectStart = false, fromIndex = 0)
                }
            }
            Phase.WAIT_FIRST -> {
                TapService.instance?.setTouchWatcher(false)
                if (anchorOnly) {
                    // 押した瞬間を 1 枚目の時刻にする。以降の補正 (−/+) はこの基準からの相対で効く
                    val sp = speed(c)
                    val t0 = tNs - ((c.leadSec + c.times[0]) / sp * 1e9).toLong() - totalOffsetMs() * 1_000_000L
                    launch(t0, injectStart = false, fromIndex = 1)
                    return
                }
                // 1 枚目を押した時刻から「開始の間・補正」を逆算して保存
                val sp = speed(c)
                val expectedNoCourse = startTouchNs + ((c.leadSec + c.times[0]) / sp * 1e9).toLong() +
                    Config.rhythmCalibMs * 1_000_000L
                val measured = ((tNs - expectedNoCourse) / 1e6).roundToInt()
                setCourseOffsetMs(ctx, measured)
                lastMeasuredMs = measured
                Config.rhythmMeasure = false
                Config.save(ctx)
                Engine.configVersion.value = Engine.configVersion.value + 1
                // 1 枚目は自分で押したので 2 枚目から自動
                launch(startTouchNs, injectStart = false, fromIndex = 1)
            }
            else -> {}
        }
    }

    fun stop() {
        stopFlag = true
        thread?.let { if (it !== Thread.currentThread()) it.join(300) }
        thread = null
        TapService.instance?.setTouchWatcher(false)
        setPhase(Phase.IDLE)
    }

    private fun launch(t0: Long, injectStart: Boolean, fromIndex: Int) {
        val c = chart ?: return
        stopFlag = false
        setPhase(Phase.PLAYING)
        thread = Thread({ run(c, t0, injectStart, fromIndex) }, "adofai-player").also { it.start() }
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

    private fun run(c: AdofaiChart, t0: Long, injectStart: Boolean, fromIndex: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        try {
            val svc = TapService.instance ?: run { lastError = "ユーザー補助がオフです"; return }
            val (x, y) = svc.rhythmPoint()
            val sp = speed(c)
            val press = Config.rhythmPressMs.coerceIn(1, 200).toDouble()

            if (injectStart) {
                if (!waitUntil(t0)) return
                svc.press(x, y, press.toLong())
            }

            // 1 枚目は開始から leadSec 後 (+ 開始の間・補正)。以降は譜面どおり
            val base = t0 + (c.leadSec / sp * 1e9).toLong()
            val n = c.times.size
            for (k in fromIndex until n) {
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
                val fingers = if (Config.rhythmMultitap) c.fingers[k] else 1
                if (TapService.instance?.press(x, y, max(1.0, durMs).toLong(), fingers) != true) {
                    lastError = "タップを送れませんでした"
                }
            }
            index = n
        } finally {
            if (!stopFlag) setPhase(Phase.IDLE)
        }
    }
}
