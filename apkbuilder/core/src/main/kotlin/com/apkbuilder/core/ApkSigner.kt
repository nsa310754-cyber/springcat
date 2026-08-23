package com.apkbuilder.core

import com.android.apksig.ApkSigner as AndroidApkSigner
import java.io.File

/** Signs an assembled (unsigned) APK with v1+v2+v3 signature schemes via Google's `apksig` library. */
object ApkSigner {

    fun sign(unsignedApkBytes: ByteArray, signingKey: SigningKey, minSdkVersion: Int = 24): ByteArray {
        BcProvider.ensureInstalled()
        val signerConfig = AndroidApkSigner.SignerConfig.Builder(
            "signer",
            signingKey.privateKey,
            listOf(signingKey.certificate),
        ).build()

        val inputFile = File.createTempFile("apkbuilder-unsigned", ".apk")
        val outputFile = File.createTempFile("apkbuilder-signed", ".apk")
        try {
            inputFile.writeBytes(unsignedApkBytes)

            AndroidApkSigner.Builder(listOf(signerConfig))
                .setInputApk(inputFile)
                .setOutputApk(outputFile)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setMinSdkVersion(minSdkVersion)
                .build()
                .sign()

            return outputFile.readBytes()
        } finally {
            inputFile.delete()
            outputFile.delete()
        }
    }
}
