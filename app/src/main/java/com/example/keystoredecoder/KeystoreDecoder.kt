package com.example.keystoredecoder

import android.util.Base64
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Key
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Provider
import java.security.PublicKey
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.security.interfaces.DSAPrivateKey
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
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
        val certificates: List<CertInfo>,
        /** The decrypted private key, if this is a key entry and it could be recovered. */
        val privateKey: PrivateKeyInfo? = null,
        /** Explains why a private key is present but not shown (e.g. wrong password). */
        val privateKeyNote: String? = null
    )

    data class PrivateKeyInfo(
        val algorithm: String,
        val format: String,
        val sizeBits: Int?,
        /** PKCS#8 PEM ("-----BEGIN PRIVATE KEY-----"). */
        val pem: String
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
        /** The public key as X.509 SubjectPublicKeyInfo PEM. */
        val publicKeyPem: String,
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
                var keyInfo: PrivateKeyInfo? = null
                var keyNote: String? = null
                if (e.isKeyEntry && e.encryptedKeyBlob != null) {
                    when {
                        password == null || password.isEmpty() ->
                            keyNote = "🔑 A private key is stored here. Enter the " +
                                "key password to decrypt and display it."
                        else -> try {
                            val pkcs8 = JksKeyProtector.recover(e.encryptedKeyBlob, password)
                            val alg = e.certificates.firstOrNull()
                                ?.publicKey?.algorithm
                            keyInfo = buildPrivateKeyInfo(pkcs8, alg)
                        } catch (_: JksKeyProtector.WrongKeyPasswordException) {
                            keyNote = "🔑 A private key is stored here, but it could " +
                                "not be decrypted with this password (in JKS the key " +
                                "password can differ from the store password)."
                        } catch (t: Throwable) {
                            keyNote = "🔑 A private key is stored here but could not be " +
                                "decoded: ${t.message}"
                        }
                    }
                }
                EntryInfo(
                    alias = e.alias,
                    entryType = if (e.isKeyEntry)
                        "PrivateKey entry" else "Trusted certificate entry",
                    creationDate = if (e.timestampMillis > 0)
                        formatDate(Date(e.timestampMillis)) else null,
                    certificates = e.certificates.map { describeCertificate(it) },
                    privateKey = keyInfo,
                    privateKeyNote = keyNote
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
                return Result.Success(readProviderKeystore(ks, type, bcProvider.name, password))
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
        provider: String,
        password: CharArray?
    ): KeystoreInfo {
        val entries = mutableListOf<EntryInfo>()
        val aliases = ks.aliases()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            val isKey = ks.isKeyEntry(alias)
            val entryType = when {
                isKey -> "PrivateKey / SecretKey entry"
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

            var keyInfo: PrivateKeyInfo? = null
            var keyNote: String? = null
            if (isKey) {
                try {
                    val key: Key? = ks.getKey(alias, password ?: CharArray(0))
                    if (key is PrivateKey && key.encoded != null) {
                        keyInfo = buildPrivateKeyInfo(key.encoded, key.algorithm)
                    } else if (key != null) {
                        keyNote = "🔑 A ${key.algorithm} secret key is stored here " +
                            "(format: ${key.format ?: "raw"})."
                    }
                } catch (t: Throwable) {
                    keyNote = "🔑 A private key is stored here but could not be " +
                        "decrypted with this password."
                }
            }

            entries.add(
                EntryInfo(
                    alias = alias,
                    entryType = entryType,
                    creationDate = creationDate,
                    certificates = certs.map { describeCertificate(it) },
                    privateKey = keyInfo,
                    privateKeyNote = keyNote
                )
            )
        }
        return KeystoreInfo(type, provider, entries.size, entries)
    }

    /**
     * Builds a [PrivateKeyInfo] from PKCS#8 bytes. [algorithmHint] (from the
     * matching certificate) is tried first; otherwise common algorithms are
     * attempted so we can still report the key size.
     */
    private fun buildPrivateKeyInfo(pkcs8: ByteArray, algorithmHint: String?): PrivateKeyInfo {
        val spec = PKCS8EncodedKeySpec(pkcs8)
        val candidates = buildList {
            algorithmHint?.let { add(it) }
            addAll(listOf("RSA", "EC", "DSA"))
        }.distinct()

        var key: PrivateKey? = null
        for (alg in candidates) {
            try {
                key = KeyFactory.getInstance(alg).generatePrivate(spec)
                break
            } catch (_: Throwable) {
                // try next algorithm
            }
        }

        return if (key != null) {
            PrivateKeyInfo(
                algorithm = key.algorithm,
                format = key.format ?: "PKCS#8",
                sizeBits = privateKeySize(key),
                pem = toPem(key.encoded ?: pkcs8)
            )
        } else {
            // Could not reconstruct a typed key; still expose the raw PKCS#8.
            PrivateKeyInfo(
                algorithm = algorithmHint ?: "Unknown",
                format = "PKCS#8",
                sizeBits = null,
                pem = toPem(pkcs8)
            )
        }
    }

    private fun privateKeySize(key: PrivateKey): Int? = when (key) {
        is RSAPrivateKey -> key.modulus.bitLength()
        is ECPrivateKey -> key.params?.curve?.field?.fieldSize
        is DSAPrivateKey -> key.params?.p?.bitLength()
        else -> null
    }

    private fun toPem(der: ByteArray, label: String = "PRIVATE KEY"): String {
        val b64 = Base64.encodeToString(der, Base64.NO_WRAP)
        val sb = StringBuilder("-----BEGIN $label-----\n")
        var i = 0
        while (i < b64.length) {
            sb.append(b64, i, minOf(i + 64, b64.length)).append('\n')
            i += 64
        }
        sb.append("-----END $label-----")
        return sb.toString()
    }

    private fun describeCertificate(cert: Certificate): CertInfo {
        val encoded = cert.encoded
        val publicKeyPem = cert.publicKey.encoded
            ?.let { toPem(it, "PUBLIC KEY") } ?: "(public key not available)"
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
                publicKeyPem = publicKeyPem,
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
            publicKeyPem = publicKeyPem,
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
