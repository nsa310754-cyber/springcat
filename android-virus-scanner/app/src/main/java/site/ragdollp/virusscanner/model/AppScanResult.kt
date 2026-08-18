package site.ragdollp.virusscanner.model

import android.graphics.drawable.Drawable

/**
 * 検出欄に表示する1アプリ分のスキャン結果。
 */
data class AppScanResult(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val icon: Drawable?,
    val suspiciousPermissions: List<String>,
    val extraFindings: List<String>
)
