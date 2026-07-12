package com.springcat.browser;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private WebView webView;
    private EditText urlBar;
    private ProgressBar progress;
    private String erudaSource = "";
    private static final String HOME_URL = "https://www.google.com";

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        urlBar = findViewById(R.id.urlBar);
        progress = findViewById(R.id.progress);
        Button btnBack = findViewById(R.id.btnBack);
        Button btnForward = findViewById(R.id.btnForward);
        Button btnReload = findViewById(R.id.btnReload);
        Button btnDevtools = findViewById(R.id.btnDevtools);

        erudaSource = loadAsset("eruda.js");

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false);
        WebView.setWebContentsDebuggingEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String u = request.getUrl().toString();
                if (u.startsWith("http://") || u.startsWith("https://")) {
                    return false;
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (!urlBar.hasFocus()) urlBar.setText(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectEruda();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    progress.setVisibility(View.VISIBLE);
                    progress.setProgress(newProgress);
                } else {
                    progress.setVisibility(View.GONE);
                }
            }
        });

        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                loadFromBar();
                return true;
            }
            return false;
        });

        btnBack.setOnClickListener(v -> {
            if (webView.canGoBack()) webView.goBack();
        });
        btnForward.setOnClickListener(v -> {
            if (webView.canGoForward()) webView.goForward();
        });
        btnReload.setOnClickListener(v -> webView.reload());
        btnDevtools.setOnClickListener(v -> toggleDevtools());

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(HOME_URL);
        }
    }

    private void loadFromBar() {
        String text = urlBar.getText().toString().trim();
        if (TextUtils.isEmpty(text)) return;
        String url;
        if (text.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
            url = text;
        } else if (text.contains(".") && !text.contains(" ")) {
            url = "https://" + text;
        } else {
            url = "https://www.google.com/search?q=" + android.net.Uri.encode(text);
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
        urlBar.clearFocus();
        webView.loadUrl(url);
    }

    // Injects Eruda (mobile devtools) into the current page if not already present.
    private void injectEruda() {
        if (TextUtils.isEmpty(erudaSource)) return;
        String js = "(function(){try{"
                + "if(!window.__springcatEruda){"
                + erudaSource + "\n"
                + "eruda.init({defaults:{displaySize:45,transparency:0.95}});"
                + "window.__springcatEruda=true;"
                + "}}catch(e){console.error('eruda inject failed',e);}})();";
        webView.evaluateJavascript(js, null);
    }

    // F12 button: injects Eruda if needed, then toggles the panel open/closed.
    private void toggleDevtools() {
        String js = "(function(){"
                + "if(!window.eruda){return 'noeruda';}"
                + "if(window.__springcatDevShown){eruda.hide();window.__springcatDevShown=false;return 'hide';}"
                + "else{eruda.show();window.__springcatDevShown=true;return 'show';}"
                + "})();";
        webView.evaluateJavascript(js, value -> {
            if (value != null && value.contains("noeruda")) {
                injectEruda();
                webView.postDelayed(() -> webView.evaluateJavascript(
                        "if(window.eruda){eruda.show();window.__springcatDevShown=true;}", null), 200);
            }
        });
    }

    private String loadAsset(String name) {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = getAssets().open(name);
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception e) {
            return "";
        }
        return sb.toString();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
