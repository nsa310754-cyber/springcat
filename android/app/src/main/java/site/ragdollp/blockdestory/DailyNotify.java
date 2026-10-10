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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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

    // 🎉 イベント告知(FCM プッシュ)用チャンネル
    static final String EVENT_CHANNEL_ID = "events";
    static final int    EVENT_NOTIF_ID   = 7002;
    // 🎉 イベント開始チェック (サーバーからの送信が無いため端末側で定期確認)
    static final int    EVENT_ALARM_REQ  = 7102;
    static final String K_EVENT_SEEN     = "event_seen";
    static final long   EVENT_CHECK_MS   = 3L * 60 * 60 * 1000;          // 3時間ごと
    static final long   EVENT_RUN_MS     = 7L * 24 * 60 * 60 * 1000;     // 開催期間 7日
    // event/current は誰でも読める (ルール: ".read": true)
    static final String EVENT_URL =
            "https://blockdestory-499622-default-rtdb.asia-southeast1.firebasedatabase.app/event/current/startedAt.json";

    private DailyNotify() { }

    static void ensureEventChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(EVENT_CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        EVENT_CHANNEL_ID, "イベント", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("ゲームのイベント告知");
                nm.createNotificationChannel(ch);
            }
        }
    }

    /** イベント通知を1件表示。タップでアプリを開く。 */
    static void postEvent(Context ctx, String title, String body) {
        if (!hasPermission(ctx)) return;
        ensureEventChannel(ctx);

        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(ctx, 1, open, flags);

        String t = (title == null || title.trim().isEmpty()) ? "🎉 Block Destroy" : title;
        String b = (body == null || body.trim().isEmpty()) ? "新しいイベントが開催中です！" : body;

        Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(ctx, EVENT_CHANNEL_ID)
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
        if (nm != null) nm.notify(EVENT_NOTIF_ID, n);
    }

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
        scheduleEventCheck(ctx);
    }

    private static PendingIntent eventAlarmPI(Context ctx) {
        Intent i = new Intent(ctx, EventCheckReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, EVENT_ALARM_REQ, i, flags);
    }

    /** イベント開始チェックを3時間ごとに登録 (不正確反復=電池に優しい)。 */
    static void scheduleEventCheck(Context ctx) {
        ensureEventChannel(ctx);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(eventAlarmPI(ctx));
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 15L * 60 * 1000, EVENT_CHECK_MS, eventAlarmPI(ctx));
    }

    /** 通知 ON ならイベントチェックを (再)登録する。アプリ起動時に呼ぶ。 */
    static void rescheduleEventCheckIfEnabled(Context ctx) {
        if (ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_ENABLED, false)) {
            scheduleEventCheck(ctx);
        }
    }

    /**
     * event/current/startedAt を読み、開催中 (開始から7日以内) の新しいイベントなら1回だけ通知する。
     * ⚠️ ネットワーク処理なのでメインスレッドから呼ばないこと。
     */
    static void checkEventNow(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean(K_ENABLED, false)) return;     // 通知 OFF なら何もしない
        long startedAt = fetchEventStartedAt();
        if (startedAt <= 0) return;
        long now = System.currentTimeMillis();
        if (now < startedAt || now - startedAt >= EVENT_RUN_MS) return;   // 開催中のみ
        if (p.getLong(K_EVENT_SEEN, 0) == startedAt) return;               // 通知済み
        p.edit().putLong(K_EVENT_SEEN, startedAt).apply();
        postEvent(ctx, "🏆 ランキングイベント開催中！",
                "20×20モードの1週間ランキングイベントが始まりました。上位10位までに💎と🔑の報酬があります！");
    }

    private static long fetchEventStartedAt() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(EVENT_URL).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            if (c.getResponseCode() != 200) return 0;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[256];
            int n;
            while ((n = in.read(buf)) > 0 && bo.size() < 4096) bo.write(buf, 0, n);
            in.close();
            String s = bo.toString("UTF-8").trim();
            if (s.isEmpty() || "null".equals(s)) return 0;
            return (long) Double.parseDouble(s);
        } catch (Throwable e) {
            return 0;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static void cancel(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(K_ENABLED, false).apply();
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) { am.cancel(alarmPI(ctx)); am.cancel(eventAlarmPI(ctx)); }
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
