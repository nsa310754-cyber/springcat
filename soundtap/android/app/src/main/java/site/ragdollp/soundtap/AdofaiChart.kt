package site.ragdollp.soundtap

import kotlin.math.max

/**
 * A Dance of Fire and Ice の譜面ファイル (.adofai) を読み、各タイルを叩く時刻を計算する。
 *
 * タイミング計算は ADOFAI-JS / ADOCAO などコミュニティ実装と同じ方式:
 * - タイル i から i+1 への移動角 (相対角) は、入ってきた方向 angleDir と次の向き angleData[i] の差。
 *   通常は時計回り、Twirl で反転。0° は 360° (一周) 扱い。999 はミッドスピン (移動 0)。
 * - 555/666/777/888 (pathData の 5/6/7/8) は直前の向きからの相対角 ±72° / ±52°。
 * - 移動拍数 = 相対角 / 180 + Pause の拍数 + Hold の回転数 × 2。秒 = 拍数 × 60 / BPM。
 * - SetSpeed (Bpm / Multiplier) はそのタイルから BPM を変更。MultiPlanet (3つ) は相対角 −60°。
 * - 1 枚目のタイル (floor 1) を叩く時刻を 0 とし、レベル開始からは max(offset, 2拍) 後。
 */
class AdofaiChart(
    val title: String,
    val artist: String,
    val bpm: Double,
    val offsetMs: Double,
    val pitch: Double,
    /** floor 1 を 0 とした各タップ時刻 (秒, 曲の再生速度 100% 基準) */
    val times: DoubleArray,
    /** ホールドなら離す時刻 (秒)、通常タップは負値 */
    val releases: DoubleArray,
    /** 何枚目のタイルか (表示用) */
    val floors: IntArray,
    val tileCount: Int,
    /** レベル開始 (最初のタップ) から floor 1 までの秒数 (100% 基準) */
    val leadSec: Double,
    val warnings: List<String>,
) {
    val durationSec get() = if (times.isEmpty()) 0.0 else times.last()

    companion object {
        private val PATH_TABLE = mapOf(
            'R' to 0.0, 'p' to 15.0, 'J' to 30.0, 'E' to 45.0, 'T' to 60.0, 'o' to 75.0,
            'U' to 90.0, 'q' to 105.0, 'G' to 120.0, 'Q' to 135.0, 'H' to 150.0, 'W' to 165.0,
            'L' to 180.0, 'x' to 195.0, 'N' to 210.0, 'Z' to 225.0, 'F' to 240.0, 'V' to 255.0,
            'D' to 270.0, 'Y' to 285.0, 'B' to 300.0, 'C' to 315.0, 'M' to 330.0, 'A' to 345.0,
            '!' to 999.0,
        )
        private val PATH_OFFSET = mapOf(
            '5' to 72.0, '6' to -72.0, '7' to 52.0, '8' to -52.0, '9' to -30.0,
            'h' to 120.0, 'j' to -120.0, 't' to 60.0, 'y' to 300.0,
        )

        private fun norm(v: Double): Double = ((v % 360.0) + 360.0) % 360.0

        private fun num(v: Any?, def: Double): Double = when (v) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull() ?: def
            else -> def
        }

        private fun truthy(v: Any?): Boolean = when (v) {
            is Boolean -> v
            is String -> v.equals("Enabled", true) || v.equals("true", true)
            is Number -> v.toInt() != 0
            else -> false
        }

        /** 譜面に含まれるリッチテキストタグ (<color=...> など) を除く */
        private fun plain(s: Any?): String = (s as? String ?: "").replace(Regex("<[^>]*>"), "").trim()

        fun parse(text: String): AdofaiChart {
            val root = LenientJson.parse(text) as? Map<*, *>
                ?: throw IllegalArgumentException("譜面ファイルの形式が正しくありません")
            val settings = root["settings"] as? Map<*, *> ?: emptyMap<String, Any>()
            val warnings = mutableListOf<String>()

            val angles: DoubleArray = when {
                root["angleData"] is List<*> ->
                    (root["angleData"] as List<*>).map { num(it, 0.0) }.toDoubleArray()
                root["pathData"] is String -> {
                    val path = root["pathData"] as String
                    var prev = 0.0
                    DoubleArray(path.length) { i ->
                        val c = path[i]
                        val a = PATH_TABLE[c]
                        val off = PATH_OFFSET[c]
                        val v = when {
                            a != null -> a
                            off != null -> prev + off
                            else -> prev
                        }
                        if (v != 999.0) prev = v
                        v
                    }
                }
                else -> throw IllegalArgumentException("angleData / pathData が見つかりません")
            }
            val n = angles.size
            if (n == 0) throw IllegalArgumentException("タイルがありません")

            val bpm0 = num(settings["bpm"], 100.0).takeIf { it > 0 } ?: 100.0
            val offset = num(settings["offset"], 0.0)
            val pitch = num(settings["pitch"], 100.0).takeIf { it > 0 } ?: 100.0

            // floor ごとのイベント
            val byFloor = HashMap<Int, MutableList<Map<*, *>>>()
            (root["actions"] as? List<*>)?.forEach { a ->
                val m = a as? Map<*, *> ?: return@forEach
                val f = num(m["floor"], -1.0).toInt()
                if (f in 0..n) byFloor.getOrPut(f) { mutableListOf() }.add(m)
            }

            val dur = DoubleArray(n)          // floor i から i+1 までの秒数
            val hold = BooleanArray(n + 1)
            val auto = BooleanArray(n + 1)
            var twirl = false
            var bpm = bpm0
            var three = false
            var autoplay = false
            var angleDir = 180.0
            var freeRoam = false

            for (i in 0 until n) {
                var pauseBeats = 0.0
                var holdRot = 0.0
                byFloor[i]?.forEach { ev ->
                    when (ev["eventType"]) {
                        "Twirl" -> twirl = !twirl
                        "SetSpeed" -> {
                            if (ev["speedType"] == "Multiplier") bpm *= num(ev["bpmMultiplier"], 1.0)
                            else bpm = num(ev["beatsPerMinute"], bpm)
                        }
                        "Pause" -> pauseBeats += num(ev["duration"], 0.0)
                        "Hold" -> {
                            val d = num(ev["duration"], 0.0)
                            holdRot += d
                            if (d > 0) hold[i] = true
                        }
                        "MultiPlanet" -> three = ev["planets"] == "ThreePlanets"
                        "AutoPlayTiles" -> autoplay = truthy(ev["enabled"])
                        "FreeRoam" -> freeRoam = true
                    }
                }
                auto[i] = autoplay

                val a = angles[i]
                var rel: Double
                val relOffset = when (a) { 555.0 -> 72.0; 666.0 -> -72.0; 777.0 -> 52.0; 888.0 -> -52.0; else -> null }
                if (relOffset != null) {
                    val actual = norm(norm(angleDir - 180) + relOffset)
                    val delta = norm(angleDir - actual)
                    rel = if (!twirl) delta else norm(360 - delta)
                    if (rel == 0.0) rel = 360.0
                    angleDir = norm(actual + 180)
                } else if (a == 999.0) {
                    var minus = 1
                    while (i - minus >= 0 && angles[i - minus] == 999.0) minus++
                    val real = if (i - minus >= 0) angles[i - minus] else 0.0
                    angleDir = norm(real + (minus - 1) * 180)
                    rel = 0.0
                } else {
                    val delta = norm(angleDir - a)
                    rel = if (!twirl) delta else norm(360 - delta)
                    if (rel < 1e-6) rel = 360.0
                    angleDir = norm(a + 180)
                }
                val nextIsMidspin = i + 1 < n && angles[i + 1] == 999.0
                if (three && rel != 0.0 && !nextIsMidspin) rel = if (rel > 60) rel - 60 else rel + 300

                val beats = rel / 180.0 + pauseBeats + holdRot * 2.0
                dur[i] = beats * 60.0 / bpm
            }
            byFloor[n]?.forEach { if (it["eventType"] == "AutoPlayTiles") autoplay = truthy(it["enabled"]) }
            auto[n] = autoplay
            if (freeRoam) warnings.add("フリーローム (自由移動) 区間はタイミングが正しく計算できません")

            // floor 1..n の到達時刻
            val hitTime = DoubleArray(n + 1)
            for (f in 2..n) hitTime[f] = hitTime[f - 1] + dur[f - 1]

            val times = ArrayList<Double>()
            val rel = ArrayList<Double>()
            val fl = ArrayList<Int>()
            var skipNext = false
            var autoCount = 0
            for (f in 1..n) {
                if (skipNext) { skipNext = false; continue }
                if (auto[f]) { autoCount++; continue }
                val t = hitTime[f]
                // ミッドスピンなど同時刻の重複は 1 回のタップにまとめる
                if (times.isNotEmpty() && t - times.last() < 0.0005) continue
                times.add(t)
                fl.add(f)
                if (hold[f] && f + 1 <= n) {
                    rel.add(hitTime[f + 1])
                    skipNext = true // ホールドの終点は離すだけ
                } else {
                    rel.add(-1.0)
                }
            }
            if (autoCount > 0) warnings.add("自動プレイ区間の $autoCount タイルはタップしません")
            if (hold.any { it }) warnings.add("ホールドは長押しで再現します")

            val lead = max(offset / 1000.0, 2 * 60.0 / bpm0)
            return AdofaiChart(
                title = plain(settings["song"]).ifEmpty { "(曲名なし)" },
                artist = plain(settings["artist"]),
                bpm = bpm0,
                offsetMs = offset,
                pitch = pitch,
                times = times.toDoubleArray(),
                releases = rel.toDoubleArray(),
                floors = fl.toIntArray(),
                tileCount = n,
                leadSec = lead,
                warnings = warnings,
            )
        }
    }
}

