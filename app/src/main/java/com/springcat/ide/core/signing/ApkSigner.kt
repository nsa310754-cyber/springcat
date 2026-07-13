package com.springcat.ide.core.signing

import com.android.apksig.ApkSigner
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Result of a signing operation, surfaced to the UI.
 */
sealed interface SigningResult {
    data class Success(val outputFile: File, val schemes: String) : SigningResult
    data class Failure(val message: String) : SigningResult
}

/**
 * Signs existing APK packages with a keystore entry using Google's official
 * `apksig` library — the same engine behind `apksigner` in the Android SDK.
 *
 * Enables APK Signature Scheme v1 (JAR), v2 and v3 so the output installs on
 * every supported Android version.
 */
class ApkSignerService {

    /**
     * @param inputApk an unsigned (or previously signed) APK on disk
     * @param outputApk destination; may equal a temp file the caller then copies out
     */
    fun sign(
        inputApk: File,
        outputApk: File,
        keyStore: KeyStore,
        alias: String,
        keyPassword: CharArray,
        minSdk: Int = 24,
    ): SigningResult {
        return try {
            val privateKey = keyStore.getKey(alias, keyPassword) as? PrivateKey
                ?: return SigningResult.Failure("No private key found for alias '$alias'")

            val chain = keyStore.getCertificateChain(alias)
                ?.filterIsInstance<X509Certificate>()
                ?.takeIf { it.isNotEmpty() }
                ?: return SigningResult.Failure("No certificate chain found for alias '$alias'")

            val signerConfig = ApkSigner.SignerConfig.Builder(
                "CERT",
                privateKey,
                chain,
            ).build()

            ApkSigner.Builder(listOf(signerConfig))
                .setInputApk(inputApk)
                .setOutputApk(outputApk)
                .setMinSdkVersion(minSdk)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign()

            SigningResult.Success(outputApk, "v1 + v2 + v3")
        } catch (t: Throwable) {
            SigningResult.Failure(t.message ?: t.javaClass.simpleName)
        }
    }
}
