package com.springcat.apkdoctor.download

import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Drives [ApkDownloader] against a tiny local HTTP server (raw sockets, since
 * `com.sun.net.httpserver` is not on the Android unit-test classpath): a real
 * APK downloads, while a web page / PWA and non-HTTP schemes come back as
 * Unsupported.
 */
class ApkDownloaderTest {

    private val zipMagic = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val dest = File("build/download-test").apply { mkdirs() }

    private lateinit var server: ServerSocket
    @Volatile private var running = true

    private fun base() = "http://127.0.0.1:${server.localPort}"

    private class Response(val status: String, val headers: List<String>, val body: ByteArray)

    private fun route(path: String): Response = when (path.substringBefore('?')) {
        "/game.apk" -> Response("200 OK", listOf("Content-Type: application/vnd.android.package-archive"), zipMagic + ByteArray(2048) { it.toByte() })
        "/page" -> Response("200 OK", listOf("Content-Type: text/html; charset=utf-8"), "<!DOCTYPE html><html><body>PWA</body></html>".toByteArray())
        "/fake.apk" -> Response("200 OK", listOf("Content-Type: application/octet-stream"), "<html>not really an apk</html>".toByteArray())
        "/redirect" -> Response("302 Found", listOf("Location: ${base()}/game.apk"), ByteArray(0))
        "/big" -> Response("200 OK", emptyList(), zipMagic + ByteArray(50_000))
        else -> Response("404 Not Found", emptyList(), "nope".toByteArray())
    }

    @Before
    fun start() {
        server = ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (running) {
                val socket = try { server.accept() } catch (e: Exception) { break }
                socket.use {
                    val reader = BufferedReader(InputStreamReader(it.getInputStream()))
                    val requestLine = reader.readLine() ?: return@use
                    val path = requestLine.split(' ').getOrElse(1) { "/" }
                    while (true) { val h = reader.readLine(); if (h.isNullOrEmpty()) break } // drain headers

                    val r = route(path)
                    val header = buildString {
                        append("HTTP/1.1 ${r.status}\r\n")
                        r.headers.forEach { append(it).append("\r\n") }
                        append("Content-Length: ${r.body.size}\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    it.getOutputStream().apply { write(header.toByteArray()); write(r.body); flush() }
                }
            }
        }
    }

    @After
    fun stop() {
        running = false
        server.close()
    }

    @Test
    fun `downloads a real apk by its magic bytes`() {
        val result = ApkDownloader().download("${base()}/game.apk", dest)
        assertTrue(result is DownloadResult.Downloaded)
        val file = (result as DownloadResult.Downloaded).file
        assertTrue("saved file must exist", file.isFile)
        assertTrue("name keeps the .apk extension", file.name.endsWith(".apk"))
        assertTrue("content must start with the ZIP magic", file.readBytes().take(4).toByteArray().contentEquals(zipMagic))
    }

    @Test
    fun `follows a redirect to the apk`() {
        assertTrue(ApkDownloader().download("${base()}/redirect", dest) is DownloadResult.Downloaded)
    }

    @Test
    fun `a web page is unsupported`() {
        val result = ApkDownloader().download("${base()}/page", dest)
        assertTrue(result is DownloadResult.Unsupported)
        assertTrue((result as DownloadResult.Unsupported).reason.contains("Web"))
    }

    @Test
    fun `html served as octet-stream is still unsupported`() {
        assertTrue(
            "magic-byte sniffing must win over the extension",
            ApkDownloader().download("${base()}/fake.apk", dest) is DownloadResult.Unsupported,
        )
    }

    @Test
    fun `non-http schemes are unsupported without touching the network`() {
        for (url in listOf("market://details?id=com.x", "intent://x#Intent;end", "magnet:?xt=y", "javascript:alert(1)")) {
            assertTrue("$url should be unsupported", ApkDownloader().download(url, dest) is DownloadResult.Unsupported)
        }
    }

    @Test
    fun `oversized downloads are rejected`() {
        assertThrows(DownloadException::class.java) { ApkDownloader(maxBytes = 1024).download("${base()}/big", dest) }
    }

    @Test
    fun `a server error becomes a download exception`() {
        val e = assertThrows(DownloadException::class.java) { ApkDownloader().download("${base()}/missing", dest) }
        assertTrue(e.message!!.contains("HTTP 404"))
    }
}
