package site.ragdollp.blockdestory;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 毎日のアラーム発火で通知を出す。 */
public class DailyNotifyReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        DailyNotify.post(ctx, "🎁 Block Destroy", "今日もデイリー報酬を受け取ろう！");
    }
}
