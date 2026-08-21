package com.apkbuilder.core

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit

data class GeneratedKeystore(
    val pkcs12Bytes: ByteArray,
    val alias: String,
    val storePassword: String,
    val keyPassword: String,
)

/**
 * Generates a fresh self-signed RSA-2048 signing identity and a PKCS12
 * keystore holding it — the same shape `keytool -genkeypair` would produce,
 * without shelling out to the `keytool` CLI (unavailable on-device).
 */
object KeystoreGenerator {
    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    fun generate(
        commonName: String,
        alias: String = "release",
        storePassword: String = randomPassword(),
        keyPassword: String = storePassword,
        validityYears: Int = 27,
    ): GeneratedKeystore {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()

        val now = Date()
        val notAfter = Date(now.time + TimeUnit.DAYS.toMillis(365L * validityYears))
        val subject = X500Name("CN=$commonName")
        val serial = BigInteger(160, SecureRandom())

        val certBuilder = JcaX509v3CertificateBuilder(subject, serial, now, notAfter, subject, keyPair.public)
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val cert: X509Certificate = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(certBuilder.build(signer))

        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, null)
        keyStore.setKeyEntry(alias, keyPair.private, keyPassword.toCharArray(), arrayOf(cert))

        val out = ByteArrayOutputStream()
        keyStore.store(out, storePassword.toCharArray())

        return GeneratedKeystore(out.toByteArray(), alias, storePassword, keyPassword)
    }

    private fun randomPassword(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val random = SecureRandom()
        return (1..20).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }
}
