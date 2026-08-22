package com.apkbuilder.core

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals

class GameObfuscatorTest {

    // Mirrors apkbuilder/template's MainActivity.decryptGameHtml() exactly, to prove the two sides agree.
    private fun decryptLikeTemplate(encrypted: ByteArray): ByteArray {
        val passphrase = "apkbuilder-game-obf-k3y"
        val iv = encrypted.copyOfRange(0, 16)
        val ciphertext = encrypted.copyOfRange(16, encrypted.size)
        val key = MessageDigest.getInstance("SHA-256").digest(passphrase.toByteArray(Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ciphertext)
    }

    @Test
    fun encryptedGameHtmlRoundTrips() {
        val plaintext = "<!DOCTYPE html><html><body>テストゲーム</body></html>".toByteArray()
        val encrypted = GameObfuscator.encryptGameHtml(plaintext)
        assertContentEquals(plaintext, decryptLikeTemplate(encrypted))
    }
}
