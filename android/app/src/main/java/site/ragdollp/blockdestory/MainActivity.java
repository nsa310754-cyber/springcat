package site.ragdollp.blockdestory;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebChromeClient;
import android.webkit.WebViewClient;

/**
 * Block Destroy を Android アプリとして動かすための WebView ラッパー。
 *
 * ゲーム本体 (assets/game.html) は完全にオフラインで動作する。
 * Firebase の世界ランキング / 広告 / reCAPTCHA などオンライン機能は
 * ネットワークが無ければ静かに失敗し、コアのゲームプレイには影響しない。
 *
 * 重要:
 *   game.html は「HTML ビューア / 簡易ブラウザ」対策として、UserAgent に
 *   "BlockdestoryApp" が含まれない WebView をブロックする実装を持つ
 *   (game.html 内の開発メモに明記)。そのため下で UserAgent の末尾に
 *   " BlockdestoryApp/1" を必ず付与する。これが無いと起動直後に
 *   「この環境ではゲームを遊べません」画面が出て起動できない。
 */
public class MainActivity extends Activity {

    private WebView webView;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // プレイ中に画面が消えないように
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage = セーブデータ保存に必須
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);            // file:///android_asset/ の読込
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        // ★ ゲームの WebView ブロック回避フック (game.html の設計に合わせる)
        s.setUserAgentString(s.getUserAgentString() + " BlockdestoryApp/1");

        // WebView 内リンクはアプリ内で開き、外部リンクだけブラウザに委ねる等は最小構成。
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            // ローカル同梱の HTML を読み込む → 完全オフライン再生
            webView.loadUrl("file:///android_asset/game.html");
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            webView.saveState(outState);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) {
            webView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    public void onBackPressed() {
        // ゲーム内で戻れるうちは WebView の履歴を戻る
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
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
