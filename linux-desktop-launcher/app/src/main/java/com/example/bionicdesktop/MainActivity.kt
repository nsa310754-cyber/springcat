package com.example.bionicdesktop

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    // SAF: 任意ファイルを選択(拡張子不問)。結果を FileRunner に渡す。
    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) { status.append("\n[file] 選択キャンセル"); return@registerForActivityResult }
        status.append("\n[file] 選択: $uri")
        thread {
            FileRunner.handle(this, uri) { line -> runOnUiThread { status.append("\n[file] $line") } }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.statusText)

        findViewById<Button>(R.id.btnGrantPerms).setOnClickListener {
            PermissionManager.requestRuntime(this)
        }
        findViewById<Button>(R.id.btnSpecialPerms).setOnClickListener {
            PermissionManager.openSpecialAccessSettings(this)
        }
        findViewById<Button>(R.id.btnRoot).setOnClickListener {
            thread {
                val ok = RootShell.isAvailable()
                val grant = if (ok) RootShell.grantSecureSettings(packageName) else "root 取得不可"
                runOnUiThread {
                    Toast.makeText(this, if (ok) "root OK" else "root なし", Toast.LENGTH_SHORT).show()
                    status.append("\n[root] ${if (ok) "取得" else "拒否/非対応"} / $grant")
                }
            }
        }
        findViewById<Button>(R.id.btnAdmin).setOnClickListener {
            // Device Admin はダイアログで有効化(ACTION_ADD_DEVICE_ADMIN がシステム確認画面を出す)。
            // ※ 用途的に必須ではない。深い制御は root を使う。
            val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(
                    android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    AdminReceiver.component(this@MainActivity)
                )
                putExtra(
                    android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "ロック/ワイプ等の管理機能を使う場合のみ有効化してください(任意)。"
                )
            }
            startActivity(intent)
        }
        findViewById<Button>(R.id.btnTerminal).setOnClickListener {
            startActivity(Intent(this, TerminalActivity::class.java))
        }
        findViewById<Button>(R.id.btnBrowser).setOnClickListener {
            startActivity(Intent(this, BrowserActivity::class.java))
        }
        findViewById<Button>(R.id.btnPickRun).setOnClickListener {
            // 拡張子不問。全 MIME を対象に選択させる。
            pickFile.launch(arrayOf("*/*"))
        }
        findViewById<Button>(R.id.btnApps).setOnClickListener {
            startActivity(Intent(this, AppsActivity::class.java))
        }
        findViewById<Button>(R.id.btnSetupGlibc).setOnClickListener {
            // APK 内蔵(assets)から展開 → デモで glibc 版 uname を実行。ネット不要。
            status.append("\n[glibc] 展開開始…")
            thread {
                val ok = GlibcRuntime.install(this) { line ->
                    runOnUiThread { status.append("\n[glibc] $line") }
                }
                if (ok) {
                    val out = GlibcRuntime.demoUname(this)
                    runOnUiThread { status.append("\n[glibc] uname -a →\n$out") }
                }
            }
        }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        status.text = "権限: ${PermissionManager.summary(this)}\n" +
            "glibc: ${if (GlibcRuntime.isInstalled(this)) "導入済み" else "未導入"}  " +
            "targetSdk=28(filesDir 実行可)"
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }
}
