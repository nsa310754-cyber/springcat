package com.apkbuilder.core.pwa

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Looks up and downloads a site's web app manifest for any page URL on that
 * domain (or subdomain). Manifest text is fetched through the r.jina.ai
 * reader proxy (`https://r.jina.ai/<url>`) rather than a direct connection:
 * some sites hang or silently stall on a plain `HttpURLConnection` from an
 * Android client (anti-bot stalling, DNS quirks, etc.), and the proxy fetches
 * server-side and reliably returns within its own timeout instead. Manifest
 * lookup is a direct check of `https://<origin>/manifest.json` (and
 * `/manifest.webmanifest`) — no HTML page fetch / `<link rel="manifest">`
 * scraping, which was the slow, failure-prone step.
 */
object PwaManifestFetcher {
    private const val TIMEOUT_MS = 20_000
    private const val JINA_READER_PREFIX = "https://r.jina.ai/"

    private val JSON_FENCE_REGEX = Regex("```(?:json)?\\s*([\\s\\S]*?)```")

    class FetchException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Fetches and parses the manifest for the site behind [pageUrl] (any URL on the target site). */
    fun fetchManifestFor(pageUrl: String): PwaManifest {
        val normalized = normalizeUrl(pageUrl)
        val origin = originOf(normalized)
            ?: throw FetchException("URLを解釈できませんでした: $pageUrl")

        val candidates = buildList {
            if (normalized.endsWith(".json", ignoreCase = true) || normalized.endsWith(".webmanifest", ignoreCase = true)) {
                add(normalized)
            }
            add("$origin/manifest.json")
            add("$origin/manifest.webmanifest")
        }.distinct()

        var lastError: Exception? = null
        for (candidate in candidates) {
            val text = try {
                extractJson(fetchTextViaJina(candidate))
            } catch (e: Exception) {
                lastError = e
                continue
            }
            val manifest = runCatching { PwaManifestParser.parse(text, candidate) }.getOrNull()
            if (manifest != null) return manifest
        }
        throw FetchException(
            "$origin から manifest.json / manifest.webmanifest を取得できませんでした" +
                (lastError?.message?.let { ": $it" } ?: ""),
            lastError,
        )
    }

    fun normalizeUrl(input: String): String {
        val trimmed = input.trim()
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
    }

    private fun originOf(url: String): String? =
        runCatching { URI(url) }.getOrNull()?.let { "${it.scheme}://${it.authority}" }

    /** Strips a ```json ... ``` fence some readers wrap non-HTML content in. */
    private fun extractJson(text: String): String {
        val trimmed = text.trim()
        val fenced = JSON_FENCE_REGEX.find(trimmed)?.groupValues?.get(1)?.trim()
        return fenced ?: trimmed
    }

    private fun fetchTextViaJina(targetUrl: String): String = String(fetchBytesViaJina(targetUrl), Charsets.UTF_8)

    private fun fetchBytesViaJina(targetUrl: String): ByteArray {
        val conn = (URL(JINA_READER_PREFIX + targetUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("X-Return-Format", "text")
            setRequestProperty("Accept-Encoding", "gzip")
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw FetchException("HTTP ${conn.responseCode} (via r.jina.ai) for $targetUrl")
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
            throw FetchException("$targetUrl の取得に失敗しました(r.jina.ai経由): ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }

    /** Icons are binary — fetched directly, not through the (text-oriented) reader proxy. */
    fun fetchBytes(url: String): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) APKBuilder")
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
