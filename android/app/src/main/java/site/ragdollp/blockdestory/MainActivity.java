package site.ragdollp.blockdestory;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebChromeClient;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Block Destroy を Android アプリとして動かすための WebView ラッパー。
 *
 * ゲーム本体 (assets/game.html) はオフラインで動作する。
 *
 * このラッパーが補っている WebView 固有の穴:
 *  - UserAgent に "BlockdestoryApp" を付与 (game.html の簡易ブラウザ判定を通す)
 *  - viewport 強制 + ズーム無効 (入力欄タップ時の自動拡大対策)
 *  - 没入フルスクリーン (スマホのシステムUIを隠す)
 *  - html2canvas を同梱アセットから供給 (スクリーンショットをオフラインで動かす)
 *  - blob/data の <a download> を横取りして端末へ保存 (スクショ/エクスポート)
 *  - 画面録画を WebView 直接キャプチャ (WebViewRecorder) で実装 (許可/選択画面なし)
 */
public class MainActivity extends Activity {

    private WebView webView;
    private androidx.webkit.WebViewAssetLoader assetLoader;

    // 同梱HTMLを配信する仮想の正規オリジン。reCAPTCHA Enterprise のキー設定に
    // このドメインを許可ドメインとして追加すると、オンライン時に reCAPTCHA が通る。
    static final String APP_ORIGIN = "https://appassets.androidplatform.net";

    static final int REQ_NOTIF_PERM = 4002;
    private volatile boolean recording = false;
    private WebViewRecorder webRecorder = null;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        webView = new WebView(this);
        // ゲーム背景(水色→ピンク)の下端色。余白が黒くならないように。
        webView.setBackgroundColor(0xFFF2A0F1);
        setContentView(webView);

        // スマホのシステムUI(ステータスバー/ナビゲーションバー)は通常表示のまま。
        // 没入フルスクリーンは端末差で黒帯が出る不具合があったため使わない。
        // バーはゲーム配色に合わせて色付けし、明るい背景に濃いアイコンを表示する。
        try {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
            androidx.core.view.WindowInsetsControllerCompat c =
                    androidx.core.view.WindowCompat.getInsetsController(
                            getWindow(), getWindow().getDecorView());
            c.setAppearanceLightStatusBars(true);
            c.setAppearanceLightNavigationBars(true);
        } catch (Throwable ignore) { }

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        // ★ ゲームの WebView ブロック回避フック
        s.setUserAgentString(s.getUserAgentString() + " BlockdestoryApp/1");

        // 同梱アセットを https://appassets.androidplatform.net/assets/ で配信
        assetLoader = new androidx.webkit.WebViewAssetLoader.Builder()
                .addPathHandler("/assets/",
                        new androidx.webkit.WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        // 🎉 イベント告知の受信準備: チャンネル作成 + トピック購読
        DailyNotify.ensureEventChannel(this);
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().subscribeToTopic("events");
        } catch (Throwable ignore) { }

        // JS ↔ ネイティブ ブリッジ
        webView.addJavascriptInterface(new SaverBridge(), "AndroidSaver");
        webView.addJavascriptInterface(new RecorderBridge(), "AndroidRecorder");
        webView.addJavascriptInterface(new NotifyBridge(), "AndroidNotify");
        webView.addJavascriptInterface(new DeviceBridge(), "AndroidDevice");

