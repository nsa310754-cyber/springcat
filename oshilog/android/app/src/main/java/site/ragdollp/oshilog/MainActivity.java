package site.ragdollp.oshilog;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;

import androidx.webkit.WebViewAssetLoader;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
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
 * 画面下部に AdMob のアダプティブ・バナー広告を表示する。広告はオンライン時のみ
 * 読み込まれ、オフラインでもアプリのコア機能はそのまま動作する。
 */
public class MainActivity extends Activity {

    // AdMob バナー広告ユニットID。
    // ※ バナー用の実ユニットIDは未提供のため、現在は Google 公式のテスト用バナーID。
    //    バナー枠を本番配信する場合は AdMob でバナー用ユニットを作成し差し替えてください。
    private static final String AD_UNIT_ID = "ca-app-pub-3940256099942544/6300978111";

    // インタースティシャル(全画面)広告ユニットID（本番）。
    private static final String AD_INTERSTITIAL_ID = "ca-app-pub-8357981710510236/9323149768";

    // インタースティシャルを出しすぎないための制御。
    private static final int   INTERSTITIAL_EVERY_N = 3;      // 3操作ごとに候補
    private static final long  INTERSTITIAL_COOLDOWN_MS = 60_000L; // 前回から60秒は出さない

    private WebView web;
    private AdView adView;
    private InterstitialAd interstitial;
    private boolean interstitialLoading = false;
    private int actionCount = 0;
    private long lastInterstitialAt = 0L;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 縦積みレイアウト: 上に WebView(可変) / 下にバナー広告(固定)。
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        web = new WebView(this);
        LinearLayout.LayoutParams webLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(web, webLp);

        adView = new AdView(this);
        adView.setAdUnitId(AD_UNIT_ID);
        adView.setAdSize(getAdSize());
        LinearLayout.LayoutParams adLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(adView, adLp);

        setContentView(root);

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

        // Mobile Ads SDK を初期化し、バナーとインタースティシャルを読み込む。
        MobileAds.initialize(this, initializationStatus -> {});
        adView.loadAd(new AdRequest.Builder().build());
        loadInterstitial();

        String url = "https://appassets.androidplatform.net/assets/app.html";
        if (savedInstanceState == null) {
            web.loadUrl(url);
        } else {
            web.restoreState(savedInstanceState);
        }
    }

    /** 画面幅に合わせたアダプティブ・バナーのサイズを返す。 */
    private AdSize getAdSize() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int adWidthDp = (int) (dm.widthPixels / dm.density);
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this, adWidthDp);
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

    /** 区切りの操作ごとに呼ばれ、頻度制限を満たす時だけ全画面広告を表示する。 */
    private void maybeShowInterstitial() {
        actionCount++;
        if (actionCount % INTERSTITIAL_EVERY_N != 0) return;
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

    @Override
    protected void onResume() {
        super.onResume();
        if (adView != null) adView.resume();
    }

    @Override
    protected void onPause() {
        if (adView != null) adView.pause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (adView != null) adView.destroy();
        super.onDestroy();
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
