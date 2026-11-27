package site.springcat.bionicrunner;

import android.webkit.JavascriptInterface;

/**
 * 実行中のページ (assets/run/harness.js) とアプリの間の窓口。ページからは window.BR として見える。
 * ページ側から呼ばれるのはブラウザ部品の専用スレッドなので、受け取る側で UI スレッドへ渡すこと。
 */
final class RunBridge {

    interface Listener {
        void onOutput(String text, boolean error);

        void onExit(int code);
    }

    private final String kind, mode, name, source;
    private final Listener listener;

    /** kind は "html" / "js" / "ts"、mode は "run" / "game" */
    RunBridge(String kind, String mode, String name, String source, Listener listener) {
        this.kind = kind;
        this.mode = mode;
        this.name = name;
        this.source = source;
        this.listener = listener;
    }

    @JavascriptInterface
    public String kind() {
        return kind;
    }

    @JavascriptInterface
    public String mode() {
        return mode;
    }

    @JavascriptInterface
    public String name() {
        return name;
    }

    @JavascriptInterface
    public String source() {
        return source;
    }

    @JavascriptInterface
    public void out(String text) {
        listener.onOutput(text, false);
    }

    @JavascriptInterface
    public void err(String text) {
        listener.onOutput(text, true);
    }

    @JavascriptInterface
    public void exit(int code) {
        listener.onExit(code);
    }

    /** ページに最初に読み込ませる補助スクリプトのタグ。HTML のファイルには、これを差し込んでから表示する */
    static final String HARNESS_TAG =
            "<script charset=\"utf-8\" src=\"file:///android_asset/run/harness.js\"></script>";
    static final String HOST_PAGE = "file:///android_asset/run/host.html";

    /**
     * HTML に補助スクリプトを差し込む。行番号がずれないよう、改行は足さない。
     * head があればその先頭、無ければ html の直後、それも無ければ文書の先頭に入れる。
     */
    static String inject(String html) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<head(\\s[^>]*)?>", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find()) return html.substring(0, m.end()) + HARNESS_TAG + html.substring(m.end());
        m = java.util.regex.Pattern
                .compile("<html(\\s[^>]*)?>", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find()) return html.substring(0, m.end()) + HARNESS_TAG + html.substring(m.end());
        return HARNESS_TAG + html;
    }

    /** ブラウザ部品を、利用者のプログラムを動かす用に設定して、窓口をつなぐ */
    @android.annotation.SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    static void attach(android.webkit.WebView web, RunBridge bridge) {
        android.webkit.WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        // 同じフォルダの画像やスクリプトを読めるようにする (HTML のファイルを開いたとき)
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(bridge, "BR");
    }

    /**
     * 読み込みを始める。html は中身に補助スクリプトを差し込んで表示し、js / ts は土台のページを開く
     * (土台のページが窓口から中身を受け取って実行する)。baseDir は元のファイルがあるフォルダ。
     */
    static void load(android.webkit.WebView web, RunBridge bridge, java.io.File baseDir) {
        if (bridge.kind.equals("html")) {
            String base = baseDir != null && baseDir.canRead()
                    ? android.net.Uri.fromFile(baseDir).toString() + "/" : "file:///android_asset/run/";
            // SVG は絵そのものなので、HTML の中に入れて表示する
            String html = bridge.name.toLowerCase(java.util.Locale.ROOT).endsWith(".svg")
                    ? "<!doctype html><html><head><meta charset=\"utf-8\"></head>"
                    + "<body style=\"margin:0;background:#fff\">" + bridge.source + "</body></html>"
                    : bridge.source;
            web.loadDataWithBaseURL(base, inject(html), "text/html", "UTF-8", null);
        } else {
            web.loadUrl(HOST_PAGE);
        }
    }
}
