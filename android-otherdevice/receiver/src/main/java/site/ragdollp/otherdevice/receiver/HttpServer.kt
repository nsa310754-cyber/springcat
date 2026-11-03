package site.ragdollp.otherdevice.receiver

import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ごく小さい HTTP サーバ。POST /upload のみ処理する。
 * Content-Length の長さだけ生バイトを読み、
 * /sdcard/otherdevice/<X-Sender>/<X-Filename> に書き出す。
 *
 * スレッドプール(=並列接続可)、Connection ごとに受理後クローズ。
 */
class HttpServer(
    private val port: Int,
    private val baseDir: File,
    private val onEvent: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        baseDir.mkdirs()
        Thread({
            try {
                server = ServerSocket(port)
                onEvent("listening on :$port → ${baseDir.absolutePath}")
                while (running.get()) {
                    val s = try { server!!.accept() } catch (_: Throwable) { break }
                    pool.execute { handle(s) }
                }
            } catch (t: Throwable) {
                onEvent("server error: ${t.message}")
            } finally {
                server?.close()
                onEvent("stopped")
            }
        }, "http-accept").start()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { server?.close() } catch (_: Throwable) {}
        pool.shutdownNow()
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 60_000
            val input = BufferedInputStream(sock.getInputStream())
            val out = sock.getOutputStream()

            val requestLine = readLine(input) ?: return respond(out, 400, "bad request")
            val parts = requestLine.split(' ')
            if (parts.size < 3 || parts[0] != "POST" || parts[1] != "/upload") {
                return respond(out, 404, "only POST /upload")
            }

            // ヘッダ読み
            var contentLength = -1L
            var sender = "unknown"
            var filename = "file.bin"
            while (true) {
                val line = readLine(input) ?: return respond(out, 400, "bad headers")
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon < 0) continue
                val k = line.substring(0, colon).trim().lowercase()
                val v = line.substring(colon + 1).trim()
                when (k) {
                    "content-length" -> contentLength = v.toLongOrNull() ?: -1L
                    "x-sender" -> sender = URLDecoder.decode(v, "UTF-8")
                    "x-filename" -> filename = URLDecoder.decode(v, "UTF-8")
                }
            }
            if (contentLength < 0) return respond(out, 411, "need content-length")

            val safeSender = safeName(sender)
            val safeFile = safeName(filename)
            val dir = File(baseDir, safeSender).apply { mkdirs() }
            val target = uniquePath(dir, safeFile)

            // ボディをストリームでファイルへ
            var remaining = contentLength
            val buf = ByteArray(32 * 1024)
            FileOutputStream(target).use { fo ->
                while (remaining > 0) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val n = input.read(buf, 0, toRead)
                    if (n <= 0) throw RuntimeException("short read")
                    fo.write(buf, 0, n)
                    remaining -= n
                }
            }
            onEvent("saved: ${target.absolutePath} (${target.length()} bytes)")
            respond(out, 200, "ok ${target.name}")
        } catch (t: Throwable) {
            Log.w("HttpServer", "handle failed", t)
            try { respond(sock.getOutputStream(), 500, "err ${t.message}") } catch (_: Throwable) {}
        } finally {
            try { sock.close() } catch (_: Throwable) {}
        }
    }

    private fun respond(out: java.io.OutputStream, code: Int, text: String) {
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"
            411 -> "Length Required"; 500 -> "Server Error"; else -> "Error" }
        val body = (text + "\n").toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code $reason\r\nContent-Length: ${body.size}\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (prev == '\r'.code && b == '\n'.code) { sb.setLength(sb.length - 1); return sb.toString() }
            sb.append(b.toChar()); prev = b
        }
    }

    private fun safeName(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\u0000]"), "_").take(120).ifBlank { "unnamed" }

    private fun uniquePath(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            f = File(dir, "$base ($i)$ext")
            if (!f.exists()) return f
            i++
        }
    }
}
