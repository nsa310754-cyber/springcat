package com.springcat.apkdoctor.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Covers the content extractor: the HTML inliner produces one self-contained
 * file, path resolution stays inside the asset root, and an APK with no HTML
 * entry point falls back to a ZIP of its content.
 */
class ContentExtractorTest {

    private val scratch = File("build/content-test").apply { mkdirs() }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `picks index html at the shallowest depth`() {
        assertEquals(
            "index.html",
            ContentExtractor.pickMainHtml(listOf("js/a.js", "sub/index.html", "index.html")),
        )
        assertEquals("game.html", ContentExtractor.pickMainHtml(listOf("game.html", "style.css")))
        assertNull(ContentExtractor.pickMainHtml(listOf("data.json", "a.js")))
    }

    @Test
    fun `resolve stays within the asset root`() {
        assertEquals("js/app.js", ContentExtractor.resolve("js/app.js", ""))
        assertEquals("js/app.js", ContentExtractor.resolve("app.js", "js"))
        assertEquals("app.js", ContentExtractor.resolve("../app.js", "js"))
        assertEquals("a/c.png", ContentExtractor.resolve("./b/../c.png", "a"))
        // External or out-of-root references are refused.
        assertNull(ContentExtractor.resolve("https://cdn/x.js", ""))
        assertNull(ContentExtractor.resolve("//cdn/x.js", ""))
        assertNull(ContentExtractor.resolve("data:text/js,1", ""))
        assertNull(ContentExtractor.resolve("#frag", ""))
        assertNull(ContentExtractor.resolve("../../etc/passwd", ""))
    }

    @Test
    fun `combineHtml inlines local scripts, styles, images and css urls`() {
        val entries = mapOf(
            "game.html" to """
                <html><head>
                  <link rel="stylesheet" href="css/app.css">
                  <script src="js/app.js"></script>
                  <script src="https://cdn.example.com/lib.js"></script>
                </head><body>
                  <img src="img/logo.png">
                  <a href="help.html">help</a>
                </body></html>
            """.trimIndent().toByteArray(),
            "css/app.css" to "body{background:url(../img/bg.png)}".toByteArray(),
            "js/app.js" to "console.log('hi');".toByteArray(),
            "img/logo.png" to bytes(0x89, 0x50, 0x4E, 0x47),
            "img/bg.png" to bytes(1, 2, 3, 4),
        )

        val html = ContentExtractor.combineHtml(entries, "game.html")!!.toString(Charsets.UTF_8)

        assertTrue("local JS inlined", html.contains("console.log('hi');"))
        assertFalse("script src removed", html.contains("js/app.js"))
        assertTrue("CDN script left external", html.contains("https://cdn.example.com/lib.js"))
        assertTrue("stylesheet inlined as <style>", html.contains("<style>") && html.contains("background:url(data:image/png;base64,"))
        assertFalse("link href removed", html.contains("css/app.css"))
        assertTrue("image inlined as data URI", html.contains("src=\"data:image/png;base64,"))
        assertTrue("nav link to another page is left alone", html.contains("href=\"help.html\""))
    }

    @Test
    fun `combineHtml falls back (returns null) when it would exceed the cap`() {
        val entries = mapOf(
            "index.html" to "<img src='big.bin'>".toByteArray(),
            "big.bin" to ByteArray(4096),
        )
        assertNull("over the tiny cap", ContentExtractor.combineHtml(entries, "index.html", sizeCap = 256))
    }

    @Test
    fun `an apk with a web game combines into one html`() {
        val apk = buildApk(
            "assets/www/index.html" to "<link rel=stylesheet href=\"s.css\"><div>game</div>".toByteArray(),
            "assets/www/s.css" to "div{color:red}".toByteArray(),
            "classes.dex" to ByteArray(8),
        )
        val combined = ContentExtractor.combine(apk, "com.example.game")
        assertTrue(combined is ContentExtractor.Combined.Html)
        val html = String((combined as ContentExtractor.Combined.Html).bytes)
        assertTrue(html.contains("<style>") && html.contains("color:red"))
        assertTrue(combined.fileName.endsWith(".html"))
    }

    @Test
    fun `an apk with no html content falls back to a zip`() {
        val apk = buildApk(
            "assets/data/level1.bin" to ByteArray(16),
            "assets/data/level2.bin" to ByteArray(16),
            "classes.dex" to ByteArray(8),
        )
        val combined = ContentExtractor.combine(apk, "com.example.game")
        assertTrue(combined is ContentExtractor.Combined.Archive)

        // The ZIP must contain exactly the content entries, not the dex.
        val zip = File(scratch, "out.zip").apply { writeBytes((combined as ContentExtractor.Combined.Archive).bytes) }
        val names = ZipFile(zip).use { z -> z.entries().asSequence().map { it.name }.toSet() }
        assertEquals(setOf("assets/data/level1.bin", "assets/data/level2.bin"), names)
    }

    @Test
    fun `listContent reports assets and res raw, not code`() {
        val apk = buildApk(
            "assets/a.html" to ByteArray(3),
            "res/raw/song.mp3" to ByteArray(5),
            "classes.dex" to ByteArray(8),
            "AndroidManifest.xml" to ByteArray(4),
        )
        val paths = ContentExtractor.listContent(apk).map { it.path }
        assertEquals(listOf("assets/a.html", "res/raw/song.mp3"), paths)
    }

    private fun buildApk(vararg entries: Pair<String, ByteArray>): File {
        val apk = File(scratch, "fixture-${entries.hashCode()}.apk")
        ZipOutputStream(apk.outputStream()).use { zos ->
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return apk
    }
}
