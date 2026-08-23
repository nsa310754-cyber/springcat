package com.apkbuilder.core.aab

import com.apkbuilder.core.BuildConfig
import com.apkbuilder.core.proto.ProtoManifestEditor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The Android App Bundle (.aab) counterpart of
 * [com.apkbuilder.core.ApkAssembler]: patches a prebuilt template bundle's
 * proto-encoded manifest (package/version/label, permissions, AdMob
 * meta-data) and swaps in the caller's game assets and launcher icons, then
 * returns an unsigned bundle to be handed to [JarSigner].
 *
 * The template bundle lays every module file under `base/`, so the APK-relative
 * override paths the rest of the app already uses (e.g. `assets/game.html`,
 * `res/mipmap-hdpi-v4/ic_launcher.png`) are simply prefixed with `base/`.
 */
object AabAssembler {

    private const val BASE = "base/"
    private const val MANIFEST_PATH = "base/manifest/AndroidManifest.xml"

    fun assemble(
        templateAabBytes: ByteArray,
        config: BuildConfig,
        apkRelativeOverrides: Map<String, ByteArray>,
        apkRelativeOmit: Set<String> = emptySet(),
    ): ByteArray {
        val overrides = apkRelativeOverrides.mapKeys { (k, _) -> BASE + k }
        val omit = apkRelativeOmit.map { BASE + it }.toSet()

        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(templateAabBytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory) entries[e.name] = zis.readBytes()
                e = zis.nextEntry
            }
        }

        val manifestProto = entries[MANIFEST_PATH]
            ?: error("template AAB has no $MANIFEST_PATH")
        val editor = ProtoManifestEditor.parse(manifestProto)
        editor.setPackage(config.packageId)
        editor.setVersionName(config.versionName)
        editor.setVersionCode(config.versionCode)
        editor.setApplicationLabel(config.appLabel)
        for (permission in config.permissions) editor.addUsesPermission(permission)
        if (config.admobApplicationId != null) {
            editor.addApplicationMetaData("com.google.android.gms.ads.APPLICATION_ID", config.admobApplicationId)
        }
        entries[MANIFEST_PATH] = editor.toByteArray()

        for (path in omit) entries.remove(path)
        for ((path, bytes) in overrides) entries[path] = bytes

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
