package com.example.keystoredecoder

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.CertificateFactory

/**
 * Minimal, dependency-free reader for Sun/Oracle **JKS** (and structurally
 * identical **JCEKS**) keystores, which no security provider on Android can
 * open.
 *
 * The certificate entries in a JKS file are stored in the clear, so aliases
 * and certificates (and therefore their SHA-1/SHA-256 fingerprints) can be
 * read without any password. The store password only protects the file's
 * SHA-1 integrity hash and the encrypted private keys; we verify that hash
 * when a password is supplied. Private keys themselves are not decrypted.
 *
 * Format reference (reverse-engineered, stable since JDK 1.2):
 *   magic(4) version(4) count(4)
 *   repeat count times:
 *     tag(4)  1=key entry, 2=trusted cert entry
 *     alias(UTF)  timestamp(8)
 *     tag==1: encKeyLen(4) encKey[] chainLen(4)
 *             repeat: [certType(UTF) if v2] certLen(4) cert[]
 *     tag==2: [certType(UTF) if v2] certLen(4) cert[]
 *   trailing 20 bytes: SHA-1 integrity hash
 */
object JksParser {

    const val MAGIC_JKS = 0xFEEDFEED.toInt()
    const val MAGIC_JCEKS = 0xCECECECE.toInt()

    private const val PRIVATE_KEY_TAG = 1
    private const val TRUSTED_CERT_TAG = 2

    data class ParsedEntry(
        val alias: String,
        val isKeyEntry: Boolean,
        val timestampMillis: Long,
        val certificates: List<Certificate>,
        /**
         * For key entries: the raw, still-encrypted protected private key
         * (a DER `EncryptedPrivateKeyInfo`). Decrypt with [JksKeyProtector].
         */
        val encryptedKeyBlob: ByteArray? = null
    )

    data class ParsedKeystore(
        val type: String,
        val entries: List<ParsedEntry>,
        /** True if a password was supplied and matched the integrity hash. */
        val integrityVerified: Boolean,
        /** True if a (non-empty) password was supplied but did not match. */
        val integrityFailed: Boolean
    )

    /** Peeks the 4-byte magic to decide if [bytes] is a JKS/JCEKS file. */
    fun magicOf(bytes: ByteArray): Int? {
        if (bytes.size < 4) return null
        return ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
    }

    fun isJksOrJceks(bytes: ByteArray): Boolean =
        magicOf(bytes).let { it == MAGIC_JKS || it == MAGIC_JCEKS }

    /**
     * @param password the store password to verify integrity with, or null
     *   to skip verification (certificates are still returned).
     */
    fun parse(bytes: ByteArray, password: CharArray?): ParsedKeystore {
        val magic = magicOf(bytes)
        val type = if (magic == MAGIC_JCEKS) "JCEKS" else "JKS"

        val cf = CertificateFactory.getInstance("X.509")
        val din = DataInputStream(ByteArrayInputStream(bytes))

        val readMagic = din.readInt()
        require(readMagic == MAGIC_JKS || readMagic == MAGIC_JCEKS) {
            "Not a JKS/JCEKS keystore"
        }
        val version = din.readInt()
        require(version == 1 || version == 2) { "Unsupported JKS version $version" }
        val count = din.readInt()
        require(count in 0..1_000_000) { "Corrupt keystore (entry count $count)" }

        val entries = ArrayList<ParsedEntry>(count)
        repeat(count) {
            val tag = din.readInt()
            val alias = din.readUTF()
            val timestamp = din.readLong()
            val certs = ArrayList<Certificate>()
            var keyBlob: ByteArray? = null

            when (tag) {
                PRIVATE_KEY_TAG -> {
                    val keyLen = din.readInt()
                    require(keyLen in 0..50_000_000) { "Corrupt key length $keyLen" }
                    keyBlob = ByteArray(keyLen).also { din.readFully(it) }
                    val chainLen = din.readInt()
                    repeat(chainLen) {
                        certs.add(readCert(din, version, cf))
                    }
                }
                TRUSTED_CERT_TAG -> {
                    certs.add(readCert(din, version, cf))
                }
                else -> throw IllegalStateException("Unknown JKS entry tag: $tag")
            }

            entries.add(
                ParsedEntry(
                    alias = alias,
                    isKeyEntry = tag == PRIVATE_KEY_TAG,
                    timestampMillis = timestamp,
                    certificates = certs,
                    encryptedKeyBlob = keyBlob
                )
            )
        }

        // Integrity verification (only if the caller supplied a password).
        var verified = false
        var failed = false
        if (password != null && bytes.size > 20) {
            val expected = bytes.copyOfRange(bytes.size - 20, bytes.size)
            val computed = computeIntegrityHash(password, bytes, bytes.size - 20)
            if (computed.contentEquals(expected)) {
                verified = true
            } else if (password.isNotEmpty()) {
                failed = true
            }
        }

        return ParsedKeystore(type, entries, verified, failed)
    }

    private fun readCert(
        din: DataInputStream,
        version: Int,
        cf: CertificateFactory
    ): Certificate {
        if (version == 2) {
            din.readUTF() // certificate type string, always "X.509" in practice
        }
        val certLen = din.readInt()
        require(certLen in 0..50_000_000) { "Corrupt certificate length $certLen" }
        val encoded = ByteArray(certLen)
        din.readFully(encoded)
        return cf.generateCertificate(ByteArrayInputStream(encoded))
    }

    /**
     * The JKS integrity hash = SHA-1( passwordUtf16 || "Mighty Aphrodite" || data ).
     */
    private fun computeIntegrityHash(
        password: CharArray,
        data: ByteArray,
        length: Int
    ): ByteArray {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(passwordToBytes(password))
        md.update("Mighty Aphrodite".toByteArray(Charsets.UTF_8))
        md.update(data, 0, length)
        return md.digest()
    }

    /** Each char becomes two bytes, high byte first (UTF-16BE-ish, as Sun does). */
    private fun passwordToBytes(password: CharArray): ByteArray {
        val out = ByteArray(password.size * 2)
        for (i in password.indices) {
            out[i * 2] = (password[i].code ushr 8).toByte()
            out[i * 2 + 1] = password[i].code.toByte()
        }
        return out
    }
}
