package site.ragdollp.pocketzip

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * ZipService (バックグラウンド) と MainActivity (UI) の間で進行状況を共有する
 * プロセス内シングルトン。同一プロセスで動くフォアグラウンドサービスなので
 * Binder 等を使わずシンプルな StateFlow 共有で十分。
 */
object ZipProgressBus {
    val state = MutableStateFlow<ZipState>(ZipState.Idle)

    @Volatile
    var cancelRequested: Boolean = false
}
