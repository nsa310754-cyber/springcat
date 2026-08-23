package com.apkbuilder.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayOutputStream

/**
 * Full-screen phone-frame preview of the current game HTML. Renders it in a
 * real WebView (so it looks exactly like the built app), captures runtime /
 * syntax errors via window.onerror to flag broken code, and grabs Play-Store
 * screenshots straight off the WebView.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewScreen(
    html: String,
    onScreenshot: (ByteArray) -> Unit,
    screenshotCount: Int,
    onBack: () -> Unit,
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var jsError by remember { mutableStateOf<String?>(null) }
    var lastCaptureMsg by remember { mutableStateOf<String?>(null) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("プレビュー / スクリーンショット", style = MaterialTheme.typography.titleMedium)
            Text(
                "実際のアプリと同じWebViewで表示します。JavaScriptの構文/実行エラーは下に表示されます。",
                style = MaterialTheme.typography.bodySmall,
            )
            jsError?.let {
                Text("⚠ JSエラー: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            // Phone-shaped preview (9:16), which is also the shape captured for the store.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(9f / 16f),
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            addJavascriptInterface(
                                object {
                                    @JavascriptInterface
                                    fun onError(msg: String) {
                                        post { jsError = msg }
                                    }
                                },
                                "__previewChecker",
                            )
                            webViewRef = this
                            loadDataWithBaseURL(
                                "https://appassets.androidplatform.net/assets/",
                                injectErrorHook(html),
                                "text/html",
                                "utf-8",
                                null,
                            )
                        }
                    },
                )
            }

            lastCaptureMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val wv = webViewRef ?: return@Button
                        val png = captureWebView(wv)
                        if (png != null) {
                            onScreenshot(png)
                            lastCaptureMsg = "撮影しました(合計 ${screenshotCount + 1} 枚)"
                        } else {
                            lastCaptureMsg = "撮影に失敗しました(画面がまだ描画されていない可能性)"
                        }
                    },
                ) { Text("スクリーンショット撮影") }
                OutlinedButton(onClick = {
                    jsError = null
                    webViewRef?.reload()
                }) { Text("再読み込み") }
                OutlinedButton(onClick = onBack) { Text("戻る") }
            }
            Text("撮影済み: ${screenshotCount}枚", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Prepends a window.onerror hook so syntax/runtime errors surface in the preview. */
private fun injectErrorHook(html: String): String {
    val hook = """
        <script>
        window.onerror = function(message, source, lineno, colno, error) {
            try { __previewChecker.onError(message + ' (line ' + (lineno||'?') + ')'); } catch(e) {}
            return false;
        };
        </script>
    """.trimIndent()
    // Insert right after <head> if present, else at the very top.
    val headIdx = html.indexOf("<head", ignoreCase = true)
    if (headIdx >= 0) {
        val close = html.indexOf('>', headIdx)
        if (close >= 0) {
            return html.substring(0, close + 1) + "\n" + hook + html.substring(close + 1)
        }
    }
    return hook + "\n" + html
}

private fun captureWebView(webView: WebView): ByteArray? {
    val width = webView.width
    val height = webView.height
    if (width <= 0 || height <= 0) return null
    return try {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        webView.draw(canvas)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        out.toByteArray()
    } catch (e: Exception) {
        null
    }
}
