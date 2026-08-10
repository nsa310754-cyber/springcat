package site.ragdollp.permeditor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * Exercises Axml/ManifestPerms against the real sample APKs checked into
 * dist/, the same ones used to validate apk-permission-manager/axml.js
 * (byte-identical no-op round trip, cross-checked with androguard).
 */
class ManifestEditTest {

    private fun findDist(): File {
        val candidates = listOf(
            File("../../dist"),
            File("../../../dist"),
            File("/home/user/springcat/dist")
        )
        return candidates.firstOrNull { it.isDirectory } ?: error("dist/ directory not found from ${File(".").absolutePath}")
    }

    private fun readManifestBytes(apk: File): ByteArray {
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml") ?: error("no AndroidManifest.xml in $apk")
            return zip.getInputStream(entry).readBytes()
        }
    }

    @Test
    fun noOpRoundTripIsByteIdentical() {
        val apk = File(findDist(), "BlockDestroy-3.5.0-release.apk")
        val original = readManifestBytes(apk)
        val doc = Axml.parse(original)
        val reserialized = Axml.serialize(doc)
        assertTrue("round-trip should be byte-identical", original.contentEquals(reserialized))
    }

    @Test
    fun listsExpectedPermissions() {
        val apk = File(findDist(), "BlockDestroy-3.5.0-release.apk")
        val doc = Axml.parse(readManifestBytes(apk))
        val perms = ManifestPerms.listPermissions(doc).map { it.name }.toSet()
        assertTrue(perms.contains("android.permission.INTERNET"))
        assertTrue(perms.contains("android.permission.POST_NOTIFICATIONS"))
        assertTrue(perms.contains("com.google.android.gms.permission.AD_ID"))
        assertEquals(12, perms.size)
    }

    @Test
    fun removeThenAddRoundTrips() {
        val apk = File(findDist(), "BlockDestroy-3.5.0-release.apk")
        val doc = Axml.parse(readManifestBytes(apk))

        val before = ManifestPerms.listPermissions(doc).map { it.name }
        assertTrue(ManifestPerms.removePermission(doc, "android.permission.INTERNET"))
        assertTrue(ManifestPerms.addPermission(doc, "android.permission.CAMERA"))
        assertTrue(ManifestPerms.addPermission(doc, "android.permission.READ_CONTACTS"))
        // duplicate add is a no-op
        assertEquals(false, ManifestPerms.addPermission(doc, "android.permission.CAMERA"))

        val after = ManifestPerms.listPermissions(doc).map { it.name }.toSet()
        assertTrue(!after.contains("android.permission.INTERNET"))
        assertTrue(after.contains("android.permission.CAMERA"))
        assertTrue(after.contains("android.permission.READ_CONTACTS"))
        assertEquals(before.size - 1 + 2, after.size)

        // Self round-trip: re-parse our own serialized output.
        val reserialized = Axml.serialize(doc)
        val doc2 = Axml.parse(reserialized)
        val after2 = ManifestPerms.listPermissions(doc2).map { it.name }.toSet()
        assertEquals(after, after2)
    }

    @Test
    fun addPermissionFallbackPathWhenNoExistingPermissions() {
        val apk = File(findDist(), "BlockDestroy-3.5.0-release.apk")
        val doc = Axml.parse(readManifestBytes(apk))

        var perms = ManifestPerms.listPermissions(doc)
        while (perms.isNotEmpty()) {
            ManifestPerms.removePermission(doc, perms[0].name)
            perms = ManifestPerms.listPermissions(doc)
        }
        assertEquals(0, ManifestPerms.listPermissions(doc).size)

        assertTrue(ManifestPerms.addPermission(doc, "android.permission.CAMERA"))
        val after = ManifestPerms.listPermissions(doc)
        assertEquals(1, after.size)
        assertEquals("android.permission.CAMERA", after[0].name)

        val reserialized = Axml.serialize(doc)
        val doc2 = Axml.parse(reserialized)
        assertEquals(listOf("android.permission.CAMERA"), ManifestPerms.listPermissions(doc2).map { it.name })
    }

    @Test
    fun worksAcrossAllSampleApks() {
        val names = listOf(
            "BlockDestroy-3.5.0-debug.apk",
            "BlockDestroy-3.5.0-release.apk",
            "OshiLog-1.0-debug.apk",
            "OshiLog-1.0-release.apk"
        )
        for (name in names) {
            val apk = File(findDist(), name)
            val original = readManifestBytes(apk)
            val doc = Axml.parse(original)
            assertTrue("$name: no-op round-trip should be byte-identical", original.contentEquals(Axml.serialize(doc)))

            val perms = ManifestPerms.listPermissions(doc)
            assertTrue("$name: should have permissions", perms.isNotEmpty())

            ManifestPerms.removePermission(doc, perms[0].name)
            ManifestPerms.addPermission(doc, "android.permission.CAMERA")
            val reserialized = Axml.serialize(doc)
            val doc2 = Axml.parse(reserialized)
            val after = ManifestPerms.listPermissions(doc2).map { it.name }.toSet()
            assertTrue("$name: CAMERA should be present after add", after.contains("android.permission.CAMERA"))
            assertTrue("$name: removed permission should be gone", !after.contains(perms[0].name))
        }
    }
}
