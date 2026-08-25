package site.ragdollp.pocketzip

import kotlin.math.ln
import kotlin.math.pow

/** ZIP 圧縮処理の進行状態。MainActivity と ZipService がこれを介して状態を共有する。 */
sealed class ZipState {
    object Idle : ZipState()

    data class Running(
        val currentPath: String,
        val filesDone: Int,
        val bytesDone: Long,
        val skipped: Int,
    ) : ZipState()

    data class Done(
        val outputPath: String,
        val filesDone: Int,
        val bytesDone: Long,
        val skipped: Int,
        val durationMs: Long,
    ) : ZipState()

    data class Cancelled(val partialPath: String?) : ZipState()

    data class Failed(val message: String) : ZipState()
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(1, units.size)
    val value = bytes / 1024.0.pow(exp.toDouble())
    return String.format("%.1f %s", value, units[exp - 1])
}
