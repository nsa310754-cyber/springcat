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
    private fun fingerprint(algorithm: String): String =
        MessageDigest.getInstance(algorithm).digest(certificate.encoded).joinToString(":") { "%02X".format(it) }

    /** Colon-separated uppercase hex (e.g. for assetlinks.json / Play). */
    val certificateSha256Fingerprint: String by lazy { fingerprint("SHA-256") }
    val certificateSha1Fingerprint: String by lazy { fingerprint("SHA-1") }
    val certificateMd5Fingerprint: String by lazy { fingerprint("MD5") }

    /** The certificate subject DN (e.g. "CN=My App"), as Android Studio's signing report shows. */
    val subject: String get() = certificate.subjectX500Principal.name
    val validUntil: String get() = certificate.notAfter.toString()
}
