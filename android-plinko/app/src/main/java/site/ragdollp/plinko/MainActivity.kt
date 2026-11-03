package site.ragdollp.plinko

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader

/**
 * ゲーム本体は assets/game/index.html。
 * WebViewAssetLoader で https://appassets.androidplatform.net/ 経由で配信すると
 * ES module の import や fetch が同一オリジン制約で詰まらない。
 */
class MainActivity : AppCompatActivity() {

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wv = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            setBackgroundColor(0xFF101014.toInt())
        }
        // AssetsPathHandler は "URL path prefix を剥いだ残り" を assets から探す。
        // "/game/index.html" → prefix "/" を剥いて "game/index.html" が assets 側のパスとして使われる。
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: android.webkit.WebResourceRequest) =
                loader.shouldInterceptRequest(req.url)
        }
        setContentView(wv)
        wv.loadUrl("https://appassets.androidplatform.net/game/index.html")
    }
}
