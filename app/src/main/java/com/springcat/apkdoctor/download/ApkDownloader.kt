package com.springcat.apkdoctor.download

import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Outcome of trying to fetch a game file from a URL. */
sealed interface DownloadResult {
    /** A real APK/bundle was downloaded and can go into the pipeline. */
    data class Downloaded(val file: File, val fileName: String) : DownloadResult

    /**
     * The URL is not a downloadable package — a PWA / web page, or a non-HTTP
     * "special" scheme (market://, intent://, …). The caller offers to open it.
     */
    data class Unsupported(val url: String, val reason: String) : DownloadResult
}

class DownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Downloads a game file (APK / XAPK / APKS / APKM / AAB) from an http(s) URL.
 *
 * What a URL points at is decided by the bytes, not the server's promises: the
 * first four bytes are peeked, and only a ZIP/APK magic (`PK\x03\x04`) is
 * downloaded. Anything else — an HTML page, a PWA, a login wall — comes back as
 * [DownloadResult.Unsupported] so the app can open it in a browser instead of
 * mis-parsing a web page as an APK. Non-HTTP schemes are refused up front.
 */
class ApkDownloader(
    private val maxBytes: Long = 2L * 1024 * 1024 * 1024,
    private val maxRedirects: Int = 5,
) {
    fun interface Progress {
        /** [total] is -1 when the server sends no Content-Length. */
        fun onProgress(downloaded: Long, total: Long)
    }

    fun download(rawUrl: String, destDir: File, progress: Progress = Progress { _, _ -> }): DownloadResult {
        val url = rawUrl.trim()
        val scheme = url.substringBefore(':', "").lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") {
            return DownloadResult.Unsupported(url, "対応していないURLです（$scheme スキーム）。ブラウザで開けます。")
        }

        val connection = connect(url)
        try {
            val contentType = connection.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
            val fileName = resolveFileName(connection, url)

            BufferedInputStream(connection.inputStream).use { input ->
                val magic = peekMagic(input)
                if (!isZip(magic)) {
                    val reason = if (looksLikeWebPage(contentType, magic)) {
                        "これはWebページ／PWAです。APKではないため、ダウンロードには未対応です。ブラウザで開けます。"
                    } else {
                        "APK/ZIP形式ではありません（${contentType.ifEmpty { "不明" }}）。ブラウザで開けます。"
                    }
                    return DownloadResult.Unsupported(url, reason)
                }

                destDir.mkdirs()
                val target = File(destDir, ensureApkName(fileName))
                val total = connection.contentLengthLong
                writeStream(input, target, total, progress)
                return DownloadResult.Downloaded(target, target.name)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun connect(startUrl: String): HttpURLConnection {
        var current = startUrl
        var redirects = 0
        while (true) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false // handled manually to allow http<->https
                connectTimeout = 15_000
                readTimeout = 30_000
                requestMethod = "GET"
                setRequestProperty("Accept", "*/*")
                setRequestProperty("User-Agent", "APKDoctor")
            }
            val code = try {
                connection.responseCode
            } catch (e: Exception) {
                connection.disconnect()
                throw DownloadException("接続に失敗しました: ${e.message}", e)
            }
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location.isNullOrBlank()) throw DownloadException("リダイレクト先が不明です")
                if (++redirects > maxRedirects) throw DownloadException("リダイレクトが多すぎます")
                current = URL(URL(current), location).toString() // resolve relative redirects
                continue
            }
            if (code != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                throw DownloadException("サーバーがエラーを返しました (HTTP $code)")
            }
            return connection
        }
    }

    /** Reads up to 4 bytes without consuming them. */
    private fun peekMagic(input: BufferedInputStream): ByteArray {
        input.mark(8)
        val magic = ByteArray(4)
        val read = input.read(magic)
        input.reset()
        return if (read <= 0) ByteArray(0) else magic.copyOf(read)
    }

    private fun isZip(magic: ByteArray): Boolean =
        magic.size >= 4 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() &&
            magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte()

    private fun looksLikeWebPage(contentType: String, magic: ByteArray): Boolean {
        if (contentType.startsWith("text/html") || contentType.startsWith("application/xhtml")) return true
        // A body that opens with '<' is markup (an HTML page or a PWA shell).
        return magic.isNotEmpty() && magic[0] == '<'.code.toByte()
    }

    private fun writeStream(input: java.io.InputStream, target: File, total: Long, progress: Progress) {
        var written = 0L
        val buffer = ByteArray(64 * 1024)
        target.outputStream().use { output ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                written += n
                if (written > maxBytes) {
                    target.delete()
                    throw DownloadException("ファイルが大きすぎます（上限 ${maxBytes / (1024 * 1024)}MB）")
                }
                output.write(buffer, 0, n)
                progress.onProgress(written, total)
            }
        }
        if (written == 0L) {
            target.delete()
            throw DownloadException("空のファイルを受信しました")
        }
    }

    private fun resolveFileName(connection: HttpURLConnection, url: String): String {
        connection.getHeaderField("Content-Disposition")?.let { disposition ->
            Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE)
                .find(disposition)?.groupValues?.get(1)?.trim()?.let { return it }
        }
        val path = runCatching { URL(url).path }.getOrDefault("")
        return path.substringAfterLast('/').ifBlank { "download" }
    }

    /** APK tooling keys off the extension, so give a bare name one. */
    private fun ensureApkName(name: String): String {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "download" }
        val known = listOf(".apk", ".xapk", ".apks", ".apkm", ".aab", ".zip")
        return if (known.any { safe.endsWith(it, ignoreCase = true) }) safe else "$safe.apk"
    }
}
