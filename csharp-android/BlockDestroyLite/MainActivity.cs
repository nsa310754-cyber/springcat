using Android.Content.PM;
using Android.Webkit;

namespace BlockDestroyLite;

[Activity(Label = "@string/app_name", MainLauncher = true, ScreenOrientation = ScreenOrientation.Portrait)]
public class MainActivity : Activity
{
    protected override void OnCreate(Bundle? savedInstanceState)
    {
        base.OnCreate(savedInstanceState);

        var webView = new WebView(this);
        webView.Settings.JavaScriptEnabled = true;
        webView.Settings.DomStorageEnabled = true;
        webView.SetWebViewClient(new WebViewClient());
        SetContentView(webView);

        webView.LoadUrl("file:///android_asset/game.html");
    }
}
