package com.example.fixapk

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * In-place editor for the binary AndroidManifest.xml (AXML).
 *
 * It only rewrites the 4-byte integer value of the minSdkVersion /
 * targetSdkVersion attributes of the <uses-sdk> element, keeping the exact
 * same byte size so no chunk offsets or the string pool have to move.  This
 * is a Kotlin port of the reference Python implementation and needs neither
 * aapt nor apktool.
 */
object AxmlPatcher {

    class AxmlException(message: String) : Exception(message)

    data class SdkVersions(val minSdk: Int?, val targetSdk: Int?)

    /** Result of a patch: the new bytes plus the effective target we reached. */
    data class PatchResult(
        val bytes: ByteArray,
        val oldMinSdk: Int?,
        val oldTargetSdk: Int?,
        val newTargetSdk: Int,
        val note: String,
    )

    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_TYPE = 0x0003
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
    private const val UTF8_FLAG = 0x0100
    private const val TYPE_INT_DEC = 0x10
    private const val ATTR_MIN_SDK = 0x0101020C
    private const val ATTR_TARGET_SDK = 0x01010270
    private const val NO_ENTRY = -1 // 0xFFFFFFFF

    private fun buf(data: ByteArray) =
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

    private fun u16(b: ByteBuffer, off: Int) = b.getShort(off).toInt() and 0xFFFF
    private fun u32(b: ByteBuffer, off: Int) = b.getInt(off)

    private class StringPool(val data: ByteArray, val base: Int) {
        val b = buf(data)
        val count = u32(b, base + 8)
        val utf8 = (u32(b, base + 16) and UTF8_FLAG) != 0
        val stringsStart = u32(b, base + 20)
        val offsetsAt = base + 28

        fun get(index: Int): String {
            if (index < 0 || index >= count) return ""
            var off = base + stringsStart + u32(b, offsetsAt + index * 4)
            if (utf8) {
                // char count, then byte count (each 1-2 byte varint)
                run { val r = len8(off); off = r.second }
                val r = len8(off); val byteLen = r.first; off = r.second
                return String(data, off, byteLen, Charsets.UTF_8)
            } else {
                val r = len16(off); val charLen = r.first; off = r.second
                return String(data, off, charLen * 2, Charsets.UTF_16LE)
            }
        }

        private fun len8(offIn: Int): Pair<Int, Int> {
            var off = offIn
            var v = data[off].toInt() and 0xFF; off++
            if (v and 0x80 != 0) {
                v = (v and 0x7F shl 8) or (data[off].toInt() and 0xFF); off++
            }
            return Pair(v, off)
        }

        private fun len16(offIn: Int): Pair<Int, Int> {
            var off = offIn
            var v = u16(b, off); off += 2
            if (v and 0x8000 != 0) {
                v = (v and 0x7FFF shl 16) or u16(b, off); off += 2
            }
            return Pair(v, off)
        }
    }

    private data class Attr(
        val resId: Int,
        val name: String,
        val value: Int,
        val dataOff: Int,
        val typeOff: Int,
        val rawOff: Int,
    )

    private fun iterChunks(b: ByteBuffer, size: Int): List<Pair<Int, Int>> {
        if (size < 8 || u16(b, 0) != RES_XML_TYPE) {
            throw AxmlException("not a binary AndroidManifest.xml (bad magic)")
        }
        val headerSize = u16(b, 2)
        val total = minOf(u32(b, 4), size)
        val out = ArrayList<Pair<Int, Int>>()
        var off = headerSize
        while (off + 8 <= total) {
            val ctype = u16(b, off)
            val csize = u32(b, off + 4)
            if (csize < 8) break
            out.add(Pair(ctype, off))
            off += csize
        }
        return out
    }

