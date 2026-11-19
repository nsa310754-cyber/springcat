package site.springcat.bionicrunner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Chrome の「ホーム画面に追加」など、アプリからの「ホーム画面にショートカットを置いて」という頼みを
 * 受け取る (Android 8 以降。Bionic Runner がホームアプリに設定されているときだけ届く)。
 * 確認してから置く。置いたものは、ホーム画面の表示が「全部」か「APK・AAB・PWA のみ」のときに並ぶ。
 */
public class PinShortcutActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT < 26) {
            finish();
            return;
        }
        LauncherApps la = (LauncherApps) getSystemService(LAUNCHER_APPS_SERVICE);
        final LauncherApps.PinItemRequest req = la == null ? null : la.getPinItemRequest(getIntent());
        if (req == null || !req.isValid() || req.getRequestType() != LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT) {
            finish();
            return;
        }
        ShortcutInfo si = req.getShortcutInfo();
        String label = si == null || si.getShortLabel() == null ? "ショートカット" : si.getShortLabel().toString();
        String from = si == null ? "" : si.getPackage();
        try {
            PackageManager pm = getPackageManager();
            from = pm.getApplicationLabel(pm.getApplicationInfo(from, 0)).toString();
        } catch (PackageManager.NameNotFoundException ignored) {
            // パッケージ名のまま
        }
        final String name = label;
        new AlertDialog.Builder(this)
                .setTitle("ホーム画面に追加")
                .setMessage(from + " から「" + label + "」をホーム画面に追加しますか?")
                .setPositiveButton("追加", (d, w) -> {
                    boolean ok = false;
                    try {
                        ok = req.accept();
                    } catch (RuntimeException ignored) {
                        // 期限切れなど
                    }
                    Toast.makeText(this, ok ? "「" + name + "」をホーム画面に追加しました" : "追加できませんでした",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("キャンセル", null)
                .setOnDismissListener(d -> finish())
                .show();
    }
}
