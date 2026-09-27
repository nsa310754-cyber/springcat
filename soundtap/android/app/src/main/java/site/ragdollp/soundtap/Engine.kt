package site.ragdollp.soundtap

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow

/** 画面・オーバーレイ・サービス間で共有する実行時の状態。 */
object Engine {
    val running = MutableStateFlow(false)
    val armed = MutableStateFlow(true)
    val triggerCount = MutableStateFlow(0)
    /** 音の発生 → タップ送信までの実測遅延 (ms)。未計測は負値 */
    val lastLatencyMs = MutableStateFlow(-1f)
    val lastTriggerDb = MutableStateFlow(-120f)
    val message = MutableStateFlow<String?>(null)
    val tapServiceConnected = MutableStateFlow(false)
    /** オーバーレイ側で設定が変わったとき (タップ位置など) に画面へ知らせる */
    val configVersion = MutableStateFlow(0)

    // メーター表示用。音声スレッドが書き、UI が 30fps 程度で読む。
    @Volatile var levelDb = -120f
    @Volatile var peakHoldDb = -120f
    @Volatile var effectiveThresholdDb = -30f

    private val main = Handler(Looper.getMainLooper())

    fun setArmed(value: Boolean) {
        armed.value = value
        main.post {
            DetectorService.instance?.updateNotification()
            TapService.instance?.refreshOverlays()
        }
    }

    fun onRunningChanged(value: Boolean) {
        running.value = value
        if (!value) {
            levelDb = -120f
            peakHoldDb = -120f
        }
        main.post { TapService.instance?.refreshOverlays() }
    }
}
