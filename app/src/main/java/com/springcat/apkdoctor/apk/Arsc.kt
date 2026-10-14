package com.springcat.apkdoctor.apk

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ArscException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Merges the `resources.arsc` of configuration splits into a base table so a
 * single APK carries every density/language/etc. resource the splits added.
 *
 * Splits of one app share the base's resource IDs, type IDs and entry indices;
 * a config split simply adds alternate *configurations* (an `xxhdpi` drawable,
 * a `ja` string) for IDs the base already defines. So merging is additive:
 *
 *  1. The base's two growable string pools — the table's global value pool and
 *     each package's key pool — keep their existing indices; a split's strings
 *     are appended after them, which is why base chunks never need rewriting.
 *  2. Each split type chunk (one configuration of one type) is copied in with
 *     its structure intact; only the 4-byte string-pool indices inside its
 *     entries are repointed to the appended positions, so the chunk length is
 *     unchanged and its offset table stays valid.
 *  3. Those chunks are appended to the matching package, and the grown pools
 *     and chunk sizes are rewritten.
 *
 * Encodings aapt2 only emits with an explicit flag (sparse entries, 16-bit
 * offsets, compact entries) and styled or multi-package tables are refused
 * rather than guessed at; the caller then keeps the base table unchanged.
 */
object Arsc {

    private const val TYPE_STRING_POOL = 0x0001
    private const val TYPE_TABLE = 0x0002
    private const val TYPE_PACKAGE = 0x0200
    private const val TYPE_TYPE = 0x0201
    private const val TYPE_SPEC = 0x0202

    private const val FLAG_UTF8 = 0x100
    private const val FLAG_SORTED = 0x1

    private const val ENTRY_FLAG_COMPLEX = 0x0001
    private const val ENTRY_FLAG_COMPACT = 0x0008

    private const val TYPE_FLAG_SPARSE = 0x01
    private const val TYPE_FLAG_OFFSET16 = 0x02

    private const val NO_ENTRY = -1 // 0xFFFFFFFF
    private const val RES_VALUE_TYPE_STRING = 0x03

    /** Merges [splits] into [base], returning a new `resources.arsc`. */
    fun merge(base: ByteArray, splits: List<ByteArray>): ByteArray {
        if (splits.isEmpty()) return base
        val baseTable = Table.parse(base)
        var valueStrings = baseTable.valuePool.strings.toMutableList()

        // Appended key strings and type chunks, grouped by target package id.
        val appendedKeys = HashMap<Int, MutableList<String>>()
        val appendedChunks = HashMap<Int, MutableList<ByteArray>>()

        for (splitBytes in splits) {
            val split = Table.parse(splitBytes)
            // Where this split's value strings will land in the merged pool.
            val valueBase = valueStrings.size
            valueStrings.addAll(split.valuePool.strings)

            for (splitPkg in split.packages) {
                val basePkg = baseTable.packages.firstOrNull { it.id == splitPkg.id }
                    ?: throw ArscException("split package 0x%02x not in base".format(splitPkg.id))

                val keys = appendedKeys.getOrPut(splitPkg.id) { mutableListOf() }
                val keyBase = basePkg.keyPool.strings.size + keys.size
                keys.addAll(splitPkg.keyPool.strings)

                val valueMap = IntArray(split.valuePool.strings.size) { valueBase + it }
                val keyMap = IntArray(splitPkg.keyPool.strings.size) { keyBase + it }

                val chunks = appendedChunks.getOrPut(splitPkg.id) { mutableListOf() }
                for (typeChunk in splitPkg.typeChunks) {
                    chunks.add(remapTypeChunk(typeChunk, keyMap, valueMap))
                }
            }
        }

        return rebuild(base, baseTable, valueStrings, appendedKeys, appendedChunks)
    }

    // --- type chunk remapping --------------------------------------------

    /**
     * Returns a copy of [chunk] with entry key indices and string values
     * repointed through [keyMap]/[valueMap]. Structure and length are untouched.
     */
    private fun remapTypeChunk(chunk: ByteArray, keyMap: IntArray, valueMap: IntArray): ByteArray {
        val out = chunk.copyOf()
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)

