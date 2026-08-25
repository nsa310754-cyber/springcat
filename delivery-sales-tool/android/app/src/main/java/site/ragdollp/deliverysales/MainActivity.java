package site.ragdollp.deliverysales;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 配達員 営業リスト作成ツール — Android ラッパー（WebView 方式・PWAではない本物のAPK）。
 *
 * UI 本体は assets/index.html（単体版と同一）を WebView に読み込む。
 * ブラウザだと CORS で X API を直接叩けないため、ネイティブ HTTP ブリッジ
 * （window.NativeHTTP.request）を JS に注入し、X/Gemini への通信を
 * ネイティブ側（HttpURLConnection）で実行して CORS を回避する。
 */
public class MainActivity extends Activity {

    private WebView webView;
    private final ExecutorService http = Executors.newFixedThreadPool(4);

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage を使う
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);

        // 外部リンク（プロフィール/投稿URL）は既定ブラウザで開く
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

        // ネイティブ HTTP ブリッジを注入
        webView.addJavascriptInterface(new NativeHttp(), "NativeHTTP");

        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    /** JS から呼べる HTTP ブリッジ（CORS 制約を受けない）。 */
    private class NativeHttp {
        /**
         * @param reqJson {"method":"GET|POST","url":"...","headers":{..},"body":"..."}
         * @param callback JS 側のグローバル関数名。結果 {"status":int,"body":string} を
         *                 JSON 文字列として1引数で渡す。
         */
        @JavascriptInterface
        public void request(final String reqJson, final String callback) {
            http.execute(() -> {
                JSONObject result = new JSONObject();
                try {
                    JSONObject req = new JSONObject(reqJson);
                    String method = req.optString("method", "GET");
                    String urlStr = req.getString("url");
                    String body = req.optString("body", null);
                    JSONObject headers = req.optJSONObject("headers");

                    HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                    conn.setRequestMethod(method);
                    conn.setConnectTimeout(20000);
                    conn.setReadTimeout(40000);
                    if (headers != null) {
                        Iterator<String> it = headers.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            conn.setRequestProperty(k, headers.getString(k));
                        }
                    }
                    if (body != null && !"GET".equalsIgnoreCase(method)) {
                        conn.setDoOutput(true);
                        try (OutputStream os = conn.getOutputStream()) {
                            os.write(body.getBytes(StandardCharsets.UTF_8));
                        }
                    }

                    int status = conn.getResponseCode();
                    InputStream is = (status >= 400) ? conn.getErrorStream() : conn.getInputStream();
                    String respBody = readAll(is);
                    conn.disconnect();

                    result.put("status", status);
                    result.put("body", respBody == null ? "" : respBody);
                } catch (Exception e) {
                    try {
                        result.put("status", 0);
                        result.put("body", "{\"error\":{\"message\":\"" + escape(e.getMessage()) + "\"}}");
                    } catch (Exception ignore) {}
                }
                deliver(callback, result.toString());
            });
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "unknown error";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 結果を UI スレッドで JS コールバックに渡す。 */
    private void deliver(final String callback, final String jsonResult) {
        final String arg = JSONObject.quote(jsonResult); // JS 文字列リテラル化
        runOnUiThread(() -> webView.evaluateJavascript(callback + "(" + arg + ")", null));
    }
}
