package site.ragdollp.mmrlzipguard;

import android.app.*;
import android.content.*;
import android.os.*;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;

public class GuardService extends Service {

    public static final String TAG = "ZipGuard";
    public static final String ACTION_LOG = "site.ragdollp.mmrlzipguard.LOG";
    public static final String EXTRA_MESSAGE = "message";
    public static final String CHANNEL_ID = "zip_guard_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final long SCAN_INTERVAL_MS = 3000;

    private static final String[] MMRL_PACKAGES = {
            "com.dergoogler.mmrl",
            "dev.dergoogler.mmrl",
    };

    private ScheduledExecutorService mExecutor;
    private final Set<String> mKnownFiles = ConcurrentHashMap.newKeySet();
    private final List<FileObserver> mObservers = new ArrayList<>();
    private File mBackupDir;
    private List<String> mWatchPaths;
    private int mSavedCount = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        mBackupDir = new File(Environment.getExternalStorageDirectory(), "MMRL-Zip-Backup");
        if (!mBackupDir.exists()) mBackupDir.mkdirs();

        mWatchPaths = buildWatchPaths();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification("監視中…"));
        broadcastLog("サービス開始");
        broadcastLog("バックアップ先: " + mBackupDir.getAbsolutePath());

        for (String path : mWatchPaths) {
            broadcastLog("監視対象: " + path);
        }

        if (Shell.isRootAvailable()) {
            broadcastLog("Root権限: 利用可能");
        } else {
            broadcastLog("Root権限: なし（通常モードで動作）");
        }

        seedKnownFiles();
        startObservers();
        startPeriodicScan();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mExecutor != null) mExecutor.shutdownNow();
        for (FileObserver obs : mObservers) {
            obs.stopWatching();
        }
        mObservers.clear();
        broadcastLog("サービス停止");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private List<String> buildWatchPaths() {
        List<String> paths = new ArrayList<>();

        // Standard download directory
        File dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (dlDir.isDirectory()) paths.add(dlDir.getAbsolutePath());

        // /sdcard/Download (alias)
        File sdcardDl = new File("/sdcard/Download");
        if (sdcardDl.isDirectory() && !paths.contains(sdcardDl.getAbsolutePath())) {
            paths.add(sdcardDl.getAbsolutePath());
        }

        if (Shell.isRootAvailable()) {
            for (String pkg : MMRL_PACKAGES) {
                // Check cache directories
                String cacheDir = "/data/data/" + pkg + "/cache";
                if (Shell.dirExists(cacheDir)) paths.add(cacheDir);

                String filesDir = "/data/data/" + pkg + "/files";
                if (Shell.dirExists(filesDir)) paths.add(filesDir);

                // Some MMRL versions use external files dir
                String extDir = "/sdcard/Android/data/" + pkg + "/files";
                if (Shell.dirExists(extDir)) paths.add(extDir);

                String extCache = "/sdcard/Android/data/" + pkg + "/cache";
                if (Shell.dirExists(extCache)) paths.add(extCache);
            }

            // /data/local/tmp is sometimes used for module installs
            if (Shell.dirExists("/data/local/tmp")) {
                paths.add("/data/local/tmp");
            }
        }

        return paths;
    }

    private void seedKnownFiles() {
        for (String path : mWatchPaths) {
            String[] files = Shell.listDir(path);
            if (files != null) {
                for (String f : files) {
                    if (isZipFile(f)) {
                        mKnownFiles.add(path + "/" + f);
                    }
                }
            }
        }
    }

    private void startObservers() {
        int mask = FileObserver.CREATE | FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO;

        for (String path : mWatchPaths) {
            File dir = new File(path);
            if (!dir.canRead() && !Shell.isRootAvailable()) continue;

            try {
                FileObserver observer = new FileObserver(dir, mask) {
                    @Override
                    public void onEvent(int event, String name) {
                        if (name == null || !isZipFile(name)) return;
                        String fullPath = path + "/" + name;
                        handleNewZip(fullPath, name);
                    }
                };
                observer.startWatching();
                mObservers.add(observer);
            } catch (Exception e) {
                Log.w(TAG, "FileObserver failed for " + path, e);
            }
        }
    }

    private void startPeriodicScan() {
        mExecutor = Executors.newSingleThreadScheduledExecutor();
        mExecutor.scheduleWithFixedDelay(this::scanAll, SCAN_INTERVAL_MS, SCAN_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void scanAll() {
        for (String path : mWatchPaths) {
            String[] files = Shell.listDir(path);
            if (files == null) continue;
            for (String f : files) {
                if (!isZipFile(f)) continue;
                String fullPath = path + "/" + f;
                if (!mKnownFiles.contains(fullPath)) {
                    handleNewZip(fullPath, f);
                }
            }
        }
    }

    private synchronized void handleNewZip(String fullPath, String fileName) {
        if (mKnownFiles.contains(fullPath)) return;
        mKnownFiles.add(fullPath);

        File src = new File(fullPath);
        File dst = new File(mBackupDir, fileName);

        // Avoid overwriting — append timestamp if duplicate
        if (dst.exists()) {
            String base = fileName.substring(0, fileName.lastIndexOf('.'));
            String ext = fileName.substring(fileName.lastIndexOf('.'));
            dst = new File(mBackupDir, base + "_" + System.currentTimeMillis() + ext);
        }

        boolean ok = Shell.copyFile(src, dst);
        if (ok) {
            mSavedCount++;
            broadcastLog("保存完了: " + fileName + " → " + dst.getName());
            updateNotification("監視中 — " + mSavedCount + " 件保存済み");
        } else {
            broadcastLog("保存失敗: " + fileName);
        }
    }

    private boolean isZipFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".zip");
    }

    private void broadcastLog(String message) {
        Log.i(TAG, message);
        Intent intent = new Intent(ACTION_LOG);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_MESSAGE, message);
        sendBroadcast(intent);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "Zip Guard 監視",
                    NotificationManager.IMPORTANCE_LOW
            );
            ch.setDescription("モジュールZIPファイルの監視・保護");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent ni = new Intent(this, MainActivity.class);
        ni.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, ni,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("MMRL Zip Guard")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }
}
