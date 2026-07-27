package com.springcat.apkdoctor.apk

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reader/writer for Android's binary XML (AXML) format, enough of it to inspect
 * and rewrite `AndroidManifest.xml` inside an APK.
 *
 * The document is parsed into a flat node list with strings resolved eagerly, so
 * callers can edit attributes without thinking about string-pool indices; the
 * pool and the resource map are rebuilt from scratch on write.
 */
object Axml {
    const val CHUNK_XML = 0x0003
    const val CHUNK_STRING_POOL = 0x0001
    const val CHUNK_RESOURCE_MAP = 0x0180
    const val CHUNK_START_NS = 0x0100
    const val CHUNK_END_NS = 0x0101
    const val CHUNK_START_ELEMENT = 0x0102
    const val CHUNK_END_ELEMENT = 0x0103
    const val CHUNK_CDATA = 0x0104

    const val TYPE_NULL = 0x00
    const val TYPE_REFERENCE = 0x01
    const val TYPE_STRING = 0x03
    const val TYPE_FLOAT = 0x04
    const val TYPE_INT_DEC = 0x10
    const val TYPE_INT_HEX = 0x11
    const val TYPE_INT_BOOLEAN = 0x12

    const val NO_ENTRY = -1

    const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    /** Framework attribute resource IDs used by the manifest checks. */
    object Attr {
        const val NAME = 0x01010003
        const val LABEL = 0x01010001
        const val ICON = 0x01010002
        const val DEBUGGABLE = 0x0101000f
        const val SHARED_USER_ID = 0x0101000e
        const val VERSION_CODE = 0x0101021b
        const val VERSION_NAME = 0x0101021c
        const val MIN_SDK_VERSION = 0x0101020c
        const val TARGET_SDK_VERSION = 0x01010270
        const val MAX_SDK_VERSION = 0x01010271
        const val TEST_ONLY = 0x01010272
        const val INSTALL_LOCATION = 0x010102b7
        const val EXTRACT_NATIVE_LIBS = 0x010104ea
        const val IS_SPLIT_REQUIRED = 0x01010591
        const val VERSION_CODE_MAJOR = 0x01010576
        const val REQUIRED_SPLIT_TYPES = 0x0101064e
        const val SPLIT_TYPES = 0x0101064f
    }
}

class AxmlAttribute(
    val uri: String?,
    val name: String,
    /** Framework resource ID, or 0 when the attribute is not resource-mapped. */
    val resourceId: Int,
    var rawValue: String?,
    var valueType: Int,
    var valueData: Int,
) {
    val isAndroid: Boolean get() = uri == Axml.ANDROID_NS

    /** Value as text, using the typed value when no raw string is present. */
    fun asString(): String? = when {
        rawValue != null -> rawValue
        valueType == Axml.TYPE_INT_BOOLEAN -> (valueData != 0).toString()
        valueType == Axml.TYPE_INT_DEC -> valueData.toString()
        valueType == Axml.TYPE_INT_HEX -> "0x%08x".format(valueData)
        valueType == Axml.TYPE_REFERENCE -> "@0x%08x".format(valueData)
        else -> null
    }

    /** Value as an integer, tolerating APKs that store numbers as strings. */
    fun asInt(): Int? = when (valueType) {
        Axml.TYPE_INT_DEC, Axml.TYPE_INT_HEX -> valueData
        Axml.TYPE_INT_BOOLEAN -> if (valueData != 0) 1 else 0
        Axml.TYPE_STRING -> rawValue?.trim()?.let { text ->
            text.toIntOrNull() ?: SDK_CODENAMES[text.uppercase()]
        }
        else -> null
    }

    fun asBoolean(): Boolean? = when (valueType) {
        Axml.TYPE_INT_BOOLEAN, Axml.TYPE_INT_DEC, Axml.TYPE_INT_HEX -> valueData != 0
        Axml.TYPE_STRING -> rawValue?.trim()?.lowercase()?.let {
            if (it == "true") true else if (it == "false") false else null
        }
        else -> null
    }

    fun setInt(value: Int) {
        valueType = Axml.TYPE_INT_DEC
        valueData = value
        rawValue = null
    }

    fun setBoolean(value: Boolean) {
        valueType = Axml.TYPE_INT_BOOLEAN
        valueData = if (value) -1 else 0
        rawValue = null
    }

    companion object {
        /**
         * Preview releases ship manifests whose SDK version is a codename letter
         * rather than a number.
         */
        private val SDK_CODENAMES = mapOf(
            "N" to 24, "N_MR1" to 25, "O" to 26, "O_MR1" to 27, "P" to 28,
            "Q" to 29, "R" to 30, "S" to 31, "S_V2" to 32, "TIRAMISU" to 33,
            "UPSIDEDOWNCAKE" to 34, "VANILLAICECREAM" to 35, "BAKLAVA" to 36,
        )
    }
}

