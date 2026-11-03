package site.ragdollp.otherdevice.sender

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore に AES-256 鍵をハード封入する。
 * アプリデータの削除 / アンインストールで鍵が消え、暗号化済みファイルは復号不可になる。
 *
 * 書き出しフォーマット:
 *   [1B magic='O'][1B ver=1][12B IV][AES-GCM 暗号文 (末尾16B タグ込み)]
 *
 * Keystore 鍵は export できないので鍵自体はどこへも出せない。
 */
object Crypto {
    private const val ALIAS = "otherdevice_key_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val MAGIC: Byte = 'O'.code.toByte()
    private const val VERSION: Byte = 1

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }

    /**
     * 平文ストリームを AES-GCM で暗号化し、ヘッダ(14B)+暗号文を out に書く。
     * 戻り値は書き出した合計バイト数。
     */
    fun encryptStream(src: InputStream, out: OutputStream): Long {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key)   // IV は Keystore が自動生成
        val iv = cipher.iv
        require(iv.size == IV_LEN) { "unexpected IV length ${iv.size}" }

        out.write(byteArrayOf(MAGIC, VERSION))
        out.write(iv)
        var total = 2L + IV_LEN
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = src.read(buf)
            if (n <= 0) break
            val part = cipher.update(buf, 0, n)
            if (part != null && part.isNotEmpty()) { out.write(part); total += part.size }
        }
        val fin = cipher.doFinal()
        if (fin.isNotEmpty()) { out.write(fin); total += fin.size }
        out.flush()
        return total
    }

    /** 復号は受信側のアプリでは行わない (鍵は送信側 Keystore にある) のでここに書くだけ。 */
    fun decryptStream(src: InputStream, out: OutputStream) {
        val key = getOrCreateKey()
        val header = ByteArray(2 + IV_LEN)
        require(src.read(header) == header.size) { "truncated header" }
        require(header[0] == MAGIC && header[1] == VERSION) { "bad magic/version" }
        val iv = header.copyOfRange(2, 2 + IV_LEN)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = src.read(buf)
            if (n <= 0) break
            val part = cipher.update(buf, 0, n)
            if (part != null && part.isNotEmpty()) out.write(part)
        }
        val fin = cipher.doFinal()
        if (fin.isNotEmpty()) out.write(fin)
        out.flush()
    }
}
