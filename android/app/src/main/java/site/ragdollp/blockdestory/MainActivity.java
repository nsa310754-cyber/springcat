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
 *  - 画面録画を MediaProjection (ScreenRecordService) で実装
 */
public class MainActivity extends Activity {

    private WebView webView;

    static final int REQ_SCREEN_CAPTURE = 4001;
    private volatile boolean recording = false;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        webView = new WebView(this);
        // ゲーム背景(水色→ピンク)の下端色。余白が黒くならないように。
        webView.setBackgroundColor(0xFFF2A0F1);
        setContentView(webView);

        // 開いた瞬間から全画面 (システムUIを隠す)
        hideSystemBarsDelayed();

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

        // JS ↔ ネイティブ ブリッジ
        webView.addJavascriptInterface(new SaverBridge(), "AndroidSaver");
        webView.addJavascriptInterface(new RecorderBridge(), "AndroidRecorder");

        webView.setWebViewClient(new WebViewClient() {
            private static final String FIX_VIEWPORT =
                "(function(){var m=document.querySelector('meta[name=viewport]');" +
                "if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');" +
                "(document.head||document.documentElement).appendChild(m);}" +
                "m.setAttribute('content','width=device-width, initial-scale=1, maximum-scale=1, " +
                "minimum-scale=1, user-scalable=no, viewport-fit=cover');})();";

            // html2canvas の CDN 要求を同梱アセットで置き換える (オフラインでスクショ可)
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    String url = request.getUrl() != null ? request.getUrl().toString() : "";
                    if (url.contains("html2canvas")) {
                        InputStream in = getAssets().open("html2canvas.min.js");
                        return new WebResourceResponse("application/javascript", "utf-8", in);
                    }
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

        webView.loadUrl("file:///android_asset/game.html");
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

    // ---- JS ブリッジ: 画面録画 (MediaProjection) ------------------------------

    private class RecorderBridge {
        @JavascriptInterface
        public boolean isRecording() { return recording; }

        @JavascriptInterface
        public void start() {
            runOnUiThread(new Runnable() {
                @Override public void run() { requestScreenCapture(); }
            });
        }

        @JavascriptInterface
        public void stop() {
            runOnUiThread(new Runnable() {
                @Override public void run() { stopScreenRecording(); }
            });
        }
    }

    private void requestScreenCapture() {
        try {
            android.media.projection.MediaProjectionManager mpm =
                (android.media.projection.MediaProjectionManager)
                    getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_SCREEN_CAPTURE);
        } catch (Exception e) {
            toast("この端末では画面録画を開始できません");
        }
    }

    private void stopScreenRecording() {
        try {
            Intent i = new Intent(this, ScreenRecordService.class);
            i.setAction(ScreenRecordService.ACTION_STOP);
            startService(i);
        } catch (Exception ignore) { }
        setRecordUi(false);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                Intent svc = new Intent(this, ScreenRecordService.class);
                svc.setAction(ScreenRecordService.ACTION_START);
                svc.putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode);
                svc.putExtra(ScreenRecordService.EXTRA_RESULT_DATA, data);
                svc.putExtra(ScreenRecordService.EXTRA_WIDTH, dm.widthPixels);
                svc.putExtra(ScreenRecordService.EXTRA_HEIGHT, dm.heightPixels);
                svc.putExtra(ScreenRecordService.EXTRA_DPI, dm.densityDpi);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svc);
                } else {
                    startService(svc);
                }
                setRecordUi(true);
                toast("画面録画を開始しました");
            } else {
                setRecordUi(false);
                toast("画面録画がキャンセルされました");
            }
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

    private void hideSystemBars() {
        try {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
            View decor = getWindow().getDecorView();
            androidx.core.view.WindowInsetsControllerCompat c =
                    androidx.core.view.WindowCompat.getInsetsController(getWindow(), decor);
            c.setSystemBarsBehavior(
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
        } catch (Throwable t) {
            // フォールバック(念のため旧APIでも隠す)
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    /** レイアウト確定後にもう一度隠す(初回適用が効かない端末対策)。 */
    private void hideSystemBarsDelayed() {
        hideSystemBars();
        final View decor = getWindow().getDecorView();
        decor.postDelayed(new Runnable() {
            @Override public void run() { hideSystemBars(); }
        }, 300);
        decor.postDelayed(new Runnable() {
            @Override public void run() { hideSystemBars(); }
        }, 1200);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBarsDelayed();
    }

    // ---- ライフサイクル --------------------------------------------------------

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        hideSystemBarsDelayed();
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