sealed interface AxmlNode {
    val line: Int
}

class AxmlStartNamespace(val prefix: String, val uri: String, override val line: Int) : AxmlNode

class AxmlEndNamespace(val prefix: String, val uri: String, override val line: Int) : AxmlNode

class AxmlElement(
    val uri: String?,
    val name: String,
    val attributes: MutableList<AxmlAttribute>,
    override val line: Int,
) : AxmlNode {
    fun attr(resourceId: Int): AxmlAttribute? = attributes.firstOrNull { it.resourceId == resourceId }

    fun attr(name: String): AxmlAttribute? =
        attributes.firstOrNull { it.uri == null && it.name == name }

    /** Returns the existing android attribute or appends a fresh one. */
    fun requireAndroidAttr(resourceId: Int, name: String): AxmlAttribute =
        attr(resourceId) ?: AxmlAttribute(
            uri = Axml.ANDROID_NS,
            name = name,
            resourceId = resourceId,
            rawValue = null,
            valueType = Axml.TYPE_INT_DEC,
            valueData = 0,
        ).also { attributes.add(it) }

    fun removeAttr(resourceId: Int): Boolean = attributes.removeAll { it.resourceId == resourceId }
}

class AxmlEndElement(val uri: String?, val name: String, override val line: Int) : AxmlNode

class AxmlCData(
    val text: String,
    val valueType: Int,
    val valueData: Int,
    override val line: Int,
) : AxmlNode

class AxmlDocument(val nodes: MutableList<AxmlNode>) {

    /** Every start element in document order. */
    val elements: List<AxmlElement> get() = nodes.filterIsInstance<AxmlElement>()

    fun element(name: String): AxmlElement? = elements.firstOrNull { it.name == name }

    fun elements(name: String): List<AxmlElement> = elements.filter { it.name == name }

    val root: AxmlElement? get() = nodes.filterIsInstance<AxmlElement>().firstOrNull()

    /**
     * Inserts a new element (as an empty, self-closing tag) as the first child of
     * [parent]. Used to add `<uses-sdk>` to manifests that lack it entirely.
     */
    fun insertChildFirst(parent: AxmlElement, child: AxmlElement) {
        val parentIndex = nodes.indexOf(parent)
        require(parentIndex >= 0) { "parent element is not part of this document" }
        nodes.add(parentIndex + 1, AxmlEndElement(child.uri, child.name, child.line))
        nodes.add(parentIndex + 1, child)
    }

    /** Removes a start element together with everything up to its matching end tag. */
    fun removeElement(element: AxmlElement) {
        val start = nodes.indexOf(element)
        if (start < 0) return
        var depth = 0
        var end = start
        while (end < nodes.size) {
            when (nodes[end]) {
                is AxmlElement -> depth++
                is AxmlEndElement -> {
                    depth--
                    if (depth == 0) break
                }
                else -> Unit
            }
            end++
        }
        val last = if (end < nodes.size) end else nodes.size - 1
        for (i in last downTo start) nodes.removeAt(i)
    }

    fun toByteArray(): ByteArray = AxmlWriter(this).write()

    companion object {
        fun parse(bytes: ByteArray): AxmlDocument = AxmlReader(bytes).read()
    }
}

private class AxmlReader(bytes: ByteArray) {
    private val buf: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private var strings: List<String> = emptyList()
    private var resourceIds: IntArray = IntArray(0)

    fun read(): AxmlDocument {
        if (buf.remaining() < 8) throw AxmlException("ファイルが小さすぎます")
        val type = buf.short.toInt() and 0xFFFF
        val headerSize = buf.short.toInt() and 0xFFFF
        val size = buf.int
        if (type != Axml.CHUNK_XML) {
            throw AxmlException("バイナリXMLではありません (type=0x%04x)".format(type))
        }
        val limit = minOf(if (size > 0) size else buf.capacity(), buf.capacity())
        buf.position(headerSize.coerceAtLeast(8))

        val nodes = mutableListOf<AxmlNode>()
        while (buf.position() + 8 <= limit) {
            val chunkStart = buf.position()
            val chunkType = buf.short.toInt() and 0xFFFF
            val chunkHeaderSize = buf.short.toInt() and 0xFFFF
            val chunkSize = buf.int
            if (chunkSize < 8 || chunkStart + chunkSize > limit) break

            when (chunkType) {
                Axml.CHUNK_STRING_POOL -> strings = readStringPool(chunkStart, chunkSize)
                Axml.CHUNK_RESOURCE_MAP -> {
                    val count = (chunkSize - chunkHeaderSize) / 4
                    resourceIds = IntArray(count) { buf.int }
                }
                Axml.CHUNK_START_NS, Axml.CHUNK_END_NS, Axml.CHUNK_START_ELEMENT,
                Axml.CHUNK_END_ELEMENT, Axml.CHUNK_CDATA,
                -> {
                    val line = buf.int
                    buf.int // comment reference, dropped on rewrite
                    readNode(chunkType, line)?.let { nodes.add(it) }
                }
            }
            buf.position(chunkStart + chunkSize)
        }
        if (nodes.none { it is AxmlElement }) throw AxmlException("XML要素が見つかりません")
        return AxmlDocument(nodes)
    }

