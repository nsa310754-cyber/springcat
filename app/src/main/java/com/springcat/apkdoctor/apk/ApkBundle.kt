package com.springcat.apkdoctor.apk

import java.io.BufferedInputStream
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

enum class ContainerKind { SINGLE_APK, BUNDLE, UNKNOWN }

/** Device traits that decide which config splits are usable. */
data class DeviceProfile(
    val sdkInt: Int,
    val abis: List<String>,
    val densityDpi: Int,
    val languages: List<String>,
) {
    /** Split qualifiers use underscores where ABI names use hyphens. */
    val abiQualifiers: List<String> get() = abis.map { it.replace('-', '_') }

    val densityQualifier: String
        get() = DENSITY_BUCKETS.minByOrNull { kotlin.math.abs(it.second - densityDpi) }?.first ?: "xhdpi"

    companion object {
        private val DENSITY_BUCKETS = listOf(
            "ldpi" to 120, "mdpi" to 160, "tvdpi" to 213, "hdpi" to 240,
            "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640,
        )
    }
}

/** One APK inside a container, together with why it was kept or skipped. */
data class BundlePart(
    val archive: ApkArchive,
    val entryName: String,
    val selected: Boolean,
    val reason: String,
)

/**
 * The uploaded file after unwrapping: either a lone APK, or a bundle
 * (`.xapk` / `.apks` / `.apkm`) split into the parts this device needs.
 */
class ApkBundle(
    val kind: ContainerKind,
    val sourceFile: File,
    val parts: List<BundlePart>,
    val error: String? = null,
) {
    val selectedParts: List<BundlePart> get() = parts.filter { it.selected }

    val base: ApkArchive? get() = parts.firstOrNull { it.archive.manifest?.isSplit == false }?.archive

    val isBundle: Boolean get() = kind == ContainerKind.BUNDLE

    companion object {

        private val ABI_QUALIFIERS = setOf("armeabi", "armeabi_v7a", "arm64_v8a", "x86", "x86_64", "mips", "mips64")
        private val DENSITY_QUALIFIERS =
            setOf("ldpi", "mdpi", "tvdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi", "nodpi", "anydpi")

        /**
         * Unwraps [file] into installable parts, extracting bundle members into
         * [workDir]. Never throws: failures surface through [ApkBundle.error].
         */
        fun open(file: File, workDir: File, device: DeviceProfile): ApkBundle {
            val single = ApkArchive.read(file)
            if (single.isReadable) {
                return ApkBundle(
                    kind = ContainerKind.SINGLE_APK,
                    sourceFile = file,
                    parts = listOf(BundlePart(single, file.name, selected = true, reason = "本体")),
                )
            }

            val apkEntries = runCatching {
                ZipFile(file).use { zip ->
                    zip.entries().toList()
                        .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                        .map { it.name }
                }
            }.getOrDefault(emptyList())

            if (apkEntries.isEmpty()) {
                // The central directory is unusable, but the local file headers
                // may still describe an APK — that is a damaged APK, which the
                // salvage repair can rebuild, not an unknown file.
                if (looksLikeApk(file)) {
                    return ApkBundle(
                        kind = ContainerKind.SINGLE_APK,
                        sourceFile = file,
                        parts = listOf(BundlePart(single, file.name, selected = true, reason = "破損したAPK")),
                    )
                }
                return ApkBundle(
                    kind = ContainerKind.UNKNOWN,
                    sourceFile = file,
                    parts = emptyList(),
                    error = single.zipError ?: "APKとして読み取れません",
                )
            }

            workDir.mkdirs()
            val extracted = mutableListOf<Pair<String, File>>()
            ZipFile(file).use { zip ->
                for (name in preferredEntries(apkEntries)) {
                    val entry = zip.getEntry(name) ?: continue
                    val target = File(workDir, name.substringAfterLast('/'))
                    runCatching {
                        zip.getInputStream(entry).use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                        extracted.add(name to target)
                    }
                }
            }

            val archives = extracted.map { (name, target) -> name to ApkArchive.read(target) }
            if (archives.none { it.second.isReadable }) {
                return ApkBundle(
                    kind = ContainerKind.BUNDLE,
                    sourceFile = file,
                    parts = emptyList(),
                    error = "バンドル内のAPKを解析できません（暗号化された .apkm の可能性があります）",
                )
            }

            return ApkBundle(
                kind = ContainerKind.BUNDLE,
                sourceFile = file,
                parts = archives.map { (name, archive) -> classify(name, archive, device) },
            )
        }

        /**
         * Streams local file headers looking for a manifest, which survives the
         * loss of the central directory that [ZipFile] depends on.
         */
        private fun looksLikeApk(file: File): Boolean = runCatching {
            ZipInputStream(BufferedInputStream(file.inputStream())).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: return@use false
                    if (entry.name == "AndroidManifest.xml") return@use true
                }
                @Suppress("UNREACHABLE_CODE")
                false
            }
        }.getOrDefault(false)

        /**
         * bundletool archives carry the same app three ways. Split APKs are what
         * the device actually wants; fall back to the universal or standalone
         * builds when no splits are present.
         */
        private fun preferredEntries(names: List<String>): List<String> {
            val splits = names.filter { it.startsWith("splits/") }
            if (splits.isNotEmpty()) return splits
            val universal = names.filter { it.substringAfterLast('/').equals("universal.apk", ignoreCase = true) }
            if (universal.isNotEmpty()) return universal
            val standalones = names.filter { it.startsWith("standalones/") }
            if (standalones.isNotEmpty()) return standalones
            return names
        }

        private fun classify(entryName: String, archive: ApkArchive, device: DeviceProfile): BundlePart {
            val manifest = archive.manifest
            if (manifest == null) {
                return BundlePart(archive, entryName, selected = false, reason = "解析できません")
            }
            val splitName = manifest.splitName
            if (splitName.isNullOrEmpty()) {
                return BundlePart(archive, entryName, selected = true, reason = "ベースAPK")
            }

            val qualifier = splitName.substringAfter("config.", missingDelimiterValue = "")
            if (qualifier.isEmpty()) {
                // A feature module master split: no device qualifier, always keep.
                return BundlePart(archive, entryName, selected = true, reason = "機能モジュール: $splitName")
            }

            return when {
                qualifier in ABI_QUALIFIERS -> {
                    val ok = qualifier in device.abiQualifiers
                    BundlePart(archive, entryName, ok, if (ok) "対応ABI: $qualifier" else "非対応ABI: $qualifier")
                }

                qualifier in DENSITY_QUALIFIERS -> {
                    val ok = qualifier == device.densityQualifier || qualifier == "nodpi" || qualifier == "anydpi"
                    BundlePart(archive, entryName, ok, if (ok) "対応解像度: $qualifier" else "他解像度: $qualifier")
                }

                else -> {
                    // Anything left is a language split.
                    val ok = device.languages.any { it.equals(qualifier, ignoreCase = true) }
                    BundlePart(archive, entryName, ok, if (ok) "対応言語: $qualifier" else "他言語: $qualifier")
                }
            }
        }
    }
}
