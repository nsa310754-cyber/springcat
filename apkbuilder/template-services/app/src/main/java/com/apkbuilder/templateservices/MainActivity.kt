package com.apkbuilder.templateservices

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.analytics.FirebaseAnalytics
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Generic offline WebView wrapper — the variant with Firebase Analytics and
 * AdMob bundled (see ../../../template for the lean variant with neither).
 * Both are inert with no visible or behavioral difference unless the builder
 * writes assets/firebase-config.json and/or assets/admob-config.json, so
 * generated apps that don't use these services aren't larger or don't
 * declare their permissions for nothing.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader

    companion object {
        private const val APP_ORIGIN = "https://appassets.androidplatform.net"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goImmersive()

        initFirebaseIfConfigured()

        webView = WebView(this)
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                try {
                    val url = request.url.toString()
                    // The game entry point may be shipped encrypted (assets/game.enc) —
                    // decrypt it in memory and serve it in place of assets/game.html.
                    if (url.endsWith("/assets/game.html") && hasEncryptedGame()) {
                        val html = decryptGameHtml()
                        if (html != null) {
                            return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html))
                        }
                    }
                    assetLoader.shouldInterceptRequest(request.url)?.let { return it }
                } catch (_: Exception) {
                }
                return null
            }
        }
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(Bridge(), "AndroidBridge")

        setContentView(buildRootView())

        // A virtual https:// origin (rather than file://) so relative asset
        // references resolve the same way whether the entry HTML came from
        // the asset loader directly or was decrypted in memory above.
        webView.loadUrl("$APP_ORIGIN/assets/game.html")
    }

    private fun buildRootView(): View {
        val bannerConfig = readAdMobBannerConfig()
        if (bannerConfig == null) {
            return webView
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        runCatching {
            MobileAds.initialize(this)
            val adView = AdView(this).apply {
                adUnitId = bannerConfig
                setAdSize(AdSize.BANNER)
            }
            root.addView(
                adView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                },
            )
            adView.loadAd(AdRequest.Builder().build())
        }
        return root
    }

    private fun goImmersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    // ---- Firebase (Analytics): configured programmatically from assets/firebase-config.json,
    // written by the builder from the user's own google-services.json. No-op if absent. -------

    private fun initFirebaseIfConfigured() {
        val json = readConfigAsset("firebase-config.json") ?: return
        runCatching {
            val obj = JSONObject(json)
            val options = FirebaseOptions.Builder()
                .setApplicationId(obj.getString("mobilesdk_app_id"))
                .setApiKey(obj.getString("api_key"))
                .setProjectId(obj.getString("project_id"))
                .apply { obj.optString("storage_bucket", "").takeIf { it.isNotEmpty() }?.let(::setStorageBucket) }
                .apply { obj.optString("gcm_sender_id", "").takeIf { it.isNotEmpty() }?.let(::setGcmSenderId) }
                .build()
            FirebaseApp.initializeApp(this, options)
            FirebaseAnalytics.getInstance(this).setAnalyticsCollectionEnabled(true)
        }
    }

    // ---- AdMob: banner ad unit id from assets/admob-config.json. No-op if absent. -----------

    private fun readAdMobBannerConfig(): String? =
        runCatching { readConfigAsset("admob-config.json")?.let { JSONObject(it).optString("bannerAdUnitId", "") } }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun readConfigAsset(name: String): String? =
        runCatching { assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()

    // ---- Encrypted game entry point (assets/game.enc, AES-256-CBC, first 16 bytes = IV). -----
    // Mirrors the scheme already used by this repo's ../../android app (see its MainActivity's
    // decryptGameHtml/gameEncPass) and its matching core/GameObfuscator.kt on the builder side.
    // This is a bar-raiser, not real secrecy — the key ships inside this same APK.

    private fun hasEncryptedGame(): Boolean = runCatching { assets.open("game.enc").close(); true }.getOrDefault(false)

    private fun gameEncPassphrase(): String {
        val p = intArrayOf(
            97, 112, 107, 98, 117, 105, 108, 100, 101, 114, 45, 103, 97, 109, 101, 45,
            111, 98, 102, 45, 107, 51, 121,
        )
        return String(p.map { it.toChar() }.toCharArray())
    }

    private fun decryptGameHtml(): ByteArray? {
        return try {
            val all = assets.open("game.enc").use { it.readBytes() }
            if (all.size <= 16) return null
            val iv = all.copyOfRange(0, 16)
            val ciphertext = all.copyOfRange(16, all.size)
            val key = MessageDigest.getInstance("SHA-256").digest(gameEncPassphrase().toByteArray(Charsets.UTF_8))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.doFinal(ciphertext)
        } catch (_: Exception) {
            null
        }
    }

    /** Minimal JS bridge so bundled web games can save exported images. */
    private inner class Bridge {
        @JavascriptInterface
        fun saveBase64Image(base64: String, fileName: String) {
            try {
                val clean = base64.substringAfter(",", base64)
                val bytes = Base64.decode(clean, Base64.DEFAULT)
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures")
                    }
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    runOnUiThread { Toast.makeText(this@MainActivity, "Saved", Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }
}
