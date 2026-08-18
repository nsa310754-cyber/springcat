package site.ragdollp.virusscanner.state

import site.ragdollp.virusscanner.model.AppScanResult

/**
 * スキャン結果(アイコンのDrawableを含み複雑)を画面間で受け渡すためのプロセス内シングルトン。
 * 単一プロセスのアプリ内でActivity間の大きなデータを共有する一般的な手法で、
 * Intentへ詰め込んでのParcel化コストを避けられる。
 */
object ScanResultsHolder {
    var results: MutableList<AppScanResult> = mutableListOf()
}