        webView.setWebViewClient(new WebViewClient() {
            // パソコン(デスクトップ)UIで表示する。
            // ゲームは @media (max-width:480px) でのみモバイル化するため、
            // viewport 幅を 1024 に固定 → 常にデスクトップレイアウト。
            // useWideViewPort + loadWithOverviewMode で画面幅に縮小フィットする。
            private static final String FIX_VIEWPORT =
                "(function(){var m=document.querySelector('meta[name=viewport]');" +
                "if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');" +
                "(document.head||document.documentElement).appendChild(m);}" +
                "m.setAttribute('content','width=1024, user-scalable=no, viewport-fit=cover');})();";

            // html2canvas の CDN 要求を同梱アセットで置き換える (オフラインでスクショ可)
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    String url = request.getUrl() != null ? request.getUrl().toString() : "";
                    // html2canvas は同梱アセットで供給 (オフラインでもスクショ可)
                    if (url.contains("html2canvas")) {
                        InputStream in = getAssets().open("html2canvas.min.js");
                        return new WebResourceResponse("application/javascript", "utf-8", in);
                    }
                    // appassets.androidplatform.net/assets/* は同梱アセットから配信
                    WebResourceResponse r = assetLoader.shouldInterceptRequest(request.getUrl());
                    if (r != null) return r;
                } catch (Exception ignore) { }
                return null; // それ以外は通常どおり (オンライン機能は維持)
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                view.evaluateJavascript(FIX_VIEWPORT, null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(FIX_VIEWPORT, null);
                view.evaluateJavascript(bridgeJs(), null);
            }
        });
        webView.setWebChromeClient(new WebChromeClient());

        // file:// ではなく仮想の https オリジンで読み込む (reCAPTCHA のドメイン検証用)
        webView.loadUrl(APP_ORIGIN + "/assets/game.html");
    }

    // ---- 注入する JS (ダウンロード横取り + 録画ボタンの結線) ---------------------

    private static String bridgeJs() {
        return
        "(function(){" +
        // blob:/data: の <a download> を横取りしてネイティブ保存
        "  if(!window.__dlHook){ window.__dlHook=true;" +
        "    document.addEventListener('click', function(e){" +
        "      var a=e.target;" +
        "      while(a && (!a.tagName || a.tagName.toUpperCase()!=='A')) a=a.parentElement;" +
        "      if(!a || !a.hasAttribute('download')) return;" +
        "      var href=a.getAttribute('href')||a.href||'';" +
        "      if(href.indexOf('blob:')!==0 && href.indexOf('data:')!==0) return;" +
        "      e.preventDefault(); e.stopPropagation();" +
        "      var name=a.getAttribute('download')||'download';" +
        "      fetch(href).then(function(r){return r.blob();}).then(function(blob){" +
        "        var fr=new FileReader();" +
        "        fr.onload=function(){ var res=String(fr.result); var c=res.indexOf(',');" +
        "          var meta=res.substring(5,c); var b64=res.substring(c+1);" +
        "          var mime=(meta.split(';')[0])||'application/octet-stream';" +
        "          try{ AndroidSaver.saveBase64(name, mime, b64); }catch(err){} };" +
        "        fr.readAsDataURL(blob);" +
        "      }).catch(function(){});" +
        "    }, true);" +
        "  }" +
        // 録画ボタンをネイティブ録画へ結線
        "  if(window.AndroidRecorder){" +
        "    window.toggleRecording=function(){ try{" +
        "      if(AndroidRecorder.isRecording()) AndroidRecorder.stop(); else AndroidRecorder.start();" +
        "    }catch(e){} };" +
        "    window.__setRecUI=function(on){ var b=document.getElementById('recordBtn');" +
        "      if(b){ b.textContent = on ? '⏹ 録画停止' : '🎥 録画開始';" +
        "        if(on) b.classList.add('recording'); else b.classList.remove('recording'); }" +
        "      var ind=document.getElementById('recIndicator'); if(ind) ind.style.display = on ? 'block':'none'; };" +
        "  }" +
        "})();";
    }

    void setRecordUi(final boolean on) {
        recording = on;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (webView != null) {
                    webView.evaluateJavascript("window.__setRecUI && window.__setRecUI(" + on + ");", null);
                }
            }
        });
    }

    // ---- JS ブリッジ: ファイル保存 --------------------------------------------

    private class SaverBridge {
        @JavascriptInterface
        public void saveBase64(final String name, final String mime, final String b64) {
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        byte[] data = Base64.decode(b64, Base64.DEFAULT);
                        Uri uri = MediaSaver.save(MainActivity.this, name, mime, data);
                        toast(uri != null ? "保存しました: " + name : "保存に失敗しました");
                    } catch (Exception e) {
                        toast("保存に失敗しました");
                    }
                }
            }).start();
        }
    }

    // ---- JS ブリッジ: 画面録画 (WebView 直接キャプチャ / 許可・選択画面なし) ----

    private class RecorderBridge {
        @JavascriptInterface
        public boolean isRecording() { return recording; }

        @JavascriptInterface
        public void start() {
            runOnUiThread(new Runnable() {
                @Override public void run() { startWebRecording(); }
            });
        }

        @JavascriptInterface
        public void stop() {
            runOnUiThread(new Runnable() {
                @Override public void run() { stopWebRecording(); }
            });
        }
    }

    private void startWebRecording() {
        if (recording || webRecorder != null) return;
        try {
            File tmp = new File(getExternalFilesDir(null), "rec_tmp.mp4");
            if (tmp.exists()) tmp.delete();
            webRecorder = new WebViewRecorder(this, webView, tmp);
            if (webRecorder.start()) {
                setRecordUi(true);
                toast("録画を開始しました");
            } else {
                webRecorder = null;
                toast("録画を開始できませんでした");
            }
        } catch (Throwable e) {
            webRecorder = null;
            toast("録画を開始できませんでした");
        }
    }

    private void stopWebRecording() {
        final WebViewRecorder rec = webRecorder;
        webRecorder = null;
        setRecordUi(false);
        if (rec == null) return;
        rec.stop(new WebViewRecorder.DoneCallback() {
            @Override public void onDone(boolean ok, File file) {
                if (ok && file != null) {
                    String name = "blockdestory_rec_" +
                            new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                                    .format(new java.util.Date()) + ".mp4";
                    android.net.Uri u = MediaSaver.saveFile(MainActivity.this, file, name, "video/mp4");
                    toast(u != null ? "録画を保存しました: " + name : "録画の保存に失敗しました");
                } else {
                    toast("録画の保存に失敗しました");
                }
            }
        });
    }

    // ---- JS ブリッジ: 端末固有ID (同じ実機を安定して識別) --------------------

    private class DeviceBridge {
        // ANDROID_ID: 端末+アプリ署名鍵ごとに安定。再インストールやデータ消去、
        // WebView の読み込みオリジン変更 (file:// ↔ appassets) でも変わらない。
        @JavascriptInterface
        @SuppressLint("HardwareIds")
        public String getId() {
            try {
                String id = android.provider.Settings.Secure.getString(
                        getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
                return id != null ? id : "";
            } catch (Exception e) {
                return "";
            }
        }
    }

    // ---- JS ブリッジ: デイリーボーナス通知 -----------------------------------

    private class NotifyBridge {
        @JavascriptInterface
        public boolean isSupported() { return true; }

        @JavascriptInterface
        public boolean hasPermission() { return DailyNotify.hasPermission(MainActivity.this); }

        @JavascriptInterface
        public boolean requestPermission() {
            if (DailyNotify.hasPermission(MainActivity.this)) return true;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requestPermissions(
                                new String[]{ android.Manifest.permission.POST_NOTIFICATIONS },
                                REQ_NOTIF_PERM);
                    }
                }
            });
            return DailyNotify.hasPermission(MainActivity.this);
        }

        @JavascriptInterface
        public void scheduleDaily(int hour, int min) {
            DailyNotify.schedule(MainActivity.this, hour, min);
        }

        @JavascriptInterface
        public void cancel() { DailyNotify.cancel(MainActivity.this); }

        @JavascriptInterface
        public void notifyNow(String title, String body) {
            DailyNotify.post(MainActivity.this, title, body);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF_PERM && webView != null) {
            // 許可結果を受けてゲーム側UIを再同期し、有効なら通知を再スケジュール
            webView.evaluateJavascript(
                    "try{ if(typeof syncNotifyUI==='function') syncNotifyUI();" +
                    " if(typeof scheduleDailyNotify==='function') scheduleDailyNotify(); }catch(e){}", null);
        }
    }

    void toast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ---- 没入フルスクリーン ----------------------------------------------------

    // ---- ライフサイクル --------------------------------------------------------

    @Override
    protected void onPause() {
        super.onPause();
        // バックグラウンドに移ったら録画を止めて保存する
        if (recording || webRecorder != null) stopWebRecording();
        if (webView != null) webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
