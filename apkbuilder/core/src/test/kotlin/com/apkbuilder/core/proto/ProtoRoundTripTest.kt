package com.apkbuilder.core.proto

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class ProtoRoundTripTest {

    private fun realManifest(): ByteArray {
        val candidates = listOf(
            File("src/test/resources/aab_manifest.pb"),
            File("core/src/test/resources/aab_manifest.pb"),
            File("/home/user/springcat/apkbuilder/core/src/test/resources/aab_manifest.pb"),
        )
        return candidates.first { it.exists() }.readBytes()
    }

    @Test
    fun losslessRoundTrip() {
        val bytes = realManifest()
        val reserialized = ProtoMessage.parse(bytes).toByteArray()
        assertContentEquals(bytes, reserialized, "parse->serialize must be byte-for-byte identical")
    }

    @Test
    fun editsChangeManifest() {
        val editor = ProtoManifestEditor.parse(realManifest())
        editor.setPackage("com.example.changed")
        editor.setVersionName("9.9.9")
        editor.setVersionCode(42)
        editor.setApplicationLabel("変更後アプリ")
        editor.addUsesPermission("android.permission.CAMERA")
        editor.addApplicationMetaData("com.google.android.gms.ads.APPLICATION_ID", "ca-app-pub-123~456")
        val out = editor.toByteArray()

        // The new literal strings must appear somewhere in the re-encoded manifest.
        val text = String(out, Charsets.ISO_8859_1)
        assertTrue(text.contains("com.example.changed"))
        assertTrue(text.contains("9.9.9"))
        assertTrue(text.contains("android.permission.CAMERA"))
        assertTrue(text.contains("com.google.android.gms.ads.APPLICATION_ID"))
        // And it must still be parseable.
        ProtoMessage.parse(out)
    }
}
