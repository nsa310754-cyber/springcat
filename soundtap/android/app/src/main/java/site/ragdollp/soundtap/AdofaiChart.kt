package site.ragdollp.soundtap

import kotlin.math.max

/**
 * A Dance of Fire and Ice の譜面ファイル (.adofai) を読み、各タイルを叩く時刻を計算する。
 *
 * タイミング計算はコミュニティ実装 (ADOFAI-JS / ADOCAO / ADOFAI-Map-Converter) と同じ方式:
 * - 相対角 = 前のタイルの向き − 次の向き + 360/惑星数 (通常 2 個 → +180°)。Twirl で符号反転。0° は 360°。
 * - 999 はミッドスピン (移動 0、同時刻なので 1 タップにまとめる)。555/666/777/888 は直前から ±72° / ±52°。
 * - 移動拍数 = 相対角 / 180 + Pause 拍 (一周タイルでは 1 拍引く) + Hold 回転数 × 2 + FreeRoam 拍。
 *   秒 = 拍数 × 60 / BPM。SetSpeed (Bpm / Multiplier) はそのタイルから。
 * - MultiPlanet は惑星数 N (2〜) に対応。AutoPlayTiles の区間は叩かない。
 * - ホールドは連続分をまとめて 1 回の長押しにし、最初の「ホールドでないタイル」で離す。
 * - Multitap は以降のタイルで必要な指の本数として記録 (押し方はアプリ設定次第)。
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
    /** マルチタップで必要な指の本数 (通常 1) */
    val fingers: IntArray,
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

            // 555/666/777/888 (直前の向きからの相対角) を絶対角に直す
            val dir = DoubleArray(n)
            var lastReal = 0.0
            for (i in 0 until n) {
                val a = angles[i]
                val rel = when (a) { 555.0 -> 72.0; 666.0 -> -72.0; 777.0 -> 52.0; 888.0 -> -52.0; else -> null }
                dir[i] = if (rel != null) norm(lastReal + rel) else a
                if (dir[i] != 999.0) lastReal = dir[i]
            }

            val dur = DoubleArray(n)          // floor i から i+1 までの秒数
            val hold = BooleanArray(n + 1)
            val auto = BooleanArray(n + 1)
            val fingers = IntArray(n + 1) { 1 }
            var reversed = false
            var bpm = bpm0
            var planets = 2
            var autoplay = false
            var taps = 1
            var staticPrev = 0.0
            var freeRoam = false
            var multitap = false
            val unknownPlanets = HashSet<String>()

            for (i in 0 until n) {
                var pauseBeats = 0.0
                var holdRot = 0.0
                var roamBeats = 0.0
                byFloor[i]?.forEach { ev ->
                    when (ev["eventType"]) {
                        "Twirl" -> reversed = !reversed
                        "SetSpeed" -> {
                            if (ev["speedType"] == "Multiplier") bpm *= num(ev["bpmMultiplier"], 1.0)
                            else bpm = num(ev["beatsPerMinute"], bpm)
                        }
                        "Pause" -> pauseBeats += num(ev["duration"], 0.0)
                        "Hold" -> {
                            val d = num(ev["duration"], 0.0)
                            holdRot += d
                            hold[i] = true
                        }
                        "MultiPlanet" -> {
                            planets = when (val p = ev["planets"]) {
                                "TwoPlanets" -> 2
                                "ThreePlanets" -> 3
                                is Number -> p.toInt().coerceAtLeast(2)
                                is String -> p.filter { it.isDigit() }.toIntOrNull()?.coerceAtLeast(2)
                                    ?: when {
                                        p.startsWith("Four", true) -> 4
                                        p.startsWith("Five", true) -> 5
                                        p.startsWith("Six", true) -> 6
                                        else -> { unknownPlanets.add(p); planets }
                                    }
                                else -> planets
                            }
                        }
                        "AutoPlayTiles" -> autoplay = truthy(ev["enabled"])
                        "FreeRoam" -> { roamBeats += num(ev["duration"], 0.0); freeRoam = true }
                        "Multitap" -> {
                            taps = num(ev["taps"] ?: ev["tapCount"] ?: ev["count"], 2.0).toInt().coerceIn(1, 10)
                            if (taps > 1) multitap = true
                        }
                    }
                }
                auto[i] = autoplay
                fingers[i] = taps

                // 相対角 (ADOFAI-Map-Converter の AngleHelper と同じ式。惑星 N 個なら 360/N を足す)
                val planetAngle = 360.0 / planets
                val curr = if (i == 0) 0.0 else dir[i - 1]
                val next = dir[i]
                val currMid = i > 0 && curr == 999.0
                var currStatic = if (currMid) staticPrev else curr
                val travel: Double
                if (next == 999.0) {
                    travel = 0.0
                    if (currMid) currStatic = norm(currStatic + planetAngle)
                } else {
                    var t = currStatic - next
                    if (reversed) t = -t
                    if (!currMid) t += planetAngle
                    t = norm(t)
                    travel = if (t < 1e-6) 360.0 else t
                }
                staticPrev = currStatic

                // 一時停止: 一周 (360°) のタイルに 1 拍の Pause は効かないゲーム側の仕様に合わせて 1 拍引く
                val pauseDeg = if (pauseBeats > 0) {
                    if (kotlin.math.abs(travel - 360.0) < 1e-6) 180.0 * max(pauseBeats - 1, 0.0) else 180.0 * pauseBeats
                } else 0.0
                val extra = pauseDeg + 360.0 * holdRot + 180.0 * roamBeats
                dur[i] = (travel + extra) / 180.0 * 60.0 / bpm
            }
            byFloor[n]?.forEach { if (it["eventType"] == "AutoPlayTiles") autoplay = truthy(it["enabled"]) }
            auto[n] = autoplay
            fingers[n] = taps
            if (freeRoam) warnings.add("フリーローム区間があります。ゲームの設定で「フリーロームを必須にしない」にしてください (その区間はタップしません)")
            if (multitap) warnings.add("マルチタップ (複数本指) のタイルがあります。ゲームの設定で必須にしないか、アプリの「マルチタップを指の本数で押す」をオンに")
            if (unknownPlanets.isNotEmpty()) warnings.add("未対応の惑星数指定: $unknownPlanets")

            // floor 1..n の到達時刻
            val hitTime = DoubleArray(n + 1)
            for (f in 2..n) hitTime[f] = hitTime[f - 1] + dur[f - 1]

            val times = ArrayList<Double>()
            val rel = ArrayList<Double>()
            val fl = ArrayList<Int>()
            val fing = ArrayList<Int>()
            var autoCount = 0
            var f = 1
            while (f <= n) {
                if (auto[f]) { autoCount++; f++; continue }
                val t = hitTime[f]
                // ミッドスピンなど同時刻の重複は 1 回のタップにまとめる
                if (times.isNotEmpty() && t - times.last() < 0.0005) { f++; continue }
                times.add(t)
                fl.add(f)
                fing.add(fingers[f])
                if (hold[f]) {
                    // ホールド: 連続するホールドは 1 回の長押し。最初の「ホールドでないタイル」で離す (そこは押さない)
                    var end = f + 1
                    while (end <= n && hold[end]) end++
                    rel.add(hitTime[minOf(end, n)])
                    f = end + 1
                } else {
                    rel.add(-1.0)
                    f++
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
                fingers = fing.toIntArray(),
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
