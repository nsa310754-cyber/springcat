package com.springcat.iso

import java.io.Closeable
import java.io.IOException
import java.io.InputStream

/**
 * A small, dependency-free ISO 9660 reader.
 *
 * It parses the Primary Volume Descriptor and directory records so the app can
 * browse the contents of a `.iso` image. When a Joliet Supplementary Volume
 * Descriptor is present it is preferred, giving proper (UTF-16BE) file names.
 *
 * The reader works from a [SectorSource] abstraction so it can be backed by a
 * random-access file, a byte array, or (as in this app) a fully buffered
 * content stream provided by Android's Storage Access Framework.
 */
class IsoReader private constructor(
    private val source: SectorSource,
    private val rootExtent: Long,
    private val rootSize: Long,
    private val joliet: Boolean
) {

    /** Root directory of the image. */
    fun root(): IsoEntry = IsoEntry(
        name = "/",
        isDirectory = true,
        size = rootSize,
        extentLba = rootExtent
    )

    /**
     * List the entries directly contained in [directory].
     * Returns directories first, then files, both sorted case-insensitively.
     */
    fun list(directory: IsoEntry): List<IsoEntry> {
        require(directory.isDirectory) { "Not a directory: ${directory.name}" }
        val entries = ArrayList<IsoEntry>()
        val totalBytes = directory.size
        var offset = directory.extentLba * SECTOR_SIZE
        var bytesRead = 0L

        while (bytesRead < totalBytes) {
            val recordLen = source.readByte(offset).toInt() and 0xFF
            if (recordLen == 0) {
                // Records never span a sector boundary; skip to the next sector.
                val intoSector = (bytesRead % SECTOR_SIZE)
                val skip = SECTOR_SIZE - intoSector
                offset += skip
                bytesRead += skip
                continue
            }

            val record = source.read(offset, recordLen)
            val entry = parseDirectoryRecord(record)
            if (entry != null && entry.name != "." && entry.name != "..") {
                entries.add(entry)
            }

            offset += recordLen
            bytesRead += recordLen
        }

        return entries.sortedWith(
            compareByDescending<IsoEntry> { it.isDirectory }
                .thenBy { it.name.lowercase() }
        )
    }

    private fun parseDirectoryRecord(r: ByteArray): IsoEntry? {
        if (r.isEmpty()) return null
        val extent = readLE32(r, 2)
        val dataLen = readLE32(r, 10)
        val flags = r[25].toInt() and 0xFF
        val isDir = (flags and 0x02) != 0
        val nameLen = r[32].toInt() and 0xFF
        if (33 + nameLen > r.size) return null

        val rawName = ByteArray(nameLen)
        System.arraycopy(r, 33, rawName, 0, nameLen)

        val name = when {
            nameLen == 1 && rawName[0].toInt() == 0 -> "."
            nameLen == 1 && rawName[0].toInt() == 1 -> ".."
            joliet -> String(rawName, Charsets.UTF_16BE).substringBefore(';')
            else -> String(rawName, Charsets.US_ASCII).substringBefore(';')
        }

        return IsoEntry(
            name = name,
            isDirectory = isDir,
            size = dataLen,
            extentLba = extent
        )
    }

    companion object {
        const val SECTOR_SIZE = 2048L
        private const val SYSTEM_AREA_SECTORS = 16L

        /**
         * Parse [stream] into an [IsoReader]. The entire stream is buffered in
         * memory, which is appropriate for the SAF-provided streams used here.
         */
        @Throws(IOException::class)
        fun open(stream: InputStream): IsoReader {
            val bytes = stream.readBytes()
            return open(ByteArraySectorSource(bytes))
        }

        @Throws(IOException::class)
        fun open(source: SectorSource): IsoReader {
            var primary: VolumeDescriptor? = null
            var supplementary: VolumeDescriptor? = null

            var sector = SYSTEM_AREA_SECTORS
            while (true) {
                val base = sector * SECTOR_SIZE
                if (base + SECTOR_SIZE > source.size()) break
                val desc = source.read(base, SECTOR_SIZE.toInt())

                // "CD001" magic at bytes 1..5 identifies a volume descriptor.
                val magic = String(desc, 1, 5, Charsets.US_ASCII)
                if (magic != "CD001") throw IOException("Not an ISO 9660 image")

                when (desc[0].toInt() and 0xFF) {
                    1 -> primary = readVolumeDescriptor(desc)          // Primary
                    2 -> {                                             // Supplementary
                        // Joliet uses UCS-2 escape sequences %/@, %/C or %/E.
                        val escape = String(desc, 88, 32, Charsets.US_ASCII)
                        if (escape.contains("%/@") || escape.contains("%/C") ||
                            escape.contains("%/E")
                        ) {
                            supplementary = readVolumeDescriptor(desc)
                        }
                    }
                    255 -> break                                       // Terminator
                }
                sector++
            }

            val chosen = supplementary ?: primary
                ?: throw IOException("No Primary Volume Descriptor found")

            return IsoReader(
                source = source,
                rootExtent = chosen.rootExtent,
                rootSize = chosen.rootSize,
                joliet = supplementary != null
            )
        }

        private fun readVolumeDescriptor(desc: ByteArray): VolumeDescriptor {
            // The root directory record lives at offset 156, length 34.
            val rootExtent = readLE32(desc, 156 + 2)
            val rootSize = readLE32(desc, 156 + 10)
            return VolumeDescriptor(rootExtent, rootSize)
        }

        private fun readLE32(b: ByteArray, offset: Int): Long {
            return (b[offset].toLong() and 0xFF) or
                ((b[offset + 1].toLong() and 0xFF) shl 8) or
                ((b[offset + 2].toLong() and 0xFF) shl 16) or
                ((b[offset + 3].toLong() and 0xFF) shl 24)
        }
    }

    private data class VolumeDescriptor(val rootExtent: Long, val rootSize: Long)
}

/** Abstraction over the raw bytes of an image. */
interface SectorSource : Closeable {
    fun size(): Long
    fun read(offset: Long, length: Int): ByteArray
    fun readByte(offset: Long): Byte
    override fun close() {}
}

/** [SectorSource] backed by an in-memory byte array. */
class ByteArraySectorSource(private val data: ByteArray) : SectorSource {
    override fun size(): Long = data.size.toLong()

    override fun read(offset: Long, length: Int): ByteArray {
        val start = offset.toInt()
        val end = minOf(start + length, data.size)
        val out = ByteArray(length)
        if (start < data.size) {
            System.arraycopy(data, start, out, 0, end - start)
        }
        return out
    }

    override fun readByte(offset: Long): Byte {
        val i = offset.toInt()
        return if (i in data.indices) data[i] else 0
    }
}
