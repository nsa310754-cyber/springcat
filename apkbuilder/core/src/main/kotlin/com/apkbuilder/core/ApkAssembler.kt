package com.apkbuilder.core

import com.apkbuilder.core.axml.AxmlDocument
import com.apkbuilder.core.zip.RawZipReader
import com.apkbuilder.core.zip.ZipOutEntry
import com.apkbuilder.core.zip.ZipWriter

data class BuildConfig(
    val appLabel: String,
    val packageId: String,
    val versionName: String,
    val versionCode: Int,
    /** e.g. "android.permission.CAMERA" */
    val permissions: List<String>,
)

/**
 * Turns the bundled generic WebView template APK into a new, unsigned,
 * caller-branded APK: swaps in the caller's web content and launcher icon,
 * rewrites the app identity/version, and declares the requested permissions —
 * all via direct ZIP + binary-manifest edits, no `aapt2`/`d8` needed on-device.
 */
object ApkAssembler {

    /**
     * @param templateApkBytes the bundled generic template (see :app assets/template.apk)
     * @param fileOverrides exact in-APK paths to replace or append, e.g.
     *   "assets/game.html" -> web page bytes, "res/mipmap-hdpi-v4/ic_launcher.png" -> resized icon bytes
     */
    fun assemble(
        templateApkBytes: ByteArray,
        config: BuildConfig,
        fileOverrides: Map<String, ByteArray>,
    ): ByteArray {
        val records = RawZipReader.read(templateApkBytes)
        val manifestRecord = records.find { it.name == "AndroidManifest.xml" }
            ?: error("template APK has no AndroidManifest.xml")

        val axml = AxmlDocument.parse(manifestRecord.inflatedBytes())
        axml.setStringAttr("manifest", "package", config.packageId)
        axml.setStringAttr("manifest", "versionName", config.versionName)
        axml.setIntAttr("manifest", "versionCode", config.versionCode)
        axml.setApplicationLabel(config.appLabel)
        for (permission in config.permissions) axml.addUsesPermission(permission)
        val newManifestBytes = axml.toByteArray()

        val usedOverrides = HashSet<String>()
        val outEntries = ArrayList<ZipOutEntry>(records.size + fileOverrides.size)

        for (record in records) {
            when {
                record.name == "AndroidManifest.xml" ->
                    outEntries.add(ZipOutEntry.Fresh(record.name, newManifestBytes, store = false))
                record.name.startsWith("META-INF/") -> {
                    // Drop the template's own signing/build metadata; a fresh
                    // signature is applied afterwards by ApkSigner.
                }
                fileOverrides.containsKey(record.name) -> {
                    outEntries.add(ZipOutEntry.Fresh(record.name, fileOverrides.getValue(record.name), store = false))
                    usedOverrides.add(record.name)
                }
                else -> outEntries.add(ZipOutEntry.Passthrough(record.name, record))
            }
        }
        for ((path, bytes) in fileOverrides) {
            if (path !in usedOverrides) outEntries.add(ZipOutEntry.Fresh(path, bytes, store = false))
        }

        return ZipWriter.write(outEntries)
    }
}
