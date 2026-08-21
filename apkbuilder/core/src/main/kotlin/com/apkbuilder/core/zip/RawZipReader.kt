package com.apkbuilder.core.zip

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/** One entry as found in a ZIP's central directory, with raw (still compressed) bytes available. */
class ZipEntryRecord(
    val name: String,
    val method: Int, // 0 = STORED, 8 = DEFLATE
    val crc32: Long,
    val compressedSize: Long,
    val uncompressedSize: Long,
    private val source: ByteArray,
    private val localHeaderOffset: Long,
) {
    /** Bytes exactly as stored in the ZIP (compressed if method==DEFLATE, raw if STORED). */
    fun rawBytes(): ByteArray {
        val p = localHeaderOffset.toInt()
        require(RawZipReader.readU32(source, p) == 0x04034b50L) { "bad local file header for $name" }
        val nameLen = RawZipReader.readU16(source, p + 26)
        val extraLen = RawZipReader.readU16(source, p + 28)
        val dataStart = p + 30 + nameLen + extraLen
        return source.copyOfRange(dataStart, dataStart + compressedSize.toInt())
    }

    fun inflatedBytes(): ByteArray {
        if (method == 0) return rawBytes()
        val inflater = Inflater(true)
        inflater.setInput(rawBytes())
        val out = ByteArrayOutputStream(uncompressedSize.toInt().coerceAtLeast(64))
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && inflater.needsInput()) break
            out.write(buf, 0, n)
        }
        inflater.end()
        return out.toByteArray()
    }
}

/**
 * Minimal read-only ZIP central-directory parser that hands back entries with
 * access to their raw (still-compressed) bytes, so unmodified APK entries can
 * be copied byte-for-byte into a rebuilt archive without an inflate/deflate
 * round trip.
 */
object RawZipReader {
    fun read(source: ByteArray): List<ZipEntryRecord> {
        val eocd = findEndOfCentralDirectory(source)
        var cdOffset = readU32(source, eocd + 16).toInt()
        var count = readU16(source, eocd + 10)
        val entries = ArrayList<ZipEntryRecord>(count)
        var p = cdOffset
        repeat(count) {
            require(readU32(source, p) == 0x02014b50L) { "bad central directory header at $p" }
            val method = readU16(source, p + 10)
            val crc = readU32(source, p + 16)
            val compSize = readU32(source, p + 20)
            val uncompSize = readU32(source, p + 24)
            val nameLen = readU16(source, p + 28)
            val extraLen = readU16(source, p + 30)
            val commentLen = readU16(source, p + 32)
            val localHeaderOffset = readU32(source, p + 42)
            val name = String(source, p + 46, nameLen, Charsets.UTF_8)
            entries.add(ZipEntryRecord(name, method, crc, compSize, uncompSize, source, localHeaderOffset))
            p += 46 + nameLen + extraLen + commentLen
        }
        return entries
    }

    private fun findEndOfCentralDirectory(source: ByteArray): Int {
        val minPos = maxOf(0, source.size - 65557)
        var i = source.size - 22
        while (i >= minPos) {
            if (readU32(source, i) == 0x06054b50L) return i
            i--
        }
        error("End of central directory not found (not a valid zip/apk)")
    }

    fun readU16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

    fun readU32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xff) or
            ((b[off + 1].toLong() and 0xff) shl 8) or
            ((b[off + 2].toLong() and 0xff) shl 16) or
            ((b[off + 3].toLong() and 0xff) shl 24)
}
