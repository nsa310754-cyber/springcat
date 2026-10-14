package com.springcat.apkdoctor

import com.springcat.apkdoctor.apk.ApkBundle
import com.springcat.apkdoctor.apk.Axml
import com.springcat.apkdoctor.apk.AxmlAttribute
import com.springcat.apkdoctor.apk.AxmlDocument
import com.springcat.apkdoctor.apk.DeviceProfile
import com.springcat.apkdoctor.apk.Zips
import com.springcat.apkdoctor.repair.ApkMerger
import com.springcat.apkdoctor.repair.SelfSignedIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * End-to-end merge of a base APK plus real aapt2-generated density/language
 * splits (and a synthesized ABI split) into one universal APK. The merged
 * `resources.arsc` and manifest are checked back with aapt2 — an independent
 * reader — and the signature with apksigner. Skips when the SDK is absent.
 */
class MergeToSingleApkTest {

    private val scratch = File("build/merge-test").apply { mkdirs() }

    private val device = DeviceProfile(36, listOf("arm64-v8a"), 480, listOf("ja"))

    private fun buildTool(name: String): File? {
        val home = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: return null
        return File(home, "build-tools").listFiles()?.sortedDescending()
            ?.firstNotNullOfOrNull { File(it, name).takeIf { f -> f.canExecute() } }
    }

    private fun androidJar(): File? {
        val home = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: return null
        return File(home, "platforms").listFiles()?.sortedDescending()
            ?.firstNotNullOfOrNull { File(it, "android.jar").takeIf { f -> f.isFile } }
    }

    private fun run(vararg argv: String): Pair<Int, String> {
        val p = ProcessBuilder(*argv).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        return p.waitFor() to out
    }

