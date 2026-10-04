package site.ragdollp.soundtap

import java.io.InputStream
import java.util.concurrent.TimeUnit

/** su (Magisk / KernelSU 等) 経由でコマンドを実行する。読み取り専用の用途にだけ使う。 */
object RootShell {

    fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** コマンドを実行し (終了コード, 標準出力+エラー) を返す */
    fun run(cmd: String, timeoutSec: Long = 120): Pair<Int, String> {
        return try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) { p.destroy(); return -1 to out }
            p.exitValue() to out
        } catch (e: Exception) {
            -1 to (e.message ?: "su を実行できません")
        }
    }

    /** root 権限を得られるか (初回は Magisk 等の許可ダイアログが出る) */
    fun available(): Boolean = run("id", 30).let { it.first == 0 && it.second.contains("uid=0") }

    /** ファイルを root で読むストリーム。close で cat プロセスも終了する */
    fun open(path: String): InputStream {
        val p = ProcessBuilder("su", "-c", "cat ${quote(path)}").start()
        val src = p.inputStream
        return object : InputStream() {
            override fun read(): Int = src.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = src.read(b, off, len)
            override fun skip(n: Long): Long = src.skip(n)
            override fun available(): Int = src.available()
            override fun close() {
                try { src.close() } catch (_: Exception) {}
                p.destroy()
            }
        }
    }
}
