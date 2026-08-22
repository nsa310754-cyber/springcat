package com.apkbuilder.core.json

/**
 * A tiny, dependency-free JSON reader. `org.json` is only available on
 * Android at runtime (not on a plain JVM), and pulling in a full JSON
 * library is overkill for reading a `manifest.json` — so this module rolls
 * its own, small enough to stay unit-testable outside Android too.
 */
sealed class JsonValue {
    object Null : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    data class Num(val value: Double) : JsonValue()
    data class Str(val value: String) : JsonValue()
    data class Arr(val items: List<JsonValue>) : JsonValue()
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue()
}

fun JsonValue?.obj(): Map<String, JsonValue> = (this as? JsonValue.Obj)?.fields ?: emptyMap()
fun JsonValue?.arr(): List<JsonValue> = (this as? JsonValue.Arr)?.items ?: emptyList()
fun JsonValue?.str(): String? = (this as? JsonValue.Str)?.value
operator fun JsonValue?.get(key: String): JsonValue? = (this as? JsonValue.Obj)?.fields?.get(key)

object MiniJson {
    fun parse(text: String): JsonValue = Parser(text).let { p ->
        p.skipWs()
        val v = p.parseValue()
        p.skipWs()
        v
    }

    private class Parser(private val s: String) {
        var i = 0

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun parseValue(): JsonValue {
            skipWs()
            return when {
                i >= s.length -> error("unexpected end of JSON")
                s[i] == '{' -> parseObject()
                s[i] == '[' -> parseArray()
                s[i] == '"' -> JsonValue.Str(parseString())
                s.startsWith("true", i) -> { i += 4; JsonValue.Bool(true) }
                s.startsWith("false", i) -> { i += 5; JsonValue.Bool(false) }
                s.startsWith("null", i) -> { i += 4; JsonValue.Null }
                else -> parseNumber()
            }
        }

        fun parseObject(): JsonValue.Obj {
            expect('{')
            val fields = LinkedHashMap<String, JsonValue>()
            skipWs()
            if (peek() == '}') { i++; return JsonValue.Obj(fields) }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                val value = parseValue()
                fields[key] = value
                skipWs()
                when (peek()) {
                    ',' -> { i++; continue }
                    '}' -> { i++; break }
                    else -> error("expected ',' or '}' at $i")
                }
            }
            return JsonValue.Obj(fields)
        }

        fun parseArray(): JsonValue.Arr {
            expect('[')
            val items = ArrayList<JsonValue>()
            skipWs()
            if (peek() == ']') { i++; return JsonValue.Arr(items) }
            while (true) {
                items.add(parseValue())
                skipWs()
                when (peek()) {
                    ',' -> { i++; continue }
                    ']' -> { i++; break }
                    else -> error("expected ',' or ']' at $i")
                }
            }
            return JsonValue.Arr(items)
        }

        fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "unterminated string" }
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        require(i < s.length) { "unterminated escape" }
                        when (val esc = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(i + 4 <= s.length) { "bad unicode escape" }
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> error("bad escape '\\$esc'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun parseNumber(): JsonValue.Num {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            val text = s.substring(start, i)
            require(text.isNotEmpty()) { "invalid number at $start" }
            return JsonValue.Num(text.toDouble())
        }

        fun peek(): Char = if (i < s.length) s[i] else ' '

        fun expect(c: Char) {
            require(i < s.length && s[i] == c) { "expected '$c' at $i" }
            i++
        }
    }
}
