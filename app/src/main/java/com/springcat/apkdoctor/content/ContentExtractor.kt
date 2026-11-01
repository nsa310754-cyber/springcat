package com.springcat.apkdoctor.content

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Surfaces the app's own content — the files it ships under `assets/` (and
 * `res/raw/`), which for a WebView game is the HTML/JS/CSS it actually runs —
 * and can fold that content into a single file.
 *
 * For a web game (an HTML entry point under `assets`) the result is one
 * self-contained HTML: same-APK scripts, stylesheets, images, fonts and media
 * referenced by relative paths are inlined (scripts and CSS inline, binaries as
 * `data:` URIs), recursing through `url(...)` inside CSS. Anything with no HTML
 * entry point, or that would inline too large, is bundled as a single ZIP
 * instead. External URLs and resources fetched at runtime by JS (`fetch`/XHR)
 * are left as-is — static inlining cannot follow those.
 */
object ContentExtractor {

    private const val DEFAULT_SIZE_CAP = 64L * 1024 * 1024

    data class ContentFile(val path: String, val size: Long)

    sealed interface Combined {
        val fileName: String
        val bytes: ByteArray

        /** One standalone HTML with local resources inlined. */
        class Html(override val fileName: String, override val bytes: ByteArray) : Combined

        /** A ZIP of the content files (no single HTML entry point, or too large). */
        class Archive(override val fileName: String, override val bytes: ByteArray) : Combined
    }

