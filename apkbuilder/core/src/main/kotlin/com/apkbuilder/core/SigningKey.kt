package com.apkbuilder.core

import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * A resolved signing identity (private key + certificate) usable by both the
 * APK signer (apksig) and the AAB JAR signer. Produced either freshly by
 * [KeystoreGenerator] or by loading a user-supplied keystore via
 * [KeystoreLoader] — the latter is what makes app *updates* possible, since a
 * new version must be signed with the same key as the installed one.
 */
class SigningKey(
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
    val alias: String,
) {
    val certificateSha256Fingerprint: String by lazy {
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString(":") { "%02X".format(it) }
    }
}
