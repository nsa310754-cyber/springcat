package site.ragdollp.permeditor

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * Minimal binary AndroidManifest.xml (AXML) reader/writer, ported from the
 * browser-side implementation in apk-permission-manager/axml.js. Implements
 * just enough of the AOSP ResourceTypes.h chunk format to round-trip a
 * manifest: string pool, optional resource map, and the flat
 * namespace/element/cdata node stream.
 *
 * `headerSize` for START_ELEMENT chunks is 16 (just the common node
 * header) - the attrExt fields (ns/name/attributeStart/...) are body, not
 * header, despite what a first read of the struct layout suggests. Getting
 * this wrong produces a document real AXML parsers (androguard, the
 * platform) reject even though a self-consistent reader/writer round-trips
 * it fine.
 */
object Axml {
    const val NO_INDEX = -1 // bit pattern 0xFFFFFFFF

    private const val CHUNK_STRING_POOL = 0x0001
    private const val CHUNK_XML = 0x0003
    private const val CHUNK_XML_START_NAMESPACE = 0x0100
    private const val CHUNK_XML_END_NAMESPACE = 0x0101
    private const val CHUNK_XML_START_ELEMENT = 0x0102
    private const val CHUNK_XML_END_ELEMENT = 0x0103
    private const val CHUNK_XML_CDATA = 0x0104
    private const val CHUNK_XML_RESOURCE_MAP = 0x0180

    data class Attribute(
        var ns: Int,
        var name: Int,
        var rawValue: Int,
        var valueSize: Int,
        var valueRes0: Int,
        var valueType: Int,
        var valueData: Int
    )

    sealed class Node {
        data class NsStart(var lineNumber: Int, var comment: Int, var prefix: Int, var uri: Int) : Node()
        data class NsEnd(var lineNumber: Int, var comment: Int, var prefix: Int, var uri: Int) : Node()
        data class ElemStart(
            var lineNumber: Int, var comment: Int, var ns: Int, var name: Int,
            var idIndex: Int, var classIndex: Int, var styleIndex: Int,
            var attributes: MutableList<Attribute>
        ) : Node()
        data class ElemEnd(var lineNumber: Int, var comment: Int, var ns: Int, var name: Int) : Node()
        data class Cdata(
            var lineNumber: Int, var comment: Int, var data: Int,
            var valueSize: Int, var valueRes0: Int, var valueType: Int, var valueData: Int
        ) : Node()
        data class Raw(var bytes: ByteArray) : Node()
    }

    class Doc(
        val strings: MutableList<String>,
        val resourceMapIds: IntArray?,
        val nodes: MutableList<Node>
    )

    fun findString(strings: List<String>, value: String): Int = strings.indexOf(value)

    fun internString(doc: Doc, value: String): Int {
        val idx = doc.strings.indexOf(value)
        if (idx != -1) return idx
        doc.strings.add(value)
        return doc.strings.size - 1
    }

    // ---------- parsing ----------

    private fun readLen16(bb: ByteBuffer, pos: Int): Pair<Int, Int> {
        val u0 = bb.getShort(pos).toInt() and 0xFFFF
        if (u0 and 0x8000 != 0) {
            val u1 = bb.getShort(pos + 2).toInt() and 0xFFFF
            return Pair(((u0 and 0x7FFF) shl 16) or u1, 4)
        }
        return Pair(u0, 2)
    }

    private fun readLen8(bytes: ByteArray, pos: Int): Pair<Int, Int> {
        val b0 = bytes[pos].toInt() and 0xFF
        if (b0 and 0x80 != 0) {
            val b1 = bytes[pos + 1].toInt() and 0xFF
            return Pair(((b0 and 0x7F) shl 8) or b1, 2)
        }
        return Pair(b0, 1)
    }

    private fun parseStringPool(bytes: ByteArray, bb: ByteBuffer, base: Int): Pair<Int, List<String>> {
        val chunkSize = bb.getInt(base + 4)
        val stringCount = bb.getInt(base + 8)
        val flags = bb.getInt(base + 16)
        val stringsStart = bb.getInt(base + 20)
        val utf8 = (flags and 0x100) != 0

        val offsets = IntArray(stringCount) { bb.getInt(base + 28 + it * 4) }
        val strings = ArrayList<String>(stringCount)
        for (i in 0 until stringCount) {
            val pos = base + stringsStart + offsets[i]
            if (utf8) {
                val (_, l1size) = readLen8(bytes, pos) // char length (unused)
                val (byteLen, l2size) = readLen8(bytes, pos + l1size)
                val dataPos = pos + l1size + l2size
                strings.add(String(bytes, dataPos, byteLen, Charsets.UTF_8))
            } else {
                val (charLen, lsize) = readLen16(bb, pos)
                strings.add(String(bytes, pos + lsize, charLen * 2, Charset.forName("UTF-16LE")))
            }
        }
        return Pair(chunkSize, strings)
    }

