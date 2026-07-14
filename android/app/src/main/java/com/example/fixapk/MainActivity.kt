package com.example.fixapk

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private lateinit var installButton: Button
    private var lastOutput: File? = null

    private val pickApk =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) convert(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        logView = findViewById(R.id.logView)
        installButton = findViewById(R.id.installButton)

        findViewById<Button>(R.id.pickButton).setOnClickListener {
            lastOutput = null
            installButton.isEnabled = false
            logView.text = ""
            pickApk.launch(arrayOf("*/*"))
        }
        installButton.setOnClickListener { lastOutput?.let { install(it) } }
    }

    private fun log(msg: String) = runOnUiThread {
        logView.append(msg + "\n")
    }

    private fun convert(uri: Uri) {
        log("読み込み中...")
        thread {
            try {
                val input = File(cacheDir, "input.apk")
                contentResolver.openInputStream(uri)?.use { ins ->
                    input.outputStream().use { ins.copyTo(it) }
                } ?: throw IllegalStateException("ファイルを開けませんでした")

                val outDir = File(filesDir, "output").apply { mkdirs() }
                val output = File(outDir, "fixed-${System.currentTimeMillis()}.apk")

                val result = ApkFixer.fix(this, input, output)
                input.delete()

                log("── 変換完了 ──")
                log("minSdkVersion    : ${result.oldMinSdk ?: "(未指定)"}")
                log("targetSdkVersion : ${result.oldTargetSdk ?: "(未指定)"} -> ${result.newTargetSdk}")
                log(result.note)
                if (result.strippedSignatures.isNotEmpty()) {
                    log("旧署名を除去: ${result.strippedSignatures.joinToString(", ")}")
                }
                log("署名: v1+v2+v3 で再署名しました")
                log("出力: ${output.name}")
                log("\n✅ 「インストール」を押してください。")

                lastOutput = output
                runOnUiThread { installButton.isEnabled = true }
            } catch (e: Exception) {
                log("失敗: ${e.message}")
            }
        }
    }

    private fun install(apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            AlertDialog.Builder(this)
                .setTitle("インストール許可が必要です")
                .setMessage("このアプリに「提供元不明のアプリのインストール」を許可してください。")
                .setPositiveButton("設定を開く") { _, _ ->
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
                .setNegativeButton("キャンセル", null)
                .show()
            return
        }

        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }
}
