package com.apkbuilder.core.proto

import java.io.ByteArrayOutputStream

/**
 * A minimal, lossless protobuf wire-format codec used to edit the
 * protobuf-encoded `AndroidManifest.xml` inside an Android App Bundle (.aab)
 * without needing `aapt2` or generated proto classes on-device.
 *
 * It parses a message into an ordered list of [ProtoField]s (preserving field
 * order and any unknown fields verbatim) and can re-serialize it byte-for-byte.
 * Length-delimited fields are kept as raw bytes and only re-parsed as nested
 * messages on demand, so an edit deep in the tree just rewrites the affected
 * bytes and every enclosing length prefix is recomputed on write.
 */
const val WIRE_VARINT = 0
const val WIRE_FIXED64 = 1
const val WIRE_LEN = 2
const val WIRE_FIXED32 = 5

class ProtoField(
    val number: Int,
    val wireType: Int,
    var varint: Long = 0,
    var bytes: ByteArray = EMPTY,
    var fixed: ByteArray = EMPTY,
) {
    companion object {
        val EMPTY = ByteArray(0)
    }

    /** Interprets a length-delimited field's payload as UTF-8 text. */
    fun asString(): String = bytes.toString(Charsets.UTF_8)

    /** Parses a length-delimited field's payload as a nested message. */
    fun asMessage(): ProtoMessage = ProtoMessage.parse(bytes)

    fun setString(value: String) {
        bytes = value.toByteArray(Charsets.UTF_8)
    }

    fun setMessage(message: ProtoMessage) {
        bytes = message.toByteArray()
    }
}

class ProtoMessage(val fields: MutableList<ProtoField> = mutableListOf()) {

    fun first(number: Int): ProtoField? = fields.firstOrNull { it.number == number }
    fun all(number: Int): List<ProtoField> = fields.filter { it.number == number }

    fun toByteArray(): ByteArray {
        val out = ByteArrayOutputStream()
        for (f in fields) {
            writeVarint(out, ((f.number.toLong()) shl 3) or f.wireType.toLong())
            when (f.wireType) {
                WIRE_VARINT -> writeVarint(out, f.varint)
                WIRE_LEN -> {
                    writeVarint(out, f.bytes.size.toLong())
                    out.write(f.bytes)
                }
                WIRE_FIXED64, WIRE_FIXED32 -> out.write(f.fixed)
                else -> error("unsupported wire type ${f.wireType}")
            }
        }
        return out.toByteArray()
    }

    companion object {
        fun parse(data: ByteArray): ProtoMessage {
            val msg = ProtoMessage()
            var p = 0
            while (p < data.size) {
                val (tag, tagLen) = readVarint(data, p)
                p += tagLen
                val number = (tag ushr 3).toInt()
                val wireType = (tag and 0x7).toInt()
                when (wireType) {
                    WIRE_VARINT -> {
                        val (v, len) = readVarint(data, p)
                        p += len
                        msg.fields.add(ProtoField(number, wireType, varint = v))
                    }
                    WIRE_LEN -> {
                        val (length, len) = readVarint(data, p)
                        p += len
                        val end = p + length.toInt()
                        msg.fields.add(ProtoField(number, wireType, bytes = data.copyOfRange(p, end)))
                        p = end
                    }
                    WIRE_FIXED64 -> {
                        msg.fields.add(ProtoField(number, wireType, fixed = data.copyOfRange(p, p + 8)))
                        p += 8
                    }
                    WIRE_FIXED32 -> {
                        msg.fields.add(ProtoField(number, wireType, fixed = data.copyOfRange(p, p + 4)))
                        p += 4
                    }
                    else -> error("unsupported wire type $wireType at $p")
                }
            }
            return msg
        }

        private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int> {
            var result = 0L
            var shift = 0
            var p = start
            while (true) {
                val b = data[p].toInt() and 0xff
                result = result or ((b.toLong() and 0x7f) shl shift)
                p++
                if (b and 0x80 == 0) break
                shift += 7
            }
            return result to (p - start)
        }

        private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
            var v = value
            while (true) {
                val b = (v and 0x7f).toInt()
                v = v ushr 7
                if (v == 0L) {
                    out.write(b)
                    break
                } else {
                    out.write(b or 0x80)
                }
            }
        }
    }
}
