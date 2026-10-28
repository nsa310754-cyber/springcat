package com.example.bionicdesktop

import io.airlift.compress.zstd.ZstdDecompressor
import org.tukaani.xz.XZInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream

/**
 * 外部ツールなしで扱える最小アーカイブ処理(純 Kotlin)。
 * 対応: tar(USTAR)/ gzip / ar(deb コンテナ)。xz・zstd は非対応(報告のみ)。
 */
object Archive {

    enum class Kind { ELF, PE, GZIP, TAR, DEB, XZ, ZSTD, ZIP, UNKNOWN }

    /** 先頭マジックバイトで種別を判定する。 */
    fun detect(file: File): Kind {
        val h = ByteArray(264)
        file.inputStream().use { it.read(h) }
        return when {
            h.size >= 4 && h[0] == 0x7F.toByte() && h[1] == 'E'.code.toByte() &&
                h[2] == 'L'.code.toByte() && h[3] == 'F'.code.toByte() -> Kind.ELF
            h.size >= 2 && h[0] == 'M'.code.toByte() && h[1] == 'Z'.code.toByte() -> Kind.PE // Windows PE (.exe/.dll)
            h.size >= 2 && h[0] == 0x1F.toByte() && h[1] == 0x8B.toByte() -> Kind.GZIP
            h.size >= 6 && String(h, 0, 6, Charsets.US_ASCII) == "!<arch" -> Kind.DEB
            h.size >= 6 && h[0] == 0xFD.toByte() && String(h, 1, 5, Charsets.US_ASCII) == "7zXZ\u0000" -> Kind.XZ
            h.size >= 4 && h[0] == 0x28.toByte() && h[1] == 0xB5.toByte() &&
                h[2] == 0x2F.toByte() && h[3] == 0xFD.toByte() -> Kind.ZSTD
            h.size >= 4 && h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte() -> Kind.ZIP
            h.size >= 263 && String(h, 257, 5, Charsets.US_ASCII) == "ustar" -> Kind.TAR
            else -> Kind.UNKNOWN
        }
    }

    /** ELF の e_machine(CPU 種別)を返す。ELF でなければ -1。 */
    fun elfMachine(file: File): Int {
        val h = ByteArray(20)
        file.inputStream().use { if (it.read(h) < 20) return -1 }
        val isElf = h[0] == 0x7F.toByte() && h[1] == 'E'.code.toByte() &&
            h[2] == 'L'.code.toByte() && h[3] == 'F'.code.toByte()
        if (!isElf) return -1
        val le = h[5].toInt() != 2 // EI_DATA: 2=big endian
        val b18 = h[18].toInt() and 0xFF
        val b19 = h[19].toInt() and 0xFF
        return if (le) (b19 shl 8) or b18 else (b18 shl 8) or b19
    }

    // 代表的な e_machine
    const val EM_386 = 3
    const val EM_ARM = 40
    const val EM_X86_64 = 62
    const val EM_AARCH64 = 183

