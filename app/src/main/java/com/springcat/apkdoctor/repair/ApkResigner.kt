package com.springcat.apkdoctor.repair

import com.android.apksig.ApkSigner
import java.io.File

/** Re-signs an APK with v1 + v2 + v3 signatures using [SigningIdentity]. */
object ApkResigner {

    /**
     * Devices with 16 KB memory pages need shared libraries aligned to that
     * boundary; 16 KB alignment also satisfies the older 4 KB requirement.
     */
    private const val LIBRARY_PAGE_ALIGNMENT = 16 * 1024

    fun sign(input: File, output: File, identity: SigningIdentity, minSdk: Int) {
        val signerConfig = ApkSigner.SignerConfig.Builder(
            "apkdoctor",
            identity.privateKey,
            listOf(identity.certificate),
        ).build()

        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(input)
            .setOutputApk(output)
            // v1 covers pre-24 devices; v2/v3 are what Android 7+ actually checks
            // and what Android 11+ requires for targetSdk 30+ apps.
            .setMinSdkVersion(minSdk.coerceIn(1, 10_000))
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setV4SigningEnabled(false)
            // Signing a debuggable APK is legitimate here: we sign whatever the
            // user brought, not only release builds.
            .setDebuggableApkPermitted(true)
            // Let apksig re-align uncompressed entries in the output.
            .setAlignmentPreserved(false)
            .setLibraryPageAlignmentBytes(LIBRARY_PAGE_ALIGNMENT)
            .setCreatedBy("APK Doctor")
            .build()
            .sign()
    }
}
