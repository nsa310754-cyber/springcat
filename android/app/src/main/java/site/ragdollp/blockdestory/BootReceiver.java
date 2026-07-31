package site.ragdollp.blockdestory;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 端末再起動後にデイリー通知のアラームを再登録する(アラームは再起動で消えるため)。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)
                || "android.intent.action.LOCKED_BOOT_COMPLETED".equals(a)) {
            DailyNotify.rescheduleIfEnabled(ctx);
        }
    }
}
