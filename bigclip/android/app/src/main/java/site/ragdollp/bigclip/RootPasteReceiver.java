package site.ragdollp.bigclip;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * 通知の「root で貼り付け」: 通知シェードを閉じ、前面アプリの入力欄へ KEYCODE_PASTE(279) を送る。
 * 長押しメニューに「貼り付け」が出ないアプリや、メニュー経由だと失敗するアプリ向け。
 */
public class RootPasteReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        PendingResult pr = goAsync();
        Context app = context.getApplicationContext();
        new Thread(() -> {
            RootShell.Result r = RootShell.run(
                    "cmd statusbar collapse 2>/dev/null || service call statusbar 2 >/dev/null\n"
                            + "sleep 0.6\n"
                            + "input keyevent 279");
            if (!r.ok()) {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        Toast.makeText(app, "root 貼り付けに失敗: " + r.out, Toast.LENGTH_LONG).show());
            }
            pr.finish();
        }).start();
    }
}
