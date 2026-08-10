package site.ragdollp.permeditor

/**
 * AndroidManifest.xml permission helpers built on top of [Axml]. Only
 * concerned with <uses-permission> / <uses-permission-sdk-23> elements
 * directly under <manifest>. Ported from apk-permission-manager/manifest.js.
 */
object ManifestPerms {
    val PERMISSION_TAGS = listOf("uses-permission", "uses-permission-sdk-23")

    data class PermissionEntry(val name: String, val tag: String)

    private data class PermissionNode(val name: String, val tag: String, val nodeIndex: Int)

    private fun attrValueString(doc: Axml.Doc, attr: Axml.Attribute): String? {
        if (attr.valueType == 0x03 && attr.valueData != Axml.NO_INDEX) return doc.strings.getOrNull(attr.valueData)
        if (attr.rawValue != Axml.NO_INDEX) return doc.strings.getOrNull(attr.rawValue)
        return null
    }

    private fun nameAttrIndex(doc: Axml.Doc, node: Axml.Node.ElemStart): Int {
        for (i in node.attributes.indices) {
            if (doc.strings.getOrNull(node.attributes[i].name) == "name") return i
        }
        return -1
    }

    private fun listPermissionNodes(doc: Axml.Doc): List<PermissionNode> {
        val out = ArrayList<PermissionNode>()
        for (i in doc.nodes.indices) {
            val n = doc.nodes[i]
            if (n !is Axml.Node.ElemStart) continue
            val tag = doc.strings.getOrNull(n.name) ?: continue
            if (tag !in PERMISSION_TAGS) continue
            val ai = nameAttrIndex(doc, n)
            if (ai == -1) continue
            val name = attrValueString(doc, n.attributes[ai]) ?: continue
            out.add(PermissionNode(name, tag, i))
        }
        return out
    }

    fun listPermissions(doc: Axml.Doc): List<PermissionEntry> =
        listPermissionNodes(doc).map { PermissionEntry(it.name, it.tag) }

    private fun findMatchingEnd(doc: Axml.Doc, startIdx: Int): Int {
        var depth = 0
        for (i in startIdx until doc.nodes.size) {
            when (doc.nodes[i]) {
                is Axml.Node.ElemStart -> depth++
                is Axml.Node.ElemEnd -> {
                    depth--
                    if (i > startIdx && depth == 0) return i
                }
                else -> {}
            }
        }
        return -1
    }

    fun removePermission(doc: Axml.Doc, name: String): Boolean {
        val target = listPermissionNodes(doc).firstOrNull { it.name == name } ?: return false
        val endIdx = findMatchingEnd(doc, target.nodeIndex)
        check(endIdx != -1) { "Malformed manifest: no matching end tag for $name" }
        for (i in endIdx downTo target.nodeIndex) doc.nodes.removeAt(i)
        return true
    }

    private fun findManifestEndIndex(doc: Axml.Doc): Int {
        for (i in doc.nodes.indices.reversed()) {
            val n = doc.nodes[i]
            if (n is Axml.Node.ElemEnd && doc.strings.getOrNull(n.name) == "manifest") return i
        }
        return -1
    }

    private fun findTemplateElement(doc: Axml.Doc, tag: String): Axml.Node.ElemStart? {
        for (n in doc.nodes) {
            if (n is Axml.Node.ElemStart && doc.strings.getOrNull(n.name) == tag && nameAttrIndex(doc, n) != -1) {
                return n
            }
        }
        return null
    }

    /**
     * Adds <uses-permission android:name="name"/> (or the sdk-23 variant)
     * as the last child of <manifest>. Clones ns/name string indices and
     * attribute shape from an existing uses-permission* element when one
     * is present; falls back to locating the "android" namespace URI and
     * a "name" attribute string used elsewhere in the manifest otherwise.
     */
    fun addPermission(doc: Axml.Doc, name: String, tag: String = "uses-permission"): Boolean {
        val existing = listPermissionNodes(doc)
        if (existing.any { it.name == name && it.tag == tag }) return false

        val template = findTemplateElement(doc, tag) ?: findTemplateElement(doc, PERMISSION_TAGS[0])
        val elemNs: Int
        val attrNs: Int
        val attrNameIdx: Int
        val elemNameIdx: Int

        if (template != null) {
            elemNs = template.ns
            val ai = nameAttrIndex(doc, template)
            val attrTemplate = template.attributes[ai]
            attrNs = attrTemplate.ns
            attrNameIdx = attrTemplate.name
            elemNameIdx = Axml.internString(doc, tag)
        } else {
            val androidUri = Axml.findString(doc.strings, "http://schemas.android.com/apk/res/android")
            val nameStr = Axml.findString(doc.strings, "name")
            check(androidUri != -1 && nameStr != -1) { "Could not find a template to add a new permission from." }
            elemNs = Axml.NO_INDEX
            attrNs = androidUri
            attrNameIdx = nameStr
            elemNameIdx = Axml.internString(doc, tag)
        }

        val valueIdx = Axml.internString(doc, name)

        val startNode = Axml.Node.ElemStart(
            lineNumber = 0, comment = Axml.NO_INDEX, ns = elemNs, name = elemNameIdx,
            idIndex = 0, classIndex = 0, styleIndex = 0,
            attributes = mutableListOf(
                Axml.Attribute(
                    ns = attrNs, name = attrNameIdx, rawValue = valueIdx,
                    valueSize = 8, valueRes0 = 0, valueType = 0x03, valueData = valueIdx
                )
            )
        )
        val endNode = Axml.Node.ElemEnd(lineNumber = 0, comment = Axml.NO_INDEX, ns = elemNs, name = elemNameIdx)

        val insertAt = findManifestEndIndex(doc)
        check(insertAt != -1) { "Could not find </manifest> to insert permission before." }
        doc.nodes.add(insertAt, startNode)
        doc.nodes.add(insertAt + 1, endNode)
        return true
    }
}
