package com.apkbuilder.core.zip

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** A single output entry: either untouched original bytes, or freshly supplied content. */
sealed class ZipOutEntry(val name: String) {
    class Passthrough(name: String, val record: ZipEntryRecord) : ZipOutEntry(name)

    /** [store] = true writes uncompressed (required for things Android must mmap, e.g. resources.arsc, icons, assets). */
    class Fresh(name: String, val content: ByteArray, val store: Boolean) : ZipOutEntry(name)
}

/**
 * Writes a new ZIP/APK from an ordered list of entries, 4-byte aligning every
 * STORED entry's data the way `zipalign` does (required for APKs so the
 * platform can mmap uncompressed resources/assets directly).
 */
object ZipWriter {
    private const val DOS_TIME = 0x0000
    private const val DOS_DATE = 0x21 // 1980-01-01, matches common reproducible-build convention

    fun write(entries: List<ZipOutEntry>): ByteArray {
        val out = ByteArrayOutputStream()
        data class Central(
            val name: ByteArray,
            val method: Int,
            val crc: Long,
            val compSize: Long,
            val uncompSize: Long,
            val localOffset: Long,
        )
        val central = ArrayList<Central>(entries.size)

        for (entry in entries) {
            val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
            val localOffset = out.size().toLong()

            val q: Quint = when (entry) {
                is ZipOutEntry.Passthrough -> {
                    val r = entry.record
                    Quint(r.method, r.crc32, r.compressedSize, r.uncompressedSize, r.rawBytes())
                }
                is ZipOutEntry.Fresh -> {
                    val crc32 = CRC32().apply { update(entry.content) }.value
                    if (entry.store) {
                        Quint(0, crc32, entry.content.size.toLong(), entry.content.size.toLong(), entry.content)
                    } else {
                        val deflated = deflate(entry.content)
                        Quint(8, crc32, deflated.size.toLong(), entry.content.size.toLong(), deflated)
                    }
                }
            }
            val (method, crc, compSize, uncompSize, dataBytes) = q

            val extra = if (method == 0) {
                alignmentPadding(base = localOffset.toInt() + 30 + nameBytes.size)
            } else {
                ByteArray(0)
            }

            writeU32(out, 0x04034b50L) // local file header signature
            writeU16(out, 20) // version needed
            writeU16(out, 0) // flags
            writeU16(out, method)
            writeU16(out, DOS_TIME)
            writeU16(out, DOS_DATE)
            writeU32(out, crc)
            writeU32(out, compSize)
            writeU32(out, uncompSize)
            writeU16(out, nameBytes.size)
            writeU16(out, extra.size)
            out.write(nameBytes)
            out.write(extra)
            out.write(dataBytes)

            central.add(Central(nameBytes, method, crc, compSize, uncompSize, localOffset))
        }

        val cdStart = out.size().toLong()
        for (c in central) {
            writeU32(out, 0x02014b50L)
            writeU16(out, 20) // version made by
            writeU16(out, 20) // version needed
            writeU16(out, 0) // flags
            writeU16(out, c.method)
            writeU16(out, DOS_TIME)
            writeU16(out, DOS_DATE)
            writeU32(out, c.crc)
            writeU32(out, c.compSize)
            writeU32(out, c.uncompSize)
            writeU16(out, c.name.size)
            writeU16(out, 0) // extra len (central directory copy has none)
            writeU16(out, 0) // comment len
            writeU16(out, 0) // disk number start
            writeU16(out, 0) // internal attrs
            writeU32(out, 0) // external attrs
            writeU32(out, c.localOffset)
            out.write(c.name)
        }
        val cdSize = out.size().toLong() - cdStart

        writeU32(out, 0x06054b50L) // EOCD signature
        writeU16(out, 0) // disk number
        writeU16(out, 0) // disk with CD
        writeU16(out, central.size)
        writeU16(out, central.size)
        writeU32(out, cdSize)
        writeU32(out, cdStart)
        writeU16(out, 0) // comment length

        return out.toByteArray()
    }

    private data class Quint(val method: Int, val crc: Long, val compSize: Long, val uncompSize: Long, val bytes: ByteArray)

    private fun deflate(content: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(content)
        deflater.finish()
        val out = ByteArrayOutputStream(content.size)
        val buf = ByteArray(8192)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    /** zipalign-style extra-field padding so the data section starts on a 4-byte boundary. */
    private fun alignmentPadding(base: Int, align: Int = 4): ByteArray {
        val rem = base % align
        if (rem == 0) return ByteArray(0)
        var need = align - rem
        if (need < 4) need += align
        val data = ByteArray(need)
        val subFieldDataSize = need - 4
        data[0] = 0
        data[1] = 0
        data[2] = (subFieldDataSize and 0xff).toByte()
        data[3] = ((subFieldDataSize shr 8) and 0xff).toByte()
        return data
    }

    private fun writeU16(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xff)
        out.write((v shr 8) and 0xff)
    }

    private fun writeU32(out: ByteArrayOutputStream, v: Long) {
        out.write((v and 0xff).toInt())
        out.write(((v shr 8) and 0xff).toInt())
        out.write(((v shr 16) and 0xff).toInt())
        out.write(((v shr 24) and 0xff).toInt())
    }
}