    private fun readNode(chunkType: Int, line: Int): AxmlNode? = when (chunkType) {
        Axml.CHUNK_START_NS -> {
            val prefix = string(buf.int).orEmpty()
            val uri = string(buf.int).orEmpty()
            AxmlStartNamespace(prefix, uri, line)
        }

        Axml.CHUNK_END_NS -> {
            val prefix = string(buf.int).orEmpty()
            val uri = string(buf.int).orEmpty()
            AxmlEndNamespace(prefix, uri, line)
        }

        Axml.CHUNK_START_ELEMENT -> readStartElement(line)

        Axml.CHUNK_END_ELEMENT -> {
            val uri = string(buf.int)
            val name = string(buf.int).orEmpty()
            AxmlEndElement(uri, name, line)
        }

        Axml.CHUNK_CDATA -> {
            val text = string(buf.int).orEmpty()
            buf.short // Res_value size
            buf.get() // res0
            val valueType = buf.get().toInt() and 0xFF
            val valueData = buf.int
            AxmlCData(text, valueType, valueData, line)
        }

        else -> null
    }

    private fun readStartElement(line: Int): AxmlElement {
        val nodeStart = buf.position() - 16
        val uri = string(buf.int)
        val name = string(buf.int).orEmpty()
        val attributeStart = buf.short.toInt() and 0xFFFF
        val attributeSize = buf.short.toInt() and 0xFFFF
        val attributeCount = buf.short.toInt() and 0xFFFF
        buf.short // id index
        buf.short // class index
        buf.short // style index

        val stride = if (attributeSize >= 20) attributeSize else 20
        val attributes = ArrayList<AxmlAttribute>(attributeCount)
        for (i in 0 until attributeCount) {
            val at = nodeStart + 16 + attributeStart + i * stride
            if (at + 20 > buf.capacity()) break
            buf.position(at)
            val attrUri = string(buf.int)
            val attrNameIndex = buf.int
            val attrName = string(attrNameIndex).orEmpty()
            val rawIndex = buf.int
            buf.short // Res_value size
            buf.get() // res0
            val valueType = buf.get().toInt() and 0xFF
            val valueData = buf.int

            val resourceId = if (attrNameIndex in resourceIds.indices) resourceIds[attrNameIndex] else 0
            val raw = when {
                rawIndex != Axml.NO_ENTRY -> string(rawIndex)
                valueType == Axml.TYPE_STRING -> string(valueData)
                else -> null
            }
            attributes.add(AxmlAttribute(attrUri, attrName, resourceId, raw, valueType, valueData))
        }
        return AxmlElement(uri, name, attributes, line)
    }

    private fun string(index: Int): String? =
        if (index == Axml.NO_ENTRY || index !in strings.indices) null else strings[index]

    private fun readStringPool(chunkStart: Int, chunkSize: Int): List<String> {
        buf.position(chunkStart + 8)
        val stringCount = buf.int
        buf.int // style count
        val flags = buf.int
        val stringsStart = buf.int
        buf.int // styles start
        val isUtf8 = (flags and 0x100) != 0

        if (stringCount < 0 || stringCount > 1 shl 22) throw AxmlException("文字列プールが不正です")
        // Style spans are meaningless in a manifest and are dropped on rewrite.
        val offsets = IntArray(stringCount) { buf.int }
        val dataStart = chunkStart + stringsStart
        val dataEnd = minOf(chunkStart + chunkSize, buf.capacity())

        return offsets.map { offset ->
            val at = dataStart + offset
            if (at < 0 || at >= dataEnd) return@map ""
            buf.position(at)
            runCatching {
                if (isUtf8) readUtf8String(dataEnd) else readUtf16String(dataEnd)
            }.getOrDefault("")
        }
    }

