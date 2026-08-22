package com.apkbuilder.core

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-CBC "obfuscation" of the game's entry HTML so it isn't sitting as
 * plaintext inside the APK zip — a bar-raiser against casual `unzip`, not
 * real secrecy (the key ships inside the same app; see the matching
 * decryptor in apkbuilder/template's MainActivity.kt, `decryptGameHtml`).
 * This is the same scheme this repo's ../android app already uses for
 * Block Destroy (assets/game.enc, IV-prefixed AES/CBC/PKCS5Padding).
 */
object GameObfuscator {
    // Must match apkbuilder/template's MainActivity.gameEncPassphrase() exactly.
    private const val PASSPHRASE = "apkbuilder-game-obf-k3y"

    /** Encrypts [plaintext], producing `[16-byte IV][ciphertext]` — the same layout `game.enc` uses. */
    fun encryptGameHtml(plaintext: ByteArray): ByteArray {
        val key = MessageDigest.getInstance("SHA-256").digest(PASSPHRASE.toByteArray(Charsets.UTF_8))
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }
}
