package com.springcat.apkdoctor.apk

/** Package facts read out of an Android App Bundle's protobuf manifest. */
data class AabInfo(
    val packageName: String?,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val splitName: String?,
    val modules: List<String>,
)

/**
 * A tiny reader for the aapt2 protobuf `XmlNode` that an `.aab` stores its
 * `AndroidManifest.xml` as — enough to show the package and version in the
 * diagnosis. An AAB is not directly installable, so nothing here is on the
 * install path; it only powers the "this is a bundle" explanation.
 *
 * Field numbers follow aapt2's `Resources.proto`:
 *   XmlNode { XmlElement element = 1; string text = 2 }
 *   XmlElement { ... string name = 3; repeated XmlAttribute attribute = 4;
 *                repeated XmlNode child = 5 }
 *   XmlAttribute { string name = 2; string value = 3; uint32 resource_id = 5;
 *                  Item compiled_item = 6 }
 *   Item { Primitive prim = 7 }
 *   Primitive { int int_decimal = 6; uint32 int_hex = 7; bool boolean = 8 }
 */
object ProtoManifest {

    private const val ATTR_VERSION_CODE = 0x0101021b
    private const val ATTR_VERSION_NAME = 0x0101021c
    private const val ATTR_MIN_SDK = 0x0101020c
    private const val ATTR_TARGET_SDK = 0x01010270

    fun parse(bytes: ByteArray, moduleNames: List<String> = emptyList()): AabInfo? = runCatching {
        val manifest = readNode(bytes)?.takeIf { it.name == "manifest" } ?: return null
        val usesSdk = manifest.children.firstOrNull { it.name == "uses-sdk" }
        AabInfo(
            packageName = manifest.attr("package")?.str,
            versionCode = manifest.attrById(ATTR_VERSION_CODE)?.intValue?.toLong()
                ?: manifest.attr("versionCode")?.let { it.intValue?.toLong() ?: it.str?.toLongOrNull() }
                ?: 0,
            versionName = manifest.attrById(ATTR_VERSION_NAME)?.textValue
                ?: manifest.attr("versionName")?.textValue,
            minSdk = usesSdk?.attrById(ATTR_MIN_SDK)?.intValue
                ?: usesSdk?.attr("minSdkVersion")?.let { it.intValue ?: it.str?.toIntOrNull() },
            targetSdk = usesSdk?.attrById(ATTR_TARGET_SDK)?.intValue
                ?: usesSdk?.attr("targetSdkVersion")?.let { it.intValue ?: it.str?.toIntOrNull() },
            splitName = manifest.attr("split")?.str,
            modules = moduleNames,
        )
    }.getOrNull()

    // --- protobuf wire decoding ------------------------------------------

    private class PElem(val name: String, val attrs: List<PAttr>, val children: List<PElem>) {
        fun attr(name: String): PAttr? = attrs.firstOrNull { it.name == name }
        fun attrById(id: Int): PAttr? = attrs.firstOrNull { it.resourceId == id }
    }

    private class PAttr(val name: String, val resourceId: Int, val str: String?, val intValue: Int?) {
        /** Best textual value, preferring the raw string. */
        val textValue: String? get() = str ?: intValue?.toString()
    }

    private fun readNode(bytes: ByteArray): PElem? {
        val node = Msg(bytes)
        val elementBytes = node.b(1) ?: return null // a text node has no element
        return readElement(elementBytes)
    }

    private fun readElement(bytes: ByteArray): PElem {
        val m = Msg(bytes)
        val name = m.b(3)?.toString(Charsets.UTF_8) ?: ""
        val attrs = m.bs(4).map { readAttr(it) }
        val children = m.bs(5).mapNotNull { readNode(it) }
        return PElem(name, attrs, children)
    }

    private fun readAttr(bytes: ByteArray): PAttr {
        val m = Msg(bytes)
        val name = m.b(2)?.toString(Charsets.UTF_8) ?: ""
        val str = m.b(3)?.toString(Charsets.UTF_8)?.ifEmpty { null }
        val resourceId = m.v(5)?.toInt() ?: 0
        val intValue = m.b(6)?.let { readItemInt(it) }
        return PAttr(name, resourceId, str, intValue)
    }

    /** Pulls an integer/boolean out of a compiled Item -> Primitive. */
    private fun readItemInt(bytes: ByteArray): Int? {
        val prim = Msg(bytes).b(7) ?: return null
        val p = Msg(prim)
        return (p.v(6) ?: p.v(7) ?: p.v(8))?.toInt()
    }

    /** One decoded protobuf message: field number -> varints and length-delimited chunks. */
    private class Msg(bytes: ByteArray) {
        private val varints = HashMap<Int, MutableList<Long>>()
        private val chunks = HashMap<Int, MutableList<ByteArray>>()

        init {
            var p = 0
            val b = bytes
            fun varint(): Long {
                var shift = 0
                var result = 0L
                while (p < b.size) {
                    val x = b[p++].toInt() and 0xFF
                    result = result or ((x and 0x7F).toLong() shl shift)
                    if (x and 0x80 == 0) break
                    shift += 7
                }
                return result
            }
            while (p < b.size) {
                val tag = varint().toInt()
                val field = tag ushr 3
                when (tag and 0x7) {
                    0 -> varints.getOrPut(field) { mutableListOf() }.add(varint())
                    1 -> { varints.getOrPut(field) { mutableListOf() }.add(0); p += 8 }
                    2 -> {
                        val len = varint().toInt()
                        if (len < 0 || p + len > b.size) break
                        chunks.getOrPut(field) { mutableListOf() }.add(b.copyOfRange(p, p + len))
                        p += len
                    }
                    5 -> { varints.getOrPut(field) { mutableListOf() }.add(0); p += 4 }
                    else -> break // groups are not used by this schema
                }
            }
        }

        fun v(field: Int): Long? = varints[field]?.firstOrNull()
        fun b(field: Int): ByteArray? = chunks[field]?.firstOrNull()
        fun bs(field: Int): List<ByteArray> = chunks[field] ?: emptyList()
    }
}
