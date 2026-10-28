package com.example.bionicdesktop

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * glibc ランタイム(APK 同梱・ネット不要)。
 *
 * 【方針】
 * Android は Bionic なので glibc バイナリはそのままでは動かない(ローダ ld-linux-*.so.1 と
 * glibc 共有ライブラリが無い)。そこで ARM64 の glibc 一式(ローダ + libc 等 + デモ実行ファイル
 * uname)を APK の assets に同梱し、初回に filesDir へ展開してローダ経由で実行する。
 * ダウンロードは一切しない(オフラインで完結)。
 *
 * 実行形(方式①・無改変): ld-linux-aarch64.so.1 --library-path LIBS PROGRAM ARGS
 *
 * 同梱物: assets/glibc-runtime-arm64.tar.gz → 展開すると filesDir/glibc/{lib,bin}
 * ・lib/ld-linux-aarch64.so.1, lib/libc.so.6 ...(Debian bookworm glibc 2.36)
 * ・bin/uname(動作確認用 glibc 実行ファイル)
 */
object GlibcRuntime {

    private const val ASSET = "glibc-runtime-arm64.tar.gz"

    /** glibc 展開先(filesDir/glibc)。 */
    fun root(ctx: Context): File = File(ctx.filesDir, "glibc")

    fun loaderPath(ctx: Context): File = File(root(ctx), "lib/ld-linux-aarch64.so.1")
    fun libDir(ctx: Context): File = File(root(ctx), "lib")
    fun binDir(ctx: Context): File = File(root(ctx), "bin")

    /** 展開済みか(ローダの存在で判定)。 */
    fun isInstalled(ctx: Context): Boolean = loaderPath(ctx).exists()

    /**
     * assets の tar.gz を filesDir へ展開する(純 Kotlin。外部 tar 不要)。
     * 端末が arm64 でない場合は実行できないので、その旨だけ記録する。
     */
    fun install(ctx: Context, onLog: (String) -> Unit): Boolean {
        if (isInstalled(ctx)) { onLog("既に展開済み"); return true }
        val is64Arm = android.os.Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
        if (!is64Arm) onLog("警告: この端末は arm64 ではないため同梱 glibc(arm64)は実行できません")
        return try {
            onLog("assets から展開中: $ASSET")
            ctx.assets.open(ASSET).use { raw ->
                GZIPInputStream(raw).use { gz -> untar(gz, ctx.filesDir) }
            }
            // ローダ・libs・bin に実行権限を付与
            root(ctx).walkTopDown().forEach { if (it.isFile) it.setExecutable(true, false) }
            onLog(if (isInstalled(ctx)) "✓ 展開完了 (${root(ctx).absolutePath})" else "✗ ローダが見つからない")
            isInstalled(ctx)
        } catch (e: Exception) {
            onLog("展開失敗: ${e.message}")
            false
        }
    }

    /** 動作確認: 同梱 uname を glibc ローダ経由で実行する。 */
    fun demoUname(ctx: Context): String {
        if (!isInstalled(ctx)) return "未展開。先に『glibc を展開(内蔵)』を実行してください。"
        return runViaLoader(ctx, File(binDir(ctx), "uname").absolutePath, listOf("-a"))
    }

    /**
     * 方式①: glibc バイナリをローダ経由で実行(無改変)。
     * 実行形: ld.so --library-path LIBS PROGRAM ARGS
     */
    fun runViaLoader(ctx: Context, program: String, args: List<String> = emptyList()): String {
        if (!isInstalled(ctx)) return "glibc 未展開。先に『glibc を展開(内蔵)』を実行してください。"
        val loader = loaderPath(ctx).absolutePath
        val libs = libDir(ctx).absolutePath
        // 自前アプリは Termux と違い LD_PRELOAD を注入しないので unset 不要。
        return NativeExecutor.run(
            ctx, loader,
            listOf("--library-path", libs, program) + args,
            env = mapOf("LD_LIBRARY_PATH" to libs),
        )
    }

    // ---- 最小 tar 展開(USTAR。通常ファイル/ディレクトリのみ対応) ----
    private fun untar(input: InputStream, outBase: File) {
        val header = ByteArray(512)
        while (true) {
            if (!readFully(input, header)) break
            if (header.all { it == 0.toByte() }) break // 終端ブロック
            val name = String(header, 0, 100, Charsets.UTF_8).substringBefore('\u0000').trim()
            if (name.isEmpty()) break
            val size = parseOctal(header, 124, 12)
            val type = header[156].toInt().toChar()
            val out = File(outBase, name)
            when (type) {
                '5' -> out.mkdirs() // ディレクトリ
                '0', '\u0000' -> {  // 通常ファイル
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o -> copyExact(input, o, size) }
                }
                else -> skip(input, size) // リンク等は無視
            }
            // 512 境界までのパディングを読み飛ばす
            val pad = ((512 - (size % 512)) % 512)
            if (pad > 0) skip(input, pad)
        }
    }

    private fun parseOctal(b: ByteArray, off: Int, len: Int): Long {
        var s = String(b, off, len, Charsets.US_ASCII).trim { it == ' ' || it == '\u0000' }
        if (s.isEmpty()) return 0
        return s.toLongOrNull(8) ?: 0
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var read = 0
        while (read < buf.size) {
            val r = input.read(buf, read, buf.size - read)
            if (r < 0) return read == buf.size
            read += r
        }
        return true
    }

    private fun copyExact(input: InputStream, out: java.io.OutputStream, size: Long) {
        var remaining = size
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val r = input.read(buf, 0, toRead)
            if (r < 0) break
            out.write(buf, 0, r)
            remaining -= r
        }
    }

    private fun skip(input: InputStream, size: Long) {
        var remaining = size
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val r = input.read(buf, 0, toRead)
            if (r < 0) break
            remaining -= r
        }
    }
}