    fun parse(bytes: ByteArray): Doc {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val rootType = bb.getShort(0).toInt() and 0xFFFF
        require(rootType == CHUNK_XML) { "Not a binary XML document (root chunk type 0x${rootType.toString(16)})" }
        val rootSize = bb.getInt(4)

        var pos = 8
        require((bb.getShort(pos).toInt() and 0xFFFF) == CHUNK_STRING_POOL) { "Expected string pool chunk at $pos" }
        val (poolSize, strings) = parseStringPool(bytes, bb, pos)
        pos += poolSize

        var resourceMapIds: IntArray? = null
        if (pos + 8 <= bytes.size && (bb.getShort(pos).toInt() and 0xFFFF) == CHUNK_XML_RESOURCE_MAP) {
            val rmSize = bb.getInt(pos + 4)
            val n = (rmSize - 8) / 4
            resourceMapIds = IntArray(n) { bb.getInt(pos + 8 + it * 4) }
            pos += rmSize
        }

        val nodes = ArrayList<Node>()
        val end = rootSize
        while (pos < end) {
            val type = bb.getShort(pos).toInt() and 0xFFFF
            val headerSize = bb.getShort(pos + 2).toInt() and 0xFFFF
            val size = bb.getInt(pos + 4)
            val lineNumber = bb.getInt(pos + 8)
            val comment = bb.getInt(pos + 12)

            when (type) {
                CHUNK_XML_START_NAMESPACE, CHUNK_XML_END_NAMESPACE -> {
                    val prefix = bb.getInt(pos + 16)
                    val uri = bb.getInt(pos + 20)
                    nodes.add(
                        if (type == CHUNK_XML_START_NAMESPACE) Node.NsStart(lineNumber, comment, prefix, uri)
                        else Node.NsEnd(lineNumber, comment, prefix, uri)
                    )
                }
                CHUNK_XML_START_ELEMENT -> {
                    val ns = bb.getInt(pos + 16)
                    val name = bb.getInt(pos + 20)
                    val attributeStart = bb.getShort(pos + 24).toInt() and 0xFFFF
                    val attributeSize = bb.getShort(pos + 26).toInt() and 0xFFFF
                    val attributeCount = bb.getShort(pos + 28).toInt() and 0xFFFF
                    val idIndex = bb.getShort(pos + 30).toInt() and 0xFFFF
                    val classIndex = bb.getShort(pos + 32).toInt() and 0xFFFF
                    val styleIndex = bb.getShort(pos + 34).toInt() and 0xFFFF
                    val attrArrayBase = pos + 16 + attributeStart
                    val attributes = ArrayList<Attribute>(attributeCount)
                    for (a in 0 until attributeCount) {
                        val ab = attrArrayBase + a * attributeSize
                        attributes.add(
                            Attribute(
                                ns = bb.getInt(ab),
                                name = bb.getInt(ab + 4),
                                rawValue = bb.getInt(ab + 8),
                                valueSize = bb.getShort(ab + 12).toInt() and 0xFFFF,
                                valueRes0 = bb.get(ab + 14).toInt() and 0xFF,
                                valueType = bb.get(ab + 15).toInt() and 0xFF,
                                valueData = bb.getInt(ab + 16)
                            )
                        )
                    }
                    nodes.add(Node.ElemStart(lineNumber, comment, ns, name, idIndex, classIndex, styleIndex, attributes))
                }
                CHUNK_XML_END_ELEMENT -> {
                    nodes.add(Node.ElemEnd(lineNumber, comment, bb.getInt(pos + 16), bb.getInt(pos + 20)))
                }
                CHUNK_XML_CDATA -> {
                    val data = bb.getInt(pos + 16)
                    val vSize = bb.getShort(pos + 20).toInt() and 0xFFFF
                    val vRes0 = bb.get(pos + 22).toInt() and 0xFF
                    val vType = bb.get(pos + 23).toInt() and 0xFF
                    val vData = bb.getInt(pos + 24)
                    nodes.add(Node.Cdata(lineNumber, comment, data, vSize, vRes0, vType, vData))
                }
                else -> {
                    nodes.add(Node.Raw(bytes.copyOfRange(pos, pos + size)))
                }
            }
            pos += size
        }

        return Doc(strings.toMutableList(), resourceMapIds, nodes)
    }

    // ---------- serialization ----------

    private fun buildStringPool(strings: List<String>): ByteArray {
        val stringCount = strings.size
        val stringsStart = 28 + stringCount * 4

        data class Enc(val chars: String, val len: Int, val headBytes: Int, val byteLen: Int)
        val encoded = strings.map { s ->
            val len = s.length
            val headBytes = if (len >= 0x8000) 4 else 2
            Enc(s, len, headBytes, headBytes + len * 2 + 2)
        }
        var dataLen = encoded.sumOf { it.byteLen }
        while ((stringsStart + dataLen) % 4 != 0) dataLen++

        val chunkSize = stringsStart + dataLen
        val out = ByteArray(chunkSize)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)

        bb.putShort(0, CHUNK_STRING_POOL.toShort())
        bb.putShort(2, 28.toShort())
        bb.putInt(4, chunkSize)
        bb.putInt(8, stringCount)
        bb.putInt(12, 0) // styleCount
        bb.putInt(16, 0) // flags: UTF-16, not sorted
        bb.putInt(20, stringsStart)
        bb.putInt(24, 0) // stylesStart

