package com.example.fixapk

import android.content.Context
import com.android.apksig.ApkSigner
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * The whole "old apk -> installable apk" pipeline, on-device:
 *
 *  1. bump the manifest so Android 14/15 stop rejecting it as too old,
 *  2. strip the stale v1 signature, re-deflate every entry, and
 *  3. re-sign with v1+v2+v3 using [com.android.apksig.ApkSigner] and the
 *     keystore bundled in assets.
 */
object ApkFixer {

    /** Android 14 needs target >= 23, Android 15 >= 24. 24 covers both and
     *  stays below the 30 threshold that would demand v2-only. */
    const val TARGET_FLOOR = 24

    private const val KEYSTORE_ASSET = "fixapk.p12"
    private const val KEYSTORE_PASS = "fixapk123"
    private const val KEY_ALIAS = "fixapk"

    data class Result(
        val output: File,
        val oldMinSdk: Int?,
        val oldTargetSdk: Int?,
        val newTargetSdk: Int,
        val note: String,
        val strippedSignatures: List<String>,
    )

    private fun isSignatureEntry(name: String): Boolean {
        val upper = name.uppercase()
        if (!upper.startsWith("META-INF/")) return false
        return upper == "META-INF/MANIFEST.MF" ||
            upper.endsWith(".SF") || upper.endsWith(".RSA") ||
            upper.endsWith(".DSA") || upper.endsWith(".EC")
    }

    /** Produce an unsigned, manifest-patched APK; returns metadata for the UI. */
    private fun repackage(input: File, unsignedOut: File): Result {
        ZipFile(input).use { zin ->
            val manifestEntry = zin.getEntry("AndroidManifest.xml")
                ?: throw AxmlPatcher.AxmlException("AndroidManifest.xml が見つかりません(APKではない?)")
            val manifestBytes = zin.getInputStream(manifestEntry).use { it.readBytes() }
            val patch = AxmlPatcher.patchToFloor(manifestBytes, TARGET_FLOOR)

            val stripped = ArrayList<String>()
            ZipOutputStream(FileOutputStream(unsignedOut)).use { zout ->
                val entries = zin.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.isDirectory) continue
                    val name = e.name
                    if (isSignatureEntry(name)) {
                        stripped.add(name)
                        continue
                    }
                    val data =
                        if (name == "AndroidManifest.xml") patch.bytes
                        else zin.getInputStream(e).use { it.readBytes() }
                    // Re-deflate everything: compressed entries need no zipalign,
                    // which keeps v2/v3 signing dependency-free on-device.
                    val ne = ZipEntry(name)
                    ne.method = ZipEntry.DEFLATED
                    zout.putNextEntry(ne)
                    zout.write(data)
                    zout.closeEntry()
                }
            }
            return Result(
                output = unsignedOut,
                oldMinSdk = patch.oldMinSdk,
                oldTargetSdk = patch.oldTargetSdk,
                newTargetSdk = patch.newTargetSdk,
                note = patch.note,
                strippedSignatures = stripped,
            )
        }
    }

    private fun loadSignerConfig(context: Context): ApkSigner.SignerConfig {
        val ks = KeyStore.getInstance("PKCS12")
        context.assets.open(KEYSTORE_ASSET).use { ks.load(it, KEYSTORE_PASS.toCharArray()) }
        val key = ks.getKey(KEY_ALIAS, KEYSTORE_PASS.toCharArray()) as PrivateKey
        val cert = ks.getCertificate(KEY_ALIAS) as X509Certificate
        return ApkSigner.SignerConfig.Builder(KEY_ALIAS, key, listOf(cert)).build()
    }

    /**
     * Convert [input] into an installable APK written to [output].
     * Runs on a background thread — do not call on the UI thread.
     */
    fun fix(context: Context, input: File, output: File): Result {
        val unsigned = File(context.cacheDir, "unsigned-${System.currentTimeMillis()}.apk")
        try {
            val meta = repackage(input, unsigned)
            output.parentFile?.mkdirs()
            if (output.exists()) output.delete()

            val signerConfig = loadSignerConfig(context)
            ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(unsigned)
                .setOutputApk(output)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign()

            return meta.copy(output = output)
        } finally {
            unsigned.delete()
        }
    }
}
