package com.springcat.apkdoctor.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Exercises the AXML reader/writer against a real APK, since a manifest that
 * merely parses in our own code is worthless if the platform rejects it.
 *
 * The fixture is this app's own debug APK; `verifyPatchedApk.sh` then feeds the
 * rewritten output to `aapt2 dump` for an independent opinion.
 */
class AxmlRoundTripTest {

    private val debugApk = File("build/outputs/apk/debug/app-debug.apk")

    private fun manifestBytes(): ByteArray {
        assumeTrue("assembleDebug must run first", debugApk.isFile)
        return requireNotNull(Zips.readEntry(debugApk, "AndroidManifest.xml"))
    }

    @Test
    fun `round trip preserves every manifest value`() {
        val original = AxmlDocument.parse(manifestBytes())
        val reparsed = AxmlDocument.parse(original.toByteArray())

        assertEquals(ManifestInfo.from(original), ManifestInfo.from(reparsed))
        assertEquals(original.elements.size, reparsed.elements.size)
        assertEquals(original.nodes.size, reparsed.nodes.size)

        for ((before, after) in original.elements.zip(reparsed.elements)) {
            assertEquals(before.name, after.name)
            assertEquals(before.attributes.size, after.attributes.size)
            val beforeAttrs = before.attributes.associate { it.resourceId to it.asString() }
            val afterAttrs = after.attributes.associate { it.resourceId to it.asString() }
            assertEquals(beforeAttrs, afterAttrs)
        }
    }

    @Test
    fun `round trip is byte-stable`() {
        val once = AxmlDocument.parse(manifestBytes()).toByteArray()
        val twice = AxmlDocument.parse(once).toByteArray()
        assertTrue("re-serializing an already-written manifest must be stable", once.contentEquals(twice))
    }

    @Test
    fun `sdk versions can be rewritten`() {
        val document = AxmlDocument.parse(manifestBytes())
        val usesSdk = requireNotNull(document.element("uses-sdk"))
        usesSdk.requireAndroidAttr(Axml.Attr.MIN_SDK_VERSION, "minSdkVersion").setInt(21)
        usesSdk.requireAndroidAttr(Axml.Attr.TARGET_SDK_VERSION, "targetSdkVersion").setInt(24)

        val patched = ManifestInfo.from(AxmlDocument.parse(document.toByteArray()))
        assertEquals(21, patched.minSdk)
        assertEquals(24, patched.targetSdk)
    }

    @Test
    fun `uses-sdk is created when the manifest has none`() {
        val document = AxmlDocument.parse(manifestBytes())
        document.element("uses-sdk")?.let { document.removeElement(it) }
        assertNull(AxmlDocument.parse(document.toByteArray()).element("uses-sdk"))

        val manifest = requireNotNull(document.element("manifest"))
        val usesSdk = AxmlElement(null, "uses-sdk", mutableListOf(), manifest.line)
        document.insertChildFirst(manifest, usesSdk)
        usesSdk.requireAndroidAttr(Axml.Attr.TARGET_SDK_VERSION, "targetSdkVersion").setInt(23)

        val reparsed = AxmlDocument.parse(document.toByteArray())
        assertNotNull(reparsed.element("uses-sdk"))
        assertEquals(23, ManifestInfo.from(reparsed).targetSdk)
        // The rest of the document must survive the insertion.
        assertNotNull(reparsed.element("application"))
        assertEquals("com.springcat.apkdoctor", ManifestInfo.from(reparsed).packageName)
    }

    @Test
    fun `resource-mapped attributes stay sorted by id`() {
        // ResTable::retrieveAttributes walks attributes forward once, so an
        // out-of-order id silently loses the attribute at runtime.
        val document = AxmlDocument.parse(AxmlDocument.parse(manifestBytes()).toByteArray())
        for (element in document.elements) {
            val ids = element.attributes
                .map { it.resourceId }
                .filter { it != 0 }
                .map { it.toLong() and 0xFFFFFFFFL }
            assertEquals("attributes of ${element.name} must be sorted", ids.sorted(), ids)
        }
    }

    @Test
    fun `patched manifest can be repacked into a working apk`() {
        val document = AxmlDocument.parse(manifestBytes())
        requireNotNull(document.element("uses-sdk"))
            .requireAndroidAttr(Axml.Attr.TARGET_SDK_VERSION, "targetSdkVersion").setInt(24)

        val output = File("build/test-patched.apk")
        Zips.repack(
            source = debugApk,
            dest = output,
            replacements = mapOf("AndroidManifest.xml" to document.toByteArray()),
            keep = { !Zips.isSignatureEntry(it) },
        )

        val archive = ApkArchive.read(output, verifySignature = false)
        assertTrue("repacked APK must be readable: ${archive.zipError}", archive.isReadable)
        assertEquals(24, archive.manifest?.targetSdk)
        assertEquals("com.springcat.apkdoctor", archive.manifest?.packageName)
    }
}
