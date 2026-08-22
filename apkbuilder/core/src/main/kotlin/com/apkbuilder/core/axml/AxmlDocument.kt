package com.apkbuilder.core.axml

import java.io.ByteArrayOutputStream

private const val CHUNK_STRING_POOL = 0x0001
private const val CHUNK_XML_RESOURCE_MAP = 0x0180
private const val CHUNK_XML_START_NAMESPACE = 0x0100
private const val CHUNK_XML_END_NAMESPACE = 0x0101
private const val CHUNK_XML_START_ELEMENT = 0x0102
private const val CHUNK_XML_END_ELEMENT = 0x0103

private const val TYPE_STRING = 0x03
private const val TYPE_INT_DEC = 0x10

/**
 * Editable in-memory model of a compiled `AndroidManifest.xml` (binary AXML,
 * the same format `aapt`/`aapt2` produce). Supports what an APK-generation
 * tool actually needs: rewriting `package`/`versionCode`/`versionName`,
 * overriding `android:label` to an arbitrary literal app name, and appending
 * new `<uses-permission>` elements — without needing `aapt2` on-device.
 *
 * Strategy: strings are only ever appended (never renumbered), so every
 * existing string-pool index used elsewhere in the file — including the
 * separate XML resource-id map, which is positionally keyed to the first N
 * pool entries — stays valid untouched. Attribute value patches overwrite
 * fixed-size fields in place. The only structural growth is a freshly
 * regenerated string pool (appended strings) and spliced-in element chunks
 * for new `<uses-permission>` nodes.
 */
