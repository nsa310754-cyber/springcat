package com.apkbuilder.core.proto

/**
 * Edits the protobuf-encoded `AndroidManifest.xml` found inside an Android App
 * Bundle (base/manifest/AndroidManifest.xml). Field numbers follow aapt2's
 * `Resources.proto`:
 *
 *   XmlNode      { element=1, text=2, source=3 }
 *   XmlElement   { namespace_declaration=1, namespace_uri=2, name=3, attribute=4, child=5 }
 *   XmlAttribute { namespace_uri=1, name=2, value=3, source=4, resource_id=5, compiled_item=6 }
 *   Item         { ref=1, str=2, ... prim=7 }
 *   String       { value=1 }
 *   Primitive    { ... int_decimal_value=6 ... }
 *
 * Mirrors what [com.apkbuilder.core.axml.AxmlDocument] does for the APK path:
 * rewrite package/versionName/versionCode/label and inject <uses-permission>
 * / <meta-data> — but for the bundle's proto manifest instead of binary AXML.
 */
class ProtoManifestEditor private constructor(private val root: ProtoMessage) {

    companion object {
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        private const val RES_ID_LABEL = 0x01010001
        private const val RES_ID_NAME = 0x01010003
        private const val RES_ID_VALUE = 0x01010024

        // XmlNode
        private const val F_NODE_ELEMENT = 1
        // XmlElement
        private const val F_EL_NAME = 3
        private const val F_EL_ATTRIBUTE = 4
        private const val F_EL_CHILD = 5
        // XmlAttribute
        private const val F_ATTR_NS = 1
        private const val F_ATTR_NAME = 2
        private const val F_ATTR_VALUE = 3
        private const val F_ATTR_RES_ID = 5
        private const val F_ATTR_COMPILED = 6
        // Item / String / Primitive
        private const val F_ITEM_STR = 2
        private const val F_ITEM_PRIM = 7
        private const val F_STRING_VALUE = 1
        private const val F_PRIM_INT_DECIMAL = 6

        fun parse(manifestProtoBytes: ByteArray): ProtoManifestEditor =
            ProtoManifestEditor(ProtoMessage.parse(manifestProtoBytes))
    }

    fun toByteArray(): ByteArray = root.toByteArray()

    private fun rootElementField(): ProtoField =
        root.first(F_NODE_ELEMENT) ?: error("proto manifest has no root element")

    /** The <manifest> element message. Caller must write it back via [writeElement]. */
    private fun manifestElement(): ProtoMessage = rootElementField().asMessage()

    private fun writeManifestElement(element: ProtoMessage) {
        rootElementField().setMessage(element)
    }

    private fun elementName(element: ProtoMessage): String? = element.first(F_EL_NAME)?.asString()

    fun setPackage(value: String) = setManifestStringAttr("package", value)
    fun setVersionName(value: String) = setManifestStringAttr("versionName", value)

    fun setVersionCode(value: Int) {
        val element = manifestElement()
        val attr = findAttr(element, "versionCode") ?: error("manifest has no versionCode attribute")
        val attrMsg = attr.asMessage()
        attrMsg.first(F_ATTR_VALUE)?.setString(value.toString())
        // compiled_item -> Primitive.int_decimal_value
        attrMsg.first(F_ATTR_COMPILED)?.let { compiled ->
            val item = compiled.asMessage()
            item.first(F_ITEM_PRIM)?.let { prim ->
                val primMsg = prim.asMessage()
                primMsg.first(F_PRIM_INT_DECIMAL)?.let { it.varint = value.toLong() }
                prim.setMessage(primMsg)
            }
            compiled.setMessage(item)
        }
        attr.setMessage(attrMsg)
        writeManifestElement(element)
    }

    private fun setManifestStringAttr(name: String, value: String) {
        val element = manifestElement()
        val attr = findAttr(element, name) ?: error("manifest has no '$name' attribute")
        val attrMsg = attr.asMessage()
        attrMsg.first(F_ATTR_VALUE)?.setString(value)
            ?: attrMsg.fields.add(ProtoField(F_ATTR_VALUE, WIRE_LEN).apply { setString(value) })
        // If a string attribute carries a compiled Str item, keep it in sync.
        attrMsg.first(F_ATTR_COMPILED)?.let { compiled ->
            val item = compiled.asMessage()
            item.first(F_ITEM_STR)?.let { strField ->
                val strMsg = strField.asMessage()
                strMsg.first(F_STRING_VALUE)?.setString(value)
                strField.setMessage(strMsg)
                compiled.setMessage(item)
            }
        }
        attr.setMessage(attrMsg)
        writeManifestElement(element)
    }

