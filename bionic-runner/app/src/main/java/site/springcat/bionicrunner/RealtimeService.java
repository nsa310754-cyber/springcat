package site.springcat.bionicrunner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * リアルタイム保護。ファイルが新しく置かれたり書き換えられたりしたら、すぐに検査する
 * ({@link AvScanner}: 定義のハッシュ値・テスト文字列・YARA ルール・危険な操作の記述)。
 * 脅威なら (設定により) 自動で隔離して通知し、疑わしいものは通知だけする。
 *
 * 見張る場所: 内部ストレージのダウンロード・ドキュメント・Bluetooth・Android/media (メッセージアプリが
 * 受け取ったファイルの置き場所) と、このアプリに登録したプログラムの置き場所。
 * 常駐して通知を出す (Android の決まり)。端末を起動したときにも、オンなら自動で始まる。
 *
 * 新しいファイルの見つけ方は 3 つ (どれかで気づけばよい。同じファイルを二度は調べない):
 *   1. ファイルの変化の知らせ (FileObserver)
 *   2. Android のメディアの一覧 (MediaStore) の変化。ブラウザやダウンロードの仕組みが保存したファイルは、
 *      1 の知らせが来ないことがあるため
 *   3. 20 秒ごとの見回り: 中身が変わったフォルダだけを開いて、新しいファイルを探す
 * 内部ストレージのほかのアプリが置いたファイルを読むには「全ファイルへのアクセス」が要る
 * (無ければ通知でそう知らせる)。
 */
public class RealtimeService extends Service {

    private static final String CHANNEL = "security";
    private static final String ALERTS = "security-alerts";
    private static final int MAX_DIRS = 3000;
    private static volatile boolean running;