class AxmlDocument private constructor(
    private val strings: MutableList<String>,
    private val resourceMapRaw: ByteArray,
    private var nodeStream: ByteArray,
) {
    private val elements: MutableList<ElementInfo> = mutableListOf()

    private class ElementInfo(
        val name: String,
        val bodyOffset: Int, // offset of ResXMLTree_attrExt within nodeStream
        val chunkStart: Int, // offset of this START_ELEMENT chunk's ResChunk_header within nodeStream
        val attrCount: Int,
        val attrStart: Int,
        val attrSize: Int,
        val nsIndex: Int,
    )

    init {
        reindexElements()
    }

    private fun reindexElements() {
        elements.clear()
        var off = 0
        while (off < nodeStream.size) {
            val ctype = readU16(nodeStream, off)
            val csize = readU32(nodeStream, off + 4).toInt()
            if (ctype == CHUNK_XML_START_ELEMENT) {
                val body = off + 16 // past ResChunk_header(8) + lineNumber(4) + comment(4)
                val ns = readS32(nodeStream, body)
                val nameIdx = readS32(nodeStream, body + 4)
                val attrStart = readU16(nodeStream, body + 8)
                val attrSize = readU16(nodeStream, body + 10)
                val attrCount = readU16(nodeStream, body + 12)
                elements.add(
                    ElementInfo(
                        name = strings[nameIdx],
                        bodyOffset = body,
                        chunkStart = off,
                        attrCount = attrCount,
                        attrStart = attrStart,
                        attrSize = attrSize,
                        nsIndex = ns,
                    ),
                )
            }
            off += csize
        }
    }

    private fun addString(value: String): Int {
        val existing = strings.indexOf(value)
        if (existing >= 0) return existing
        strings.add(value)
        return strings.size - 1
    }

    // ResXMLTree_attribute layout, relative to the struct's start offset (aoff):
    //   ns:4  name:4  rawValue:4  size:2  res0:1  dataType:1  data:4   (20 bytes)

    /** Overwrites an existing string-typed attribute (e.g. `package`, `versionName`) with a new literal value. */
    fun setStringAttr(elementName: String, attrName: String, newValue: String) {
        val (_, aoff) = findAttr(elementName, attrName)
        val newIdx = addString(newValue)
        writeU32(nodeStream, aoff + 8, newIdx) // rawValue
        nodeStream[aoff + 12] = 8 // Res_value.size = 8
        nodeStream[aoff + 14] = 0 // res0
        nodeStream[aoff + 15] = TYPE_STRING.toByte()
        writeU32(nodeStream, aoff + 16, newIdx) // Res_value.data
    }

    /** Overwrites an existing int-typed attribute (e.g. `versionCode`) with a new literal value. */
    fun setIntAttr(elementName: String, attrName: String, newValue: Int) {
        val (_, aoff) = findAttr(elementName, attrName)
        writeU32(nodeStream, aoff + 8, -1) // rawValue = none
        nodeStream[aoff + 12] = 8
        nodeStream[aoff + 14] = 0
        nodeStream[aoff + 15] = TYPE_INT_DEC.toByte()
        writeU32(nodeStream, aoff + 16, newValue)
    }

    /**
     * Forces `android:label` on `<application>` to a literal string, even if
     * the template compiled it as a `@string/...` resource reference.
     */
    fun setApplicationLabel(newLabel: String) = setStringAttr("application", "label", newLabel)

    /** Appends `<uses-permission android:name="..."/>` as a child of `<manifest>`, right before `<application>`. */
    fun addUsesPermission(permissionName: String) {
        val nameAttr = findAnyAttrByName("name")
            ?: error("template manifest has no existing 'name' attribute to model the new one on")
        val appEl = elements.find { it.name == "application" }
            ?: error("template manifest has no <application> element")

        val tagIdx = addString("uses-permission")
        val valueIdx = addString(permissionName)

        val newChunk = buildLeafElement(
            tagIdx = tagIdx,
            nsIndex = -1,
            attrs = listOf(AttrSpec(nameAttr.nsIndex, nameAttr.nameIndex, valueIdx)),
        )
        nodeStream = spliceInsert(nodeStream, appEl.chunkStart, newChunk)
        reindexElements()
    }

    /** Appends `<meta-data android:name="name" android:value="value"/>` as the last child of `<application>`. */
    fun addApplicationMetaData(name: String, value: String) {
        val nameAttr = findAnyAttrByName("name")
            ?: error("template manifest has no existing 'name' attribute to model the new one on")
        val valueAttr = findAnyAttrByName("value")
            ?: error("template manifest has no existing 'value' attribute to model the new one on")
        val appEl = elements.find { it.name == "application" }
            ?: error("template manifest has no <application> element")

        val tagIdx = addString("meta-data")
        val nameValueIdx = addString(name)
        val valueValueIdx = addString(value)
        val insertAt = findMatchingEndOffset(appEl.chunkStart)

        val newChunk = buildLeafElement(
            tagIdx = tagIdx,
            nsIndex = -1,
            attrs = listOf(
                AttrSpec(nameAttr.nsIndex, nameAttr.nameIndex, nameValueIdx),
                AttrSpec(valueAttr.nsIndex, valueAttr.nameIndex, valueValueIdx),
            ),
        )
        nodeStream = spliceInsert(nodeStream, insertAt, newChunk)
        reindexElements()
    }

    /** Offset (within [nodeStream]) of the END_ELEMENT chunk that closes the START_ELEMENT at [startChunkOffset]. */
    private fun findMatchingEndOffset(startChunkOffset: Int): Int {
        var off = startChunkOffset
        var depth = 0
        while (off < nodeStream.size) {
            val ctype = readU16(nodeStream, off)
            val csize = readU32(nodeStream, off + 4).toInt()
            when (ctype) {
                CHUNK_XML_START_ELEMENT -> depth++
                CHUNK_XML_END_ELEMENT -> {
                    depth--
                    if (depth == 0) return off
                }
            }
            off += csize
        }
        error("no matching end element found for chunk at $startChunkOffset")
    }

    fun toByteArray(): ByteArray {
        val poolBytes = buildStringPool(strings)
        val total = 8 + poolBytes.size + resourceMapRaw.size + nodeStream.size
        val out = ByteArrayOutputStream(total)
        writeChunkHeader(out, type = 0x0003, headerSize = 8, size = total)
        out.write(poolBytes)
        out.write(resourceMapRaw)
        out.write(nodeStream)
        return out.toByteArray()
    }

    private data class NameAttrRef(val nsIndex: Int, val nameIndex: Int)

    private fun findAnyAttrByName(attrName: String): NameAttrRef? {
        for (el in elements) {
            var aoff = el.bodyOffset + el.attrStart
            repeat(el.attrCount) {
                val aNs = readS32(nodeStream, aoff)
                val aName = readS32(nodeStream, aoff + 4)
                if (aName >= 0 && strings[aName] == attrName) return NameAttrRef(aNs, aName)
                aoff += el.attrSize
            }
        }
        return null
    }

    /** Returns (element, byte offset of that attribute's ResXMLTree_attribute struct). */
    private fun findAttr(elementName: String, attrName: String): Pair<ElementInfo, Int> {
        val el = elements.find { it.name == elementName }
            ?: error("no <$elementName> element in manifest")
        var aoff = el.bodyOffset + el.attrStart
        repeat(el.attrCount) {
            val aName = readS32(nodeStream, aoff + 4)
            if (aName >= 0 && strings[aName] == attrName) return el to aoff
            aoff += el.attrSize
        }
        error("no '$attrName' attribute on <$elementName>")
    }

    private data class AttrSpec(val nsIndex: Int, val nameIndex: Int, val valueIdx: Int)

    /** Builds a self-closed `<tag ns:attr1="v1" ns:attr2="v2" .../>` START_ELEMENT + END_ELEMENT chunk pair. */
    private fun buildLeafElement(tagIdx: Int, nsIndex: Int, attrs: List<AttrSpec>): ByteArray {
        val out = ByteArrayOutputStream()

        // START_ELEMENT
        val startSize = 8 + 8 + 20 + 20 * attrs.size // header + line/comment + attrExt + N attributes
        writeChunkHeader(out, type = CHUNK_XML_START_ELEMENT, headerSize = 0x10, size = startSize)
        writeU32Stream(out, 0) // lineNumber
        writeS32Stream(out, -1) // comment
        writeS32Stream(out, nsIndex) // ns
        writeS32Stream(out, tagIdx) // name
        writeU16Stream(out, 20) // attributeStart
        writeU16Stream(out, 20) // attributeSize
        writeU16Stream(out, attrs.size) // attributeCount
        writeU16Stream(out, 0) // idIndex
        writeU16Stream(out, 0) // classIndex
        writeU16Stream(out, 0) // styleIndex
        for (attr in attrs) {
            writeS32Stream(out, attr.nsIndex)
            writeS32Stream(out, attr.nameIndex)
            writeS32Stream(out, attr.valueIdx) // rawValue
            writeU16Stream(out, 8) // Res_value.size
            out.write(0) // res0
            out.write(TYPE_STRING)
            writeU32Stream(out, attr.valueIdx) // Res_value.data
        }

        // END_ELEMENT
        val endSize = 8 + 8 + 8
        writeChunkHeader(out, type = CHUNK_XML_END_ELEMENT, headerSize = 0x10, size = endSize)
        writeU32Stream(out, 0)
        writeS32Stream(out, -1)
        writeS32Stream(out, nsIndex)
        writeS32Stream(out, tagIdx)

        return out.toByteArray()
    }

    companion object {
        fun parse(manifestBytes: ByteArray): AxmlDocument {
            val rootType = readU16(manifestBytes, 0)
            require(rootType == 0x0003) { "not a binary AndroidManifest.xml (root chunk type 0x${rootType.toString(16)})" }
            val rootHeaderSize = readU16(manifestBytes, 2)
            val rootSize = readU32(manifestBytes, 4).toInt()

            var off = rootHeaderSize
            var strings: List<String>? = null
            var resourceMapRaw = ByteArray(0)
            var nodeStreamStart = -1

            while (off < rootSize) {
                val ctype = readU16(manifestBytes, off)
                val chsize = readU16(manifestBytes, off + 2)
                val csize = readU32(manifestBytes, off + 4).toInt()
                when (ctype) {
                    CHUNK_STRING_POOL -> strings = parseStringPool(manifestBytes, off)
                    CHUNK_XML_RESOURCE_MAP -> resourceMapRaw = manifestBytes.copyOfRange(off, off + csize)
                    else -> if (nodeStreamStart == -1) nodeStreamStart = off
                }
                off += csize
            }
            requireNotNull(strings) { "manifest has no string pool" }
            require(nodeStreamStart >= 0) { "manifest has no XML node chunks" }

            val nodeStream = manifestBytes.copyOfRange(nodeStreamStart, rootSize)
            return AxmlDocument(strings.toMutableList(), resourceMapRaw, nodeStream)
        }

        private fun parseStringPool(buf: ByteArray, chunkStart: Int): List<String> {
            val off = chunkStart + 8
            val stringCount = readU32(buf, off).toInt()
            val flags = readU32(buf, off + 8).toInt()
            val stringsStart = readU32(buf, off + 12).toInt()
            val isUtf8 = (flags and 0x100) != 0
            val offsetsBase = off + 20
            val poolStart = chunkStart + stringsStart
            val result = ArrayList<String>(stringCount)
            for (i in 0 until stringCount) {
                val entryOffset = readU32(buf, offsetsBase + i * 4).toInt()
                val p = poolStart + entryOffset
                if (isUtf8) {
                    var pp = p
                    var charLen = buf[pp].toInt() and 0xff
                    pp += 1
                    if (charLen and 0x80 != 0) {
                        charLen = ((charLen and 0x7f) shl 8) or (buf[pp].toInt() and 0xff)
                        pp += 1
                    }
                    var byteLen = buf[pp].toInt() and 0xff
                    pp += 1
                    if (byteLen and 0x80 != 0) {
                        byteLen = ((byteLen and 0x7f) shl 8) or (buf[pp].toInt() and 0xff)
                        pp += 1
                    }
                    result.add(String(buf, pp, byteLen, Charsets.UTF_8))
                } else {
                    var pp = p
                    var charLen = readU16(buf, pp)
                    pp += 2
                    if (charLen and 0x8000 != 0) {
                        val hi = readU16(buf, pp)
                        charLen = ((charLen and 0x7fff) shl 16) or hi
                        pp += 2
                    }
                    result.add(String(buf, pp, charLen * 2, Charsets.UTF_16LE))
                }
            }
            return result
        }

        private fun buildStringPool(strings: List<String>): ByteArray {
            // UTF-16, non-styled — the format every Android version understands.
            val blobs = strings.map { s ->
                val out = ByteArrayOutputStream()
                val len = s.length
                if (len < 0x8000) {
                    writeU16Stream(out, len)
                } else {
                    writeU16Stream(out, (len ushr 16) or 0x8000)
                    writeU16Stream(out, len and 0xffff)
                }
                out.write(s.toByteArray(Charsets.UTF_16LE))
                writeU16Stream(out, 0) // null terminator
                out.toByteArray()
            }
            val offsets = IntArray(blobs.size)
            var acc = 0
            for (i in blobs.indices) {
                offsets[i] = acc
                acc += blobs[i].size
            }
            var stringsBlobSize = acc
            // pad the blob region to a 4-byte boundary
            val blobPad = (4 - (stringsBlobSize % 4)) % 4
            stringsBlobSize += blobPad

            val headerFieldsSize = 20
            val offsetsSize = blobs.size * 4
            val stringsStart = 8 + headerFieldsSize + offsetsSize
            val totalSize = stringsStart + stringsBlobSize

            val out = ByteArrayOutputStream(totalSize)
            writeChunkHeader(out, type = CHUNK_STRING_POOL, headerSize = 8 + headerFieldsSize, size = totalSize)
            writeU32Stream(out, blobs.size) // stringCount
            writeU32Stream(out, 0) // styleCount
            writeU32Stream(out, 0) // flags (UTF-16)
            writeU32Stream(out, stringsStart) // stringsStart
            writeU32Stream(out, 0) // stylesStart
            for (o in offsets) writeU32Stream(out, o)
            for (b in blobs) out.write(b)
            repeat(blobPad) { out.write(0) }
            return out.toByteArray()
        }

        private fun spliceInsert(buf: ByteArray, at: Int, insert: ByteArray): ByteArray {
            val result = ByteArray(buf.size + insert.size)
            System.arraycopy(buf, 0, result, 0, at)
            System.arraycopy(insert, 0, result, at, insert.size)
            System.arraycopy(buf, at, result, at + insert.size, buf.size - at)
            return result
        }

        private fun writeChunkHeader(out: ByteArrayOutputStream, type: Int, headerSize: Int, size: Int) {
            writeU16Stream(out, type)
            writeU16Stream(out, headerSize)
            writeU32Stream(out, size)
        }

        private fun readU16(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

        private fun readS32(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or
                ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)

        private fun readU32(b: ByteArray, off: Int): Long = readS32(b, off).toLong() and 0xffffffffL

        private fun writeU32(b: ByteArray, off: Int, v: Int) {
            b[off] = (v and 0xff).toByte()
            b[off + 1] = ((v shr 8) and 0xff).toByte()
            b[off + 2] = ((v shr 16) and 0xff).toByte()
            b[off + 3] = ((v shr 24) and 0xff).toByte()
        }

        private fun writeU16Stream(out: ByteArrayOutputStream, v: Int) {
            out.write(v and 0xff)
            out.write((v shr 8) and 0xff)
        }

        private fun writeU32Stream(out: ByteArrayOutputStream, v: Int) {
            out.write(v and 0xff)
            out.write((v shr 8) and 0xff)
            out.write((v shr 16) and 0xff)
            out.write((v shr 24) and 0xff)
        }

        private fun writeS32Stream(out: ByteArrayOutputStream, v: Int) = writeU32Stream(out, v)
    }
}
