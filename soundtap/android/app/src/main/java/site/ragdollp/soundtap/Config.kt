package site.ragdollp.soundtap

import android.content.Context

/**
 * すべての設定値。音声スレッドから毎ブロック読むため @Volatile の素のフィールドにしている
 * (ロックやFlowを挟まず、検出ループのオーバーヘッドを最小にする)。
 */
object Config {
    const val SOURCE_MIC = 0
    const val SOURCE_INTERNAL = 1

    @Volatile var source = SOURCE_MIC

    /** 検出しきい値 (dBFS, サンプルのピーク値で判定) */
    @Volatile var thresholdDb = -30f
    /** ノイズ追従: 周囲の音量 + riseDb を超えたときだけ反応 */
    @Volatile var adaptive = false
    @Volatile var riseDb = 12f
    /** 低音カット (1次ハイパス) のカットオフ。0 でオフ */
    @Volatile var highPassHz = 0f
    /** 1回の読み取りフレーム数 (小さいほど反応が速い) */
    @Volatile var blockFrames = 64

    /** 音の発生時刻から数えたタップまでの遅延 */
    @Volatile var delayMs = 0
    /** 1回タップの押下時間 */
    @Volatile var pressMs = 10
    @Volatile var tapCount = 1
    @Volatile var tapIntervalMs = 60
    /** 一度反応したら次に反応するまでの無視時間 */
    @Volatile var cooldownMs = 300
    /** 1回反応したら自動で一時停止 */
    @Volatile var oneShot = false

    /** タップ位置 (画面座標 px)。未設定は負値 */
    @Volatile var tapX = -1f
    @Volatile var tapY = -1f

    @Volatile var showMarker = true
    @Volatile var showBubble = true

    // ---- ADOFAI モード
    @Volatile var selectedCourse = ""
    /** 全コース共通の補正 (ms, + で遅く) */
    @Volatile var rhythmCalibMs = 0
    /** 1 タップの押下時間 */
    @Volatile var rhythmPressMs = 20
    /** スピードトライアル倍率 ×10 (10 = ×1.0 〜 30 = ×3.0) */
    @Volatile var rhythmTrial = 10
    const val START_TOUCH = 0
    const val START_AUTO = 1
    /** 開始のしかた: 画面タップ (自分で押す) / ▶ で自動タップ */
    @Volatile var rhythmStartMode = START_TOUCH
    /** 次回、1 枚目を自分で押して開始の間を測る */
    @Volatile var rhythmMeasure = false
    /** 画面上バーの −/+ の刻み */
    @Volatile var rhythmStepMs = 5
    private val courseAdjust = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun courseAdjustMs(key: String): Int = courseAdjust[key] ?: 0
    fun courseAdjustOrNull(key: String): Int? = courseAdjust[key]
    fun setCourseAdjustMs(ctx: Context, key: String, v: Int) {
        if (key.isEmpty()) return
        courseAdjust[key] = v
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("adj_$key", v).apply()
    }

    private const val PREFS = "soundtap"

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        source = p.getInt("source", source)
        thresholdDb = p.getFloat("thresholdDb", thresholdDb)
        adaptive = p.getBoolean("adaptive", adaptive)
        riseDb = p.getFloat("riseDb", riseDb)
        highPassHz = p.getFloat("highPassHz", highPassHz)
        blockFrames = p.getInt("blockFrames", blockFrames)
        delayMs = p.getInt("delayMs", delayMs)
        pressMs = p.getInt("pressMs", pressMs)
        tapCount = p.getInt("tapCount", tapCount)
        tapIntervalMs = p.getInt("tapIntervalMs", tapIntervalMs)
        cooldownMs = p.getInt("cooldownMs", cooldownMs)
        oneShot = p.getBoolean("oneShot", oneShot)
        tapX = p.getFloat("tapX", tapX)
        tapY = p.getFloat("tapY", tapY)
        showMarker = p.getBoolean("showMarker", showMarker)
        showBubble = p.getBoolean("showBubble", showBubble)
        selectedCourse = p.getString("selectedCourse", selectedCourse) ?: ""
        rhythmCalibMs = p.getInt("rhythmCalibMs", rhythmCalibMs)
        rhythmPressMs = p.getInt("rhythmPressMs", rhythmPressMs)
        rhythmTrial = p.getInt("rhythmTrial", rhythmTrial)
        rhythmStartMode = p.getInt("rhythmStartMode", rhythmStartMode)
        rhythmMeasure = p.getBoolean("rhythmMeasure", rhythmMeasure)
        rhythmStepMs = p.getInt("rhythmStepMs", rhythmStepMs)
        p.all.forEach { (k, v) -> if (k.startsWith("adj_") && v is Int) courseAdjust[k.removePrefix("adj_")] = v }
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("source", source)
            .putFloat("thresholdDb", thresholdDb)
            .putBoolean("adaptive", adaptive)
            .putFloat("riseDb", riseDb)
            .putFloat("highPassHz", highPassHz)
            .putInt("blockFrames", blockFrames)
            .putInt("delayMs", delayMs)
            .putInt("pressMs", pressMs)
            .putInt("tapCount", tapCount)
            .putInt("tapIntervalMs", tapIntervalMs)
            .putInt("cooldownMs", cooldownMs)
            .putBoolean("oneShot", oneShot)
            .putFloat("tapX", tapX)
            .putFloat("tapY", tapY)
            .putBoolean("showMarker", showMarker)
            .putBoolean("showBubble", showBubble)
            .putString("selectedCourse", selectedCourse)
            .putInt("rhythmCalibMs", rhythmCalibMs)
            .putInt("rhythmPressMs", rhythmPressMs)
            .putInt("rhythmTrial", rhythmTrial)
            .putInt("rhythmStartMode", rhythmStartMode)
            .putBoolean("rhythmMeasure", rhythmMeasure)
            .putInt("rhythmStepMs", rhythmStepMs)
            .apply()
    }
}
