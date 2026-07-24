package com.example.keystoredecoder

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Provider
import java.security.PublicKey
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reads a Java/Android keystore file and extracts its contents in a
 * human-readable form, similar to `keytool -list -v`.
 *
 * Supported formats:
 *   - JKS / JCEKS  → parsed by [JksParser] (no provider ships these on Android)
 *   - PKCS12 (.p12/.pfx), BKS, BCFKS → parsed via a BouncyCastle provider
 */
object KeystoreDecoder {

    private val bcProvider: Provider = BouncyCastleProvider()

    sealed class Result {
        data class Success(val info: KeystoreInfo) : Result()
        /** A password is required (encrypted) or the supplied one was wrong. */
        data class PasswordRequired(val message: String) : Result()
        data class Failure(val message: String) : Result()
    }

    data class KeystoreInfo(
        val detectedType: String,
        val provider: String,
        val entryCount: Int,
        val entries: List<EntryInfo>,
        /** Optional note about password/integrity status. */
        val note: String? = null
    )

    data class EntryInfo(
        val alias: String,
        val entryType: String,
        val creationDate: String?,
        val certificates: List<CertInfo>
    )

    data class CertInfo(
        val subject: String,
        val issuer: String,
        val serialNumber: String,
        val validFrom: String,
        val validUntil: String,
        val expired: Boolean,
        val signatureAlgorithm: String,
        val publicKeyInfo: String,
        val version: Int,
        val sha256: String,
        val sha1: String,
        val md5: String
    )

    fun decode(bytes: ByteArray, password: CharArray?): Result {
        // JKS / JCEKS: no provider on Android can read these, use our parser.
        if (JksParser.isJksOrJceks(bytes)) {
            return decodeJks(bytes, password)
        }
        return decodeWithProvider(bytes, password)
    }

    private fun decodeJks(bytes: ByteArray, password: CharArray?): Result {
        return try {
            val parsed = JksParser.parse(bytes, password)
            val entries = parsed.entries.map { e ->
                EntryInfo(
                    alias = e.alias,
                    entryType = if (e.isKeyEntry)
                        "PrivateKey entry" else "Trusted certificate entry",
                    creationDate = if (e.timestampMillis > 0)
                        formatDate(Date(e.timestampMillis)) else null,
                    certificates = e.certificates.map { describeCertificate(it) }
                )
            }
            val note = when {
                parsed.integrityFailed ->
                    "⚠️ Password did NOT match this keystore's integrity hash. " +
                        "Certificates below are still valid (they are stored " +
                        "unencrypted), but the password is incorrect."
                parsed.integrityVerified ->
                    "🔓 Password verified — keystore integrity OK."
                else ->
                    "ℹ️ No password entered. Certificates were read without " +
                        "verifying the keystore password."
            }
            Result.Success(
                KeystoreInfo(
                    detectedType = parsed.type,
                    provider = "built-in JKS parser",
                    entryCount = entries.size,
                    entries = entries,
                    note = note
                )
            )
        } catch (t: Throwable) {
            Result.Failure("Failed to parse JKS/JCEKS keystore: ${t.message}")
        }
    }

    private fun decodeWithProvider(bytes: ByteArray, password: CharArray?): Result {
        val typesToTry = listOf("PKCS12", "BKS", "BCFKS")
        var lastError: Throwable? = null
        var sawPasswordProblem = false

        for (type in typesToTry) {
            try {
                val ks = KeyStore.getInstance(type, bcProvider)
                bytes.inputStream().use { input -> ks.load(input, password) }
                return Result.Success(readProviderKeystore(ks, type, bcProvider.name))
            } catch (t: Throwable) {
                lastError = t
                if (isPasswordProblem(t)) sawPasswordProblem = true
            }
        }

        return when {
            sawPasswordProblem -> Result.PasswordRequired(
                "Wrong password, or a password is required to open this keystore."
            )
            else -> Result.Failure(
                "Could not read this file as a keystore.\n" +
                    (lastError?.message ?: "Unknown error") +
                    "\n\nSupported: JKS, PKCS12 (.p12/.pfx), BKS, BCFKS, JCEKS."
            )
        }
    }

    private fun isPasswordProblem(t: Throwable): Boolean {
        var e: Throwable? = t
        while (e != null) {
            val m = e.message?.lowercase() ?: ""
            if (m.contains("password") ||
                m.contains("integrity check") ||
                m.contains("mac check") ||
                m.contains("tampered") ||
                (m.contains("wrong") && m.contains("pad"))
            ) return true
            e = e.cause
        }
        return false
    }

    private fun readProviderKeystore(
        ks: KeyStore,
        type: String,
        provider: String
    ): KeystoreInfo {
        val entries = mutableListOf<EntryInfo>()
        val aliases = ks.aliases()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            val entryType = when {
                ks.isKeyEntry(alias) -> "PrivateKey / SecretKey entry"
                ks.isCertificateEntry(alias) -> "Trusted certificate entry"
                else -> "Unknown"
            }
            val creationDate = try {
                ks.getCreationDate(alias)?.let { formatDate(it) }
            } catch (_: Throwable) {
                null
            }
            val chain: Array<Certificate>? = try {
                ks.getCertificateChain(alias)
            } catch (_: Throwable) {
                null
            }
            val certs: List<Certificate> = when {
                !chain.isNullOrEmpty() -> chain.toList()
                else -> ks.getCertificate(alias)?.let { listOf(it) } ?: emptyList()
            }
            entries.add(
                EntryInfo(
                    alias = alias,
                    entryType = entryType,
                    creationDate = creationDate,
                    certificates = certs.map { describeCertificate(it) }
                )
            )
        }
        return KeystoreInfo(type, provider, entries.size, entries)
    }

    private fun describeCertificate(cert: Certificate): CertInfo {
        val encoded = cert.encoded
        if (cert is X509Certificate) {
            val now = System.currentTimeMillis()
            return CertInfo(
                subject = cert.subjectX500Principal.name,
                issuer = cert.issuerX500Principal.name,
                serialNumber = cert.serialNumber.toString(16).uppercase(),
                validFrom = formatDate(cert.notBefore),
                validUntil = formatDate(cert.notAfter),
                expired = cert.notAfter.time < now || cert.notBefore.time > now,
                signatureAlgorithm = cert.sigAlgName,
                publicKeyInfo = describeKey(cert.publicKey),
                version = cert.version,
                sha256 = fingerprint(encoded, "SHA-256"),
                sha1 = fingerprint(encoded, "SHA-1"),
                md5 = fingerprint(encoded, "MD5")
            )
        }
        return CertInfo(
            subject = "(non-X.509 certificate: ${cert.type})",
            issuer = "-",
            serialNumber = "-",
            validFrom = "-",
            validUntil = "-",
            expired = false,
            signatureAlgorithm = "-",
            publicKeyInfo = describeKey(cert.publicKey),
            version = 0,
            sha256 = fingerprint(encoded, "SHA-256"),
            sha1 = fingerprint(encoded, "SHA-1"),
            md5 = fingerprint(encoded, "MD5")
        )
    }

    private fun describeKey(key: PublicKey): String = when (key) {
        is RSAPublicKey -> "RSA ${key.modulus.bitLength()}-bit"
        is ECPublicKey -> "EC ${key.params?.curve?.field?.fieldSize ?: "?"}-bit"
        else -> "${key.algorithm} key"
    }

    private fun fingerprint(data: ByteArray, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm).digest(data)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
    private fun formatDate(date: Date): String = dateFormat.format(date)
}