    /** Sets android:label on <application> to a literal string (replacing a @string ref if present). */
    fun setApplicationLabel(label: String) {
        val element = manifestElement()
        val appChild = findChildElementField(element, "application")
            ?: error("manifest has no <application> element")
        val appNode = appChild.asMessage() // XmlNode
        val appElementField = appNode.first(F_NODE_ELEMENT) ?: error("<application> node has no element")
        val appElement = appElementField.asMessage()

        val labelAttr = findAttr(appElement, "label")
        if (labelAttr != null) {
            val attrMsg = labelAttr.asMessage()
            attrMsg.first(F_ATTR_VALUE)?.setString(label)
                ?: attrMsg.fields.add(ProtoField(F_ATTR_VALUE, WIRE_LEN).apply { setString(label) })
            // Force compiled_item to a literal Str (drops any @string/app_name reference).
            attrMsg.fields.removeAll { it.number == F_ATTR_COMPILED }
            attrMsg.fields.add(ProtoField(F_ATTR_COMPILED, WIRE_LEN).apply { setMessage(stringItem(label)) })
            labelAttr.setMessage(attrMsg)
        } else {
            appElement.fields.add(
                ProtoField(F_EL_ATTRIBUTE, WIRE_LEN).apply {
                    setMessage(buildAttribute(ANDROID_NS, "label", label, RES_ID_LABEL))
                },
            )
        }
        appElementField.setMessage(appElement)
        appChild.setMessage(appNode)
        writeManifestElement(element)
    }

    /** Adds <uses-permission android:name="..."/> as a child of <manifest>. */
    fun addUsesPermission(permission: String) {
        val element = manifestElement()
        element.fields.add(
            ProtoField(F_EL_CHILD, WIRE_LEN).apply {
                setMessage(elementNode("uses-permission", listOf(buildAttribute(ANDROID_NS, "name", permission, RES_ID_NAME))))
            },
        )
        writeManifestElement(element)
    }

    /** Adds <meta-data android:name=".." android:value=".."/> under <application>. */
    fun addApplicationMetaData(name: String, value: String) {
        val element = manifestElement()
        val appChild = findChildElementField(element, "application")
            ?: error("manifest has no <application> element")
        val appNode = appChild.asMessage()
        val appElementField = appNode.first(F_NODE_ELEMENT) ?: error("<application> node has no element")
        val appElement = appElementField.asMessage()
        appElement.fields.add(
            ProtoField(F_EL_CHILD, WIRE_LEN).apply {
                setMessage(
                    elementNode(
                        "meta-data",
                        listOf(
                            buildAttribute(ANDROID_NS, "name", name, RES_ID_NAME),
                            buildAttribute(ANDROID_NS, "value", value, RES_ID_VALUE),
                        ),
                    ),
                )
            },
        )
        appElementField.setMessage(appElement)
        appChild.setMessage(appNode)
        writeManifestElement(element)
    }

    // ---- builders ----

    private fun findAttr(element: ProtoMessage, name: String): ProtoField? =
        element.all(F_EL_ATTRIBUTE).firstOrNull { it.asMessage().first(F_ATTR_NAME)?.asString() == name }

    private fun findChildElementField(element: ProtoMessage, childName: String): ProtoField? =
        element.all(F_EL_CHILD).firstOrNull { childField ->
            childField.asMessage().first(F_NODE_ELEMENT)?.asMessage()?.let { elementName(it) } == childName
        }

    /** Wraps an XmlElement in an XmlNode message. */
    private fun elementNode(name: String, attributes: List<ProtoMessage>): ProtoMessage {
        val element = ProtoMessage()
        element.fields.add(ProtoField(F_EL_NAME, WIRE_LEN).apply { setString(name) })
        for (attr in attributes) {
            element.fields.add(ProtoField(F_EL_ATTRIBUTE, WIRE_LEN).apply { setMessage(attr) })
        }
        val node = ProtoMessage()
        node.fields.add(ProtoField(F_NODE_ELEMENT, WIRE_LEN).apply { setMessage(element) })
        return node
    }

    private fun buildAttribute(namespace: String, name: String, value: String, resourceId: Int): ProtoMessage {
        val attr = ProtoMessage()
        attr.fields.add(ProtoField(F_ATTR_NS, WIRE_LEN).apply { setString(namespace) })
        attr.fields.add(ProtoField(F_ATTR_NAME, WIRE_LEN).apply { setString(name) })
        attr.fields.add(ProtoField(F_ATTR_VALUE, WIRE_LEN).apply { setString(value) })
        attr.fields.add(ProtoField(F_ATTR_RES_ID, WIRE_VARINT).apply { varint = resourceId.toLong() and 0xffffffffL })
        attr.fields.add(ProtoField(F_ATTR_COMPILED, WIRE_LEN).apply { setMessage(stringItem(value)) })
        return attr
    }

    /** Item { str: String { value } } */
    private fun stringItem(value: String): ProtoMessage {
        val str = ProtoMessage()
        str.fields.add(ProtoField(F_STRING_VALUE, WIRE_LEN).apply { setString(value) })
        val item = ProtoMessage()
        item.fields.add(ProtoField(F_ITEM_STR, WIRE_LEN).apply { setMessage(str) })
        return item
    }
}
