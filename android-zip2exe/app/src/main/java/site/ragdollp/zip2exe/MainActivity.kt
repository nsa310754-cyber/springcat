package site.ragdollp.zip2exe

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * ZIP ファイルを受け取り、Info-ZIP の `unzipsfx.exe` を頭に連結した
 * 自己解凍形式 (SFX) の Windows PE 実行可能ファイル (.exe) を書き出す。
 *
 *  構造: [unzipsfx.exe 本体 (~100KB)] + [元の ZIP の全バイト]
 *
 * SFX は Windows 上で実行すると自身を開いて ZIP 部分を読み、同階層に展開する。
 * Android/Linux/macOS では走らない(あくまで Windows 用)。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var picked: TextView
    private lateinit var log: TextView
    private var inputUri: Uri? = null
    private var inputName: String? = null

    private val pickZip = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        if (r.resultCode == Activity.RESULT_OK) setInput(r.data?.data)
    }

    private val createExe = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            r.data?.data?.let { outUri -> doConvert(outUri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sv = ScrollView(this)
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 72, 48, 48)
        }
        sv.addView(ll, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT))

        ll.addView(TextView(this).apply { text = "ZIP → SFX EXE 変換"; textSize = 22f })
        ll.addView(TextView(this).apply {
            text = "ZIP ファイルを選ぶと、Windows 上でダブルクリックするだけで同階層に展開される " +
                   "自己解凍 EXE を作成します (Info-ZIP unzipsfx を頭に連結)。"
            setPadding(0, 16, 0, 24)
        })

        picked = TextView(this).apply { text = "未選択" }
        ll.addView(picked)

        ll.addView(button("ZIP を選ぶ") {
            pickZip.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/zip", "application/x-zip-compressed", "application/octet-stream"))
            })
        })
        ll.addView(button("EXE を作って保存") { askSaveLocation() })

        log = TextView(this).apply { setPadding(0, 24, 0, 0); setTextIsSelectable(true) }
        ll.addView(log)

        ll.addView(TextView(this).apply {
            text = "この EXE は Windows 専用です(32bit PE)。\n" +
                   "Windows 上でダブルクリック → UAC/SmartScreen 警告が出ますが実行可。\n" +
                   "暗号化や署名は付いていません。"
            setPadding(0, 32, 0, 0); setTextColor(0xFFFFCC66.toInt())
        })

        setContentView(sv)

        // 共有インテントで受け取った場合
        intent?.takeIf { it.action == Intent.ACTION_SEND }
            ?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            ?.let { setInput(it) }
    }

    private fun setInput(uri: Uri?) {
        inputUri = uri
        inputName = uri?.let { queryName(it) } ?: "archive"
        picked.text = "選択: $inputName"
    }

    private fun askSaveLocation() {
        if (inputUri == null) { toast("ZIP 未選択"); return }
        val base = inputName?.substringBeforeLast('.', inputName!!) ?: "archive"
        createExe.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, "$base.exe")
        })
    }

    private fun doConvert(outUri: Uri) {
        val inUri = inputUri ?: return
        appendLog("変換開始...")
        thread {
            try {
                // 事前チェック: 本当に ZIP か?
                val probe = contentResolver.openInputStream(inUri)!!.use {
                    val b = ByteArray(4); it.read(b); b
                }
                val isZip = probe[0] == 0x50.toByte() && probe[1] == 0x4B.toByte() &&
                    (probe[2] == 0x03.toByte() || probe[2] == 0x05.toByte() || probe[2] == 0x07.toByte())
                if (!isZip) runOnUiThread { appendLog("⚠ PK ヘッダが見つかりません。普通の ZIP ではないかも。続行します。") }

                var total = 0L
                contentResolver.openOutputStream(outUri)!!.use { out ->
                    // 1) stub を書き出す
                    assets.open("unzipsfx.exe").use { stub ->
                        val stubBytes = stub.copyToWithCount(out)
                        total += stubBytes
                        runOnUiThread { appendLog("stub 書き出し: $stubBytes bytes") }
                    }
                    // 2) 元 ZIP をそのまま付加
                    contentResolver.openInputStream(inUri)!!.use { src ->
                        val zipBytes = src.copyToWithCount(out)
                        total += zipBytes
                        runOnUiThread { appendLog("ZIP 付加: $zipBytes bytes") }
                    }
                }
                runOnUiThread { appendLog("✅ 完了: 合計 $total bytes → 保存先に書き出しました") }
            } catch (t: Throwable) {
                runOnUiThread { appendLog("❌ エラー: ${t.javaClass.simpleName}: ${t.message}") }
            }
        }
    }

    private fun InputStream.copyToWithCount(out: OutputStream): Long {
        val buf = ByteArray(64 * 1024); var total = 0L
        while (true) {
            val n = read(buf); if (n <= 0) break
            out.write(buf, 0, n); total += n
        }
        out.flush()
        return total
    }

    private fun queryName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return uri.lastPathSegment
    }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s; setPadding(24, 24, 24, 24); setOnClickListener { onClick() }
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun appendLog(s: String) {
        log.text = (log.text.toString() + "\n" + s).takeLast(4000)
    }
}
