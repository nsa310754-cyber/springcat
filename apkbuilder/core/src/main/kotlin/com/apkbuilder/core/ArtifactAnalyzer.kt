package com.apkbuilder.core

import com.apkbuilder.core.axml.AxmlDocument
import com.apkbuilder.core.proto.ProtoManifestEditor
import com.apkbuilder.core.zip.RawZipReader
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

data class EntrySize(val name: String, val size: Long)

data class ArtifactInfo(
    val kind: String, // "APK" or "AAB"
    val packageId: String?,
    val versionName: String?,
    val versionCode: Int?,
    val label: String?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val permissions: List<String>,
    val totalUncompressedSize: Long,
    val fileCount: Int,
    val dexCount: Int,
    val largestEntries: List<EntrySize>,
)

/**
 * A lightweight "APK Analyzer" (the Android Studio feature): reads a built
 * .apk or .aab and reports its manifest facts (package/version/label/sdk/
 * permissions) and its file composition (biggest entries, DEX count, total
 * uncompressed size) — reusing the same manifest parsers the builder uses to
 * write them.
 */
object ArtifactAnalyzer {

    fun analyze(bytes: ByteArray): ArtifactInfo {
        val isAab = looksLikeAab(bytes)
        return if (isAab) analyzeAab(bytes) else analyzeApk(bytes)
    }

    private fun looksLikeAab(bytes: ByteArray): Boolean =
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (e.name == "BundleConfig.pb" || e.name.startsWith("base/manifest/")) return true
                    e = zis.nextEntry
                }
            }
            false
        }.getOrDefault(false)

    private fun analyzeApk(bytes: ByteArray): ArtifactInfo {
        val records = RawZipReader.read(bytes)
        val entries = records.map { EntrySize(it.name, it.uncompressedSize) }
        val manifestRecord = records.find { it.name == "AndroidManifest.xml" }
        var pkg: String? = null
        var versionName: String? = null
        var versionCode: Int? = null
        var label: String? = null
        var minSdk: Int? = null
        var targetSdk: Int? = null
        var permissions: List<String> = emptyList()
        if (manifestRecord != null) {
            runCatching {
                val axml = AxmlDocument.parse(manifestRecord.inflatedBytes())
                pkg = axml.getStringAttr("manifest", "package")
                versionName = axml.getStringAttr("manifest", "versionName")
                versionCode = axml.getIntAttr("manifest", "versionCode")
                label = axml.getStringAttr("application", "label")
                minSdk = axml.getIntAttr("uses-sdk", "minSdkVersion")
                targetSdk = axml.getIntAttr("uses-sdk", "targetSdkVersion")
                permissions = axml.getUsesPermissions()
            }
        }
        return build("APK", pkg, versionName, versionCode, label, minSdk, targetSdk, permissions, entries) {
            it.name.endsWith(".dex")
        }
    }

    private fun analyzeAab(bytes: ByteArray): ArtifactInfo {
        val entries = ArrayList<EntrySize>()
        var manifestProto: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    val content = zis.readBytes()
                    entries.add(EntrySize(e.name, content.size.toLong()))
                    if (e.name == "base/manifest/AndroidManifest.xml") manifestProto = content
                }
                e = zis.nextEntry
            }
        }
        var pkg: String? = null
        var versionName: String? = null
        var versionCode: Int? = null
        var label: String? = null
        var minSdk: Int? = null
        var targetSdk: Int? = null
        var permissions: List<String> = emptyList()
        manifestProto?.let { mp ->
            runCatching {
                val editor = ProtoManifestEditor.parse(mp)
                pkg = editor.getManifestAttr("package")
                versionName = editor.getManifestAttr("versionName")
                versionCode = editor.getManifestAttr("versionCode")?.toIntOrNull()
                label = editor.getChildAttr("application", "label")
                minSdk = editor.getChildAttr("uses-sdk", "minSdkVersion")?.toIntOrNull()
                targetSdk = editor.getChildAttr("uses-sdk", "targetSdkVersion")?.toIntOrNull()
                permissions = editor.getPermissions()
            }
        }
        return build("AAB", pkg, versionName, versionCode, label, minSdk, targetSdk, permissions, entries) {
            it.name.endsWith(".dex")
        }
    }

    private fun build(
        kind: String,
        pkg: String?,
        versionName: String?,
        versionCode: Int?,
        label: String?,
        minSdk: Int?,
        targetSdk: Int?,
        permissions: List<String>,
        entries: List<EntrySize>,
        isDex: (EntrySize) -> Boolean,
    ) = ArtifactInfo(
        kind = kind,
        packageId = pkg,
        versionName = versionName,
        versionCode = versionCode,
        label = label,
        minSdk = minSdk,
        targetSdk = targetSdk,
        permissions = permissions,
        totalUncompressedSize = entries.sumOf { it.size },
        fileCount = entries.size,
        dexCount = entries.count(isDex),
        largestEntries = entries.sortedByDescending { it.size }.take(15),
    )
}
