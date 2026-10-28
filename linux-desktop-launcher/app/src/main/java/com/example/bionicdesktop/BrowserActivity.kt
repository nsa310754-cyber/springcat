package com.example.bionicdesktop

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * ダウンロード対応ブラウザ。
 * ・Android Studio / .crx(Chrome拡張ファイル)/ バイナリ等を DownloadManager で取得できる。
 * ・取得先は Download フォルダ。そこから Terminal / glibc ランナーへ渡して実行する流れ。
 *   ※ .crx は「取得」まで。拡張を動かすには拡張対応の Chromium 本体(別途)が必要。
 */
class BrowserActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)

        val web = findViewById<WebView>(R.id.web)
        val urlBar = findViewById<EditText>(R.id.url)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.webViewClient = WebViewClient()

        // ダウンロードは OS の DownloadManager に委譲(通知付き・バックグラウンド継続)。
        web.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            try {
                val name = URLUtilName(url, contentDisposition)
                val req = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimeType)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                    allowScanningByMediaScanner()
                }
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Toast.makeText(this, "DL 開始: $name", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "DL 失敗: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        val go = {
            var u = urlBar.text.toString().trim()
            if (u.isNotEmpty()) {
                if (!u.startsWith("http")) u = "https://$u"
                web.loadUrl(u)
            }
        }
        findViewById<Button>(R.id.go).setOnClickListener { go() }
        urlBar.setOnEditorActionListener { _, _, _ -> go(); true }

        urlBar.setText("https://developer.android.com/studio")
        web.loadUrl("https://developer.android.com/studio")
    }

    override fun onBackPressed() {
        val web = findViewById<WebView>(R.id.web)
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    private fun URLUtilName(url: String, disposition: String?): String {
        android.webkit.URLUtil.guessFileName(url, disposition, null)?.let { return it }
        return url.substringAfterLast('/').ifEmpty { "download.bin" }
    }
}
