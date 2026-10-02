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

/**
 * The headline case from the request: a `base.apk` plus several split APKs,
 * each uninstallable on its own, picked as loose files and installed together.
 */
class MultiSplitInstallTest {

    private val debugApk = File("build/outputs/apk/debug/app-debug.apk")
    private val scratch = File("build/multisplit-test")

    private val device = DeviceProfile(
        sdkInt = 36,
        abis = listOf("arm64-v8a"),
        densityDpi = 480,
        languages = listOf("ja"),
    )

    private fun splitFile(splitName: String?, fileName: String): File {
        scratch.mkdirs()
        val document = AxmlDocument.parse(requireNotNull(Zips.readEntry(debugApk, "AndroidManifest.xml")))
        if (splitName != null) {
            requireNotNull(document.element("manifest")).attributes.add(
                AxmlAttribute(null, "split", 0, splitName, Axml.TYPE_STRING, 0),
            )
        }
        val target = File(scratch, fileName)
        Zips.repack(
            source = debugApk,
            dest = target,
            replacements = mapOf("AndroidManifest.xml" to document.toByteArray()),
            keep = { !Zips.isSignatureEntry(it) }, // strip signatures -> each part is "uninstallable"
        )
        return target
    }

    @Test
    fun `loose base plus splits install as one signed set`() {
        assumeTrue("assembleDebug must run first", debugApk.isFile)
        val files = listOf(
            splitFile(null, "base.apk"),
            splitFile("config.arm64_v8a", "split_config.arm64_v8a.apk"),
            splitFile("config.x86", "split_config.x86.apk"),
            splitFile("config.xxhdpi", "split_config.xxhdpi.apk"),
        )

        val bundle = ApkBundle.openMultiple(files, File(scratch, "parts"), device)
        assertEquals(ContainerKind.BUNDLE, bundle.kind)

        val selected = bundle.selectedParts.map { it.archive.file.name }.toSet()
        assertTrue("base must be kept", "base.apk" in selected)
        assertTrue("matching ABI split kept", "split_config.arm64_v8a.apk" in selected)
        assertTrue("matching density split kept", "split_config.xxhdpi.apk" in selected)
        assertTrue("foreign ABI split dropped", "split_config.x86.apk" !in selected)

        val report = ApkInspector.inspect(
            bundle = bundle,
            context = InspectionContext(device, installedApp = null, canRequestInstalls = true),
            displayName = "4 個のAPK",
        )
        // Stripped signatures across the set must be caught and auto-fixable.
        assertTrue(IssueId.NO_SIGNATURE in report.diagnoses.map { it.id })
        assertTrue(report.isRepairable)
        assertTrue(RepairAction.RESIGN in report.repairActions)

        val result = ApkRepairer { SelfSignedIdentity.generate().first }.repair(
            bundle = bundle,
            actions = report.repairActions,
            device = device,
            workDir = File(scratch, "work"),
            outputDir = File(scratch, "out"),
            log = {},
        )

        assertEquals("base + two matching splits", 3, result.files.size)
        assertEquals("base must be installed first", "base.apk", result.files.first().name)
        val signers = result.files.map { f ->
            val a = ApkArchive.read(f)
            assertTrue("${f.name} must verify", a.signature.verified)
            a.signature.certificateSha256
        }.distinct()
        assertEquals("a split set must share one signer", 1, signers.size)
    }

    @Test
    fun `files from a different app are flagged, not installed`() {
        assumeTrue("assembleDebug must run first", debugApk.isFile)

        // A base of the real app, plus an "alien" split whose package differs.
        val base = splitFile(null, "base.apk")
        val alien = run {
            val doc = AxmlDocument.parse(requireNotNull(Zips.readEntry(debugApk, "AndroidManifest.xml")))
            requireNotNull(doc.element("manifest")).apply {
                attr("package")?.let { it.rawValue = "com.other.app" }
                attributes.add(AxmlAttribute(null, "split", 0, "config.arm64_v8a", Axml.TYPE_STRING, 0))
            }
            val f = File(scratch, "alien_split.apk")
            Zips.repack(base, f, mapOf("AndroidManifest.xml" to doc.toByteArray())) { !Zips.isSignatureEntry(it) }
            f
        }

        val bundle = ApkBundle.openMultiple(listOf(base, alien), File(scratch, "parts2"), device)
        val report = ApkInspector.inspect(
            bundle = bundle,
            context = InspectionContext(device, installedApp = null, canRequestInstalls = true),
            displayName = "2 個のAPK",
        )
        assertTrue(IssueId.MULTIPLE_PACKAGES in report.diagnoses.map { it.id })
        assertTrue("the alien file must not be selected", bundle.selectedParts.none { it.archive.file.name == "alien_split.apk" })
    }
}
