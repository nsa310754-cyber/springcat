package site.ragdollp.filemanager;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.InputStream;

/**
 * ファイルマネージャ本体のホスト。
 *
 * UI は assets/app.html を WebView で描画し、実際のファイル操作(一覧/読み書き/
 * 圧縮/解凍/APK検査・インストール/ストレージ情報)はネイティブの {@link FileBridge}
 * が担当する。JS/TS の実行や HTML/JSX プレビューはオンライン時に WebView 内で
 * (CDN の transpiler を使って) 完結する。
 */
public class MainActivity extends Activity {

    private WebView web;
    private FileBridge bridge;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        // プレビュー/実行用に mixed でなく https CDN を読めるようにしておく。
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        // assets を仮想オリジンとして提供し、file:// 由来の制約を避ける。
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (u != null && "appassets.local".equals(u.getHost())) {
                    String path = u.getPath();
                    if (path == null || path.equals("/")) path = "/app.html";
                    try {
                        InputStream in = getAssets().open(path.substring(1));
                        String mime = guessMime(path);
                        WebResourceResponse r = new WebResourceResponse(mime, "UTF-8", in);
                        return r;
                    } catch (Exception e) {
                        return null;
                    }
                }
                return null;
            }
        });

        bridge = new FileBridge(this, web);
        web.addJavascriptInterface(bridge, "Bridge");

        web.loadUrl("https://appassets.local/app.html");
    }

    private String guessMime(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "text/plain";
    }

    /** UI スレッドから WebView の JS 関数を呼ぶ (FileBridge のコールバック用)。 */
    void jsCallback(final String js) {
        runOnUiThread(() -> {
            try { web.evaluateJavascript(js, null); } catch (Throwable ignored) {}
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // 全ファイルアクセス設定から戻ってきたら UI に再判定を促す。
        if (requestCode == FileBridge.REQ_ALL_FILES) {
            jsCallback("window.onAllFilesResult && window.onAllFilesResult(" + hasAllFiles() + ");");
        }
    }

    private boolean hasAllFiles() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return true;
    }

    @Override
    public void onBackPressed() {
        // 戻るキーは UI 側 (フォルダ階層) に委ねる。JS が false を返したら通常の戻る。
        web.evaluateJavascript("window.onAndroidBack && window.onAndroidBack()", value -> {
            if (!"true".equals(value)) {
                finish();
            }
        });
    }
}
