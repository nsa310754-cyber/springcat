package site.ragdollp.springcraft;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * SpringCat の Minecraft 統合版アドオン (assets/SpringCat.mcaddon) を、
 * Google Play にある他のアドオン配布アプリと同じ方式でインストールするアプリ。
 *
 * 手順:
 *  1. アプリ内蔵の .mcaddon をキャッシュ領域へコピー (assets は content:// で直接共有できないため)
 *  2. FileProvider で content:// URI を発行し、MIME "application/mcaddon" を付けて
 *     ACTION_VIEW インテントを Minecraft (com.mojang.minecraftpe) に送る
 *  3. Minecraft 側のインポート確認 UI がアドオンの取り込みを引き継ぐ
 */
public class MainActivity extends Activity {

    private static final String TAG = "SpringCraft";
    private static final String MINECRAFT_PACKAGE = "com.mojang.minecraftpe";
    private static final String ASSET_ADDON_NAME = "SpringCat.mcaddon";
    private static final String FILE_PROVIDER_AUTHORITY = "site.ragdollp.springcraft.fileprovider";

    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.status);
        Button btnInstall = findViewById(R.id.btnInstall);
        Button btnLaunch = findViewById(R.id.btnLaunch);
        Button btnGetMinecraft = findViewById(R.id.btnGetMinecraft);

        btnInstall.setOnClickListener(v -> installAddon());
        btnLaunch.setOnClickListener(v -> launchMinecraft());
        btnGetMinecraft.setOnClickListener(v -> openMinecraftStorePage());
    }

    private boolean isMinecraftInstalled() {
        try {
            getPackageManager().getPackageInfo(MINECRAFT_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void installAddon() {
        if (!isMinecraftInstalled()) {
            setStatus(getString(R.string.status_no_minecraft));
            findViewById(R.id.btnGetMinecraft).setVisibility(View.VISIBLE);
            return;
        }

        File addonFile;
        try {
            addonFile = copyAddonToCache();
        } catch (IOException e) {
            Log.e(TAG, "failed to stage addon", e);
            setStatus(getString(R.string.status_error));
            return;
        }

        Uri contentUri = FileProvider.getUriForFile(this, FILE_PROVIDER_AUTHORITY, addonFile);

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(contentUri, "application/mcaddon");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setPackage(MINECRAFT_PACKAGE);

        try {
            startActivity(intent);
            setStatus(getString(R.string.status_handed_off));
        } catch (ActivityNotFoundException e) {
            // Minecraft がこの MIME タイプ向けの明示的ハンドラを持たない端末向けフォールバック。
            // package 指定を外し、Android のアプリ選択に委ねる。
            intent.setPackage(null);
            try {
                startActivity(intent);
                setStatus(getString(R.string.status_handed_off));
            } catch (ActivityNotFoundException e2) {
                Log.e(TAG, "no activity to handle mcaddon", e2);
                setStatus(getString(R.string.status_error));
                Toast.makeText(this, R.string.status_error, Toast.LENGTH_LONG).show();
            }
        }
    }

    private File copyAddonToCache() throws IOException {
        File dir = new File(getCacheDir(), "mcaddon");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("cannot create cache dir: " + dir);
        }
        File out = new File(dir, ASSET_ADDON_NAME);
        try (InputStream in = getAssets().open(ASSET_ADDON_NAME);
             OutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
        }
        return out;
    }

    private void launchMinecraft() {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(MINECRAFT_PACKAGE);
        if (launchIntent == null) {
            setStatus(getString(R.string.status_no_minecraft));
            findViewById(R.id.btnGetMinecraft).setVisibility(View.VISIBLE);
            return;
        }
        try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launchIntent);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "failed to launch minecraft", e);
            setStatus(getString(R.string.status_launch_failed));
        }
    }

    private void openMinecraftStorePage() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + MINECRAFT_PACKAGE)));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + MINECRAFT_PACKAGE)));
        }
    }

    private void setStatus(String text) {
        statusView.setText(text);
    }
}