        var offset = 0
        for (i in 0 until stringCount) {
            bb.putInt(28 + i * 4, offset)
            val e = encoded[i]
            val p = stringsStart + offset
            if (e.headBytes == 4) {
                bb.putShort(p, (0x8000 or (e.len ushr 16)).toShort())
                bb.putShort(p + 2, (e.len and 0xFFFF).toShort())
            } else {
                bb.putShort(p, e.len.toShort())
            }
            val charPos = p + e.headBytes
            for (c in 0 until e.len) {
                bb.putShort(charPos + c * 2, e.chars[c].code.toShort())
            }
            bb.putShort(charPos + e.len * 2, 0)
            offset += e.byteLen
        }
        return out
    }

    private fun buildResourceMap(ids: IntArray): ByteArray {
        val chunkSize = 8 + ids.size * 4
        val out = ByteArray(chunkSize)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(0, CHUNK_XML_RESOURCE_MAP.toShort())
        bb.putShort(2, 8.toShort())
        bb.putInt(4, chunkSize)
        for (i in ids.indices) bb.putInt(8 + i * 4, ids[i])
        return out
    }

    private fun buildNode(node: Node): ByteArray = when (node) {
        is Node.NsStart -> buildNsLike(CHUNK_XML_START_NAMESPACE, node.lineNumber, node.comment, node.prefix, node.uri)
        is Node.NsEnd -> buildNsLike(CHUNK_XML_END_NAMESPACE, node.lineNumber, node.comment, node.prefix, node.uri)
        is Node.ElemEnd -> buildNsLike(CHUNK_XML_END_ELEMENT, node.lineNumber, node.comment, node.ns, node.name)
        is Node.Cdata -> {
            val out = ByteArray(28)
            val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            bb.putShort(0, CHUNK_XML_CDATA.toShort())
            bb.putShort(2, 16.toShort())
            bb.putInt(4, 28)
            bb.putInt(8, node.lineNumber)
            bb.putInt(12, node.comment)
            bb.putInt(16, node.data)
            bb.putShort(20, node.valueSize.toShort())
            bb.put(22, node.valueRes0.toByte())
            bb.put(23, node.valueType.toByte())
            bb.putInt(24, node.valueData)
            out
        }
        is Node.ElemStart -> {
            val attrCount = node.attributes.size
            val size = 36 + attrCount * 20
            val out = ByteArray(size)
            val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            bb.putShort(0, CHUNK_XML_START_ELEMENT.toShort())
            bb.putShort(2, 16.toShort()) // headerSize: node header only, ext is body
            bb.putInt(4, size)
            bb.putInt(8, node.lineNumber)
            bb.putInt(12, node.comment)
            bb.putInt(16, node.ns)
            bb.putInt(20, node.name)
            bb.putShort(24, 20.toShort()) // attributeStart
            bb.putShort(26, 20.toShort()) // attributeSize
            bb.putShort(28, attrCount.toShort())
            bb.putShort(30, node.idIndex.toShort())
            bb.putShort(32, node.classIndex.toShort())
            bb.putShort(34, node.styleIndex.toShort())
            for (a in 0 until attrCount) {
                val at = node.attributes[a]
                val ab = 36 + a * 20
                bb.putInt(ab, at.ns)
                bb.putInt(ab + 4, at.name)
                bb.putInt(ab + 8, at.rawValue)
                bb.putShort(ab + 12, at.valueSize.toShort())
                bb.put(ab + 14, at.valueRes0.toByte())
                bb.put(ab + 15, at.valueType.toByte())
                bb.putInt(ab + 16, at.valueData)
            }
            out
        }
        is Node.Raw -> node.bytes
    }

    private fun buildNsLike(type: Int, lineNumber: Int, comment: Int, a: Int, b: Int): ByteArray {
        val out = ByteArray(24)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(0, type.toShort())
        bb.putShort(2, 16.toShort())
        bb.putInt(4, 24)
        bb.putInt(8, lineNumber)
        bb.putInt(12, comment)
        bb.putInt(16, a)
        bb.putInt(20, b)
        return out
    }

    fun serialize(doc: Doc): ByteArray {
        val poolBytes = buildStringPool(doc.strings)
        val rmBytes = doc.resourceMapIds?.let { buildResourceMap(it) } ?: ByteArray(0)
        val nodeBytesList = doc.nodes.map { buildNode(it) }

        var totalSize = 8 + poolBytes.size + rmBytes.size
        for (nb in nodeBytesList) totalSize += nb.size

        val out = ByteArrayOutputStream(totalSize)
        val header = ByteArray(8)
        val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        hb.putShort(0, CHUNK_XML.toShort())
        hb.putShort(2, 8.toShort())
        hb.putInt(4, totalSize)
        out.write(header)
        out.write(poolBytes)
        out.write(rmBytes)
        for (nb in nodeBytesList) out.write(nb)
        return out.toByteArray()
    }
}
