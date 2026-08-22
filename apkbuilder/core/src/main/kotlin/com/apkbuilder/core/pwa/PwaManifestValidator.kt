package com.apkbuilder.core.pwa

data class PwaValidationResult(
    val installable: Boolean,
    val issues: List<String>,
    val warnings: List<String>,
)

/**
 * A simplified version of Chrome's PWA "installability" criteria: enough to
 * tell a user whether wrapping this manifest will feel like a real app
 * (usable name, a large-enough icon, a standalone display mode) rather than
 * a strict spec conformance check.
 */
object PwaManifestValidator {
    private const val MIN_ICON_SIZE = 192

    fun validate(manifest: PwaManifest): PwaValidationResult {
        val issues = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (manifest.displayName.isNullOrBlank()) {
            issues.add("manifest に name も short_name もありません")
        }

        val bestIcon = manifest.bestIcon()
        if (bestIcon == null) {
            issues.add("manifest に icons がありません")
        } else if (bestIcon.maxDimension() in 1 until MIN_ICON_SIZE) {
            warnings.add("最大のアイコンが ${bestIcon.maxDimension()}px しかありません(推奨: ${MIN_ICON_SIZE}px 以上)")
        } else if (bestIcon.maxDimension() == 0) {
            warnings.add("アイコンの sizes が不明です(そのまま使用しますが表示が乱れる可能性があります)")
        }

        val hasLargeIcon = manifest.icons.any { it.maxDimension() >= MIN_ICON_SIZE }
        if (bestIcon != null && !hasLargeIcon && bestIcon.maxDimension() != 0) {
            warnings.add("${MIN_ICON_SIZE}px 以上のアイコンが見つかりません")
        }

        when (manifest.display) {
            null -> warnings.add("display が指定されていません(既定は 'browser' 扱いになります)")
            "browser" -> warnings.add("display が 'browser' です。アプリらしい全画面表示にはなりません")
            "standalone", "fullscreen", "minimal-ui" -> Unit
            else -> warnings.add("display の値 '${manifest.display}' は不明です")
        }

        if (manifest.startUrl.isBlank()) {
            issues.add("start_url を解決できませんでした")
        }

        return PwaValidationResult(installable = issues.isEmpty(), issues = issues, warnings = warnings)
    }
}
