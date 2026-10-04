package site.ragdollp.soundtap

import org.tukaani.xz.LZMAInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * ゲームのデータ (APK / Unity アセットバンドル / 生のファイル) を先頭から流し読みし、
 * 中に埋め込まれた ADOFAI の譜面 JSON ("angleData" / "pathData" を含むもの) を取り出す。
 *
 * - APK (zip) は ZipInputStream で 1 エントリずつ
 * - UnityFS バンドルはブロック情報を読み、LZ4 / LZ4HC / LZMA のブロックを順に展開
 * - それ以外 (SerializedFile, .resS, 素の .adofai 等) はそのまま
 * Unity の TextAsset は [名前長][名前][4byte整列][本文長][本文] の並びなので、
 * 本文の直前を見てアセット名 (例: "AR-X") と正確な長さを取る。取れなければ { } の対応で切り出す。
 */
class UnityScanner(
    private val outDir: File,
    private val onFound: (Found) -> Unit,
    private val log: (String) -> Unit,
) {
    class Found(val assetName: String, val file: File, val size: Int, val source: String)

    @Volatile var cancelled = false
    var bytesScanned = 0L
        private set
    var patternHits = 0
        private set
    private var foundCount = 0

    // ---------------------------------------------------------------- 入口

    /** path のファイルを読む。open は実際のストリームを開く関数 (root の cat など) */
    fun scanFile(path: String, open: () -> InputStream) {
        try {
            open().use { raw -> scanAny(BufferedInputStream(raw, 1 shl 16), path, depth = 0) }
        } catch (e: Exception) {
            log("× $path: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun scanAny(input: BufferedInputStream, name: String, depth: Int) {
        input.mark(16)
        val head = ByteArray(8)
        val n = readFully(input, head, 0, 8)
        input.reset()
        when {
            n >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
                head[2].toInt() == 3 && head[3].toInt() == 4 && depth < 2 -> scanZip(input, name, depth)
            n >= 7 && String(head, 0, 7, Charsets.US_ASCII) == "UnityFS" -> scanBundle(input, name)
            else -> scanRaw(input, name)
        }
    }

    private fun scanZip(input: InputStream, name: String, depth: Int) {
        val zip = ZipInputStream(input)
        while (!cancelled) {
            val e = try { zip.nextEntry } catch (ex: Exception) {
                log("× $name: zip ${ex.message}"); null
            } ?: break
            if (e.isDirectory) continue
            val en = e.name
            // 明らかに関係ないものは飛ばす (画像・コード・ネイティブライブラリ)
            if (en.endsWith(".png") || en.endsWith(".dex") || en.endsWith(".so") ||
                en.startsWith("res/") || en.startsWith("META-INF/") || en.endsWith(".arsc")
            ) continue
            try {
                scanAny(BufferedInputStream(NonClosing(zip), 1 shl 16), "$name!$en", depth + 1)
            } catch (ex: Exception) {
                log("× $name!$en: ${ex.javaClass.simpleName} ${ex.message}")
            }
        }
    }

    private fun scanRaw(input: InputStream, name: String) {
        val finder = Finder(name)
        val buf = ByteArray(1 shl 16)
        while (!cancelled) {
            val r = input.read(buf)
            if (r < 0) break
            finder.feed(buf, 0, r)
        }
        finder.finish()
    }

    // ---------------------------------------------------------------- UnityFS

    private fun scanBundle(input: InputStream, name: String) {
        val cin = Counting(input)
        val d = DataInputStream(cin)
        cstr(d) // "UnityFS"
        val version = d.readInt()
        val unityVer = cstr(d)
        cstr(d) // revision
        d.readLong() // total size
        val compInfo = d.readInt()
        val uncompInfo = d.readInt()
        val flags = d.readInt()
        if (version >= 7) align(cin, 16)
        if (flags and 0x80 != 0) {
            log("! $name: UnityFS v$version ($unityVer) ブロック情報が末尾にある形式は未対応")
            return
        }
        val infoRaw = ByteArray(compInfo)
        d.readFully(infoRaw)
        val info = decompress(infoRaw, uncompInfo, flags and 0x3F, name)
        val bi = DataInputStream(ByteArrayInputStream(info))
        bi.skipBytes(16) // hash
        val blockCount = bi.readInt()
        val uSizes = IntArray(blockCount)
        val cSizes = IntArray(blockCount)
        val bFlags = IntArray(blockCount)
        for (i in 0 until blockCount) {
            uSizes[i] = bi.readInt(); cSizes[i] = bi.readInt(); bFlags[i] = bi.readUnsignedShort()
        }
        val comp = bFlags.map { it and 0x3F }.distinct()
        log("・$name: UnityFS v$version Unity $unityVer ブロック $blockCount 圧縮 $comp")
        if (flags and 0x200 != 0) align(cin, 16)

        val finder = Finder(name)
        for (i in 0 until blockCount) {
            if (cancelled) return
            val c = bFlags[i] and 0x3F
            when (c) {
                0 -> pipe(d, cSizes[i].toLong(), finder)
                1 -> {
                    // LZMA: 5 バイトのプロパティ + 生データ。大きいので流しながら展開
                    val props = d.readUnsignedByte()
                    val dict = Integer.reverseBytes(d.readInt())
                    val lim = Limited(d, cSizes[i].toLong() - 5)
                    val lz = LZMAInputStream(lim, uSizes[i].toLong(), props.toByte(), dict)
                    pipe(lz, uSizes[i].toLong(), finder)
                    lim.drain() // 次のブロックの先頭に合わせる
                }
                2, 3 -> {
                    val src = ByteArray(cSizes[i])
                    d.readFully(src)
                    val dst = ByteArray(uSizes[i])
                    val outLen = lz4Decompress(src, 0, src.size, dst, dst.size)
                    finder.feed(dst, 0, outLen)
                }
                else -> {
                    log("! $name: 未対応の圧縮形式 $c (ブロック $i)")
                    return
                }
            }
        }
        finder.finish()
    }

    private fun decompress(src: ByteArray, outSize: Int, comp: Int, name: String): ByteArray = when (comp) {
        0 -> src
        1 -> {
            val props = src[0]
            val dict = (src[1].toInt() and 0xFF) or ((src[2].toInt() and 0xFF) shl 8) or
                ((src[3].toInt() and 0xFF) shl 16) or ((src[4].toInt() and 0xFF) shl 24)
            val out = ByteArray(outSize)
            val lz = LZMAInputStream(ByteArrayInputStream(src, 5, src.size - 5), outSize.toLong(), props, dict)
            readFully(lz, out, 0, outSize)
            out
        }
        2, 3 -> ByteArray(outSize).also { lz4Decompress(src, 0, src.size, it, outSize) }
        else -> throw IllegalStateException("$name: ブロック情報の圧縮形式 $comp は未対応")
    }

    private fun pipe(input: InputStream, length: Long, finder: Finder) {
        val buf = ByteArray(1 shl 16)
        var left = length
        while (left > 0 && !cancelled) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw EOFException()
            finder.feed(buf, 0, r)
            left -= r
        }
    }

    // ---------------------------------------------------------------- 譜面 JSON の検出

    private inner class Finder(val source: String) {
        private val histSize = 8192
        private val hist = ByteArray(histSize)
        private var pos = 0L // これまでに読んだバイト数

        private var capture: Capture? = null

        private val pat1 = "\"angleData\"".toByteArray()
        private val pat2 = "\"pathData\"".toByteArray()

        private fun at(abs: Long): Int = hist[(abs % histSize).toInt()].toInt() and 0xFF

        fun feed(b: ByteArray, off: Int, len: Int) {
            bytesScanned += len
            for (i in off until off + len) {
                val v = b[i]
                hist[(pos % histSize).toInt()] = v
                pos++
                val c = capture
                if (c != null) {
                    if (!c.add(v)) { complete(c); capture = null }
                    continue
                }
                if (v == '"'.code.toByte() && (endsWith(pat1) || endsWith(pat2))) {
                    patternHits++
                    startCapture()
                }
            }
        }

        fun finish() {
            capture?.let { if (it.exactLeft < 0 && it.depth == 0 && it.len > 0) complete(it) }
            capture = null
        }

        private fun endsWith(p: ByteArray): Boolean {
            if (pos < p.size) return false
            for (k in p.indices) if (at(pos - p.size + k) != (p[k].toInt() and 0xFF)) return false
            return true
        }

        private fun int32le(abs: Long): Long =
            (at(abs).toLong()) or (at(abs + 1).toLong() shl 8) or (at(abs + 2).toLong() shl 16) or (at(abs + 3).toLong() shl 24)

        private fun startCapture() {
            // パターンの直前、空白だけを挟んで '{' があること (譜面ファイル先頭のキー)
            val patLen = if (endsWith(pat1)) pat1.size else pat2.size
            val minPos = maxOf(0L, pos - 256)
            var q = pos - patLen - 1
            while (q >= minPos && at(q).let { it == ' '.code || it == '\n'.code || it == '\r'.code || it == '\t'.code }) q--
            if (q < minPos || at(q) != '{'.code) return
            var jsonStart = q
            // UTF-8 BOM
            if (jsonStart >= minPos + 3 && at(jsonStart - 3) == 0xEF && at(jsonStart - 2) == 0xBB && at(jsonStart - 1) == 0xBF) jsonStart -= 3

            // TextAsset のヘッダ (本文長・名前) を探す
            var exact = -1L
            var assetName = ""
            if (jsonStart - 4 >= maxOf(0L, pos - histSize + 8)) {
                val l = int32le(jsonStart - 4)
                if (l in (pos - jsonStart)..(256L shl 20)) {
                    exact = l
                    assetName = findName(jsonStart - 4)
                }
            }
            val c = Capture(exact)
            for (a in jsonStart until pos) c.add(hist[(a % histSize).toInt()])
            c.assetName = assetName
            capture = c
        }

        /** lenPos は本文長 int の位置。その前の [名前長][名前][0埋め] を探す */
        private fun findName(lenPos: Long): String {
            val low = maxOf(0L, pos - histSize + 8)
            for (pad in 0..3) {
                val nameEnd = lenPos - pad
                var ok = true
                for (z in 0 until pad) if (at(nameEnd + z) != 0) ok = false
                if (!ok) continue
                for (k in 1..200) {
                    val lp = nameEnd - k - 4
                    if (lp < low) break
                    if (int32le(lp) == k.toLong()) {
                        val bytes = ByteArray(k) { hist[((lp + 4 + it) % histSize).toInt()] }
                        if (bytes.all { it.toInt() != 0 && (it.toInt() and 0xFF) >= 0x20 }) {
                            return String(bytes, Charsets.UTF_8)
                        }
                    }
                }
            }
            return ""
        }

        private fun complete(c: Capture) {
            foundCount++
            val f = File(outDir, "found_%04d.adofai".format(foundCount))
            f.outputStream().use { it.write(c.buf, 0, c.len) }
            onFound(Found(c.assetName, f, c.len, source))
        }
    }

    /** 譜面本文を貯める。exactLeft >= 0 なら長さ指定、そうでなければ { } の対応で終端を決める */
    private class Capture(exactLen: Long) {
        var buf = ByteArray(1 shl 16)
        var len = 0
        var exactLeft = exactLen
        var assetName = ""
        var depth = 0
        private var inStr = false
        private var esc = false
        private val limit = 128 shl 20

        /** 1 バイト追加。まだ続くなら true */
        fun add(v: Byte): Boolean {
            if (len == buf.size) {
                if (len >= limit) return false
                buf = buf.copyOf(minOf(buf.size * 2, limit))
            }
            buf[len++] = v
            if (exactLeft >= 0) {
                exactLeft--
                return exactLeft > 0
            }
            val ch = v.toInt().toChar()
            if (inStr) {
                if (esc) esc = false
                else if (ch == '\\') esc = true
                else if (ch == '"') inStr = false
            } else when (ch) {
                '"' -> inStr = true
                '{', '[' -> depth++
                '}', ']' -> { depth--; if (depth <= 0) return false }
            }
            return true
        }
    }

    // ---------------------------------------------------------------- 小物

    private class Counting(input: InputStream) : FilterInputStream(input) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }

    private class Limited(input: InputStream, private var left: Long) : FilterInputStream(input) {
        override fun read(): Int = if (left <= 0) -1 else super.read().also { if (it >= 0) left-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val r = super.read(b, off, minOf(len.toLong(), left).toInt())
            if (r > 0) left -= r
            return r
        }
        override fun close() {}
        fun drain() { val b = ByteArray(8192); while (read(b, 0, b.size) > 0) {} }
    }

    private class NonClosing(input: InputStream) : FilterInputStream(input) {
        override fun close() {}
    }

    private fun align(cin: Counting, a: Int) {
        val pad = ((a - (cin.count % a)) % a).toInt()
        var left = pad
        while (left > 0) { if (cin.read() < 0) throw EOFException(); left-- }
    }

    private fun cstr(d: DataInputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = d.readUnsignedByte()
            if (c == 0) break
            sb.append(c.toChar())
            if (sb.length > 256) break
        }
        return sb.toString()
    }

    companion object {
        fun readFully(input: InputStream, b: ByteArray, off: Int, len: Int): Int {
            var n = 0
            while (n < len) {
                val r = input.read(b, off + n, len - n)
                if (r < 0) break
                n += r
            }
            return n
        }

        /** LZ4 ブロック形式の展開 (Unity の LZ4 / LZ4HC 共通) */
        fun lz4Decompress(src: ByteArray, srcOff: Int, srcLen: Int, dst: ByteArray, dstLen: Int): Int {
            var s = srcOff
            val end = srcOff + srcLen
            var d = 0
            while (s < end) {
                val token = src[s++].toInt() and 0xFF
                var lit = token ushr 4
                if (lit == 15) {
                    while (true) { val b = src[s++].toInt() and 0xFF; lit += b; if (b != 255) break }
                }
                System.arraycopy(src, s, dst, d, lit)
                s += lit; d += lit
                if (s >= end || d >= dstLen) break
                val off = (src[s].toInt() and 0xFF) or ((src[s + 1].toInt() and 0xFF) shl 8)
                s += 2
                var ml = token and 15
                if (ml == 15) {
                    while (true) { val b = src[s++].toInt() and 0xFF; ml += b; if (b != 255) break }
                }
                ml += 4
                var m = d - off
                if (off <= 0 || m < 0) throw IllegalStateException("LZ4 データが壊れています")
                repeat(ml) { dst[d++] = dst[m++] }
            }
            return d
        }
    }
}
