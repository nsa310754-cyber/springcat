package com.springcat.ide.core.keystore

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Parameters for a signing identity, mirroring the fields `keytool` asks for.
 */
data class DistinguishedName(
    val commonName: String,
    val organizationalUnit: String = "",
    val organization: String = "",
    val locality: String = "",
    val state: String = "",
    val country: String = "",
) {
    /** RFC 4514 string, skipping blank components. */
    fun toX500(): String = buildList {
        if (commonName.isNotBlank()) add("CN=${commonName.trim()}")
        if (organizationalUnit.isNotBlank()) add("OU=${organizationalUnit.trim()}")
        if (organization.isNotBlank()) add("O=${organization.trim()}")
        if (locality.isNotBlank()) add("L=${locality.trim()}")
        if (state.isNotBlank()) add("ST=${state.trim()}")
        if (country.isNotBlank()) add("C=${country.trim()}")
    }.joinToString(", ")
}

enum class KeyAlgorithm(val jcaName: String, val defaultSize: Int, val signature: String) {
    RSA("RSA", 2048, "SHA256withRSA"),
    RSA_4096("RSA", 4096, "SHA256withRSA"),
    EC("EC", 256, "SHA256withECDSA"),
}

/** Everything the caller needs to persist and re-open a generated keystore. */
data class KeystoreRequest(
    val alias: String,
    val keystorePassword: CharArray,
    val keyPassword: CharArray,
    val dn: DistinguishedName,
    val validityYears: Int = 25,
    val algorithm: KeyAlgorithm = KeyAlgorithm.RSA,
    /** "PKCS12" (recommended) or "JKS" for legacy compatibility. */
    val storeType: String = "PKCS12",
)

data class GeneratedKeystore(
    val keyStore: KeyStore,
    val alias: String,
    val certificate: X509Certificate,
    val privateKey: java.security.PrivateKey,
)

/**
 * Creates Android-compatible signing keystores and self-signed X.509
 * certificates entirely on-device. Backed by BouncyCastle so it behaves the
 * same as the desktop `keytool` an Android developer is used to.
 */
class KeystoreManager {

    init {
        // Android ships a stripped-down provider under the name "BC" that lacks
        // the cert-builder classes we need. Only the full BouncyCastleProvider
        // will do, so replace whatever is registered unless it's already ours.
        val existing = Security.getProvider(PROVIDER)
        if (existing !is BouncyCastleProvider) {
            Security.removeProvider(PROVIDER)
            Security.addProvider(BouncyCastleProvider())
        }
    }

    /** Generate a fresh key pair + self-signed certificate inside a new keystore. */
    fun generate(request: KeystoreRequest): GeneratedKeystore {
        require(request.alias.isNotBlank()) { "Alias must not be blank" }
        require(request.dn.commonName.isNotBlank()) { "Certificate requires at least a Common Name (CN)" }
        require(request.validityYears in 1..1000) { "Validity must be between 1 and 1000 years" }

        val keyPair = generateKeyPair(request.algorithm)
        val certificate = selfSign(keyPair, request.dn, request.validityYears, request.algorithm)

        val keyStore = KeyStore.getInstance(request.storeType).apply {
            load(null, null)
            setKeyEntry(
                request.alias,
                keyPair.private,
                request.keyPassword,
                arrayOf(certificate),
            )
        }

        return GeneratedKeystore(keyStore, request.alias, certificate, keyPair.private)
    }

    /** Write a keystore to a stream (e.g. a SAF document) using its store password. */
    fun write(keyStore: KeyStore, out: OutputStream, password: CharArray) {
        out.use { keyStore.store(it, password) }
    }

    /** Export just the public certificate in DER form (a `.cer` / `.crt` file). */
    fun exportCertificateDer(certificate: X509Certificate, out: OutputStream) {
        out.use { it.write(certificate.encoded) }
    }

    /** Export the public certificate as PEM text (`-----BEGIN CERTIFICATE-----`). */
    fun exportCertificatePem(certificate: X509Certificate, out: OutputStream) {
        val base64 = java.util.Base64.getMimeEncoder(64, "\n".toByteArray())
            .encodeToString(certificate.encoded)
        val pem = "-----BEGIN CERTIFICATE-----\n$base64\n-----END CERTIFICATE-----\n"
        out.use { it.write(pem.toByteArray()) }
    }

    /** Load an existing keystore for signing/inspection. */
    fun load(bytes: ByteArray, storeType: String, password: CharArray): KeyStore =
        KeyStore.getInstance(storeType).apply {
            load(bytes.inputStream(), password)
        }

    private fun generateKeyPair(algorithm: KeyAlgorithm): KeyPair {
        val generator = KeyPairGenerator.getInstance(algorithm.jcaName, PROVIDER)
        generator.initialize(algorithm.defaultSize, SecureRandom())
        return generator.generateKeyPair()
    }

    private fun selfSign(
        keyPair: KeyPair,
        dn: DistinguishedName,
        validityYears: Int,
        algorithm: KeyAlgorithm,
    ): X509Certificate {
        val subject = X500Name(dn.toX500())
        val now = System.currentTimeMillis()
        val notBefore = Date(now)
        val notAfter = Date(now + validityYears * 365L * 24 * 60 * 60 * 1000)
        val serial = BigInteger(64, SecureRandom())

        val builder = JcaX509v3CertificateBuilder(
            subject, // issuer == subject for a self-signed cert
            serial,
            notBefore,
            notAfter,
            subject,
            keyPair.public,
        )

        val signer = JcaContentSignerBuilder(algorithm.signature)
            .setProvider(PROVIDER)
            .build(keyPair.private)

        return JcaX509CertificateConverter()
            .setProvider(PROVIDER)
            .getCertificate(builder.build(signer))
    }

    companion object {
        const val PROVIDER = "BC"
    }
}
