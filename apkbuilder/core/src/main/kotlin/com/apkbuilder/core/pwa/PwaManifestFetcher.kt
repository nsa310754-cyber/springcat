package com.apkbuilder.core.pwa

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Looks up and downloads a site's web app manifest starting from any page
 * URL on that domain (or subdomain) — plain `java.net` I/O, so it runs the
 * same on a JVM test and inside the Android app.
 */
object PwaManifestFetcher {
    private const val TIMEOUT_MS = 15_000
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36 APKBuilder"

    private val MANIFEST_LINK_REGEX = Regex(
        """<link\s+[^>]*rel=["'](?:[^"']*\bmanifest\b[^"']*)["'][^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val HREF_REGEX = Regex("""href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    class FetchException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Fetches and parses the manifest reachable from [pageUrl] (any URL on the target site). */
    fun fetchManifestFor(pageUrl: String): PwaManifest {
        val normalized = normalizeUrl(pageUrl)
        val manifestUrl = discoverManifestUrl(normalized)
            ?: throw FetchException("$normalized から manifest.json を見つけられませんでした(<link rel=\"manifest\"> も /manifest.json も見つかりません)")
        val manifestText = fetchText(manifestUrl)
        return runCatching { PwaManifestParser.parse(manifestText, manifestUrl) }
            .getOrElse { throw FetchException("manifest.json の解析に失敗しました: ${it.message}", it) }
    }

    fun normalizeUrl(input: String): String {
        val trimmed = input.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }

    private fun discoverManifestUrl(pageUrl: String): String? {
        val html = runCatching { fetchText(pageUrl) }.getOrNull()
        if (html != null) {
            val linkTag = MANIFEST_LINK_REGEX.find(html)?.value
            val href = linkTag?.let { HREF_REGEX.find(it)?.groupValues?.get(1) }
            if (href != null) {
                val resolved = runCatching { URI(pageUrl).resolve(href).toString() }.getOrNull()
                if (resolved != null && urlExists(resolved)) return resolved
            }
        }

        val origin = runCatching { URI(pageUrl) }.getOrNull()?.let { "${it.scheme}://${it.authority}" } ?: return null
        for (candidate in listOf("$origin/manifest.json", "$origin/manifest.webmanifest")) {
            if (urlExists(candidate)) return candidate
        }
        return null
    }

    private fun urlExists(url: String): Boolean =
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                instanceFollowRedirects = true
            }
            val ok = conn.responseCode in 200..299
            conn.disconnect()
            ok
        }.getOrDefault(false)

    fun fetchText(url: String): String = String(fetchBytes(url), Charsets.UTF_8)

    fun fetchBytes(url: String): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            // Decode gzip ourselves below — some hosts send Content-Encoding: gzip
            // unconditionally, and relying on the platform's implicit handling
            // (which is inconsistent behind a proxy) silently returned raw gzip bytes.
            setRequestProperty("Accept-Encoding", "gzip")
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw FetchException("HTTP ${conn.responseCode} for $url")
            }
            val raw = conn.inputStream.use { it.readBytes() }
            return if (conn.contentEncoding?.equals("gzip", ignoreCase = true) == true) {
                GZIPInputStream(raw.inputStream()).use { it.readBytes() }
            } else {
                raw
            }
        } catch (e: FetchException) {
            throw e
        } catch (e: Exception) {
            throw FetchException("$url の取得に失敗しました: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }
}
