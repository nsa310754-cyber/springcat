package site.ragdollp.codeconv;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * codeconv — オフライン言語コンバーターの WebView ラッパー。
 *
 * assets 内の index.html / convert.js を file:///android_asset から読み込む。
 * 変換処理はすべて WebView 内の JavaScript で完結し、ネットワークは使用しない
 * （AndroidManifest に INTERNET 権限も宣言していない）。
 */
public class MainActivity extends Activity {

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);           // 変換ロジックの実行に必要
        s.setDomStorageEnabled(true);           // テーマ設定の保存（localStorage）
        // ネットワークは使わないのでキャッシュ/オンライン系は既定のまま。
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(false);

        // 外部リンク（例: SpringCat 本サイト）はアプリ内 WebView で開かず既定ブラウザへ。
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && url.startsWith("file:///android_asset/")) {
                    return false; // 同梱ページ内の遷移はそのまま
                }
                try {
                    startActivity(new android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(url)));
                } catch (Exception ignored) {}
                return true;
            }
        });

        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
