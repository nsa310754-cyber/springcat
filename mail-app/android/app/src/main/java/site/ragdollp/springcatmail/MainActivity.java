package site.ragdollp.springcatmail;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

public class MainActivity extends Activity {

    private static final String APP_URL = "https://springcat-mail.nsa310754.workers.dev/";
    private static final String APP_HOST = Uri.parse(APP_URL).getHost();
    private static final String CHANNEL_ID = "important_mail";
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 1001;

    // Preference order for opening links that lead outside the app (e.g.
    // links tapped inside an email). Apple/Safari has no Android package —
    // that preference only makes sense for an iOS build of this app.
    private static final String[] PREFERRED_BROWSER_PACKAGES = {
            "com.android.chrome",  // Chrome
            "com.amazon.cloud9",   // Amazon Silk
            "com.microsoft.emmx",  // Microsoft Edge
    };

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createNotificationChannel();

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri.getHost() != null && uri.getHost().equalsIgnoreCase(APP_HOST)) {
                    return false; // keep in-app navigation (the SPA itself) inside the WebView
                }
                openInPreferredBrowser(uri);
                return true;
            }
        });
        // Exposed to the web app as `window.AndroidNotify`. The web app's
        // JS-only Notification API doesn't exist inside a bare WebView, so
        // this bridge lets it trigger a real Android notification instead.
        webView.addJavascriptInterface(new NotificationBridge(), "AndroidNotify");

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(APP_URL);
        }
    }

    private void openInPreferredBrowser(Uri uri) {
        PackageManager pm = getPackageManager();
        for (String pkg : PREFERRED_BROWSER_PACKAGES) {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.setPackage(pkg);
            if (intent.resolveActivity(pm) != null) {
                try {
                    startActivity(intent);
                    return;
                } catch (Exception ignored) {
                    // Try the next preferred browser.
                }
            }
        }
        // None of the preferred browsers are installed; fall back to
        // whatever the system considers the default handler.
        Intent fallback = new Intent(Intent.ACTION_VIEW, uri);
        if (fallback.resolveActivity(pm) != null) {
            startActivity(fallback);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "重要メール", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("springcat mail の重要メール通知");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private class NotificationBridge {
        @JavascriptInterface
        public boolean hasPermission() {
            return NotificationManagerCompat.from(MainActivity.this).areNotificationsEnabled();
        }

        @JavascriptInterface
        public void requestPermission() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runOnUiThread(() -> {
                    if (ActivityCompat.checkSelfPermission(MainActivity.this,
                            Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(MainActivity.this,
                                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                                NOTIFICATION_PERMISSION_REQUEST_CODE);
                    }
                });
            }
            // Pre-Android 13, notification permission is granted implicitly
            // (subject to the user's system-level app notification toggle).
        }

        @JavascriptInterface
        public void postNotification(String title, String body, String tag) {
            runOnUiThread(() -> {
                Intent intent = new Intent(MainActivity.this, MainActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
                PendingIntent pendingIntent = PendingIntent.getActivity(
                        MainActivity.this, tag.hashCode(), intent, flags);

                NotificationCompat.Builder builder = new NotificationCompat.Builder(MainActivity.this, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_dialog_email)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent)
                        .setPriority(NotificationCompat.PRIORITY_HIGH);

                if (ActivityCompat.checkSelfPermission(MainActivity.this,
                        Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                        || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    NotificationManagerCompat.from(MainActivity.this).notify(tag.hashCode(), builder.build());
                }
            });
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }
}