    private fun readUtf8String(limit: Int): String {
        readUtf8Length() // length in characters, unused
        val byteLength = readUtf8Length()
        val available = (limit - buf.position()).coerceAtLeast(0)
        val count = byteLength.coerceIn(0, available)
        val data = ByteArray(count)
        buf.get(data)
        return String(data, Charsets.UTF_8)
    }

    /** UTF-8 pools encode a length > 0x7F across two bytes. */
    private fun readUtf8Length(): Int {
        val first = buf.get().toInt() and 0xFF
        return if (first and 0x80 != 0) ((first and 0x7F) shl 8) or (buf.get().toInt() and 0xFF) else first
    }

    private fun readUtf16String(limit: Int): String {
        var length = buf.short.toInt() and 0xFFFF
        if (length and 0x8000 != 0) {
            length = ((length and 0x7FFF) shl 16) or (buf.short.toInt() and 0xFFFF)
        }
        val available = ((limit - buf.position()) / 2).coerceAtLeast(0)
        val count = length.coerceIn(0, available)
        val chars = CharArray(count) { buf.short.toInt().toChar() }
        return String(chars)
    }
}

private class AxmlWriter(private val document: AxmlDocument) {

    /** Resource-mapped attribute names, which must occupy the lowest pool indices. */
    private val headStrings = mutableListOf<String>()
    private val headResourceIds = mutableListOf<Int>()
    private val headIndex = HashMap<Long, Int>()

    private val tailStrings = mutableListOf<String>()
    private val tailIndex = HashMap<String, Int>()

    fun write(): ByteArray {
        collectAttributeNames()

        val body = ByteArrayOutputStream()
        for (node in document.nodes) writeNode(body, node)
        val nodeBytes = body.toByteArray()

        val poolBytes = writeStringPool()
        val resourceMapBytes = writeResourceMap()

        val total = 8 + poolBytes.size + resourceMapBytes.size + nodeBytes.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(Axml.CHUNK_XML.toShort())
        out.putShort(8.toShort())
        out.putInt(total)
        out.put(poolBytes)
        out.put(resourceMapBytes)
        out.put(nodeBytes)
        return out.array()
    }

    /**
     * Every resource-mapped attribute name is interned up front, sorted by
     * resource ID: the framework walks an element's attributes assuming that
     * order, and the resource map is looked up positionally by string index.
     */
    private fun collectAttributeNames() {
        val mapped = sortedMapOf<Long, String>()
        for (node in document.nodes) {
            if (node !is AxmlElement) continue
            node.attributes.sortWith(ATTRIBUTE_ORDER)
            for (attribute in node.attributes) {
                if (attribute.resourceId == 0) continue
                mapped.putIfAbsent(attribute.resourceId.toUnsignedLong(), attribute.name)
            }
        }
        for ((resourceId, name) in mapped) {
            headIndex[resourceId] = headStrings.size
            headStrings.add(name)
            headResourceIds.add(resourceId.toInt())
        }
    }

    /** Interns a plain string after the resource-mapped block. */
    private fun intern(value: String?): Int {
        if (value == null) return Axml.NO_ENTRY
        tailIndex[value]?.let { return it }
        val index = headStrings.size + tailStrings.size
        tailStrings.add(value)
        tailIndex[value] = index
        return index
    }

    private fun attributeNameIndex(attribute: AxmlAttribute): Int =
        if (attribute.resourceId != 0) {
            headIndex.getValue(attribute.resourceId.toUnsignedLong())
        } else {
            intern(attribute.name)
        }

