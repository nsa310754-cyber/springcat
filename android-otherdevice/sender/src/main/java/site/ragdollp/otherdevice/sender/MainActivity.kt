package site.ragdollp.otherdevice.sender

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread

/**
 * 送信アプリ: 選んだファイルを Keystore 由来の AES-256-GCM で暗号化して
 * 受信アプリ (ポート 8787) に POST /upload で投げる。
 *
 *  リクエスト:
 *    POST /upload HTTP/1.1
 *    Host: <ip>:<port>
 *    X-Sender: <送信元の表示名>  (URL エンコード済)
 *    X-Filename: <元のファイル名>.enc
 *    Content-Length: N
 *    (空行)
 *    [暗号化バイト列]
 */
class MainActivity : AppCompatActivity() {

    private lateinit var nameField: EditText
    private lateinit var hostField: EditText
    private lateinit var portField: EditText
    private lateinit var pickedView: TextView
    private lateinit var logView: TextView
    private var pickedUri: Uri? = null
    private var pickedName: String? = null

    private val pickFile = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            pickedUri = r.data?.data
            pickedName = pickedUri?.let { queryName(it) } ?: "file"
            pickedView.text = "選択: $pickedName"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sp = getSharedPreferences("sender", MODE_PRIVATE)

        val root = ScrollView(this)
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 72, 48, 48)
        }
        root.addView(ll, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        ll.addView(TextView(this).apply { text = "OtherDevice 送信"; textSize = 22f })

        ll.addView(label("自分の表示名 (受信側のフォルダ名)"))
        nameField = edit(sp.getString("name", android.os.Build.MODEL) ?: "device"); ll.addView(nameField)

        ll.addView(label("受信スマホの IP"))
        hostField = edit(sp.getString("host", "192.168.") ?: ""); ll.addView(hostField)

        ll.addView(label("ポート (既定 8787)"))
        portField = edit(sp.getString("port", "8787") ?: "8787").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }; ll.addView(portField)

        pickedView = TextView(this).apply { text = "未選択"; setPadding(0, 24, 0, 8) }
        ll.addView(pickedView)

        ll.addView(button("ファイルを選ぶ") {
            pickFile.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            })
        })
        ll.addView(button("暗号化して送信") { send(sp) })

        logView = TextView(this).apply {
            text = ""; setPadding(0, 24, 0, 0); setTextIsSelectable(true)
        }
        ll.addView(logView)

        ll.addView(TextView(this).apply {
            text = "⚠ 鍵はこの端末の Android Keystore に自動生成されます。\n" +
                   "アプリデータ削除 / アンインストールで鍵が消え、送った暗号化ファイルは復号できなくなります。"
            setPadding(0, 24, 0, 0); setTextColor(0xFFFFCC66.toInt())
        })

        setContentView(root)
    }

    private fun send(sp: android.content.SharedPreferences) {
        val uri = pickedUri ?: return log("ファイル未選択")
        val name = nameField.text.toString().ifBlank { "device" }
        val host = hostField.text.toString().trim()
        val port = portField.text.toString().toIntOrNull() ?: 8787
        if (host.isBlank()) return log("IP 未入力")
        sp.edit().putString("name", name).putString("host", host).putString("port", port.toString()).apply()

        val origName = pickedName ?: "file"
        log("暗号化中 → $host:$port へ送信...")

        thread {
            try {
                // いったんメモリへ暗号化 (Content-Length を事前に出すため)
                val bout = ByteArrayOutputStream()
                contentResolver.openInputStream(uri)!!.use { input ->
                    Crypto.encryptStream(input, bout)
                }
                val body = bout.toByteArray()

                val sock = Socket(host, port)
                sock.soTimeout = 60_000
                val out = DataOutputStream(sock.getOutputStream())
                val req = buildString {
                    append("POST /upload HTTP/1.1\r\n")
                    append("Host: $host:$port\r\n")
                    append("X-Sender: ${name.encodeHeader()}\r\n")
                    append("X-Filename: ${origName.encodeHeader()}.enc\r\n")
                    append("Content-Type: application/octet-stream\r\n")
                    append("Content-Length: ${body.size}\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(req.toByteArray(Charsets.UTF_8))
                out.write(body)
                out.flush()

                val resp = sock.getInputStream().bufferedReader().readLine() ?: ""
                sock.close()
                runOnUiThread { log("完了: $resp  (${body.size} bytes)") }
            } catch (t: Throwable) {
                runOnUiThread { log("エラー: ${t.javaClass.simpleName}: ${t.message}") }
            }
        }
    }

    private fun queryName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return uri.lastPathSegment
    }

    private fun log(s: String) {
        logView.text = (logView.text.toString() + "\n" + s).takeLast(2000)
    }

    private fun label(s: String) = TextView(this).apply {
        text = s; setPadding(0, 20, 0, 4)
    }
    private fun edit(initial: String) = EditText(this).apply { setText(initial) }
    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s; setPadding(24, 24, 24, 24); setOnClickListener { onClick() }
    }

    private fun String.encodeHeader(): String = java.net.URLEncoder.encode(this, "UTF-8")
}
