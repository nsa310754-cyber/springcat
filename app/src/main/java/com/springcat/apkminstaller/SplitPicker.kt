package com.springcat.apkminstaller

import android.content.Context
import android.os.Build
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * バンドルに入っている split のうち、この端末 (Fire タブレット) に必要なものだけを選ぶ。
 * base + 対応 ABI + 近い DPI + 端末の言語 + 機能 split を採用する。
 */
object SplitPicker {

    private val DPI_BUCKETS = mapOf(
        "ldpi" to 120, "mdpi" to 160, "tvdpi" to 213, "hdpi" to 240,
        "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640
    )
    private val DENSITY_ANY = setOf("nodpi", "anydpi")
    private val KNOWN_ABIS = setOf(
        "armeabi", "armeabi_v7a", "arm64_v8a", "x86", "x86_64", "mips", "mips64"
    )

    class Selection(val chosen: List<File>, val skipped: List<File>, val notes: List<String>)

    fun pick(context: Context, files: List<File>, installAll: Boolean): Selection {
        if (installAll || files.size <= 1) {
            return Selection(files, emptyList(), emptyList())
        }

        val notes = mutableListOf<String>()
        val abis = Build.SUPPORTED_ABIS.map { it.replace('-', '_').lowercase(Locale.US) }.toSet()
        val deviceDpi = context.resources.displayMetrics.densityDpi
        val langs = deviceLanguages(context)

        val classified = files.map { it to classify(it.name) }
        val abiSplits = classified.filter { it.second is Kind.Abi }
        val dpiSplits = classified.filter { it.second is Kind.Dpi }
        val langSplits = classified.filter { it.second is Kind.Language }

        // ABI: 端末が対応するものだけ。1 つも合わなければ全部入れて OS に任せる。
        val abiMatched = abiSplits.filter { (it.second as Kind.Abi).abi in abis }
        val abiChosen = when {
            abiSplits.isEmpty() -> emptyList()
            abiMatched.isNotEmpty() -> abiMatched
            else -> {
                notes += "対応する ABI split が見つからないため ABI split を全部入れます"
                abiSplits
            }
        }

        // DPI: 端末の密度に一番近いバケットを 1 つ (nodpi/anydpi は常に採用)
        val dpiChosen = if (dpiSplits.isEmpty()) emptyList() else {
            val anyDpi = dpiSplits.filter { (it.second as Kind.Dpi).bucket in DENSITY_ANY }
            val real = dpiSplits.filter { (it.second as Kind.Dpi).bucket !in DENSITY_ANY }
            val best = real.minByOrNull {
                abs((DPI_BUCKETS[(it.second as Kind.Dpi).bucket] ?: 160) - deviceDpi)
            }
            anyDpi + listOfNotNull(best)
        }

        // 言語: 端末の言語 + en。合わなければ全部入れる。
        val langMatched = langSplits.filter { (it.second as Kind.Language).lang in langs }
        val langChosen = when {
            langSplits.isEmpty() -> emptyList()
            langMatched.isNotEmpty() -> langMatched
            else -> {
                notes += "一致する言語 split が無いため言語 split を全部入れます"
                langSplits
            }
        }

        val chosen = LinkedHashSet<File>()
        classified.filter { it.second is Kind.BaseOrFeature }.forEach { chosen += it.first }
        (abiChosen + dpiChosen + langChosen).forEach { chosen += it.first }

        val skipped = files.filter { it !in chosen }
        return Selection(chosen.toList(), skipped, notes)
    }

    private sealed class Kind {
        object BaseOrFeature : Kind()
        class Abi(val abi: String) : Kind()
        class Dpi(val bucket: String) : Kind()
        class Language(val lang: String) : Kind()
    }

    /** split_config.arm64_v8a.apk / config.xxhdpi.apk / xxx-ja.apk などから種別を判定する。 */
    private fun classify(fileName: String): Kind {
        val stem = fileName.removeSuffix(".apk").removeSuffix(".APK")
            .lowercase(Locale.US).replace('-', '_')
        val token = stem.substringAfterLast('.').substringAfterLast("config_")
            .substringAfterLast("config.").substringAfterLast('_')
        val tokens = listOf(stem.substringAfterLast('.'), token)

        for (t in tokens) {
            val cleaned = t.trim('_', '.')
            if (cleaned in KNOWN_ABIS) return Kind.Abi(cleaned)
            if (cleaned in DPI_BUCKETS || cleaned in DENSITY_ANY) return Kind.Dpi(cleaned)
            if (isLanguageTag(cleaned) && stem.contains("config")) return Kind.Language(cleaned)
        }
        // 区切りが '.' でない命名 (例: myapp_arm64_v8a.apk / myapp-xxhdpi.apk) も拾う
        KNOWN_ABIS.firstOrNull { stem.endsWith("_$it") || stem.endsWith(".$it") }
            ?.let { return Kind.Abi(it) }
        (DPI_BUCKETS.keys + DENSITY_ANY).firstOrNull { stem.endsWith("_$it") || stem.endsWith(".$it") }
            ?.let { return Kind.Dpi(it) }

        // 末尾トークンだけで言語判定 (例: mypkg.ja.apk)
        val last = stem.substringAfterLast('.').trim('_')
        if (last != stem && isLanguageTag(last)) return Kind.Language(last)

        return Kind.BaseOrFeature
    }

    private fun isLanguageTag(s: String): Boolean =
        s.length in 2..3 && s.all { it in 'a'..'z' } && Locale.getISOLanguages().contains(s)

    private fun deviceLanguages(context: Context): Set<String> {
        val out = linkedSetOf("en")
        val conf = context.resources.configuration
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val list = conf.locales
            for (i in 0 until list.size()) out += list.get(i).language.lowercase(Locale.US)
        } else {
            @Suppress("DEPRECATION")
            out += conf.locale.language.lowercase(Locale.US)
        }
        return out
    }
}
