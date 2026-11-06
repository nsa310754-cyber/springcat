package site.ragdollp.rvnc

import android.graphics.Bitmap
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * ごく小さい RFB 3.8 (VNC) クライアント。
 *
 * サポート:
 *  - セキュリティタイプ: None (1) / VNC auth (2, DES パスワード)
 *  - エンコーディング: Raw (0) と CopyRect (1) のみ
 *  - ピクセルフォーマット: サーバの既定 (通常 32bpp, little-endian, true-color)
 *  - 入力: PointerEvent (ボタンマスク), KeyEvent (keysym)
 *
 * TightVNC / TigerVNC / UltraVNC すべての既定設定で動く。
 */
class RfbClient(
    private val host: String,
    private val port: Int,
    private val password: String?,
) {
    lateinit var sock: Socket
        private set
    private lateinit var inp: DataInputStream
    private lateinit var out: DataOutputStream

    var fbWidth: Int = 0; private set
    var fbHeight: Int = 0; private set
    var serverName: String = ""; private set

    // ピクセルフォーマット (サーバ既定を採用)
    private var bitsPerPixel = 32
    private var depth = 24
    private var bigEndian = false
    private var trueColor = true
    private var redMax = 255;   private var redShift = 16
    private var greenMax = 255; private var greenShift = 8
    private var blueMax = 255;  private var blueShift = 0

    fun connect() {
        sock = Socket(host, port)
        sock.tcpNoDelay = true
        sock.soTimeout = 30_000
        inp = DataInputStream(BufferedInputStream(sock.getInputStream()))
        out = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))

        // 1) プロトコルバージョン
        val ver = ByteArray(12); inp.readFully(ver)
        out.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII)); out.flush()

        // 2) セキュリティ
        val n = inp.readUnsignedByte()
        if (n == 0) {
            val reasonLen = inp.readInt()
            val reason = ByteArray(reasonLen).also { inp.readFully(it) }
            throw IOException("server refused: ${String(reason)}")
        }
        val types = ByteArray(n).also { inp.readFully(it) }
        val secType: Int = when {
            types.contains(1.toByte()) -> 1          // None
            types.contains(2.toByte()) -> 2          // VNC auth
            else -> throw IOException("no supported security type (offered: ${types.toList()})")
        }
        out.writeByte(secType); out.flush()
        if (secType == 2) {
            val challenge = ByteArray(16).also { inp.readFully(it) }
            val resp = vncDesEncrypt(challenge, (password ?: "").toByteArray(Charsets.US_ASCII))
            out.write(resp); out.flush()
        }
        val secResult = inp.readInt()
        if (secResult != 0) {
            // RFB 3.8 でも失敗理由が付くことがある
            val reason = try {
                val len = inp.readInt(); String(ByteArray(len).also { inp.readFully(it) })
            } catch (_: Throwable) { "auth failed" }
            throw IOException("auth failed: $reason")
        }

        // 3) ClientInit (shared = 1)
        out.writeByte(1); out.flush()

        // 4) ServerInit
        fbWidth = inp.readUnsignedShort()
        fbHeight = inp.readUnsignedShort()
        bitsPerPixel = inp.readUnsignedByte()
        depth = inp.readUnsignedByte()
        bigEndian = inp.readUnsignedByte() != 0
        trueColor = inp.readUnsignedByte() != 0
        redMax = inp.readUnsignedShort()
        greenMax = inp.readUnsignedShort()
        blueMax = inp.readUnsignedShort()
        redShift = inp.readUnsignedByte()
        greenShift = inp.readUnsignedByte()
        blueShift = inp.readUnsignedByte()
        inp.skipBytes(3)                               // padding
        val nameLen = inp.readInt()
        serverName = String(ByteArray(nameLen).also { inp.readFully(it) }, Charsets.UTF_8)

        if (bitsPerPixel != 32 && bitsPerPixel != 16) {
            // 対応していない bpp は 32bpp に切り替える
            requestPixelFormat32()
        }

        // 5) SetEncodings (Raw と CopyRect を提示)
        out.writeByte(2); out.writeByte(0); out.writeShort(2)
        out.writeInt(0)   // Raw
        out.writeInt(1)   // CopyRect
        out.flush()
    }

    /** サーバに「32bpp, little-endian, 8/8/8 で送って」と要求 */
    private fun requestPixelFormat32() {
        bitsPerPixel = 32; depth = 24; bigEndian = false; trueColor = true
        redMax = 255; greenMax = 255; blueMax = 255
        redShift = 16; greenShift = 8; blueShift = 0
        out.writeByte(0); out.writeByte(0); out.writeShort(0) // padding
        out.writeByte(bitsPerPixel); out.writeByte(depth)
        out.writeByte(0); out.writeByte(1)                   // bigEndian=0, trueColor=1
        out.writeShort(redMax); out.writeShort(greenMax); out.writeShort(blueMax)
        out.writeByte(redShift); out.writeByte(greenShift); out.writeByte(blueShift)
        out.writeByte(0); out.writeByte(0); out.writeByte(0) // padding
        out.flush()
    }

    /** 画面全体の最新状態を要求 (incremental=0) */
    fun requestFull() {
        out.writeByte(3); out.writeByte(0)
        out.writeShort(0); out.writeShort(0)
        out.writeShort(fbWidth); out.writeShort(fbHeight)
        out.flush()
    }

    /** 差分だけ要求 */
    fun requestIncremental() {
        out.writeByte(3); out.writeByte(1)
        out.writeShort(0); out.writeShort(0)
        out.writeShort(fbWidth); out.writeShort(fbHeight)
        out.flush()
    }

    /** サーバからのメッセージを 1 つ処理。FramebufferUpdate なら [bmp] に描画する。 */
    fun readMessage(bmp: Bitmap): Int {
        val type = inp.readUnsignedByte()
        when (type) {
            0 -> {  // FramebufferUpdate
                inp.skipBytes(1)                      // padding
                val rects = inp.readUnsignedShort()
                for (i in 0 until rects) {
                    val x = inp.readUnsignedShort()
                    val y = inp.readUnsignedShort()
                    val w = inp.readUnsignedShort()
                    val h = inp.readUnsignedShort()
                    val enc = inp.readInt()
                    when (enc) {
                        0 -> readRaw(bmp, x, y, w, h)
                        1 -> readCopyRect(bmp, x, y, w, h)
                        else -> throw IOException("unsupported encoding $enc")
                    }
                }
            }
            2 -> { /* Bell */ }
            3 -> { // ServerCutText
                inp.skipBytes(3)
                val len = inp.readInt()
                inp.skipBytes(len)
            }
            else -> throw IOException("unknown server message $type")
        }
        return type
    }

    private val rowBuf = ByteArray(4096 * 4)
    private fun readRaw(bmp: Bitmap, x: Int, y: Int, w: Int, h: Int) {
        val pixels = IntArray(w)
        val bpp = bitsPerPixel / 8
        if (rowBuf.size < w * bpp) throw IOException("row too wide")
        for (row in 0 until h) {
            inp.readFully(rowBuf, 0, w * bpp)
            if (bpp == 4) {
                for (c in 0 until w) {
                    val b0 = rowBuf[c*4].toInt() and 0xff
                    val b1 = rowBuf[c*4 + 1].toInt() and 0xff
                    val b2 = rowBuf[c*4 + 2].toInt() and 0xff
                    val b3 = rowBuf[c*4 + 3].toInt() and 0xff
                    val px = if (bigEndian)
                        (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
                    else
                        (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
                    val r = ((px ushr redShift) and redMax) * 255 / redMax
                    val g = ((px ushr greenShift) and greenMax) * 255 / greenMax
                    val b = ((px ushr blueShift) and blueMax) * 255 / blueMax
                    pixels[c] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            } else {   // bpp == 2 (16bpp)
                for (c in 0 until w) {
                    val lo = rowBuf[c*2].toInt() and 0xff
                    val hi = rowBuf[c*2 + 1].toInt() and 0xff
                    val px = if (bigEndian) (lo shl 8) or hi else (hi shl 8) or lo
                    val r = ((px ushr redShift) and redMax) * 255 / redMax
                    val g = ((px ushr greenShift) and greenMax) * 255 / greenMax
                    val b = ((px ushr blueShift) and blueMax) * 255 / blueMax
                    pixels[c] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            bmp.setPixels(pixels, 0, w, x, y + row, w, 1)
        }
    }

    private fun readCopyRect(bmp: Bitmap, dx: Int, dy: Int, w: Int, h: Int) {
        val sx = inp.readUnsignedShort()
        val sy = inp.readUnsignedShort()
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, sx, sy, w, h)
        bmp.setPixels(px, 0, w, dx, dy, w, h)
    }

    fun sendPointer(x: Int, y: Int, buttonMask: Int) {
        synchronized(out) {
            out.writeByte(5)
            out.writeByte(buttonMask)
            out.writeShort(x.coerceIn(0, fbWidth - 1))
            out.writeShort(y.coerceIn(0, fbHeight - 1))
            out.flush()
        }
    }

    fun sendKey(keysym: Int, down: Boolean) {
        synchronized(out) {
            out.writeByte(4)
            out.writeByte(if (down) 1 else 0)
            out.writeShort(0)
            out.writeInt(keysym)
            out.flush()
        }
    }

    fun close() { try { sock.close() } catch (_: Throwable) {} }

    companion object {
        /** VNC 独自 DES: 鍵の各バイトをビット反転してから DES/ECB する */
        private fun vncDesEncrypt(challenge: ByteArray, pass: ByteArray): ByteArray {
            val key = ByteArray(8)
            for (i in 0 until 8) {
                val b = if (i < pass.size) pass[i].toInt() and 0xff else 0
                // ビット反転 (0b10110000 → 0b00001101)
                var r = 0
                for (bit in 0 until 8) if ((b ushr bit) and 1 != 0) r = r or (1 shl (7 - bit))
                key[i] = r.toByte()
            }
            val cipher = Cipher.getInstance("DES/ECB/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
            return cipher.doFinal(challenge)
        }
    }
}
