package com.apkbuilder.core

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Loads a user-supplied keystore (to sign an *update* with the original key).
 * Tries PKCS12 first (the format this tool itself generates, and keytool's
 * modern default), then a hand-rolled JKS reader (the classic Android upload
 * key format — Android's JCA has no JKS provider, so it is parsed directly),
 * then BKS via Bouncy Castle.
 */
object KeystoreLoader {

    class LoadException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * @param alias which key to use; if blank, the keystore's first key alias is chosen.
     * @param keyPassword password for the key entry; if blank, [storePassword] is reused.
     */
    fun load(bytes: ByteArray, storePassword: String, keyPassword: String, alias: String): SigningKey {
        BcProvider.ensureInstalled()
        val keyPw = keyPassword.ifBlank { storePassword }

        // PKCS12 (and BKS) via the JCA KeyStore API.
        for (type in listOf("PKCS12", "BKS")) {
            val result = runCatching { loadViaKeyStore(type, bytes, storePassword, keyPw, alias) }.getOrNull()
            if (result != null) return result
        }
        // JKS (parsed manually — no provider for it on Android).
        runCatching { loadJks(bytes, keyPw, alias) }.getOrNull()?.let { return it }

        throw LoadException(
            "keystore を読み込めませんでした。対応形式は PKCS12(.p12/.keystore) / JKS / BKS です。" +
                "パスワードやエイリアスが正しいか確認してください。",
        )
    }

    private fun loadViaKeyStore(type: String, bytes: ByteArray, storePassword: String, keyPassword: String, alias: String): SigningKey {
        val ks = if (type == "PKCS12") {
            KeyStore.getInstance(type, BouncyCastleProvider.PROVIDER_NAME)
        } else {
            KeyStore.getInstance(type, BouncyCastleProvider.PROVIDER_NAME)
        }
        ks.load(ByteArrayInputStream(bytes), storePassword.toCharArray())
        val useAlias = alias.ifBlank { firstKeyAlias(ks) ?: error("no key alias") }
        val key = ks.getKey(useAlias, keyPassword.toCharArray()) as? PrivateKey
            ?: error("alias '$useAlias' is not a private key")
        val cert = ks.getCertificate(useAlias) as? X509Certificate
            ?: error("alias '$useAlias' has no X.509 certificate")
        return SigningKey(key, cert, useAlias)
    }

    private fun firstKeyAlias(ks: KeyStore): String? {
        val aliases = ks.aliases()
        while (aliases.hasMoreElements()) {
            val a = aliases.nextElement()
            if (ks.isKeyEntry(a)) return a
        }
        return null
    }

    // ---- JKS reader (Sun's proprietary format) ----
    // Format: magic 0xFEEDFEED, version(int), count(int), then entries; each private-key
    // entry holds an EncryptedPrivateKeyInfo protected by the "Sun key protector" (a
    // SHA-1 keystream XOR keyed by the key password), followed by its certificate chain.

    private const val JKS_MAGIC = 0xFEEDFEED.toInt()

    private fun loadJks(bytes: ByteArray, keyPassword: String, alias: String): SigningKey {
        DataInputStream(ByteArrayInputStream(bytes)).use { din ->
            val magic = din.readInt()
            require(magic == JKS_MAGIC) { "not a JKS keystore" }
            din.readInt() // version
            val count = din.readInt()

            var chosen: Pair<PrivateKey, X509Certificate>? = null
            var chosenAlias: String? = null
            val cf = CertificateFactory.getInstance("X.509")

            repeat(count) {
                val tag = din.readInt()
                val entryAlias = din.readUTF()
                din.readLong() // creation date
                when (tag) {
                    1 -> { // private key entry
                        val protectedLen = din.readInt()
                        val protectedKey = ByteArray(protectedLen).also { din.readFully(it) }
                        val chainLen = din.readInt()
                        val certs = ArrayList<X509Certificate>(chainLen)
                        repeat(chainLen) {
                            din.readUTF() // cert type ("X.509")
                            val certLen = din.readInt()
                            val certBytes = ByteArray(certLen).also { din.readFully(it) }
                            certs.add(cf.generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate)
                        }
                        if ((alias.isBlank() || alias == entryAlias) && chosen == null) {
                            val pkcs8 = recoverJksKey(protectedKey, keyPassword)
                            val key = parsePrivateKey(pkcs8)
                            chosen = key to certs.first()
                            chosenAlias = entryAlias
                        }
                    }
                    2 -> { // trusted cert entry
                        din.readUTF()
                        val certLen = din.readInt()
                        din.skipBytes(certLen)
                    }
                    else -> error("unknown JKS entry tag $tag")
                }
            }
            val (key, cert) = chosen ?: throw LoadException("JKS 内に秘密鍵が見つかりませんでした")
            return SigningKey(key, cert, chosenAlias ?: alias)
        }
    }

    /** Decrypts an EncryptedPrivateKeyInfo protected by the Sun JKS key protector, returning PKCS#8 DER. */
    private fun recoverJksKey(protectedKey: ByteArray, password: String): ByteArray {
        // protectedKey is a DER EncryptedPrivateKeyInfo; its OCTET STRING payload is what we decrypt.
        val encrypted = extractEncryptedPayload(protectedKey)
        val saltLen = 20
        val digestLen = 20
        val salt = encrypted.copyOfRange(0, saltLen)
        val encrKeyLen = encrypted.size - saltLen - digestLen
        val encrKey = encrypted.copyOfRange(saltLen, saltLen + encrKeyLen)

        val passwordBytes = passwordToBytes(password)
        val md = MessageDigest.getInstance("SHA-1")
        val plainKey = ByteArray(encrKeyLen)
        var digest = salt
        var i = 0
        while (i < encrKeyLen) {
            md.update(passwordBytes)
            md.update(digest)
            digest = md.digest()
            var j = 0
            while (j < digest.size && i + j < encrKeyLen) {
                plainKey[i + j] = (encrKey[i + j].toInt() xor digest[j].toInt()).toByte()
                j++
            }
            i += digest.size
        }
        return plainKey
    }

    /** Extracts the OCTET STRING payload of a DER EncryptedPrivateKeyInfo (SEQUENCE{ AlgId, OCTET STRING }). */
    private fun extractEncryptedPayload(der: ByteArray): ByteArray {
        var p = 0
        require(der[p].toInt() and 0xff == 0x30) { "expected SEQUENCE" }
        p++
        p += readDerLen(der, p).second // skip outer length bytes
        // AlgorithmIdentifier (SEQUENCE)
        require(der[p].toInt() and 0xff == 0x30) { "expected AlgId SEQUENCE" }
        p++
        val (algLen, algLenBytes) = readDerLen(der, p)
        p += algLenBytes + algLen
        // OCTET STRING
        require(der[p].toInt() and 0xff == 0x04) { "expected OCTET STRING" }
        p++
        val (octLen, octLenBytes) = readDerLen(der, p)
        p += octLenBytes
        return der.copyOfRange(p, p + octLen)
    }

    private fun readDerLen(der: ByteArray, off: Int): Pair<Int, Int> {
        val first = der[off].toInt() and 0xff
        if (first and 0x80 == 0) return first to 1
        val numBytes = first and 0x7f
        var len = 0
        for (k in 1..numBytes) len = (len shl 8) or (der[off + k].toInt() and 0xff)
        return len to (1 + numBytes)
    }

    private fun parsePrivateKey(pkcs8: ByteArray): PrivateKey {
        for (algo in listOf("RSA", "EC", "DSA")) {
            runCatching { return KeyFactory.getInstance(algo).generatePrivate(PKCS8EncodedKeySpec(pkcs8)) }
        }
        throw LoadException("秘密鍵の形式を認識できませんでした")
    }

    /** JKS uses each char's two bytes (UTF-16BE) as the password material. */
    private fun passwordToBytes(password: String): ByteArray {
        val out = ByteArray(password.length * 2)
        for (i in password.indices) {
            out[i * 2] = (password[i].code ushr 8).toByte()
            out[i * 2 + 1] = password[i].code.toByte()
        }
        return out
    }
}
