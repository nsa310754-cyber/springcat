package com.apkbuilder.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeystoreLoaderTest {

    private fun resource(name: String): ByteArray {
        val candidates = listOf(
            File("src/test/resources/$name"),
            File("core/src/test/resources/$name"),
            File("/home/user/springcat/apkbuilder/core/src/test/resources/$name"),
        )
        return candidates.first { it.exists() }.readBytes()
    }

    @Test
    fun loadsGeneratedPkcs12AndMatchesFingerprint() {
        val gen = KeystoreGenerator.generate(commonName = "RoundTrip")
        val key = KeystoreLoader.load(gen.pkcs12Bytes, gen.storePassword, gen.keyPassword, gen.alias)
        assertEquals(gen.certificateSha256Fingerprint, key.certificateSha256Fingerprint)
        assertEquals("release", key.alias)
    }

    @Test
    fun loadsPkcs12WithBlankAliasPicksFirstKey() {
        val gen = KeystoreGenerator.generate(commonName = "AutoAlias")
        val key = KeystoreLoader.load(gen.pkcs12Bytes, gen.storePassword, gen.keyPassword, alias = "")
        assertEquals(gen.certificateSha256Fingerprint, key.certificateSha256Fingerprint)
    }

    @Test
    fun loadsClassicJksKeystore() {
        // Fixture built with: keytool -genkeypair -storetype JKS -alias testalias
        //   -storepass storepw123 -keypass keypw456 -keyalg RSA
        val key = KeystoreLoader.load(resource("test.jks"), "storepw123", "keypw456", "testalias")
        assertEquals("testalias", key.alias)
        assertTrue(key.certificate.subjectX500Principal.name.contains("JKS Test"))
        // The recovered private key must actually pair with the certificate: sign & verify.
        val sig = java.security.Signature.getInstance("SHA256withRSA")
        sig.initSign(key.privateKey)
        sig.update("hello".toByteArray())
        val signed = sig.sign()
        val verify = java.security.Signature.getInstance("SHA256withRSA")
        verify.initVerify(key.certificate.publicKey)
        verify.update("hello".toByteArray())
        assertTrue(verify.verify(signed), "recovered JKS key does not match its certificate")
    }
}
