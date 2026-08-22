package com.apkbuilder.core

import com.android.apksig.ApkSigner as AndroidApkSigner
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/** Signs an assembled (unsigned) APK with v1+v2+v3 signature schemes via Google's `apksig` library. */
object ApkSigner {

    fun sign(unsignedApkBytes: ByteArray, keystore: GeneratedKeystore, minSdkVersion: Int = 24): ByteArray {
        BcProvider.ensureInstalled()
        val ks = KeyStore.getInstance("PKCS12", BouncyCastleProvider.PROVIDER_NAME)
        ks.load(ByteArrayInputStream(keystore.pkcs12Bytes), keystore.storePassword.toCharArray())
        val privateKey = ks.getKey(keystore.alias, keystore.keyPassword.toCharArray()) as PrivateKey
        val cert = ks.getCertificate(keystore.alias) as X509Certificate

        val signerConfig = AndroidApkSigner.SignerConfig.Builder(
            "signer",
            privateKey,
            listOf(cert),
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