/**
 * .adofai 用の寛容な JSON パーサ。
 * 実際の譜面ファイルには BOM・末尾カンマ・要素間のカンマ抜け・// コメントがあり、
 * 標準の JSON パーサでは読めないことが多いため自前で読む。
 */
object LenientJson {
    fun parse(text: String): Any? = P(text.removePrefix("﻿")).run { skip(); value() }

    private class P(val s: String) {
        var i = 0

        fun skip() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == ',' || c == ' ') { i++; continue }
                if (c == '/' && i + 1 < s.length && s[i + 1] == '/') {
                    while (i < s.length && s[i] != '\n') i++; continue
                }
                if (c == '/' && i + 1 < s.length && s[i + 1] == '*') {
                    val end = s.indexOf("*/", i + 2)
                    i = if (end < 0) s.length else end + 2; continue
                }
                break
            }
        }

        fun value(): Any? {
            if (i >= s.length) throw IllegalArgumentException("ファイルの途中で終わっています")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> if (c == '-' || c == '+' || c == '.' || c.isDigit()) number()
                else throw IllegalArgumentException("読めない文字 '$c' (位置 $i)")
            }
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++ // {
            while (true) {
                skip()
                if (i >= s.length) break
                if (s[i] == '}') { i++; break }
                val k = if (s[i] == '"') str() else bareKey()
                skip()
                if (i < s.length && s[i] == ':') i++
                skip()
                m[k] = value()
            }
            return m
        }

        fun bareKey(): String {
            val st = i
            while (i < s.length && s[i] != ':' && !s[i].isWhitespace()) i++
            return s.substring(st, i)
        }

        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++ // [
            while (true) {
                skip()
                if (i >= s.length) break
                if (s[i] == ']') { i++; break }
                l.add(value())
            }
            return l
        }

        fun str(): String {
            val sb = StringBuilder()
            i++ // "
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) break
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> {
                                val h = s.substring(i, minOf(i + 4, s.length))
                                h.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        fun number(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(st, i).toDoubleOrNull() ?: 0.0
        }
    }
}
