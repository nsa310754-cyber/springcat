package site.ragdollp.blockdestory;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.Calendar;

/**
 * デイリーボーナス通知(ネイティブ)。
 * WebView は Web Notification / getDisplayMedia 等が無いため、毎日の通知は
 * Android の AlarmManager + NotificationManager で行う(アプリを閉じても届く)。
 */
final class DailyNotify {

    static final String PREFS      = "daily_notify";
    static final String K_ENABLED  = "enabled";
    static final String K_HOUR     = "hour";
    static final String K_MIN      = "min";
    static final String CHANNEL_ID = "daily_bonus";
    static final int    NOTIF_ID   = 7001;
    static final int    ALARM_REQ  = 7101;

    private DailyNotify() { }

    static boolean hasPermission(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "デイリーボーナス", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("毎日のデイリーボーナスのお知らせ");
                nm.createNotificationChannel(ch);
            }
        }
    }

    private static PendingIntent alarmPI(Context ctx) {
        Intent i = new Intent(ctx, DailyNotifyReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, ALARM_REQ, i, flags);
    }

    /** 毎日 hour:min に通知するよう登録(不正確反復=電池に優しい)。 */
    static void schedule(Context ctx, int hour, int min) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit().putBoolean(K_ENABLED, true).putInt(K_HOUR, hour).putInt(K_MIN, min).apply();
        ensureChannel(ctx);

        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, min);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        am.cancel(alarmPI(ctx));
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP,
                c.getTimeInMillis(), AlarmManager.INTERVAL_DAY, alarmPI(ctx));
    }

    static void cancel(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(K_ENABLED, false).apply();
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(alarmPI(ctx));
    }

    /** 端末再起動後などに、有効なら再登録する。 */
    static void rescheduleIfEnabled(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.getBoolean(K_ENABLED, false)) {
            schedule(ctx, p.getInt(K_HOUR, 20), p.getInt(K_MIN, 0));
        }
    }

    /** 通知を1件表示。タップでアプリを開く。 */
    static void post(Context ctx, String title, String body) {
        if (!hasPermission(ctx)) return;
        ensureChannel(ctx);

        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, flags);

        String t = (title == null || title.trim().isEmpty()) ? "🎁 Block Destroy" : title;
        String b = (body == null || body.trim().isEmpty()) ? "今日もデイリー報酬を受け取ろう！" : body;

        Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(ctx, CHANNEL_ID)
                : new Notification.Builder(ctx);
        Notification n = builder
                .setContentTitle(t)
                .setContentText(b)
                .setStyle(new Notification.BigTextStyle().bigText(b))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, n);
    }
}