    private fun usesSdkAttributes(data: ByteArray): List<Attr> {
        val b = buf(data)
        val chunks = iterChunks(b, data.size)

        var pool: StringPool? = null
        var resmapOff = -1
        var resmapCount = 0
        for ((ctype, off) in chunks) {
            if (ctype == RES_STRING_POOL_TYPE && pool == null) {
                pool = StringPool(data, off)
            } else if (ctype == RES_XML_RESOURCE_MAP_TYPE && resmapOff < 0) {
                val headerSize = u16(b, off + 2)
                val csize = u32(b, off + 4)
                resmapOff = off + headerSize
                resmapCount = (csize - headerSize) / 4
            }
        }
        val sp = pool ?: throw AxmlException("no string pool found")

        for ((ctype, off) in chunks) {
            if (ctype != RES_XML_START_ELEMENT_TYPE) continue
            val nameIdx = u32(b, off + 20)
            if (sp.get(nameIdx) != "uses-sdk") continue

            val attrStart = u16(b, off + 24)
            val attrSize = u16(b, off + 26)
            val attrCount = u16(b, off + 28)
            val first = off + 16 + attrStart
            val attrs = ArrayList<Attr>()
            for (i in 0 until attrCount) {
                val a = first + i * attrSize
                val nameIndex = u32(b, a + 4)
                val resId =
                    if (resmapOff >= 0 && nameIndex < resmapCount)
                        u32(b, resmapOff + nameIndex * 4)
                    else 0
                attrs.add(
                    Attr(
                        resId = resId,
                        name = sp.get(nameIndex),
                        value = u32(b, a + 16),
                        dataOff = a + 16,
                        typeOff = a + 15,
                        rawOff = a + 8,
                    )
                )
            }
            return attrs
        }
        throw AxmlException("no <uses-sdk> element found")
    }

    private fun matches(a: Attr, resId: Int, name: String) =
        a.resId == resId || a.name == name

    fun readSdkVersions(data: ByteArray): SdkVersions {
        var min: Int? = null
        var target: Int? = null
        for (a in usesSdkAttributes(data)) {
            if (matches(a, ATTR_MIN_SDK, "minSdkVersion")) min = a.value
            else if (matches(a, ATTR_TARGET_SDK, "targetSdkVersion")) target = a.value
        }
        return SdkVersions(min, target)
    }

    /**
     * Raise the effective targetSdkVersion to at least [floor].  If an explicit
     * targetSdkVersion attribute exists it is bumped; otherwise the
     * minSdkVersion (which the platform uses as the implicit target) is bumped.
     */
    fun patchToFloor(dataIn: ByteArray, floor: Int): PatchResult {
        val data = dataIn.copyOf()
        val b = buf(data)
        val attrs = usesSdkAttributes(data)

        var min: Int? = null
        var target: Int? = null
        var targetAttr: Attr? = null
        var minAttr: Attr? = null
        for (a in attrs) {
            if (matches(a, ATTR_MIN_SDK, "minSdkVersion")) { min = a.value; minAttr = a }
            else if (matches(a, ATTR_TARGET_SDK, "targetSdkVersion")) { target = a.value; targetAttr = a }
        }

        fun apply(a: Attr, newVal: Int) {
            b.putInt(a.dataOff, newVal)
            data[a.typeOff] = TYPE_INT_DEC.toByte()
            b.putInt(a.rawOff, NO_ENTRY)
        }

        val newTarget: Int
        val note: String
        when {
            targetAttr != null -> {
                newTarget = maxOf(target!!, floor)
                if (newTarget != target) {
                    apply(targetAttr, newTarget)
                    note = "targetSdkVersion $target -> $newTarget"
                } else note = "targetSdkVersion は既に $target(変更不要)"
            }
            minAttr != null -> {
                newTarget = maxOf(min!!, floor)
                if (newTarget != min) {
                    apply(minAttr, newTarget)
                    note = "targetSdkVersion 未指定のため minSdkVersion $min -> $newTarget"
                } else note = "minSdkVersion は既に $min(変更不要)"
            }
            else -> throw AxmlException(
                "minSdkVersion/targetSdkVersion が無いため、その場書き換えできません"
            )
        }

        return PatchResult(data, min, target, newTarget, note)
    }
}
