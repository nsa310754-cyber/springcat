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
import android.widget.Toast;

/**
 * 常駐通知を出し、通知のボタンからいつでも 4 分割起動できるようにするサービス。
 * 透明ディスプレイ (HiddenDisplay) もこのプロセスが生きている間だけ存在するため、
 * 透明アプリを動かしている間はこのサービスで常駐し続ける。
 */
public class SplitService extends Service {
    private static final String CHANNEL = "split4";
    private static final int NOTIFICATION_ID = 1;
    private static final String ACTION_RESTORE = "site.ragdollp.split4.RESTORE_HIDDEN";
    private static final String ACTION_CLOSE = "site.ragdollp.split4.CLOSE_HIDDEN";

    static boolean running;

    static void start(Context c) {
        c.startForegroundService(new Intent(c, SplitService.class));
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, SplitService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_RESTORE.equals(action)) {
            SplitLauncher.restoreHidden(msg -> {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                showNotification();
            });
        } else if (ACTION_CLOSE.equals(action)) {
            SplitLauncher.closeHidden();
            Toast.makeText(this, "透明アプリを終了しました", Toast.LENGTH_SHORT).show();
        }
        showNotification();
        running = true;
        return START_STICKY;
    }

    private void showNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "常駐通知", NotificationManager.IMPORTANCE_LOW));

        int piFlags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent launch = PendingIntent.getActivity(this, 1,
                new Intent(this, LaunchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), piFlags);
        PendingIntent open = PendingIntent.getActivity(this, 2,
                new Intent(this, MainActivity.class), piFlags);

        boolean hidden = HiddenDisplay.isActive();
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_grid)
                .setContentTitle("4分割ランチャー")
                .setContentText(hidden ? "透明アプリが動作中" : "タップで設定 / ボタンで4分割起動")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "4分割で起動", launch).build());
        if (hidden) {
            b.addAction(new Notification.Action.Builder(null, "画面に戻す", service(ACTION_RESTORE, 3)).build());
            b.addAction(new Notification.Action.Builder(null, "透明アプリを終了", service(ACTION_CLOSE, 4)).build());
        }
        Notification n = b.build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private PendingIntent service(String action, int requestCode) {
        return PendingIntent.getService(this, requestCode,
                new Intent(this, SplitService.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public void onDestroy() {
        running = false;
        HiddenDisplay.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
