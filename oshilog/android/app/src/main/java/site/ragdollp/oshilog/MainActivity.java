package site.ragdollp.oshilog;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;

/**
 * OshiLog — WebView ラッパー本体。
 *
 * 同梱した assets/app.html を https://appassets.androidplatform.net/assets/app.html
 * として配信する。file:// ではなく正規オリジンで配信することで localStorage が
 * 安定して使える (推し・予定・記録などの端末内保存に対応するため)。
 *
 * 広告は AdMob のインタースティシャル(全画面)のみ。画面の区切り(保存・タブ切替)で
 * 呼ばれ、前回表示から一定時間(5分)経っている時だけ表示する。広告はオンライン時のみ
 * 読み込まれ、オフラインでもアプリのコア機能はそのまま動作する。
 */
public class MainActivity extends Activity {

    // インタースティシャル(全画面)広告ユニットID（本番）。
    private static final String AD_INTERSTITIAL_ID = "ca-app-pub-8357981710510236/9323149768";

    // 前回表示からこの時間が経っていない間は出さない（5分）。
    private static final long INTERSTITIAL_COOLDOWN_MS = 5 * 60 * 1000L;

    private WebView web;
    private InterstitialAd interstitial;
    private boolean interstitialLoading = false;
    private long lastInterstitialAt = 0L;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);

        web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // localStorage (保存データ)
        s.setDatabaseEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);                    // 端末のフォント倍率でレイアウトを崩さない

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
        });

        // Webアプリ(app.html)から「区切りとなる操作」を受け取るためのブリッジ。
        // 同梱アセット(信頼済みオリジン)のみを読み込むため公開しても安全。
        web.addJavascriptInterface(new AdBridge(), "AndroidBridge");

        // 起動直後には出さず、最初の広告は起動から5分後以降にする。
        lastInterstitialAt = System.currentTimeMillis();

        // Mobile Ads SDK を初期化し、インタースティシャルを先読みする。
        MobileAds.initialize(this, initializationStatus -> {});
        loadInterstitial();

        String url = "https://appassets.androidplatform.net/assets/app.html";
        if (savedInstanceState == null) {
            web.loadUrl(url);
        } else {
            web.restoreState(savedInstanceState);
        }
    }

    /** インタースティシャルを1つ先読みしておく。 */
    private void loadInterstitial() {
        if (interstitialLoading || interstitial != null) return;
        interstitialLoading = true;
        InterstitialAd.load(this, AD_INTERSTITIAL_ID, new AdRequest.Builder().build(),
                new InterstitialAdLoadCallback() {
                    @Override
                    public void onAdLoaded(InterstitialAd ad) {
                        interstitial = ad;
                        interstitialLoading = false;
                    }
                    @Override
                    public void onAdFailedToLoad(LoadAdError error) {
                        interstitial = null;
                        interstitialLoading = false;
                    }
                });
    }

    /** 画面の区切りごとに呼ばれ、前回表示から5分以上なら全画面広告を表示する。 */
    private void maybeShowInterstitial() {
        if (System.currentTimeMillis() - lastInterstitialAt < INTERSTITIAL_COOLDOWN_MS) return;
        if (interstitial == null) { loadInterstitial(); return; }

        interstitial.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override
            public void onAdDismissedFullScreenContent() { interstitial = null; loadInterstitial(); }
            @Override
            public void onAdFailedToShowFullScreenContent(AdError adError) { interstitial = null; loadInterstitial(); }
        });
        lastInterstitialAt = System.currentTimeMillis();
        interstitial.show(this);
    }

    /** app.html から呼び出される JS ブリッジ。 */
    private class AdBridge {
        @JavascriptInterface
        public void onAction() {
            runOnUiThread(MainActivity.this::maybeShowInterstitial);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    // 端末の戻るボタンで WebView の履歴を戻す。
    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
