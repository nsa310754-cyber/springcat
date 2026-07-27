package com.springcat.apkdoctor

import com.android.apksig.ApkVerifier
import com.springcat.apkdoctor.apk.ApkArchive
import com.springcat.apkdoctor.apk.ApkBundle
import com.springcat.apkdoctor.apk.Axml
import com.springcat.apkdoctor.apk.AxmlDocument
import com.springcat.apkdoctor.apk.DeviceProfile
import com.springcat.apkdoctor.apk.Zips
import com.springcat.apkdoctor.diagnose.ApkInspector
import com.springcat.apkdoctor.diagnose.InspectionContext
import com.springcat.apkdoctor.diagnose.IssueId
import com.springcat.apkdoctor.diagnose.RepairAction
import com.springcat.apkdoctor.repair.ApkRepairer
import com.springcat.apkdoctor.repair.SelfSignedIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Walks a deliberately broken APK through inspect → repair → verify, the same
 * path the app takes at runtime.
 */
class RepairPipelineTest {

    private val debugApk = File("build/outputs/apk/debug/app-debug.apk")
    private val scratch = File("build/pipeline-test")

    private val device = DeviceProfile(
        sdkInt = 36,
        abis = listOf("arm64-v8a", "armeabi-v7a"),
        densityDpi = 480,
        languages = listOf("ja", "en"),
    )

    /**
     * Produces an APK with three real install blockers: an ancient
     * `targetSdkVersion`, the `testOnly` flag, and no signature at all.
     */
    private fun brokenApk(): File {
        assumeTrue("assembleDebug must run first", debugApk.isFile)
        scratch.mkdirs()
        val document = AxmlDocument.parse(requireNotNull(Zips.readEntry(debugApk, "AndroidManifest.xml")))
        requireNotNull(document.element("uses-sdk"))
            .requireAndroidAttr(Axml.Attr.TARGET_SDK_VERSION, "targetSdkVersion").setInt(15)
        requireNotNull(document.element("application"))
            .requireAndroidAttr(Axml.Attr.TEST_ONLY, "testOnly").setBoolean(true)

        val broken = File(scratch, "broken.apk")
        Zips.repack(
            source = debugApk,
            dest = broken,
            replacements = mapOf("AndroidManifest.xml" to document.toByteArray()),
            keep = { !Zips.isSignatureEntry(it) },
        )
        return broken
    }

    @Test
    fun `broken apk is diagnosed, repaired and verifiably signed`() {
        val broken = brokenApk()

        // --- diagnose ------------------------------------------------------
        val bundle = ApkBundle.open(broken, File(scratch, "parts"), device)
        val report = ApkInspector.inspect(
            bundle = bundle,
            context = InspectionContext(device = device, installedApp = null, canRequestInstalls = true),
            displayName = "broken.apk",
        )

        val issues = report.diagnoses.map { it.id }
        assertTrue("expected an unsigned APK to be flagged, got $issues", IssueId.NO_SIGNATURE in issues)
        assertTrue("expected targetSdk to be flagged, got $issues", IssueId.TARGET_SDK_TOO_LOW in issues)
        assertTrue("expected testOnly to be flagged, got $issues", IssueId.TEST_ONLY in issues)
        assertTrue("every blocker here is fixable", report.isRepairable)
        assertEquals(
            setOf(RepairAction.RESIGN, RepairAction.RAISE_TARGET_SDK, RepairAction.CLEAR_TEST_ONLY),
            report.repairActions.toSet(),
        )

        // --- repair --------------------------------------------------------
        val log = mutableListOf<String>()
        val result = ApkRepairer { SelfSignedIdentity.generate().first }.repair(
            bundle = bundle,
            actions = report.repairActions,
            device = device,
            workDir = File(scratch, "work"),
            outputDir = File(scratch, "out"),
            log = { log.add(it) },
        )

        assertEquals(1, result.files.size)
        assertTrue(result.resigned)

        // --- verify --------------------------------------------------------
        val fixed = result.files.single()
        val archive = ApkArchive.read(fixed)
        assertTrue("repaired APK must parse: ${archive.zipError}", archive.isReadable)
        assertEquals("targetSdk raised to the platform floor", 24, archive.manifest?.targetSdk)
        assertFalse("testOnly must be cleared", archive.manifest?.testOnly ?: true)
        assertEquals("com.springcat.apkdoctor", archive.manifest?.packageName)

        val verification = ApkVerifier.Builder(fixed).setMinCheckedPlatformVersion(24).build().verify()
        assertTrue("apksig must accept the signature: ${verification.allErrors}", verification.isVerified)
        assertTrue("v2 needed for Android 7+", verification.isVerifiedUsingV2Scheme)
        assertTrue("v3 needed for Android 9+", verification.isVerifiedUsingV3Scheme)

        // A verifier only consults v1 when the platform range reaches below 24,
        // so the JAR signature is checked with an explicitly lower floor.
        assertTrue(
            "JAR signature files must be written for pre-24 devices",
            Zips.entryNames(fixed).any { it.uppercase().endsWith(".SF") },
        )
        val legacy = ApkVerifier.Builder(fixed).setMinCheckedPlatformVersion(21).build().verify()
        assertTrue("v1 must verify for pre-24 devices: ${legacy.allErrors}", legacy.isVerifiedUsingV1Scheme)

        // The repaired APK now passes the checks it originally failed.
        val recheck = ApkInspector.inspect(
            bundle = ApkBundle.open(fixed, File(scratch, "parts2"), device),
            context = InspectionContext(device = device, installedApp = null, canRequestInstalls = true),
            displayName = "fixed.apk",
        )
        assertTrue("repaired APK should have no blockers left: ${recheck.blockers.map { it.id }}", recheck.blockers.isEmpty())
    }

