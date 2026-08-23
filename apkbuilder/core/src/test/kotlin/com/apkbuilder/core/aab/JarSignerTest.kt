package com.apkbuilder.core.aab

import com.apkbuilder.core.KeystoreGenerator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class JarSignerTest {

    private fun zipOf(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val map = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                if (!e.isDirectory) map[e.name] = zis.readBytes()
                e = zis.nextEntry
            }
        }
        return map
    }

    @Test
    fun signsAndPreservesContentWithLongPaths() {
        val longName = "base/res/drawable-xxxhdpi-v4/a_" + "x".repeat(80) + ".png"
        val input = zipOf(
            mapOf(
                "BundleConfig.pb" to byteArrayOf(1, 2, 3),
                "base/manifest/AndroidManifest.xml" to "manifest".toByteArray(),
                "base/assets/game.html" to "<html>ゲーム</html>".toByteArray(),
                longName to ByteArray(200) { it.toByte() },
            ),
        )
        val keystore = KeystoreGenerator.generate(commonName = "JarSigner Test")
        val signed = JarSigner.sign(input, keystore.signingKey())

        val out = readZip(signed)
        // Signature files were added.
        assertTrue(out.containsKey("META-INF/MANIFEST.MF"))
        assertTrue(out.keys.any { it.startsWith("META-INF/") && it.endsWith(".SF") })
        assertTrue(out.keys.any { it.startsWith("META-INF/") && it.endsWith(".RSA") })

        // Original entries survive byte-for-byte.
        assertContentEquals("<html>ゲーム</html>".toByteArray(), out["base/assets/game.html"])
        assertContentEquals(ByteArray(200) { it.toByte() }, out[longName])

        // The manifest lists every content entry, and long paths are wrapped to <=72-byte lines.
        val mf = out.getValue("META-INF/MANIFEST.MF").toString(Charsets.UTF_8)
        assertTrue(mf.contains("SHA-256-Digest:"))
        for (line in mf.split("\r\n")) {
            assertTrue(line.toByteArray(Charsets.UTF_8).size <= 70, "manifest line too long: $line")
        }
    }
}
