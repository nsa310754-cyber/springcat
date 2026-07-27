package com.springcat.apkdoctor.repair

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date

data class SigningIdentity(
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
) {
    val fingerprintSha256: String
        get() = com.springcat.apkdoctor.apk.Digests.sha256(certificate.encoded)
}

/** Generates a fresh self-signed RSA identity; usable without an Android Context. */
object SelfSignedIdentity {
    private const val KEY_SIZE = 2048
    private const val ONE_DAY_MS = 24L * 60 * 60 * 1000
    private const val THIRTY_YEARS_MS = 30L * 365 * 24 * 60 * 60 * 1000

    fun generate(): Pair<SigningIdentity, java.security.KeyPair> {
        val provider = BouncyCastleProvider()
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_SIZE) }.generateKeyPair()

        val now = System.currentTimeMillis()
        val subject = X500Name("CN=APK Doctor, O=APK Doctor, C=JP")
        val certificate = JcaX509CertificateConverter()
            .setProvider(provider)
            .getCertificate(
                JcaX509v3CertificateBuilder(
                    /* issuer = */ subject,
                    /* serial = */ BigInteger(64, SecureRandom()).abs(),
                    /* notBefore = */ Date(now - ONE_DAY_MS),
                    /* notAfter = */ Date(now + THIRTY_YEARS_MS),
                    /* subject = */ subject,
                    /* publicKey = */ keyPair.public,
                ).build(
                    JcaContentSignerBuilder("SHA256withRSA")
                        .setProvider(provider)
                        .build(keyPair.private),
                ),
            )
        return SigningIdentity(keyPair.private, certificate) to keyPair
    }
}

/**
 * The self-signed key used to re-sign repaired APKs.
 *
 * It is generated once per install and kept in the app's private storage, so
 * every APK this app repairs shares one signer: repairing the same app twice
 * still produces an upgradable pair. The keystore password is a constant
 * because the file never leaves private app storage, where it is already
 * protected by the app sandbox.
 */
class SigningKeys(context: Context) {

    private val keystoreFile = File(context.filesDir, KEYSTORE_NAME)

    @Synchronized
    fun loadOrCreate(): SigningIdentity {
        cached?.let { return it }
        val identity = if (keystoreFile.exists()) {
            runCatching { load() }.getOrElse { create() }
        } else {
            create()
        }
        cached = identity
        return identity
    }

    private fun load(): SigningIdentity {
        val store = KeyStore.getInstance(KEYSTORE_TYPE)
        keystoreFile.inputStream().use { store.load(it, PASSWORD) }
        val key = store.getKey(ALIAS, PASSWORD) as PrivateKey
        val certificate = store.getCertificate(ALIAS) as X509Certificate
        return SigningIdentity(key, certificate)
    }

    private fun create(): SigningIdentity {
        val (identity, keyPair) = SelfSignedIdentity.generate()

        val store = KeyStore.getInstance(KEYSTORE_TYPE)
        store.load(null, null)
        store.setKeyEntry(ALIAS, keyPair.private, PASSWORD, arrayOf(identity.certificate))
        keystoreFile.outputStream().use { store.store(it, PASSWORD) }

        return identity
    }

    private companion object {
        const val KEYSTORE_NAME = "repair-signing-key.p12"
        const val KEYSTORE_TYPE = "PKCS12"
        const val ALIAS = "apkdoctor"
        val PASSWORD = "apkdoctor".toCharArray()

        @Volatile
        var cached: SigningIdentity? = null
    }
}
