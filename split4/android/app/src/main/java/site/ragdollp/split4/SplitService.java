package site.ragdollp.split4;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** 常駐通知を出し、通知のボタンからいつでも 4 分割起動できるようにするサービス。 */
public class SplitService extends Service {
    private static final String CHANNEL = "split4";
    private static final int NOTIFICATION_ID = 1;

    static boolean running;

    static void start(Context c) {
        c.startForegroundService(new Intent(c, SplitService.class));
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, SplitService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "常駐通知", NotificationManager.IMPORTANCE_LOW));

        int piFlags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent launch = PendingIntent.getActivity(this, 1,
                new Intent(this, LaunchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), piFlags);
        PendingIntent open = PendingIntent.getActivity(this, 2,
                new Intent(this, MainActivity.class), piFlags);

        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_grid)
                .setContentTitle("4分割ランチャー")
                .setContentText("タップで設定 / ボタンで4分割起動")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "4分割で起動", launch).build())
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        running = true;
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
