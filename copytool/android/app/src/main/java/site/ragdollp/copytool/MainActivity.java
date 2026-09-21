package site.ragdollp.copytool;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * 文字コピーツール — Android ラッパー（WebView 方式・PWAではない本物のAPK）。
 *
 * UI 本体は assets/index.html（Web版と同一）を WebView に読み込むだけ。
 * 外部通信・広告なし、完全オフラインで動作する。
 * ファイル選択（&lt;input type=file&gt;）を動かすため WebChromeClient で
 * システムのファイル選択画面を起動する。
 */
public class MainActivity extends Activity {

    private static final int FILE_CHOOSER_REQUEST = 51426;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);

        // 外部リンクは既定ブラウザで開く（このアプリ内に外部リンクは無いが念のため）
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri uri = req.getUrl();
                String scheme = uri.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    return true;
                }
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                              FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST);
                } catch (ActivityNotFoundException e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        // Web版UIから「springコピー」（25MB対応のネイティブコピー）を呼べるようにする
        webView.addJavascriptInterface(new JsBridge(), "AndroidBridge");

        webView.loadUrl("file:///android_asset/index.html");
    }

    /** WebView 側 JS から呼び出せるネイティブブリッジ。 */
    private class JsBridge {
        @JavascriptInterface
        public String springCopy(String text) {
            return ClipHelper.copyLargeText(MainActivity.this, text);
        }

        /**
         * クリップボードの内容を読み出す（springペースト）。
         * 戻り値は "OK:実際のテキスト" または "ERR:エラーメッセージ" の形式。
         * （JavascriptInterfaceはStringしか返せないため先頭に状態を付ける）
         */
        @JavascriptInterface
        public String springPaste() {
            try {
                String text = ClipHelper.readClipboardText(MainActivity.this);
                if (text == null) return "ERR:貼り付けられる内容がありません";
                return "OK:" + text;
            } catch (ClipHelper.ClipTooLargeException e) {
                return "ERR:25MBを超えているため貼り付けられません";
            }
        }

        /** 「全アプリ対応」アクセシビリティサービスが現在ONになっているか。 */
        @JavascriptInterface
        public boolean isAccessibilityEnabled() {
            return AccessibilityUtil.isEnabled(MainActivity.this, SpringCopyAccessibilityService.class);
        }

        /**
         * OSの設定画面を開く。Androidの仕様上、アプリからアクセシビリティ
         * サービスを直接ON/OFFすることはできず、ユーザーが設定画面で
         * 手動で切り替える必要がある。
         */
        @JavascriptInterface
        public void openAccessibilitySettings() {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != FILE_CHOOSER_REQUEST) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (filePathCallback == null) return;

        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clipData = data.getClipData();
            if (clipData != null) {
                results = new Uri[clipData.getItemCount()];
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    results[i] = clipData.getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                results = new Uri[]{ data.getData() };
            }
        }
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 設定画面からアプリに戻ってきたとき、最新のON/OFF状態をページ側に反映する
        if (webView != null) {
            boolean enabled = AccessibilityUtil.isEnabled(this, SpringCopyAccessibilityService.class);
            webView.evaluateJavascript(
                    "window.updateAccessibilityStatus && window.updateAccessibilityStatus(" + enabled + ");",
                    null);
        }
    }
}
