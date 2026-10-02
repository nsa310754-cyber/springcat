package com.springcat.apkdoctor.apk

import com.springcat.apkdoctor.diagnose.ApkInspector
import com.springcat.apkdoctor.diagnose.InspectionContext
import com.springcat.apkdoctor.diagnose.IssueId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Verifies the protobuf-manifest reader against a real proto manifest produced
 * by `aapt2 convert --output-format proto` on the app's own APK — the same
 * encoding an `.aab` uses. Skips when the SDK is not on the build machine.
 */
class ProtoManifestTest {

    private val debugApk = File("build/outputs/apk/debug/app-debug.apk")
    private val scratch = File("build/proto-test").apply { mkdirs() }

    private fun aapt2(): File? {
        val home = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: return null
        return File(home, "build-tools").listFiles()
            ?.sortedDescending()
            ?.firstNotNullOfOrNull { dir -> File(dir, "aapt2").takeIf { it.canExecute() } }
    }

    /** Produces a proto-format AndroidManifest.xml the way an .aab stores it. */
    private fun protoManifest(): ByteArray {
        assumeTrue("assembleDebug must run first", debugApk.isFile)
        val aapt2 = aapt2()
        assumeTrue("aapt2 not available", aapt2 != null)

        val protoApk = File(scratch, "proto.apk")
        val proc = ProcessBuilder(
            aapt2!!.absolutePath, "convert", "--output-format", "proto",
            "-o", protoApk.absolutePath, debugApk.absolutePath,
        ).redirectErrorStream(true).start()
        val log = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals("aapt2 convert failed: $log", 0, proc.waitFor())

        return requireNotNull(Zips.readEntry(protoApk, "AndroidManifest.xml")) {
            "proto APK had no manifest"
        }
    }

    @Test
    fun `reads package, version and sdk levels from a proto manifest`() {
        val info = ProtoManifest.parse(protoManifest())
        assertNotNull("proto manifest should parse", info)
        info!!
        assertEquals("com.springcat.apkdoctor", info.packageName)
        assertEquals(1L, info.versionCode)
        assertEquals("1.0", info.versionName)
        assertEquals(26, info.minSdk)
        assertEquals(36, info.targetSdk)
    }

    @Test
    fun `an aab is recognised and diagnosed, not mistaken for a broken file`() {
        val manifest = protoManifest()
        val aab = File(scratch, "app.aab")
        ZipOutputStream(aab.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("BundleConfig.pb")); zip.closeEntry()
            zip.putNextEntry(ZipEntry("base/manifest/AndroidManifest.xml")); zip.write(manifest); zip.closeEntry()
            zip.putNextEntry(ZipEntry("base/dex/classes.dex")); zip.write(ByteArray(8)); zip.closeEntry()
        }

        val device = DeviceProfile(36, listOf("arm64-v8a"), 480, listOf("ja"))
        val bundle = ApkBundle.open(aab, File(scratch, "aabparts"), device)
        assertEquals(ContainerKind.AAB, bundle.kind)
        assertEquals("com.springcat.apkdoctor", bundle.aabInfo?.packageName)

        val report = ApkInspector.inspect(
            bundle = bundle,
            context = InspectionContext(device, installedApp = null, canRequestInstalls = true),
            displayName = "app.aab",
        )
        assertTrue(IssueId.AAB_NEEDS_CONVERSION in report.diagnoses.map { it.id })
        assertEquals("com.springcat.apkdoctor", report.packageName)
        assertEquals(36, report.targetSdk)
    }
}
