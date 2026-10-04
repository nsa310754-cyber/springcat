package site.ragdollp.soundtap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UnityScannerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val json = ("{\n\t\"angleData\": [0, 0, 90, 999, 270, 0], \n\t\"settings\": { \"bpm\": 150, \"song\": \"Libertas\", \"artist\": \"Zekk\" },\n" +
        "\t\"actions\": [ { \"floor\": 2, \"eventType\": \"Twirl\" } ],\n\t\"decorations\": [ { \"text\": \"{ not a brace }\" } ]\n}")
        .toByteArray()

    /** Unity TextAsset と同じ並び: [名前長][名前][4byte整列][本文長][本文] */
    private fun textAsset(name: String, body: ByteArray): ByteArray {
        val nb = name.toByteArray()
        val pad = (4 - nb.size % 4) % 4
        val bb = ByteBuffer.allocate(4 + nb.size + pad + 4 + body.size + 3).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(nb.size); bb.put(nb); repeat(pad) { bb.put(0) }
        bb.putInt(body.size); bb.put(body)
        return bb.array().copyOf(bb.position())
    }

    private fun junk(n: Int, seed: Int) = ByteArray(n) { ((it * 31 + seed) % 251).toByte() }

    private fun scan(name: String, data: ByteArray): List<UnityScanner.Found> {
        val out = ArrayList<UnityScanner.Found>()
        val sc = UnityScanner(tmp.newFolder(), { out.add(it) }, { println(it) })
        sc.scanFile(name) { data.inputStream() }
        return out
    }

    @Test fun findsTextAssetInRawData() {
        val data = junk(5000, 1) + textAsset("AR-X", json) + junk(3000, 2)
        val f = scan("sharedassets1.assets", data)
        assertEquals(1, f.size)
        assertEquals("AR-X", f[0].assetName)
        assertArrayEquals(json, f[0].file.readBytes())
        val c = AdofaiChart.parse(f[0].file.readText())
        assertEquals("Libertas", c.title)
    }

    @Test fun braceFallbackWithoutHeader() {
        val data = junk(100, 3) + byteArrayOf(0x7F, 0x7F, 0x7F, 0x7F) + json + junk(100, 4)
        val f = scan("raw.bin", data)
        assertEquals(1, f.size)
        assertEquals("", f[0].assetName)
        assertArrayEquals(json, f[0].file.readBytes())
    }

    /** リテラルだけの LZ4 ブロックを作る (圧縮はしないが形式は正しい) */
    private fun lz4Literal(src: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        val n = src.size
        if (n < 15) o.write(n shl 4) else {
            o.write(0xF0); var r = n - 15
            while (r >= 255) { o.write(255); r -= 255 }
            o.write(r)
        }
        o.write(src)
        return o.toByteArray()
    }

    @Test fun lz4WithMatch() {
        // "abc" + 後方参照 (offset 3, 長さ 6) = "abcabcabc"
        val src = byteArrayOf(0x32, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 3, 0)
        val dst = ByteArray(9)
        assertEquals(9, UnityScanner.lz4Decompress(src, 0, src.size, dst, 9))
        assertEquals("abcabcabc", String(dst))
    }

    private fun unityFsBundle(payload: ByteArray, blockSize: Int): ByteArray {
        val blocks = payload.toList().chunked(blockSize).map { it.toByteArray() }
        val comp = blocks.map { lz4Literal(it) }
        val info = ByteArrayOutputStream().also { bo ->
            val d = DataOutputStream(bo)
            d.write(ByteArray(16))
            d.writeInt(blocks.size)
            blocks.forEachIndexed { i, b -> d.writeInt(b.size); d.writeInt(comp[i].size); d.writeShort(2) }
            d.writeInt(1)
            d.writeLong(0); d.writeLong(payload.size.toLong()); d.writeInt(4); d.write("CAB-test\u0000".toByteArray())
        }.toByteArray()
        val o = ByteArrayOutputStream()
        val d = DataOutputStream(o)
        d.write("UnityFS\u0000".toByteArray())
        d.writeInt(7)
        d.write("5.x.x\u0000".toByteArray())
        d.write("2021.3.0f1\u0000".toByteArray())
        d.writeLong(0)
        d.writeInt(info.size); d.writeInt(info.size); d.writeInt(0x40 or 0x200) // 情報は無圧縮
        while (o.size() % 16 != 0) d.write(0)
        d.write(info)
        while (o.size() % 16 != 0) d.write(0)
        comp.forEach { d.write(it) }
        return o.toByteArray()
    }

    @Test fun findsInsideLz4BundleAcrossBlocks() {
        val payload = junk(7000, 5) + textAsset("AR-X", json) + junk(4000, 6)
        // ブロックを小さくして、譜面がブロックの境目をまたぐようにする
        val f = scan("data.unity3d", unityFsBundle(payload, 97))
        assertEquals(1, f.size)
        assertEquals("AR-X", f[0].assetName)
        assertArrayEquals(json, f[0].file.readBytes())
    }

    /** Unity 形式の LZMA (5 バイトのプロパティ + 生データ。.lzma ヘッダの 8 バイトのサイズは無し) */
    private fun unityLzma(src: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        org.tukaani.xz.LZMAOutputStream(o, org.tukaani.xz.LZMA2Options(), src.size.toLong()).use { it.write(src) }
        val a = o.toByteArray()
        return a.copyOfRange(0, 5) + a.copyOfRange(13, a.size)
    }

    @Test fun findsInsideLzmaBundle() {
        val payload = junk(9000, 9) + textAsset("AR-X", json) + junk(2000, 10)
        val blocks = listOf(payload.copyOfRange(0, 6000), payload.copyOfRange(6000, payload.size))
        val comp = blocks.map { unityLzma(it) }
        val info = ByteArrayOutputStream().also { bo ->
            val d = DataOutputStream(bo)
            d.write(ByteArray(16)); d.writeInt(blocks.size)
            blocks.forEachIndexed { i, b -> d.writeInt(b.size); d.writeInt(comp[i].size); d.writeShort(1) }
            d.writeInt(0)
        }.toByteArray()
        val o = ByteArrayOutputStream()
        val d = DataOutputStream(o)
        d.write("UnityFS\u0000".toByteArray()); d.writeInt(6)
        d.write("5.x.x\u0000".toByteArray()); d.write("2019.4.0f1\u0000".toByteArray())
        d.writeLong(0); d.writeInt(info.size); d.writeInt(info.size); d.writeInt(0)
        d.write(info); comp.forEach { d.write(it) }
        val f = scan("level.bundle", o.toByteArray())
        assertEquals(1, f.size)
        assertEquals("AR-X", f[0].assetName)
        assertArrayEquals(json, f[0].file.readBytes())
    }

    @Test fun findsInsideApkZip() {
        val bundle = unityFsBundle(junk(3000, 7) + textAsset("1-X", json) + junk(500, 8), 1024)
        val zo = ByteArrayOutputStream()
        ZipOutputStream(zo).use { z ->
            z.putNextEntry(ZipEntry("assets/bin/Data/data.unity3d")); z.write(bundle); z.closeEntry()
            z.putNextEntry(ZipEntry("assets/other.txt")); z.write("hello".toByteArray()); z.closeEntry()
        }
        val f = scan("split_UnityDataAssetPack.apk", zo.toByteArray())
        assertEquals(1, f.size)
        assertEquals("1-X", f[0].assetName)
    }

    @Test fun brokenLengthDoesNotSwallowFollowingLevels() {
        // 1 つ目の長さヘッダが壊れて (実際より大きく) いても、後ろの譜面を飲み込まない
        val bogus = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(50 shl 20).array()
        val data = junk(300, 11) + bogus + json + junk(500, 12) +
            textAsset("2-X", json) + junk(700, 13) + textAsset("AR-X", json) + junk(100, 14)
        val f = scan("resources.assets", data)
        assertEquals(listOf("", "2-X", "AR-X"), f.map { it.assetName })
        f.forEach { AdofaiChart.parse(it.file.readText()) } // どれも譜面として読める
    }

    @Test fun findsLevelWhoseFirstKeyIsNotAngleData() {
        val other = ("{\n\t\"settings\": { \"bpm\": 120, \"song\": \"First Settings\" },\n" +
            "\t\"angleData\": [0, 0, 0],\n\t\"actions\": []\n}").toByteArray()
        val data = junk(2000, 15) + textAsset("5-X", other) + junk(2000, 16)
        val f = scan("sharedassets2.assets", data)
        assertEquals(1, f.size)
        assertEquals("5-X", f[0].assetName)
        assertArrayEquals(other, f[0].file.readBytes())
    }
}
