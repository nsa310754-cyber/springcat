package com.lunax.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * LunaxOS — a WebView shell that hosts the Lunax virtual environment.
 * All assets (index.html, JS, CSS) are bundled offline in assets/; the app
 * needs no network and no permissions.
 */
public class MainActivity extends Activity {

    static final int FILE_REQUEST = 1001;

    WebView web;
    ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Match the app's dark background to avoid a white flash on launch.
        getWindow().setStatusBarColor(Color.parseColor("#0a0713"));
        getWindow().setNavigationBarColor(Color.parseColor("#0f0a1d"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0a0713"));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);        // localStorage (virtual filesystem)
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        // Let the in-page "Import" button open the Android file picker.
        web.setWebChromeClient(new LunaxChromeClient(this));

        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_REQUEST && filePathCallback != null) {
            Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        // Back button navigates the WebView history before leaving the app.
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    /** Named (non-anonymous) chrome client so dexing stays happy on JDK 21 / d8. */
    static final class LunaxChromeClient extends WebChromeClient {
        private final MainActivity activity;

        LunaxChromeClient(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public boolean onShowFileChooser(WebView webView,
                ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (activity.filePathCallback != null) {
                activity.filePathCallback.onReceiveValue(null);
            }
            activity.filePathCallback = callback;
            Intent intent = params.createIntent();
            try {
                activity.startActivityForResult(intent, FILE_REQUEST);
            } catch (Exception e) {
                activity.filePathCallback = null;
                return false;
            }
            return true;
        }
    }
}