    /** Minimal 1x1 RGB PNG so aapt2 accepts a drawable. */
    private fun writePng(file: File, r: Int, g: Int, b: Int) {
        fun chunk(type: String, data: ByteArray): ByteArray {
            val t = type.toByteArray(Charsets.US_ASCII)
            val len = ByteArray(4).also { it[0] = (data.size ushr 24).toByte(); it[1] = (data.size ushr 16).toByte(); it[2] = (data.size ushr 8).toByte(); it[3] = data.size.toByte() }
            val crc = CRC32().apply { update(t); update(data) }.value
            val crcB = ByteArray(4).also { it[0] = (crc ushr 24).toByte(); it[1] = (crc ushr 16).toByte(); it[2] = (crc ushr 8).toByte(); it[3] = crc.toByte() }
            return len + t + data + crcB
        }
        val ihdr = byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0)
        val raw = byteArrayOf(0, r.toByte(), g.toByte(), b.toByte())
        val compressed = Deflater().run { setInput(raw); finish(); val buf = ByteArray(64); val n = deflate(buf); buf.copyOf(n) }
        file.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + chunk("IHDR", ihdr) + chunk("IDAT", compressed) + chunk("IEND", ByteArray(0)))
    }

    /** base.apk + xxhdpi + ja splits via aapt2, plus a synthesized ABI split. */
    private fun buildFixtures(aapt2: File, jar: File): List<File> {
        val proj = File(scratch, "proj").apply { deleteRecursively() }
        File(proj, "res/drawable").mkdirs(); File(proj, "res/drawable-xxhdpi").mkdirs()
        File(proj, "res/values").mkdirs(); File(proj, "res/values-ja").mkdirs()
        writePng(File(proj, "res/drawable/ic.png"), 255, 0, 0)
        writePng(File(proj, "res/drawable-xxhdpi/ic.png"), 0, 255, 0)
        File(proj, "res/values/strings.xml").writeText("<resources><string name=\"app_name\">App</string><string name=\"hello\">Hello</string></resources>")
        File(proj, "res/values-ja/strings.xml").writeText("<resources><string name=\"app_name\">アプリ</string><string name=\"hello\">こんにちは</string></resources>")
        File(proj, "AndroidManifest.xml").writeText(
            "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"com.example.merge\">" +
                "<application android:label=\"@string/app_name\" android:hasCode=\"false\"/></manifest>",
        )

        val compiled = File(scratch, "compiled.zip")
        val (cc, co) = run(aapt2.path, "compile", "--dir", File(proj, "res").path, "-o", compiled.path)
        assertEquals("aapt2 compile: $co", 0, cc)

        val base = File(scratch, "base.apk")
        val xxhdpi = File(scratch, "split_config.xxhdpi.apk")
        val ja = File(scratch, "split_config.ja.apk")
        val (lc, lo) = run(
            aapt2.path, "link", "-o", base.path, "-I", jar.path,
            "--manifest", File(proj, "AndroidManifest.xml").path,
            "--min-sdk-version", "21", "--target-sdk-version", "34",
            "--split", "${xxhdpi.path}:xxhdpi", "--split", "${ja.path}:ja", compiled.path,
        )
        assertEquals("aapt2 link: $lo", 0, lc)

        // Synthesize an ABI split from the base: a native lib + a split name,
        // with no resources of its own (a real ABI split carries only lib/).
        val abi = File(scratch, "split_config.arm64_v8a.apk")
        val doc = AxmlDocument.parse(requireNotNull(Zips.readEntry(base, "AndroidManifest.xml")))
        requireNotNull(doc.element("manifest")).attributes.add(
            AxmlAttribute(null, "split", 0, "config.arm64_v8a", Axml.TYPE_STRING, 0),
        )
        Zips.repack(
            source = base,
            dest = abi,
            replacements = mapOf(
                "AndroidManifest.xml" to doc.toByteArray(),
                "lib/arm64-v8a/libtest.so" to ByteArray(32) { it.toByte() },
            ),
            keep = { !Zips.isSignatureEntry(it) && it != "resources.arsc" && !it.startsWith("res/") },
        )
        return listOf(base, xxhdpi, ja, abi)
    }

    @Test
    fun `base plus splits merge into one universal apk`() {
        val aapt2 = buildTool("aapt2")
        val apksigner = buildTool("apksigner")
        val jar = androidJar()
        assumeTrue("Android SDK not available", aapt2 != null && jar != null)

        val files = buildFixtures(aapt2!!, jar!!)
        val bundle = ApkBundle.openMultiple(files, File(scratch, "parts"), device)

        val result = ApkMerger { SelfSignedIdentity.generate().first }.merge(
            bundle = bundle,
            workDir = File(scratch, "work"),
            outputDir = File(scratch, "out"),
            log = {},
        )
        val merged = result.file
        assertTrue("merged APK must exist", merged.isFile)
        assertEquals("com.example.merge", result.packageName)
        assertTrue("density/language resources must be merged", result.resourcesMerged)

        // The native lib from the ABI split must be present.
        assertTrue("merged APK must contain the ABI split's .so", "lib/arm64-v8a/libtest.so" in Zips.entryNames(merged))

        // aapt2 — an independent reader — must accept the merged manifest and arsc.
        val (bc, badging) = run(aapt2.path, "dump", "badging", merged.path)
        assertEquals("aapt2 dump badging: $badging", 0, bc)
        assertTrue("no split marker should remain: $badging", !badging.contains("split="))
        assertTrue(badging.contains("com.example.merge"))

        val (rc, resources) = run(aapt2.path, "dump", "resources", merged.path)
        assertEquals("aapt2 dump resources: $resources", 0, rc)
        assertTrue("merged table must keep the base drawable", resources.contains("drawable/ic"))
        assertTrue("merged table must gain the xxhdpi config", resources.contains("(xxhdpi)"))
        assertTrue("merged table must gain the ja config", resources.contains("(ja)"))
        assertTrue("merged table must keep the default string", resources.contains("Hello"))
        assertTrue("merged table must gain the ja string", resources.contains("こんにちは"))

        if (apksigner != null) {
            val (sc, so) = run(apksigner.path, "verify", "--print-certs", merged.path)
            assertEquals("apksigner verify: $so", 0, sc)
        }
    }
}
