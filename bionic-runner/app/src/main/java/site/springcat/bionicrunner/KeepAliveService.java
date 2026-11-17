package site.springcat.bionicrunner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 画面の上に表示を出しているあいだや、自動操作のプログラムが動いているあいだ、アプリを生かしておく
 * ための常駐サービス。これが無いと、別のアプリを前面にした時点で Android がこのアプリを止めてしまい、
 * 表示が消えたりプログラムが止まったりする。
 *
 * 動いているあいだは通知を出す (Android の決まり)。通知の「停止」で、表示をすべて消して終わる。
 */
public class KeepAliveService extends Service {

    private static final String CHANNEL = "keepalive";
    private static final String ACTION_STOP = "site.springcat.bionicrunner.STOP_KEEPALIVE";
    private static final String ACTION_STOP_ALL = "site.springcat.bionicrunner.STOP_ALL";
    private static volatile boolean running;

    static boolean isRunning() {
        return running;
    }

    /** 動いていなければ始める。裏からは始められない端末の状態もあるので、失敗しても先へ進む */
    static void ensureStarted(Context c) {
        if (running) return;
        try {
            Intent i = new Intent(c, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (RuntimeException ignored) {
            // 始められなかった: アプリが裏に回ると止められるかもしれないが、前面にいる間は動く
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, KeepAliveService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_ALL.equals(intent.getAction())) {
            // 実行中のプログラムをすべて終了 (bot などを、そのコマンドプロンプトへ行かずに止める)
            final Context app = getApplicationContext();
            new Thread(() -> {
                final String msg = StopAll.run(app);
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_LONG).show());
            }, "stop-all").start();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Control.stopAll(this); // 表示を消す。このサービスも止まる
            return START_NOT_STICKY;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "実行中のプログラム",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("画面の上への表示や自動操作のプログラムが動いているあいだ表示します");
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, KeepAliveService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopAll = PendingIntent.getService(this, 2,
                new Intent(this, KeepAliveService.class).setAction(ACTION_STOP_ALL),
                PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification n = b.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Bionic Runner: プログラムが動いています")
                .setContentText("「すべて終了」で、実行中のプログラム (bot など) をまとめて止めます")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "すべて終了", stopAll).build())
                .addAction(new Notification.Action.Builder(null, "表示だけ停止", stop).build())
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(1, n);
            }
            running = true;
        } catch (RuntimeException e) {
            stopSelf(); // 裏から始めようとして断られた
        }
        return START_NOT_STICKY;
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