    /** Content entry paths with sizes: everything under `assets`, then `res/raw`. */
    fun listContent(apk: File): List<ContentFile> = runCatching {
        ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && isContent(it.name) }
                .map { ContentFile(it.name, it.size.coerceAtLeast(0)) }
                .sortedBy { it.path }
                .toList()
        }
    }.getOrDefault(emptyList())

    /** Builds the combined output, or null when the APK ships no content files. */
    fun combine(apk: File, baseName: String, sizeCap: Long = DEFAULT_SIZE_CAP): Combined? {
        val assets = readAssets(apk)
        val mainHtml = assets.keys.let(::pickMainHtml)
        if (mainHtml != null) {
            val html = runCatching { combineHtml(assets, mainHtml, sizeCap) }.getOrNull()
            if (html != null) return Combined.Html("$baseName.html", html)
        }
        // No HTML entry point, or inlining overflowed: bundle everything as a ZIP.
        val zip = zipContent(apk) ?: return null
        return Combined.Archive("$baseName-content.zip", zip)
    }

    private fun isContent(name: String): Boolean =
        name.startsWith("assets/") || name.startsWith("res/raw/")

    /** assets entries keyed relative to the assets root (e.g. `js/app.js`). */
    private fun readAssets(apk: File): Map<String, ByteArray> = runCatching {
        ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith("assets/") }
                .associate { it.name.removePrefix("assets/") to zip.getInputStream(it).use { s -> s.readBytes() } }
        }
    }.getOrDefault(emptyMap())

    private fun zipContent(apk: File): ByteArray? = runCatching {
        ZipFile(apk).use { zip ->
            val entries = zip.entries().asSequence().filter { !it.isDirectory && isContent(it.name) }.toList()
            if (entries.isEmpty()) return null
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zos ->
                for (entry in entries) {
                    zos.putNextEntry(ZipEntry(entry.name))
                    zip.getInputStream(entry).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            out.toByteArray()
        }
    }.getOrNull()

    /** index.html at the shallowest depth, else the single/first *.html. */
    internal fun pickMainHtml(paths: Collection<String>): String? {
        val htmls = paths.filter { it.endsWith(".html", true) || it.endsWith(".htm", true) }
        if (htmls.isEmpty()) return null
        return htmls.filter { it.substringAfterLast('/').equals("index.html", true) }
            .minByOrNull { it.count { c -> c == '/' } }
            ?: htmls.minByOrNull { it.count { c -> c == '/' } }
    }

    // --- HTML inlining ---------------------------------------------------

    private val SCRIPT_SRC = Regex(
        """<script\b[^>]*?\bsrc\s*=\s*(["'])(.*?)\1[^>]*>\s*</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val LINK_TAG = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val STYLE_BLOCK = Regex(
        """(<style\b[^>]*>)(.*?)(</style>)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val MEDIA_TAG = Regex("""<(img|source|audio|video|track|image|input)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val MEDIA_ATTR = Regex("""\b(src|poster|href|xlink:href)\s*=\s*(["'])(.*?)\2""", RegexOption.IGNORE_CASE)
    private val CSS_URL = Regex("""url\(\s*(["']?)(.*?)\1\s*\)""", RegexOption.IGNORE_CASE)
    private val HREF_ATTR = Regex("""\bhref\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE)

    internal fun combineHtml(entries: Map<String, ByteArray>, mainPath: String, sizeCap: Long = DEFAULT_SIZE_CAP): ByteArray? {
        val baseDir = mainPath.substringBeforeLast('/', "")
        var html = entries[mainPath]?.toString(Charsets.UTF_8) ?: return null

        // 1. Media tags -> data: URIs (scoped to media tags so script/CSS text is untouched).
        html = MEDIA_TAG.replace(html) { tag ->
            MEDIA_ATTR.replace(tag.value) inner@{ attr ->
                val resolved = resolve(attr.groupValues[3], baseDir) ?: return@inner attr.value
                val bytes = entries[resolved] ?: return@inner attr.value
                "${attr.groupValues[1]}=${attr.groupValues[2]}${dataUri(resolved, bytes)}${attr.groupValues[2]}"
            }
        }

        // 2. <script src> -> inline JS.
        html = SCRIPT_SRC.replace(html) { m ->
            val resolved = resolve(m.groupValues[2], baseDir) ?: return@replace m.value
            val js = entries[resolved]?.toString(Charsets.UTF_8) ?: return@replace m.value
            "<script>\n${js.replace("</script", "<\\/script")}\n</script>"
        }

        // 3. <link rel=stylesheet href> -> inline <style> (with its own url()s inlined).
        html = LINK_TAG.replace(html) { m ->
            val tag = m.value
            if (!tag.contains("stylesheet", ignoreCase = true)) return@replace tag
            val href = HREF_ATTR.find(tag)?.groupValues?.get(2) ?: return@replace tag
            val resolved = resolve(href, baseDir) ?: return@replace tag
            val css = entries[resolved]?.toString(Charsets.UTF_8) ?: return@replace tag
            "<style>\n${inlineCss(css, resolved.substringBeforeLast('/', ""), entries)}\n</style>"
        }

        // 4. Pre-existing inline <style> blocks: inline their url()s.
        html = STYLE_BLOCK.replace(html) { m ->
            m.groupValues[1] + inlineCss(m.groupValues[2], baseDir, entries) + m.groupValues[3]
        }

        val out = html.toByteArray(Charsets.UTF_8)
        return if (out.size.toLong() > sizeCap) null else out
    }

    private fun inlineCss(css: String, cssDir: String, entries: Map<String, ByteArray>): String =
        CSS_URL.replace(css) { m ->
            val resolved = resolve(m.groupValues[2], cssDir) ?: return@replace m.value
            val bytes = entries[resolved] ?: return@replace m.value
            "url(${dataUri(resolved, bytes)})"
        }

    /**
     * Resolves a relative reference against [baseDir] to an assets key, or null
     * when it is external (absolute URL, data:, fragment, …) or escapes the root.
     */
    internal fun resolve(reference: String, baseDir: String): String? {
        val ref = reference.trim()
        if (ref.isEmpty()) return null
        val lower = ref.lowercase(Locale.ROOT)
        val external = listOf("http://", "https://", "//", "data:", "blob:", "mailto:", "tel:", "javascript:", "#")
        if (external.any { lower.startsWith(it) }) return null

        val clean = ref.substringBefore('#').substringBefore('?')
        if (clean.isEmpty()) return null
        val start = if (clean.startsWith("/")) "" else baseDir
        val combined = if (start.isEmpty()) clean.removePrefix("/") else "$start/${clean}"

        val stack = ArrayDeque<String>()
        for (part in combined.split('/')) {
            when (part) {
                "", "." -> {}
                ".." -> if (stack.isNotEmpty()) stack.removeLast() else return null
                else -> stack.addLast(part)
            }
        }
        return stack.joinToString("/").ifEmpty { null }
    }

    private fun dataUri(path: String, bytes: ByteArray): String =
        "data:${mimeOf(path)};base64,${Base64.getEncoder().encodeToString(bytes)}"

    private fun mimeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "css" -> "text/css"
        "js", "mjs" -> "text/javascript"
        "html", "htm" -> "text/html"
        "json" -> "application/json"
        "wasm" -> "application/wasm"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "ico" -> "image/x-icon"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "mp3" -> "audio/mpeg"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        else -> "application/octet-stream"
    }
}