        // ResTable_type: ResChunk_header(8) | id(1)@8 | flags(1)@9 | reserved(2) |
        // entryCount(4)@12 | entriesStart(4)@16 | config(…). headerSize covers the config.
        val headerSize = bb.getShort(2).toInt() and 0xFFFF
        val flags = out[9].toInt() and 0xFF
        if (flags and (TYPE_FLAG_SPARSE or TYPE_FLAG_OFFSET16) != 0) {
            throw ArscException("sparse/offset16 type chunk unsupported")
        }
        val entryCount = bb.getInt(12)
        val entriesStart = bb.getInt(16)
        val offsetArray = headerSize // offsets immediately follow the header

        for (i in 0 until entryCount) {
            val offset = bb.getInt(offsetArray + i * 4)
            if (offset == NO_ENTRY) continue
            val entryPos = entriesStart + offset
            val entrySize = bb.getShort(entryPos).toInt() and 0xFFFF
            val entryFlags = bb.getShort(entryPos + 2).toInt() and 0xFFFF
            if (entryFlags and ENTRY_FLAG_COMPACT != 0) {
                throw ArscException("compact entry unsupported")
            }

            // ResTable_entry.key -> key string pool index.
            remapIndex(bb, entryPos + 4, keyMap)

            val body = entryPos + entrySize
            if (entryFlags and ENTRY_FLAG_COMPLEX != 0) {
                val count = bb.getInt(entryPos + 12)
                var map = body
                repeat(count) {
                    // ResTable_map { name(4 ref); Res_value value(8) }
                    remapResValueString(bb, map + 4, valueMap)
                    map += 12
                }
            } else {
                remapResValueString(bb, body, valueMap)
            }
        }
        return out
    }

    /** Repoints a Res_value at [pos] when it is a string reference. */
    private fun remapResValueString(bb: ByteBuffer, pos: Int, valueMap: IntArray) {
        val dataType = bb.get(pos + 3).toInt() and 0xFF
        if (dataType == RES_VALUE_TYPE_STRING) remapIndex(bb, pos + 4, valueMap)
    }

    private fun remapIndex(bb: ByteBuffer, pos: Int, map: IntArray) {
        val original = bb.getInt(pos)
        if (original == NO_ENTRY) return
        if (original < 0 || original >= map.size) throw ArscException("string index $original out of range")
        bb.putInt(pos, map[original])
    }

    // --- reassembly ------------------------------------------------------

    private fun rebuild(
        base: ByteArray,
        table: Table,
        valueStrings: List<String>,
        appendedKeys: Map<Int, List<String>>,
        appendedChunks: Map<Int, List<ByteArray>>,
    ): ByteArray {
        val valuePool = encodePool(valueStrings, table.valuePool.utf8)

        val packages = table.packages.map { pkg ->
            val extraKeys = appendedKeys[pkg.id].orEmpty()
            val extraChunks = appendedChunks[pkg.id].orEmpty()
            if (extraKeys.isEmpty() && extraChunks.isEmpty()) {
                base.copyOfRange(pkg.start, pkg.start + pkg.size)
            } else {
                rebuildPackage(base, pkg, extraKeys, extraChunks)
            }
        }

        val body = ByteArrayOutputStream()
        body.write(valuePool)
        packages.forEach { body.write(it) }
        val bodyBytes = body.toByteArray()

        val headerSize = ByteBuffer.wrap(base).order(ByteOrder.LITTLE_ENDIAN).getShort(2).toInt() and 0xFFFF
        val out = ByteBuffer.allocate(headerSize + bodyBytes.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put(base, 0, headerSize) // table header (packageCount unchanged)
        out.putInt(4, headerSize + bodyBytes.size) // chunk size
        out.position(headerSize)
        out.put(bodyBytes)
        return out.array()
    }

    private fun rebuildPackage(
        base: ByteArray,
        pkg: Package,
        extraKeys: List<String>,
        extraChunks: List<ByteArray>,
    ): ByteArray {
        val keyPool = encodePool(pkg.keyPool.strings + extraKeys, pkg.keyPool.utf8)

        // Package layout: header | typeStrings | keyStrings | body(type/spec…).
        val header = base.copyOfRange(pkg.start, pkg.start + pkg.headerSize)
        val typeStrings = base.copyOfRange(pkg.start + pkg.typeStringsOffset, pkg.start + pkg.keyStringsOffset)
        val bodyStart = pkg.start + pkg.keyStringsOffset + pkg.keyPool.chunkSize
        val originalBody = base.copyOfRange(bodyStart, pkg.start + pkg.size)

        val assembled = ByteArrayOutputStream()
        assembled.write(header)
        assembled.write(typeStrings)
        assembled.write(keyPool)
        assembled.write(originalBody)
        extraChunks.forEach { assembled.write(it) }
        val bytes = assembled.toByteArray()

        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, bytes.size) // package chunk size
        return bytes
    }

    // --- parsing ---------------------------------------------------------

    private class Table(
        val valuePool: StringPool,
        val packages: List<Package>,
    ) {
        companion object {
            fun parse(bytes: ByteArray): Table {
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                if ((bb.getShort(0).toInt() and 0xFFFF) != TYPE_TABLE) throw ArscException("not a resource table")
                val headerSize = bb.getShort(2).toInt() and 0xFFFF
                val total = bb.getInt(4).coerceAtMost(bytes.size)

                var pos = headerSize
                var valuePool: StringPool? = null
                val packages = mutableListOf<Package>()
                while (pos + 8 <= total) {
                    val type = bb.getShort(pos).toInt() and 0xFFFF
                    val size = bb.getInt(pos + 4)
                    if (size < 8 || pos + size > total) break
                    when (type) {
                        TYPE_STRING_POOL -> if (valuePool == null) valuePool = StringPool.parse(bytes, pos)
                        TYPE_PACKAGE -> packages.add(Package.parse(bytes, pos))
                    }
                    pos += size
                }
                return Table(
                    valuePool ?: throw ArscException("no value string pool"),
                    packages.ifEmpty { throw ArscException("no packages") },
                )
            }
        }
    }

    private class Package(
        val id: Int,
        val start: Int,
        val size: Int,
        val headerSize: Int,
        val typeStringsOffset: Int,
        val keyStringsOffset: Int,
        val keyPool: StringPool,
        val typeChunks: List<ByteArray>,
    ) {
        companion object {
            fun parse(bytes: ByteArray, start: Int): Package {
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val headerSize = bb.getShort(start + 2).toInt() and 0xFFFF
                val size = bb.getInt(start + 4)
                val id = bb.getInt(start + 8)
                val typeStringsOffset = bb.getInt(start + 268)
                val keyStringsOffset = bb.getInt(start + 276)
                val keyPool = StringPool.parse(bytes, start + keyStringsOffset)

                val typeChunks = mutableListOf<ByteArray>()
                var pos = start + keyStringsOffset + keyPool.chunkSize
                val end = start + size
                while (pos + 8 <= end) {
                    val type = bb.getShort(pos).toInt() and 0xFFFF
                    val chunkSize = bb.getInt(pos + 4)
                    if (chunkSize < 8 || pos + chunkSize > end) break
                    if (type == TYPE_TYPE) typeChunks.add(bytes.copyOfRange(pos, pos + chunkSize))
                    pos += chunkSize
                }
                return Package(id, start, size, headerSize, typeStringsOffset, keyStringsOffset, keyPool, typeChunks)
            }
        }
    }

    private fun encodePool(strings: List<String>, utf8: Boolean): ByteArray = StringPool.encode(strings, utf8)

    // --- string pools ----------------------------------------------------

    private class StringPool(val strings: List<String>, val utf8: Boolean, val chunkSize: Int) {
        companion object {
            fun parse(bytes: ByteArray, start: Int): StringPool {
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                if ((bb.getShort(start).toInt() and 0xFFFF) != TYPE_STRING_POOL) throw ArscException("not a string pool")
                val headerSize = bb.getShort(start + 2).toInt() and 0xFFFF
                val chunkSize = bb.getInt(start + 4)
                val count = bb.getInt(start + 8)
                val styleCount = bb.getInt(start + 12)
                if (styleCount != 0) throw ArscException("styled string pools unsupported")
                val flags = bb.getInt(start + 16)
                val stringsStart = bb.getInt(start + 20)
                val utf8 = flags and FLAG_UTF8 != 0

                val strings = ArrayList<String>(count)
                val dataStart = start + stringsStart
                for (i in 0 until count) {
                    val offset = bb.getInt(start + headerSize + i * 4)
                    strings.add(decodeString(bytes, dataStart + offset, utf8))
                }
                return StringPool(strings, utf8, chunkSize)
            }

            private fun decodeString(bytes: ByteArray, at: Int, utf8: Boolean): String {
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                var p = at
                if (utf8) {
                    p = skipLen8(bytes, p) // character count, unused
                    val (byteLen, next) = readLen8(bytes, p)
                    return String(bytes, next, byteLen, Charsets.UTF_8)
                }
                var charLen = bb.getShort(p).toInt() and 0xFFFF
                p += 2
                if (charLen and 0x8000 != 0) {
                    charLen = ((charLen and 0x7FFF) shl 16) or (bb.getShort(p).toInt() and 0xFFFF)
                    p += 2
                }
                val chars = CharArray(charLen) { bb.getShort(p + it * 2).toShort().toInt().toChar() }
                return String(chars)
            }

            private fun skipLen8(bytes: ByteArray, p: Int): Int {
                val first = bytes[p].toInt() and 0xFF
                return if (first and 0x80 != 0) p + 2 else p + 1
            }

            private fun readLen8(bytes: ByteArray, p: Int): Pair<Int, Int> {
                val first = bytes[p].toInt() and 0xFF
                return if (first and 0x80 != 0) {
                    ((first and 0x7F) shl 8 or (bytes[p + 1].toInt() and 0xFF)) to (p + 2)
                } else {
                    first to (p + 1)
                }
            }

            /** Builds a complete, unsorted string-pool chunk in [utf8] or UTF-16. */
            fun encode(strings: List<String>, utf8: Boolean): ByteArray {
                val data = ByteArrayOutputStream()
                val offsets = IntArray(strings.size)
                strings.forEachIndexed { i, s ->
                    offsets[i] = data.size()
                    if (utf8) writeUtf8(data, s) else writeUtf16(data, s)
                }
                var payload = data.toByteArray()
                if (payload.size % 4 != 0) payload += ByteArray(4 - payload.size % 4)

                val headerSize = 28
                val stringsStart = headerSize + 4 * strings.size
                val size = stringsStart + payload.size
                val out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
                out.putShort(TYPE_STRING_POOL.toShort())
                out.putShort(headerSize.toShort())
                out.putInt(size)
                out.putInt(strings.size)
                out.putInt(0) // style count
                out.putInt(if (utf8) FLAG_UTF8 else 0) // flags, SORTED cleared
                out.putInt(stringsStart)
                out.putInt(0) // styles start
                offsets.forEach { out.putInt(it) }
                out.put(payload)
                return out.array()
            }

            private fun writeUtf8(out: ByteArrayOutputStream, s: String) {
                val bytes = s.toByteArray(Charsets.UTF_8)
                writeLen8(out, s.length) // character count (UTF-16 units; BMP-safe)
                writeLen8(out, bytes.size)
                out.write(bytes)
                out.write(0)
            }

            private fun writeLen8(out: ByteArrayOutputStream, value: Int) {
                if (value > 0x7F) out.write((value shr 8) or 0x80)
                out.write(value and 0xFF)
            }

            private fun writeUtf16(out: ByteArrayOutputStream, s: String) {
                val chars = s.toCharArray()
                fun u16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
                if (chars.size > 0x7FFF) {
                    u16(((chars.size shr 16) or 0x8000) and 0xFFFF)
                    u16(chars.size and 0xFFFF)
                } else {
                    u16(chars.size)
                }
                for (c in chars) u16(c.code)
                u16(0) // NUL terminator
            }
        }
    }
}
