package com.springcat.browser;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    private static final String HOME_URL = "https://www.google.com";
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/122.0.0.0 Safari/537.36";

    // Desktop layout width (CSS px) forced via the viewport meta in desktop mode.
    private static final int DESKTOP_WIDTH = 1024;

    // Forces the page to lay out at a desktop width so CSS media queries and
    // window.innerWidth reflect a PC, then WebView overview-mode scales it to fit.
    private static final String VIEWPORT_JS =
            "(function(){try{"
            + "var W=" + DESKTOP_WIDTH + ";"
            + "var set=function(){var m=document.querySelector('meta[name=\"viewport\"]');"
            + "if(!m){m=document.createElement('meta');m.setAttribute('name','viewport');"
            + "(document.head||document.documentElement).appendChild(m);}"
            + "m.setAttribute('content','width='+W);};"
            + "set();"
            + "if(document.addEventListener){document.addEventListener('DOMContentLoaded',set);}"
            + "}catch(e){}})();";

    // Runs before page scripts: makes JS-based platform detection (client hints,
    // navigator.platform, touch points, screen size, pointer type) see a desktop
    // browser, so PC-only pages work.
    private static final String DESKTOP_SPOOF_JS =
            "(function(){try{"
            + "var d=function(o,k,v){try{Object.defineProperty(o,k,{get:function(){return v;},configurable:true});}catch(e){}};"
            + "d(navigator,'platform','Linux x86_64');"
            + "d(navigator,'maxTouchPoints',0);"
            + "d(navigator,'userAgent','" + DESKTOP_UA + "');"
            + "d(navigator,'vendor','Google Inc.');"
            + "var brands=[{brand:'Chromium',version:'122'},{brand:'Google Chrome',version:'122'},{brand:'Not(A:Brand',version:'24'}];"
            + "var uad={brands:brands,mobile:false,platform:'Linux',"
            + "getHighEntropyValues:function(h){return Promise.resolve({architecture:'x86',bitness:'64',brands:brands,mobile:false,model:'',platform:'Linux',platformVersion:'6.0.0',uaFullVersion:'122.0.0.0',fullVersionList:brands});},"
            + "toJSON:function(){return {brands:brands,mobile:false,platform:'Linux'};}};"
            + "d(navigator,'userAgentData',uad);"
            // Screen / pixel ratio look like a desktop monitor.
            + "d(screen,'width',1920);d(screen,'height',1080);"
            + "d(screen,'availWidth',1920);d(screen,'availHeight',1040);"
            + "d(window,'devicePixelRatio',1);"
            // Pointer/hover capability queries report a mouse, not touch.
            + "if(window.matchMedia){var mm=window.matchMedia.bind(window);"
            + "window.matchMedia=function(q){var r=mm(q);try{q=String(q);"
            + "var stub=function(v){return {matches:v,media:q,onchange:null,addListener:function(){},removeListener:function(){},addEventListener:function(){},removeEventListener:function(){},dispatchEvent:function(){return false;}};};"
            + "if(/(any-)?pointer:\\s*fine|(any-)?hover:\\s*hover/.test(q))return stub(true);"
            + "if(/(any-)?pointer:\\s*coarse|(any-)?hover:\\s*none/.test(q))return stub(false);"
            + "}catch(e){}return r;};}"
            + "try{delete window.ontouchstart;}catch(e){}"
            + VIEWPORT_JS
            + "}catch(e){}})();";

    // Manual fallback snippet the user can copy into the F12 console when a site
    // still detects mobile. Kept verbatim (human-readable) for pasting.
    private static final String PC_SPOOF_SNIPPET =
            "// 1. 画面サイズとピクセル比の完全偽装\n"
            + "Object.defineProperty(window, 'innerWidth', { get: () => 1920 });\n"
            + "Object.defineProperty(window, 'innerHeight', { get: () => 1080 });\n"
            + "Object.defineProperty(window, 'devicePixelRatio', { get: () => 1 });\n"
            + "\n"
            + "// 2. 基本的なOS・プラットフォーム情報の偽装（Windows11化）\n"
            + "Object.defineProperty(navigator, 'platform', { get: () => 'Win32' });\n"
            + "Object.defineProperty(navigator, 'userAgent', { \n"
            + "    get: () => 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36' \n"
            + "});\n"
            + "\n"
            + "// 3. スマホ特有のタッチ機能を「PC（マウス）」に偽装\n"
            + "Object.defineProperty(navigator, 'maxTouchPoints', { get: () => 0 });\n"
            + "Object.defineProperty(navigator, 'msMaxTouchPoints', { get: () => 0 });\n"
            + "Object.defineProperty(window, 'Ontouchstart', { get: () => undefined });\n"
            + "\n"
            + "// 4. 最新のGoogle系サイトが使う「UserAgentData」の鉄壁偽装\n"
            + "if (navigator.userAgentData) {\n"
            + "    Object.defineProperty(navigator.userAgentData, 'mobile', { get: () => false });\n"
            + "    Object.defineProperty(navigator.userAgentData, 'platform', { get: () => 'Windows' });\n"
            + "    navigator.userAgentData.getHighEntropyValues = function(hints) {\n"
            + "        return Promise.resolve({\n"
            + "            architecture: \"x86\",\n"
            + "            bitness: \"64\",\n"
            + "            model: \"\",\n"
            + "            platform: \"Windows\",\n"
            + "            platformVersion: \"15.0.0\", // Windows 11\n"
            + "            uaFullVersion: \"120.0.0.0\"\n"
            + "        });\n"
            + "    };\n"
            + "}\n"
            + "\n"
            + "console.log(\"=== PC偽装スクリプトを実行しました ===\");\n";

    // Menu item ids
    private static final int MI_BM_ADD = 1;
    private static final int MI_BM_LIST = 2;
    private static final int MI_UA = 3;
    private static final int MI_JS = 4;
    private static final int MI_HOME = 5;
    private static final int MI_DL_LIST = 6;
    private static final int MI_PC_SNIPPET = 7;
    private static final int MI_EXT = 8;

    private FrameLayout webContainer;
    private LinearLayout tabBar;
    private EditText urlBar;
    private ProgressBar progress;
    private TextView downloadBanner;

    private String erudaSource = "";
    private String mobileUa = "";

    private SharedPreferences prefs;
    private boolean uaDesktop;   // User-Agent Switcher -> Desktop
    private boolean jsEnabled;   // script / noscript

    private final List<Tab> tabs = new ArrayList<>();
    private int current = -1;

    private static class Tab {
        WebView web;
        String title = "新しいタブ";
        String url = "";
        ScriptHandler spoofHandler;   // document-start desktop spoof (androidx.webkit)
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("springcat", MODE_PRIVATE);
        uaDesktop = prefs.getBoolean("ua_desktop", true);   // default: desktop (PC) mode
        jsEnabled = prefs.getBoolean("js_enabled", true);

        webContainer = findViewById(R.id.webContainer);
        tabBar = findViewById(R.id.tabBar);
        urlBar = findViewById(R.id.urlBar);
        progress = findViewById(R.id.progress);
        downloadBanner = findViewById(R.id.downloadBanner);

        erudaSource = loadAsset("eruda.js");
        mobileUa = WebSettings.getDefaultUserAgent(this);
        WebView.setWebContentsDebuggingEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);

        findViewById(R.id.btnHome).setOnClickListener(v -> loadUrl(HOME_URL));
        findViewById(R.id.btnBack).setOnClickListener(v -> {
            WebView w = currentWeb();
            if (w != null && w.canGoBack()) w.goBack();
        });
        findViewById(R.id.btnForward).setOnClickListener(v -> {
            WebView w = currentWeb();
            if (w != null && w.canGoForward()) w.goForward();
        });
        findViewById(R.id.btnReload).setOnClickListener(v -> {
            WebView w = currentWeb();
            if (w != null) w.reload();
        });
        findViewById(R.id.btnDevtools).setOnClickListener(v -> toggleDevtools());
        findViewById(R.id.btnNewTab).setOnClickListener(v -> newTab(HOME_URL));
        findViewById(R.id.btnMenu).setOnClickListener(this::showMenu);

        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                loadFromBar();
                return true;
            }
            return false;
        });

        newTab(HOME_URL);
    }

    // ---------------- Tabs ----------------

    private WebView currentWeb() {
        if (current >= 0 && current < tabs.size()) return tabs.get(current).web;
        return null;
    }

    private Tab newTabRaw() {
        Tab t = new Tab();
        WebView w = new WebView(this);
        w.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        configureWebView(w);
        t.web = w;
        tabs.add(t);
        webContainer.addView(w);
        applyDesktopSpoof(t);
        switchTab(tabs.size() - 1);
        return t;
    }

    // Add or remove the document-start desktop spoof for a tab based on uaDesktop.
    private void applyDesktopSpoof(Tab t) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return;
        try {
            if (uaDesktop) {
                if (t.spoofHandler == null) {
                    Set<String> origins = new HashSet<>();
                    origins.add("*");
                    t.spoofHandler = WebViewCompat.addDocumentStartJavaScript(
                            t.web, DESKTOP_SPOOF_JS, origins);
                }
            } else if (t.spoofHandler != null) {
                t.spoofHandler.remove();
                t.spoofHandler = null;
            }
        } catch (Exception ignored) {
        }
    }

    private void newTab(String url) {
        Tab t = newTabRaw();
        t.web.loadUrl(url);
    }

    private void switchTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        current = index;
        for (int i = 0; i < tabs.size(); i++) {
            tabs.get(i).web.setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
        Tab t = tabs.get(index);
        if (!urlBar.hasFocus()) urlBar.setText(t.url);
        rebuildTabBar();
    }

    private void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        Tab t = tabs.get(index);
        webContainer.removeView(t.web);
        t.web.destroy();
        tabs.remove(index);
        if (tabs.isEmpty()) {
            newTab(HOME_URL);
            return;
        }
        switchTab(Math.min(index, tabs.size() - 1));
    }

    private void rebuildTabBar() {
        tabBar.removeAllViews();
        for (int i = 0; i < tabs.size(); i++) {
            final int idx = i;
            Tab t = tabs.get(i);
            boolean active = (i == current);

            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setBackgroundColor(active ? 0xFF1565C0 : 0xFF0A356E);
            chip.setPadding(dp(10), dp(6), dp(6), dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(4);
            chip.setLayoutParams(lp);

            TextView title = new TextView(this);
            title.setText(TextUtils.isEmpty(t.title) ? "タブ" : t.title);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setMaxWidth(dp(130));
            title.setTextColor(Color.WHITE);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            chip.addView(title);

            TextView close = new TextView(this);
            close.setText("  ×");
            close.setTextColor(Color.WHITE);
            close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            close.setPadding(dp(6), 0, dp(4), 0);
            chip.addView(close);

            chip.setOnClickListener(v -> switchTab(idx));
            close.setOnClickListener(v -> closeTab(idx));
            tabBar.addView(chip);
        }
    }

    // ---------------- WebView setup ----------------

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView(WebView web) {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(jsEnabled);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(uaDesktop ? DESKTOP_UA : mobileUa);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String u = request.getUrl().toString();
                return !(u.startsWith("http://") || u.startsWith("https://"));
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                setTabUrl(view, url);
                // Fallback for WebViews without DOCUMENT_START_SCRIPT support.
                if (uaDesktop && jsEnabled
                        && !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    view.evaluateJavascript(DESKTOP_SPOOF_JS, null);
                }
                if (view == currentWeb() && !urlBar.hasFocus()) urlBar.setText(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                setTabUrl(view, url);
                if (uaDesktop && jsEnabled) view.evaluateJavascript(VIEWPORT_JS, null);
                injectExtensions(view);
                injectEruda(view);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (view != currentWeb()) return;
                if (newProgress < 100) {
                    progress.setVisibility(View.VISIBLE);
                    progress.setProgress(newProgress);
                } else {
                    progress.setVisibility(View.GONE);
                }
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                super.onReceivedTitle(view, title);
                Tab t = tabOf(view);
                if (t != null && !TextUtils.isEmpty(title)) {
                    t.title = title;
                    rebuildTabBar();
                }
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, Message resultMsg) {
                Tab t = newTabRaw();
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(t.web);
                resultMsg.sendToTarget();
                return true;
            }
        });

        // Download handling: show an in-app banner and enqueue with DownloadManager.
        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                String name = URLUtil.guessFileName(url, contentDisposition, mimetype);
                showDownloadBanner(name);
                recordDownload(name, url);
                try {
                    DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                    req.setMimeType(mimetype);
                    req.addRequestHeader("User-Agent", userAgent);
                    req.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url));
                    req.setTitle(name);
                    req.setDescription("SpringCat DevBrowser");
                    req.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                    DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    if (dm != null) dm.enqueue(req);
                } catch (Exception e) {
                    // e.g. blob:/data: URLs the DownloadManager can't handle
                }
            }
        });
    }

    private Tab tabOf(WebView web) {
        for (Tab t : tabs) if (t.web == web) return t;
        return null;
    }

    private void setTabUrl(WebView web, String url) {
        Tab t = tabOf(web);
        if (t != null) t.url = url;
    }

    private void loadUrl(String url) {
        WebView w = currentWeb();
        if (w != null) w.loadUrl(url);
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
            url = "https://www.google.com/search?q=" + Uri.encode(text);
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
        urlBar.clearFocus();
        loadUrl(url);
    }

    // ---------------- Download banner ----------------

    private void showDownloadBanner(String name) {
        downloadBanner.setText(getString(R.string.downloading, name));
        downloadBanner.setVisibility(View.VISIBLE);
        downloadBanner.removeCallbacks(hideBanner);
        downloadBanner.postDelayed(hideBanner, 4500);
    }

    private final Runnable hideBanner = () -> downloadBanner.setVisibility(View.GONE);

    // ---------------- Eruda / F12 ----------------

    private void injectEruda(WebView web) {
        if (TextUtils.isEmpty(erudaSource) || !jsEnabled) return;
        String js = "(function(){try{"
                + "if(!window.__springcatEruda){"
                + erudaSource + "\n"
                + "eruda.init({defaults:{displaySize:45,transparency:0.95}});"
                + "window.__springcatEruda=true;"
                + "}}catch(e){}})();";
        web.evaluateJavascript(js, null);
    }

    private void toggleDevtools() {
        final WebView web = currentWeb();
        if (web == null) return;
        String js = "(function(){"
                + "if(!window.eruda){return 'noeruda';}"
                + "if(window.__springcatDevShown){eruda.hide();window.__springcatDevShown=false;return 'hide';}"
                + "else{eruda.show();window.__springcatDevShown=true;return 'show';}"
                + "})();";
        web.evaluateJavascript(js, value -> {
            if (value != null && value.contains("noeruda")) {
                injectEruda(web);
                web.postDelayed(() -> web.evaluateJavascript(
                        "if(window.eruda){eruda.show();window.__springcatDevShown=true;}", null), 200);
            }
        });
    }

    // ---------------- Menu (UA / JS / bookmarks / home) ----------------

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        m.add(0, MI_DL_LIST, 0, "⬇ ダウンロード一覧");
        m.add(0, MI_BM_ADD, 1, getString(R.string.menu_bookmark_add));
        m.add(0, MI_BM_LIST, 2, getString(R.string.menu_bookmarks));

        MenuItem ua = m.add(0, MI_UA, 3, "PCサイト表示（デスクトップUA）");
        ua.setCheckable(true);
        ua.setChecked(uaDesktop);

        m.add(0, MI_PC_SNIPPET, 4, "🖥 PC偽装スクリプトをコピー");
        m.add(0, MI_EXT, 5, "🧩 拡張機能（ユーザースクリプト）");

        MenuItem js = m.add(0, MI_JS, 6, "JavaScript を有効（script / noscript）");
        js.setCheckable(true);
        js.setChecked(jsEnabled);

        m.add(0, MI_HOME, 7, getString(R.string.menu_home));

        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case MI_DL_LIST:
                    showDownloads();
                    return true;
                case MI_PC_SNIPPET:
                    showPcSnippetDialog();
                    return true;
                case MI_EXT:
                    showExtensions();
                    return true;
                case MI_BM_ADD:
                    addCurrentBookmark();
                    return true;
                case MI_BM_LIST:
                    showBookmarks();
                    return true;
                case MI_UA:
                    uaDesktop = !uaDesktop;
                    prefs.edit().putBoolean("ua_desktop", uaDesktop).apply();
                    applyUaToAll();
                    Toast.makeText(this, uaDesktop ? "デスクトップ（PC）表示に切替" : "モバイル表示に切替",
                            Toast.LENGTH_SHORT).show();
                    return true;
                case MI_JS:
                    jsEnabled = !jsEnabled;
                    prefs.edit().putBoolean("js_enabled", jsEnabled).apply();
                    applyJsToAll();
                    Toast.makeText(this, jsEnabled ? "JavaScript: ON (script)" : "JavaScript: OFF (noscript)",
                            Toast.LENGTH_SHORT).show();
                    return true;
                case MI_HOME:
                    loadUrl(HOME_URL);
                    return true;
            }
            return false;
        });
        pm.show();
    }

    private void applyUaToAll() {
        for (Tab t : tabs) {
            t.web.getSettings().setUserAgentString(uaDesktop ? DESKTOP_UA : mobileUa);
            applyDesktopSpoof(t);
            t.web.reload();
        }
    }

    private void applyJsToAll() {
        for (Tab t : tabs) {
            t.web.getSettings().setJavaScriptEnabled(jsEnabled);
            t.web.reload();
        }
    }

    // ---------------- PC spoof snippet (manual fallback) ----------------

    private void showPcSnippetDialog() {
        new AlertDialog.Builder(this)
                .setTitle("PC偽装スクリプト")
                .setMessage("自動でPC表示にならないサイト向けの手動用スクリプトです。\n\n"
                        + "「コピー」して F12 コンソールに貼り付けて実行するか、"
                        + "「今すぐ実行」で現在のページに直接適用できます。")
                .setPositiveButton("コピー", (d, w) -> copyPcSnippet())
                .setNeutralButton("今すぐ実行", (d, w) -> runPcSnippet())
                .setNegativeButton("閉じる", null)
                .show();
    }

    private void copyPcSnippet() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("PC spoof script", PC_SPOOF_SNIPPET));
            Toast.makeText(this, "コピーしました。F12コンソールに貼り付けて実行してください",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void runPcSnippet() {
        WebView w = currentWeb();
        if (w == null) return;
        w.evaluateJavascript(PC_SPOOF_SNIPPET, null);
        Toast.makeText(this, "現在のページにPC偽装を適用しました", Toast.LENGTH_SHORT).show();
    }

    // ---------------- Extensions (userscripts) ----------------
    //
    // Real Chrome .crx extensions can't run in an Android WebView, so this manages
    // userscript-style "extensions": JS downloaded from a URL (or pasted) that is
    // auto-injected into every page while enabled.

    private JSONArray loadExtensions() {
        try {
            return new JSONArray(prefs.getString("extensions", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void saveExtensions(JSONArray arr) {
        prefs.edit().putString("extensions", arr.toString()).apply();
    }

    // Runs each enabled extension in the freshly loaded page.
    private void injectExtensions(WebView web) {
        if (!jsEnabled) return;
        JSONArray arr = loadExtensions();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || !o.optBoolean("enabled", true)) continue;
            String code = o.optString("code", "");
            if (TextUtils.isEmpty(code)) continue;
            web.evaluateJavascript(
                    "(function(){try{\n" + code + "\n}catch(e){if(window.console)console.error('[ext]',e);}})();",
                    null);
        }
    }

    private void addExtensionRecord(String name, String code, boolean enabled) {
        try {
            JSONArray arr = loadExtensions();
            JSONObject o = new JSONObject();
            o.put("name", TextUtils.isEmpty(name) ? "script" : name);
            o.put("code", code);
            o.put("enabled", enabled);
            arr.put(o);
            saveExtensions(arr);
        } catch (Exception ignored) {
        }
    }

    private void setExtensionEnabled(int idx, boolean enabled) {
        JSONArray arr = loadExtensions();
        JSONObject o = arr.optJSONObject(idx);
        if (o != null) {
            try {
                o.put("enabled", enabled);
            } catch (Exception ignored) {
            }
            saveExtensions(arr);
        }
    }

    private void deleteExtension(int idx) {
        JSONArray arr = loadExtensions();
        JSONArray kept = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            if (i == idx) continue;
            kept.put(arr.opt(i));
        }
        saveExtensions(kept);
    }

    private void showExtensions() {
        JSONArray arr = loadExtensions();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, dp(4));

        if (arr.length() == 0) {
            TextView tv = new TextView(this);
            tv.setText("拡張機能はまだありません。\n「追加」から URL 取得、またはコード貼り付けで追加できます。\n"
                    + "（GreasyFork 等の .user.js を貼り付け／取得すると拡張として動作します）");
            root.addView(tv);
        } else {
            for (int i = 0; i < arr.length(); i++) {
                final int idx = i;
                JSONObject o = arr.optJSONObject(i);
                String name = o != null ? o.optString("name", "script") : "script";
                boolean en = o != null && o.optBoolean("enabled", true);

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);

                CheckBox cb = new CheckBox(this);
                cb.setChecked(en);
                cb.setOnCheckedChangeListener((b, checked) -> setExtensionEnabled(idx, checked));
                row.addView(cb);

                TextView tv = new TextView(this);
                tv.setText(name);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                tv.setSingleLine(true);
                tv.setEllipsize(TextUtils.TruncateAt.END);
                tv.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(tv);

                Button del = new Button(this);
                del.setText("削除");
                del.setOnClickListener(v -> {
                    deleteExtension(idx);
                    Toast.makeText(this, "削除しました", Toast.LENGTH_SHORT).show();
                    showExtensions();
                });
                row.addView(del);
                root.addView(row);
            }
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(root);

        new AlertDialog.Builder(this)
                .setTitle("拡張機能（ユーザースクリプト）")
                .setView(sv)
                .setPositiveButton("追加", (d, w) -> showAddExtension())
                .setNegativeButton("閉じる", null)
                .setOnDismissListener(d -> {
                    WebView cw = currentWeb();
                    if (cw != null) cw.reload();   // apply enable/disable changes
                })
                .show();
    }

    private void showAddExtension() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, pad, pad, 0);

        final EditText name = new EditText(this);
        name.setHint("名前（任意）");
        name.setSingleLine(true);
        box.addView(name);

        final EditText url = new EditText(this);
        url.setHint("URL（.user.js / .js を取得）");
        url.setSingleLine(true);
        url.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(url);

        TextView or = new TextView(this);
        or.setText("― または コードを貼り付け ―");
        or.setPadding(0, dp(10), 0, dp(4));
        box.addView(or);

        final EditText code = new EditText(this);
        code.setHint("ここに JavaScript を貼り付け");
        code.setGravity(Gravity.TOP);
        code.setMinLines(5);
        code.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        box.addView(code);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("拡張機能を追加")
                .setView(sv)
                .setPositiveButton("追加", (d, w) -> {
                    String n = name.getText().toString().trim();
                    String u = url.getText().toString().trim();
                    String c = code.getText().toString();
                    if (!TextUtils.isEmpty(u)) {
                        downloadExtension(n, u);
                    } else if (!TextUtils.isEmpty(c.trim())) {
                        addExtensionRecord(TextUtils.isEmpty(n) ? "script" : n, c, true);
                        Toast.makeText(this, "追加しました", Toast.LENGTH_SHORT).show();
                        showExtensions();
                    } else {
                        Toast.makeText(this, "URL かコードを入力してください", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void downloadExtension(final String name, final String urlStr) {
        Toast.makeText(this, "取得中…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String code = null, err = null;
            HttpURLConnection con = null;
            try {
                con = (HttpURLConnection) new URL(urlStr).openConnection();
                con.setConnectTimeout(15000);
                con.setReadTimeout(20000);
                con.setInstanceFollowRedirects(true);
                con.setRequestProperty("User-Agent", DESKTOP_UA);
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append('\n');
                    code = sb.toString();
                }
            } catch (Exception e) {
                err = e.getMessage();
            } finally {
                if (con != null) con.disconnect();
            }
            final String fcode = code;
            final String ferr = err;
            runOnUiThread(() -> {
                if (!TextUtils.isEmpty(fcode)) {
                    String nm = TextUtils.isEmpty(name) ? guessExtName(urlStr) : name;
                    addExtensionRecord(nm, fcode, true);
                    Toast.makeText(this, "追加しました: " + nm, Toast.LENGTH_SHORT).show();
                    showExtensions();
                } else {
                    Toast.makeText(this, "取得に失敗しました: " + ferr, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private String guessExtName(String url) {
        try {
            String path = Uri.parse(url).getLastPathSegment();
            if (!TextUtils.isEmpty(path)) return path;
        } catch (Exception ignored) {
        }
        return "script";
    }

    // ---------------- Downloads list ----------------

    private void recordDownload(String name, String url) {
        try {
            JSONArray arr = new JSONArray(prefs.getString("downloads", "[]"));
            JSONObject o = new JSONObject();
            o.put("n", name);
            o.put("u", url);
            o.put("t", System.currentTimeMillis());
            arr.put(o);
            prefs.edit().putString("downloads", arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private void showDownloads() {
        JSONArray arr;
        try {
            arr = new JSONArray(prefs.getString("downloads", "[]"));
        } catch (Exception e) {
            arr = new JSONArray();
        }
        final int n = arr.length();
        final String[] labels = new String[n];
        final String[] urls = new String[n];
        java.text.SimpleDateFormat fmt =
                new java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.getDefault());
        for (int i = 0; i < n; i++) {
            // newest first
            JSONObject o = arr.optJSONObject(n - 1 - i);
            String name = o != null ? o.optString("n", "file") : "file";
            long time = o != null ? o.optLong("t", 0) : 0;
            labels[i] = name + (time > 0 ? "\n" + fmt.format(new java.util.Date(time)) : "");
            urls[i] = o != null ? o.optString("u", "") : "";
        }

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("ダウンロード一覧")
                .setNeutralButton("端末のDL画面を開く", (d, w) -> {
                    try {
                        startActivity(new android.content.Intent(
                                DownloadManager.ACTION_VIEW_DOWNLOADS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception e) {
                        Toast.makeText(this, "ダウンロード画面を開けませんでした",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("閉じる", null);

        if (n == 0) {
            b.setMessage("まだダウンロードはありません。");
        } else {
            b.setItems(labels, (dialog, which) -> {
                // Re-open the source URL of the selected download in a new tab.
                if (!TextUtils.isEmpty(urls[which])) newTab(urls[which]);
            });
            b.setPositiveButton("履歴を消去", (d, w) ->
                    prefs.edit().remove("downloads").apply());
        }
        b.show();
    }

    // ---------------- Bookmarks ----------------

    private JSONArray loadBookmarks() {
        try {
            return new JSONArray(prefs.getString("bookmarks", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void saveBookmarks(JSONArray arr) {
        prefs.edit().putString("bookmarks", arr.toString()).apply();
    }

    private void addCurrentBookmark() {
        Tab t = (current >= 0 && current < tabs.size()) ? tabs.get(current) : null;
        if (t == null || TextUtils.isEmpty(t.url)) return;
        try {
            JSONArray arr = loadBookmarks();
            JSONObject o = new JSONObject();
            o.put("t", TextUtils.isEmpty(t.title) ? t.url : t.title);
            o.put("u", t.url);
            arr.put(o);
            saveBookmarks(arr);
            Toast.makeText(this, "ブックマークに追加しました", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }

    private void showBookmarks() {
        final JSONArray arr = loadBookmarks();
        if (arr.length() == 0) {
            Toast.makeText(this, "ブックマークはまだありません", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] titles = new String[arr.length()];
        final String[] urls = new String[arr.length()];
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            titles[i] = o != null ? o.optString("t", o.optString("u")) : "";
            urls[i] = o != null ? o.optString("u", "") : "";
        }
        new AlertDialog.Builder(this)
                .setTitle("ブックマーク")
                .setItems(titles, (dialog, which) -> newTab(urls[which]))
                .setNeutralButton("削除…", (dialog, which) -> showDeleteBookmarks(titles))
                .setNegativeButton("閉じる", null)
                .show();
    }

    private void showDeleteBookmarks(String[] titles) {
        final boolean[] checked = new boolean[titles.length];
        new AlertDialog.Builder(this)
                .setTitle("削除するブックマークを選択")
                .setMultiChoiceItems(titles, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("削除", (d, w) -> {
                    JSONArray arr = loadBookmarks();
                    JSONArray kept = new JSONArray();
                    for (int i = 0; i < arr.length(); i++) {
                        if (i < checked.length && checked[i]) continue;
                        kept.put(arr.opt(i));
                    }
                    saveBookmarks(kept);
                    Toast.makeText(this, "削除しました", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    // ---------------- Utils ----------------

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
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
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            WebView w = currentWeb();
            if (w != null && w.canGoBack()) {
                w.goBack();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }
}
