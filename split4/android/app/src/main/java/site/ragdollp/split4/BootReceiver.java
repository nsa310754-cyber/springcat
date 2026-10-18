package site.ragdollp.split4;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 「起動時に常駐」が ON なら端末起動時に常駐通知サービスを開始する。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) && new Prefs(c).startOnBoot()) {
            SplitService.start(c);
        }
    }
}
