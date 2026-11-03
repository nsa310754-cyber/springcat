package site.ragdollp.otherdevice.receiver

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val sv = ScrollView(this)
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 72, 48, 48)
        }
        sv.addView(ll)

        ll.addView(TextView(this).apply { text = "OtherDevice 受信"; textSize = 22f })
        status = TextView(this).apply {
            setPadding(0, 24, 0, 24); textSize = 14f; setTextIsSelectable(true)
        }
        ll.addView(status)

        ll.addView(button("ストレージ全アクセスを許可") { requestAllFilesAccess() })
        ll.addView(button("サーバを開始 (ポート 8787)") {
            ServerService.start(this); refresh()
        })
        ll.addView(button("サーバを停止") {
            ServerService.stop(this); refresh()
        })
        ll.addView(button("保存先フォルダを開く") { openFolder() })

        ll.addView(TextView(this).apply {
            text = "保存先: /sdcard/otherdevice/<送信元の名前>/"
            setPadding(0, 24, 0, 0)
        })

        log = TextView(this).apply { setPadding(0, 24, 0, 0); setTextIsSelectable(true) }
        ll.addView(log)

        setContentView(sv)

        ServerService.listener = { msg ->
            runOnUiThread {
                val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                log.text = ("[$ts] $msg\n" + log.text).take(4000)
            }
        }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipInt = wm.connectionInfo.ipAddress
        val ip = if (ipInt == 0) "(Wi-Fi 未接続?)"
                 else "%d.%d.%d.%d".format(
                     ipInt and 0xff, ipInt shr 8 and 0xff,
                     ipInt shr 16 and 0xff, ipInt shr 24 and 0xff)
        val canWrite = canWriteAllFiles()
        status.text = """
            この端末の IP : $ip
            ポート        : 8787
            送信アプリにこの IP:ポートを入れて下さい
            全ファイル書込権限: ${if (canWrite) "✅" else "❌ 未許可"}
            保存先 : ${File(Environment.getExternalStorageDirectory(), "otherdevice").absolutePath}
        """.trimIndent()
    }

    private fun canWriteAllFiles(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else true

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")))
        } else {
            Toast.makeText(this, "既に許可済 or 旧 OS", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFolder() {
        val dir = File(Environment.getExternalStorageDirectory(), "otherdevice").apply { mkdirs() }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse("file://${dir.absolutePath}"), "resource/folder")
            })
        } catch (_: Throwable) {
            Toast.makeText(this, dir.absolutePath, Toast.LENGTH_LONG).show()
        }
    }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s; setPadding(24, 24, 24, 24); setOnClickListener { onClick() }
    }
}
