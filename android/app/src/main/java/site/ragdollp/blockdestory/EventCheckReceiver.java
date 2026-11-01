package site.ragdollp.blockdestory;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 🎉 イベント開始チェック (数時間ごとのアラームで起動)。
 * サーバーから通知を送る仕組みが無いため、端末側で event/current を読みに行き、
 * 新しいイベントが開催中なら1回だけ通知する。通知設定 ON の時だけ動く。
 */
public class EventCheckReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        final Context app = ctx.getApplicationContext();
        final PendingResult pr = goAsync();
        new Thread(new Runnable() {
            @Override public void run() {
                try { DailyNotify.checkEventNow(app); }
                catch (Throwable ignore) { }
                finally { pr.finish(); }
            }
        }).start();
    }
}