    @Test
    fun `a healthy apk is reported as installable and left alone`() {
        assumeTrue("assembleDebug must run first", debugApk.isFile)
        val bundle = ApkBundle.open(debugApk, File(scratch, "healthy"), device)
        val report = ApkInspector.inspect(
            bundle = bundle,
            context = InspectionContext(device = device, installedApp = null, canRequestInstalls = true),
            displayName = "app-debug.apk",
        )
        assertTrue("blockers found on a good APK: ${report.blockers.map { it.id }}", report.isInstallableAsIs)
    }

    @Test
    fun `a truncated apk is salvaged back into a valid one`() {
        assumeTrue("assembleDebug must run first", debugApk.isFile)
        scratch.mkdirs()
        // Lop off the central directory: the classic "downloaded APK is corrupt".
        val bytes = debugApk.readBytes()
        val truncated = File(scratch, "truncated.apk")
        truncated.writeBytes(bytes.copyOf(bytes.size - 2048))

        val bundle = ApkBundle.open(truncated, File(scratch, "parts3"), device)
        val report = ApkInspector.inspect(
            bundle = bundle,
            context = InspectionContext(device = device, installedApp = null, canRequestInstalls = true),
            displayName = "truncated.apk",
        )

        val issues = report.diagnoses.map { it.id }
        assertTrue("a damaged archive must be repairable, not simply rejected: $issues", IssueId.ZIP_CORRUPT in issues)
        assertTrue("salvaging drops the signature, so re-signing must be planned", RepairAction.RESIGN in report.repairActions)
        assertTrue("every blocker here is fixable: $issues", report.isRepairable)

        val result = ApkRepairer { SelfSignedIdentity.generate().first }.repair(
            bundle = bundle,
            actions = report.repairActions,
            device = device,
            workDir = File(scratch, "work3"),
            outputDir = File(scratch, "out3"),
            log = {},
        )

        val fixed = result.files.single()
        val archive = ApkArchive.read(fixed)
        assertTrue("rebuilt APK must parse: ${archive.zipError}", archive.isReadable)
        assertEquals("com.springcat.apkdoctor", archive.manifest?.packageName)
        assertTrue("rebuilt APK must be signed", archive.signature.verified)

        val recheck = ApkInspector.inspect(
            bundle = ApkBundle.open(fixed, File(scratch, "parts4"), device),
            context = InspectionContext(device = device, installedApp = null, canRequestInstalls = true),
            displayName = "fixed.apk",
        )
        assertTrue("rebuilt APK should install cleanly: ${recheck.blockers.map { it.id }}", recheck.blockers.isEmpty())
    }
}