    private HandlerThread thread;
    private Handler worker;
    private final Map<String, FileObserver> observers = new HashMap<>();
    private final Set<String> pending = new LinkedHashSet<>();
    private int scanned, found;
    /** 調べたファイル (場所 → 大きさと更新時刻)。同じ中身のまま二度調べないため */
    private final Map<String, String> checked = new java.util.LinkedHashMap<String, String>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> e) {
            return size() > 5000;
        }
    };
    /** 見回り (3) で、前回より後に変わったフォルダだけを開くための時刻 */
    private long lastSweep;
    private static final long SWEEP_MS = 20_000;
    private android.database.ContentObserver media;
    private long mediaSince;

    static boolean isRunning() {
        return running;
    }

    static void sync(Context c) {
        Intent i = new Intent(c, RealtimeService.class);
        try {
            if (Prefs.avRealtime(c)) {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
                else c.startService(i);
            } else {
                c.stopService(i);
            }
        } catch (RuntimeException ignored) {
            // 裏からは始められない状態: 次にアプリを開いたときに始まる
        }
    }

    /** 見張る場所 */
    static List<File> roots(Context c) {
        File sd = Environment.getExternalStorageDirectory();
        List<File> r = new ArrayList<>();
        for (String d : new String[]{"Download", "Documents", "Bluetooth", "Android/media"}) r.add(new File(sd, d));
        r.add(new File(c.getFilesDir(), "bin"));
        r.add(new File(c.getFilesDir(), "pkg"));
        r.add(new File(c.getFilesDir(), "home"));
        return r;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("realtime");
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Prefs.avRealtime(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        channels();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(2, notification(),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(2, notification());
            }
        } catch (RuntimeException e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) {
            running = true;
            worker.post(() -> {
                for (File r : roots(this)) watchTree(r, 0);
                lastSweep = System.currentTimeMillis();
                mediaSince = System.currentTimeMillis() / 1000 - 5;
                watchMedia();
                worker.postDelayed(sweeper, SWEEP_MS);
                updateNotification();
            });
        }
        return START_STICKY;
    }

    private void channels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "リアルタイム保護",
                NotificationManager.IMPORTANCE_MIN);
        ch.setDescription("リアルタイム保護が動いているあいだ表示します");
        nm.createNotificationChannel(ch);
        NotificationChannel al = new NotificationChannel(ALERTS, "脅威の検出",
                NotificationManager.IMPORTANCE_HIGH);
        al.setDescription("ウイルスなどの脅威や、疑わしいファイルを見つけたときに知らせます");
        nm.createNotificationChannel(al);
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 10,
                new Intent(this, SecurityActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(storageOk() ? "リアルタイム保護: オン"
                        : "リアルタイム保護: オン (⚠ 全ファイルへのアクセスがありません)")
                .setContentText(storageOk()
                        ? "見張っているフォルダ " + observers.size() + " 個・検査 " + scanned + " 件・検出 " + found + " 件"
                        : "ダウンロードなどの、ほかのアプリが置いたファイルを調べられません。セキュリティの画面で許可してください")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        try {
            nm.notify(2, notification());
        } catch (RuntimeException ignored) {
            // 通知を出せない
        }
    }

    /** 内部ストレージのほかのアプリのファイルを読めるか (Android 11 からは「全ファイルへのアクセス」が要る) */
    private boolean storageOk() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    // ------------------------------------------------------------------ 見張り

    /** 2. メディアの一覧 (MediaStore) の変化を見張り、新しく足されたファイルを調べる */
    private void watchMedia() {
        media = new android.database.ContentObserver(worker) {
            @Override
            public void onChange(boolean selfChange) {
                worker.removeCallbacks(mediaQuery);
                worker.postDelayed(mediaQuery, 1500); // 続けて来る知らせをまとめる
            }
        };
        try {
            getContentResolver().registerContentObserver(
                    android.provider.MediaStore.Files.getContentUri("external"), true, media);
        } catch (RuntimeException ignored) {
            media = null; // 見張れない (古い端末など): 1 と 3 だけで見つける
        }
    }

    private final Runnable mediaQuery = this::queryMedia;

    /** 前回より後に足された・変わったファイルを、メディアの一覧から探す */
    @SuppressWarnings("deprecation")
    private void queryMedia() {
        long since = mediaSince;
        mediaSince = System.currentTimeMillis() / 1000 - 2;
        String[] cols = {android.provider.MediaStore.MediaColumns.DATA};
        try (android.database.Cursor cur = getContentResolver().query(
                android.provider.MediaStore.Files.getContentUri("external"), cols,
                android.provider.MediaStore.MediaColumns.DATE_ADDED + ">=? OR "
                        + android.provider.MediaStore.MediaColumns.DATE_MODIFIED + ">=?",
                new String[]{String.valueOf(since), String.valueOf(since)}, null)) {
            if (cur == null) return;
            int n = 0;
            while (cur.moveToNext() && n++ < 500) {
                String path = cur.getString(0);
                if (path != null && underRoots(path)) schedule(new File(path));
            }
        } catch (RuntimeException ignored) {
            // 一覧を読めない (許可が無いなど)
        }
    }

    /** path が見張る場所の中か */
    private boolean underRoots(String path) {
        for (File r : roots(this)) if (path.startsWith(r.getAbsolutePath() + "/")) return true;
        return false;
    }

    /** 3. 見回り: 前回より後に中身が変わったフォルダだけを開いて、新しいファイルを調べる */
    private final Runnable sweeper = new Runnable() {
        @Override
        public void run() {
            long since = lastSweep - 2000;
            lastSweep = System.currentTimeMillis();
            for (String d : new ArrayList<>(observers.keySet())) {
                File dir = new File(d);
                if (dir.lastModified() < since) continue;
                File[] kids = dir.listFiles();
                if (kids == null) continue;
                for (File k : kids) {
                    if (k.isFile() && k.lastModified() >= since) schedule(k);
                    else if (k.isDirectory() && !observers.containsKey(k.getAbsolutePath())) watchTree(k, 0);
                }
            }
            // 新しく足したフォルダの見張り (1) が付けられなかったときのために、見張る場所の一番上は毎回見る
            for (File r : roots(RealtimeService.this)) {
                File[] kids = r.listFiles();
                if (kids == null) continue;
                for (File k : kids) if (k.isFile() && k.lastModified() >= since) schedule(k);
            }
            if (running) worker.postDelayed(this, SWEEP_MS);
        }
    };

    /** dir とその中のフォルダを見張る (FileObserver は中のフォルダまでは見ないので、1 つずつ付ける) */
    private void watchTree(File dir, int depth) {
        if (!dir.isDirectory() || depth > 12 || observers.size() >= MAX_DIRS || Archive.isSymlink(dir)) return;
        String path = dir.getAbsolutePath();
        if (observers.containsKey(path)) return;
        // Android/media の下のアプリのフォルダは、送受信したファイルの置き場所だけで十分
        FileObserver o = new Watcher(path);
        o.startWatching();
        observers.put(path, o);
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) if (k.isDirectory()) watchTree(k, depth + 1);
    }

    private final class Watcher extends FileObserver {
        private final String dir;

        @SuppressWarnings("deprecation")
        Watcher(String dir) {
            super(dir, CLOSE_WRITE | MOVED_TO | CREATE | DELETE_SELF);
            this.dir = dir;
        }

        @Override
        public void onEvent(int event, String name) {
            int e = event & ALL_EVENTS;
            if ((e & DELETE_SELF) != 0) {
                worker.post(() -> {
                    FileObserver o = observers.remove(dir);
                    if (o != null) o.stopWatching();
                });
                return;
            }
            if (name == null) return;
            final File f = new File(dir, name);
            if ((e & CREATE) != 0) {
                // 新しいフォルダは見張りに加える。ファイルは書き終わり (CLOSE_WRITE) を待つ
                worker.postDelayed(() -> {
                    if (f.isDirectory()) watchTree(f, 0);
                }, 300);
                return;
            }
            schedule(f);
        }
    }

    /** 同じファイルの続けての書き込みをまとめるため、少し待ってから検査する */
    private void schedule(final File f) {
        final String p = f.getAbsolutePath();
        synchronized (pending) {
            if (!pending.add(p)) return;
        }
        worker.postDelayed(() -> {
            synchronized (pending) {
                pending.remove(p);
            }
            check(f);
        }, 1500);
    }

    private void check(File f) {
        if (!f.isFile()) return;
        String path = f.getAbsolutePath();
        // 同じ中身 (大きさと更新時刻が同じ) のまま、もう調べたものは省く (3 つの見つけ方で重なるため)
        String sig = f.length() + ":" + f.lastModified();
        if (sig.equals(checked.get(path))) return;
        checked.put(path, sig);
        // 一時ファイル (ダウンロードの途中など) は、書き終わって名前が変わったときに調べる
        String n = f.getName().toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".crdownload") || n.endsWith(".part") || n.endsWith(".tmp") || n.startsWith(".pending-")) return;
        AvScanner.Verdict v = AvScanner.scan(this, f);
        scanned++;
        if (v == null || v.level == AvScanner.CLEAN) {
            if (scanned % 20 == 0) updateNotification();
            return;
        }
        found++;
        String action = "検出のみ (通知だけ)";
        String kind = v.level == AvScanner.THREAT ? "脅威" : "疑わしい";
        int mode = Prefs.avAction(this);
        boolean act = v.level == AvScanner.THREAT || Prefs.avActOnSuspicious(this);
        if (act && mode == Prefs.AV_DELETE) {
            // 問答無用で消して、通知だけする
            if (f.delete() || !f.exists()) {
                action = "削除しました";
                AvScanner.history(this, "削除 (" + kind + "・リアルタイム保護)", path, v.name);
            } else {
                action = "削除できませんでした (読み取り専用の場所かもしれません)";
                AvScanner.history(this, "検出 (" + kind + "・削除できず)", path, v.name);
            }
        } else if (act && mode == Prefs.AV_QUARANTINE) {
            try {
                AvScanner.quarantine(this, f, v.name);
                action = "隔離しました";
            } catch (java.io.IOException e) {
                action = "隔離できませんでした (" + e.getMessage() + ")";
                AvScanner.history(this, "検出 (" + kind + "・隔離できず)", path, v.name);
            }
        } else {
            AvScanner.history(this, "検出 (" + kind + ")", path, v.name);
        }
        alert(f, v, action);
        updateNotification();
    }

    private void alert(File f, AvScanner.Verdict v, String action) {
        PendingIntent open = PendingIntent.getActivity(this, 11,
                new Intent(this, SecurityActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, ALERTS) : new Notification.Builder(this);
        Notification n = b.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle((v.level == AvScanner.THREAT ? "脅威を見つけました: " : "疑わしいファイル: ") + v.name)
                .setContentText(f.getName() + " — " + action)
                .setStyle(new Notification.BigTextStyle().bigText(f.getAbsolutePath() + "\n" + v.engine + ": "
                        + v.name + "\n" + action))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        try {
            nm.notify(1000 + (int) (System.currentTimeMillis() % 100000), n);
        } catch (RuntimeException ignored) {
            // 通知を出せない (通知の許可が無いなど)
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) {
            worker.removeCallbacks(sweeper);
            worker.removeCallbacks(mediaQuery);
        }
        if (media != null) {
            try {
                getContentResolver().unregisterContentObserver(media);
            } catch (RuntimeException ignored) {
                // もう外れている
            }
        }
        for (FileObserver o : observers.values()) o.stopWatching();
        observers.clear();
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 端末の起動時に、オンならリアルタイム保護を始める */
    public static class Boot extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                    || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
                sync(c);
            }
        }
    }
}