    /** glibc 依存の ELF か(PT_INTERP に ld-linux を持つか)を緩く判定する。 */
    fun elfNeedsGlibc(file: File): Boolean {
        val bytes = file.readBytes()
        val needle = "ld-linux".toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..(bytes.size - needle.size)) {
            for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    /** tar(USTAR)を outBase へ展開。展開したトップレベル名の集合を返す。 */
    fun untar(input: InputStream, outBase: File): Set<String> {
        val tops = linkedSetOf<String>()
        val header = ByteArray(512)
        while (true) {
            if (!readFully(input, header)) break
            if (header.all { it == 0.toByte() }) break
            val name = String(header, 0, 100, Charsets.UTF_8).substringBefore('\u0000').trim()
            if (name.isEmpty()) break
            tops.add(name.trimStart('.', '/').substringBefore('/'))
            val size = parseOctal(header, 124, 12)
            val type = header[156].toInt().toChar()
            val out = File(outBase, name)
            when (type) {
                '5' -> out.mkdirs()
                '0', '\u0000' -> {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { copyExact(input, it, size) }
                }
                else -> skip(input, size)
            }
            val pad = ((512 - (size % 512)) % 512)
            if (pad > 0) skip(input, pad)
        }
        return tops
    }

    /** gzip を展開して中身を返す(tar.gz なら untar に渡す用)。 */
    fun gunzip(input: InputStream): InputStream = GZIPInputStream(input)

    /** xz を展開して中身を返す(pure-Java)。 */
    fun unxz(input: InputStream): InputStream = XZInputStream(input)

    /** zstd を一括展開して中身を返す(pure-Java。フレームに非圧縮サイズが必要)。 */
    fun unzstd(input: InputStream): InputStream {
        val src = input.readBytes()
        val size = ZstdDecompressor.getDecompressedSize(src, 0, src.size)
        require(size in 1..Int.MAX_VALUE.toLong()) { "zstd: 非圧縮サイズ不明/過大 ($size)" }
        val out = ByteArray(size.toInt())
        val n = ZstdDecompressor().decompress(src, 0, src.size, out, 0, out.size)
        return ByteArrayInputStream(out, 0, n)
    }

    /** 圧縮ストリームを種別に応じて復元する(gz/xz/zst/無圧縮)。 */
    private fun decompressByName(name: String, raw: InputStream): InputStream = when {
        name.endsWith(".gz") -> gunzip(raw)
        name.endsWith(".xz") -> unxz(raw)
        name.endsWith(".zst") -> unzstd(raw)
        else -> raw
    }

    /**
     * deb(ar コンテナ)から data.tar* を取り出して outBase へ展開する。
     * data.tar.gz / data.tar は対応。data.tar.xz / .zst は非対応。
     * @return 説明メッセージ
     */
    fun extractDeb(file: File, outBase: File): String {
        file.inputStream().buffered().use { input ->
            val magic = ByteArray(8)
            if (!readFully(input, magic) || String(magic, Charsets.US_ASCII) != "!<arch>\n")
                return "deb ではありません"
            while (true) {
                val hdr = ByteArray(60)
                if (!readFully(input, hdr)) break
                val memName = String(hdr, 0, 16, Charsets.US_ASCII).trim()
                val memSize = String(hdr, 48, 10, Charsets.US_ASCII).trim().toLongOrNull() ?: break
                if (memName.startsWith("data.tar")) {
                    val limited = LimitedInput(input, memSize)
                    val tar = decompressByName(memName, limited)
                    val tops = untar(tar, outBase)
                    return "$memName を展開: ${tops.joinToString(", ")}"
                } else {
                    skip(input, memSize)
                    if (memSize % 2 == 1L) skip(input, 1) // ar は 2 バイト境界
                }
            }
        }
        return "data.tar* が見つかりませんでした"
    }

    // ---- helpers ----
    private fun parseOctal(b: ByteArray, off: Int, len: Int): Long {
        val s = String(b, off, len, Charsets.US_ASCII).trim { it == ' ' || it == '\u0000' }
        return if (s.isEmpty()) 0 else (s.toLongOrNull(8) ?: 0)
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

    private fun copyExact(input: InputStream, out: OutputStream, size: Long) {
        var rem = size; val buf = ByteArray(64 * 1024)
        while (rem > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
            if (r < 0) break
            out.write(buf, 0, r); rem -= r
        }
    }

    private fun skip(input: InputStream, size: Long) {
        var rem = size; val buf = ByteArray(64 * 1024)
        while (rem > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
            if (r < 0) break
            rem -= r
        }
    }

    /** ar メンバーを size バイトに制限して読む InputStream(gzip/untar へ渡す用)。 */
    private class LimitedInput(val base: InputStream, var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = base.read(); if (b >= 0) remaining--
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val r = base.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (r > 0) remaining -= r
            return r
        }
    }
}
