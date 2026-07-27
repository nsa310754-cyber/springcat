package com.springcat.apkdoctor

import com.springcat.apkdoctor.apk.ApkArchive
import com.springcat.apkdoctor.apk.ApkBundle
import com.springcat.apkdoctor.apk.Axml
import com.springcat.apkdoctor.apk.AxmlAttribute
import com.springcat.apkdoctor.apk.AxmlDocument
import com.springcat.apkdoctor.apk.ContainerKind
import com.springcat.apkdoctor.apk.DeviceProfile
import com.springcat.apkdoctor.apk.Zips
import com.springcat.apkdoctor.diagnose.ApkInspector
import com.springcat.apkdoctor.diagnose.InspectionContext
import com.springcat.apkdoctor.diagnose.IssueId
import com.springcat.apkdoctor.diagnose.RepairAction
import com.springcat.apkdoctor.repair.ApkRepairer
import com.springcat.apkdoctor.repair.SelfSignedIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Covers the split-bundle path: an `.xapk` holding a base plus config splits,
 * only some of which belong on this device.
 */
class BundleRepairTest {

    private val debugApk = File("build/outputs/apk/debug/app-debug.apk")
    private val scratch = File("build/bundle-test")

    private val device = DeviceProfile(
        sdkInt = 36,
        abis = listOf("arm64-v8a"),
        densityDpi = 480,
        languages = listOf("ja"),
    )

    /** Turns the debug APK into a config split by giving it a `split` name. */
    private fun splitApk(splitName: String, target: File): File {
        val document = AxmlDocument.parse(requireNotNull(Zips.readEntry(debugApk, "AndroidManifest.xml")))
        val manifest = requireNotNull(document.element("manifest"))
        manifest.attributes.add(
            AxmlAttribute(
                uri = null,
                name = "split",
                resourceId = 0,
                rawValue = splitName,
                valueType = Axml.TYPE_STRING,
                valueData = 0,
            ),
        )
        Zips.repack(
            source = debugApk,
            dest = target,
            replacements = mapOf("AndroidManifest.xml" to document.toByteArray()),
            keep = { !Zips.isSignatureEntry(it) },
        )
        return target
    }

    private fun buildXapk(): File {
        assumeTrue("assembleDebug must run first", debugApk.isFile)
        scratch.mkdirs()
        val base = File(scratch, "base.apk").also { debugApk.copyTo(it, overwrite = true) }
        val arm = splitApk("config.arm64_v8a", File(scratch, "split_config.arm64_v8a.apk"))
        val x86 = splitApk("config.x86", File(scratch, "split_config.x86.apk"))
        val xhdpi = splitApk("config.xxhdpi", File(scratch, "split_config.xxhdpi.apk"))

        val xapk = File(scratch, "app.xapk")
        ZipOutputStream(xapk.outputStream()).use { zip ->
            for (file in listOf(base, arm, x86, xhdpi)) {
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return xapk
    }

    @Test
    fun `xapk is unwrapped and filtered down to the splits this device needs`() {
        val bundle = ApkBundle.open(buildXapk(), File(scratch, "parts"), device)

        assertEquals(ContainerKind.BUNDLE, bundle.kind)
        assertEquals(4, bundle.parts.size)

        val selected = bundle.selectedParts.map { it.archive.file.name }.toSet()
        assertTrue("the base APK is always required", "base.apk" in selected)
        assertTrue("the matching ABI split is required", "split_config.arm64_v8a.apk" in selected)
        assertTrue("the matching density split is required", "split_config.xxhdpi.apk" in selected)
        assertTrue("a foreign ABI split must be dropped", "split_config.x86.apk" !in selected)
    }

    @Test
    fun `bundle repair signs every part with one key`() {
        val bundle = ApkBundle.open(buildXapk(), File(scratch, "parts2"), device)
        val report = ApkInspector.inspect(
            bundle = bundle,
            context = InspectionContext(device = device, installedApp = null, canRequestInstalls = true),
            displayName = "app.xapk",
        )

        val issues = report.diagnoses.map { it.id }
        assertTrue("a bundle cannot be installed by tapping it: $issues", IssueId.BUNDLE_CONTAINER in issues)
        // The splits here were repacked and lost their signatures; that has to be
        // caught even though the base APK is properly signed.
        assertTrue("an unsigned split must be detected: $issues", IssueId.NO_SIGNATURE in issues)
        assertTrue(RepairAction.SELECT_SPLITS in report.repairActions)
        assertTrue(RepairAction.RESIGN in report.repairActions)

        val result = ApkRepairer { SelfSignedIdentity.generate().first }.repair(
            bundle = bundle,
            actions = report.repairActions,
            device = device,
            workDir = File(scratch, "work"),
            outputDir = File(scratch, "out"),
            log = {},
        )

        assertEquals("base plus two matching splits", 3, result.files.size)
        assertEquals("the base APK must come first", "base.apk", result.files.first().name)

        val fingerprints = result.files.map { file ->
            val archive = ApkArchive.read(file)
            assertTrue("${file.name} must verify: ${archive.signature.errors}", archive.signature.verified)
            archive.signature.certificateSha256
        }.distinct()
        assertEquals("all parts of a session must share one signer", 1, fingerprints.size)
    }
}