    private fun writeNode(out: ByteArrayOutputStream, node: AxmlNode) {
        when (node) {
            is AxmlStartNamespace -> out.writeChunk(Axml.CHUNK_START_NS, node.line, 8) {
                putInt(intern(node.prefix))
                putInt(intern(node.uri))
            }

            is AxmlEndNamespace -> out.writeChunk(Axml.CHUNK_END_NS, node.line, 8) {
                putInt(intern(node.prefix))
                putInt(intern(node.uri))
            }

            is AxmlElement -> {
                val nameIndex = intern(node.name)
                val uriIndex = intern(node.uri)
                val attributeIndices = node.attributes.map { attributeNameIndex(it) to intern(it.rawValue) }
                out.writeChunk(Axml.CHUNK_START_ELEMENT, node.line, 20 + 20 * node.attributes.size) {
                    putInt(uriIndex)
                    putInt(nameIndex)
                    putShort(20.toShort()) // attributeStart
                    putShort(20.toShort()) // attributeSize
                    putShort(node.attributes.size.toShort())
                    putShort(0.toShort()) // id index
                    putShort(0.toShort()) // class index
                    putShort(0.toShort()) // style index
                    node.attributes.forEachIndexed { i, attribute ->
                        val (attrNameIndex, rawIndex) = attributeIndices[i]
                        putInt(intern(attribute.uri))
                        putInt(attrNameIndex)
                        putInt(rawIndex)
                        putShort(8.toShort()) // Res_value size
                        put(0.toByte()) // res0
                        put(attribute.valueType.toByte())
                        // A string value points at the pool, so it must be re-interned.
                        putInt(if (attribute.valueType == Axml.TYPE_STRING) rawIndex else attribute.valueData)
                    }
                }
            }

            is AxmlEndElement -> {
                val uriIndex = intern(node.uri)
                val nameIndex = intern(node.name)
                out.writeChunk(Axml.CHUNK_END_ELEMENT, node.line, 8) {
                    putInt(uriIndex)
                    putInt(nameIndex)
                }
            }

            is AxmlCData -> {
                val textIndex = intern(node.text)
                out.writeChunk(Axml.CHUNK_CDATA, node.line, 12) {
                    putInt(textIndex)
                    putShort(8.toShort())
                    put(0.toByte())
                    put(node.valueType.toByte())
                    putInt(if (node.valueType == Axml.TYPE_STRING) textIndex else node.valueData)
                }
            }
        }
    }

    private fun ByteArrayOutputStream.writeChunk(
        type: Int,
        line: Int,
        payloadSize: Int,
        payload: ByteBuffer.() -> Unit,
    ) {
        val size = 16 + payloadSize
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(type.toShort())
        buffer.putShort(16.toShort())
        buffer.putInt(size)
        buffer.putInt(line)
        buffer.putInt(Axml.NO_ENTRY) // comment
        buffer.payload()
        write(buffer.array())
    }

    /** UTF-16 pool: universally supported and free of the UTF-8 length quirks. */
    private fun writeStringPool(): ByteArray {
        val all = headStrings + tailStrings
        val data = ByteArrayOutputStream()
        val offsets = IntArray(all.size)
        all.forEachIndexed { index, value ->
            offsets[index] = data.size()
            val chars = value.toCharArray()
            val header = ByteArrayOutputStream()
            val buffer = if (chars.size >= 0x8000) {
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).apply {
                    putShort((((chars.size shr 16) and 0x7FFF) or 0x8000).toShort())
                    putShort((chars.size and 0xFFFF).toShort())
                }
            } else {
                ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
                    .apply { putShort(chars.size.toShort()) }
            }
            header.write(buffer.array())
            data.write(header.toByteArray())
            val body = ByteBuffer.allocate(chars.size * 2 + 2).order(ByteOrder.LITTLE_ENDIAN)
            for (c in chars) body.putShort(c.code.toShort())
            body.putShort(0)
            data.write(body.array())
        }
        var stringData = data.toByteArray()
        val padding = (4 - stringData.size % 4) % 4
        if (padding != 0) stringData += ByteArray(padding)

        val headerSize = 28
        val stringsStart = headerSize + 4 * all.size
        val size = stringsStart + stringData.size
        val out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(Axml.CHUNK_STRING_POOL.toShort())
        out.putShort(headerSize.toShort())
        out.putInt(size)
        out.putInt(all.size)
        out.putInt(0) // style count
        out.putInt(0) // flags: UTF-16, unsorted
        out.putInt(stringsStart)
        out.putInt(0) // styles start
        for (offset in offsets) out.putInt(offset)
        out.put(stringData)
        return out.array()
    }

    private fun writeResourceMap(): ByteArray {
        val size = 8 + 4 * headResourceIds.size
        val out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(Axml.CHUNK_RESOURCE_MAP.toShort())
        out.putShort(8.toShort())
        out.putInt(size)
        for (id in headResourceIds) out.putInt(id)
        return out.array()
    }

    private companion object {
        /**
         * Resource-mapped attributes ascending by ID, unmapped ones last —
         * `ResTable::retrieveAttributes` performs a single forward walk and would
         * silently miss anything out of order.
         */
        val ATTRIBUTE_ORDER = Comparator<AxmlAttribute> { a, b ->
            val left = if (a.resourceId == 0) Long.MAX_VALUE else a.resourceId.toUnsignedLong()
            val right = if (b.resourceId == 0) Long.MAX_VALUE else b.resourceId.toUnsignedLong()
            left.compareTo(right)
        }

        fun Int.toUnsignedLong(): Long = toLong() and 0xFFFFFFFFL
    }
}

class AxmlException(message: String, cause: Throwable? = null) : Exception(message, cause)
