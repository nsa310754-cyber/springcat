package site.ragdollp.blockdestory;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * MediaProjection を使った画面録画のフォアグラウンドサービス。
 * WebView の getDisplayMedia が使えない代わりに、ネイティブで画面全体を録画する。
 */
public class ScreenRecordService extends Service {

    public static final String ACTION_START = "site.ragdollp.blockdestory.REC_START";
    public static final String ACTION_STOP  = "site.ragdollp.blockdestory.REC_STOP";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    public static final String EXTRA_WIDTH  = "width";
    public static final String EXTRA_HEIGHT = "height";
    public static final String EXTRA_DPI    = "dpi";

    private static final String CHANNEL_ID = "screen_record";
    private static final int NOTIF_ID = 42;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private MediaRecorder recorder;
    private File tempFile;
    private boolean running = false;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopRecording();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action) || intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // フォアグラウンド開始 (Android 10+ は mediaProjection タイプ必須)
        startForegroundNotification();

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity_RESULT_CANCELED());
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        int width = intent.getIntExtra(EXTRA_WIDTH, 720);
        int height = intent.getIntExtra(EXTRA_HEIGHT, 1280);
        int dpi = intent.getIntExtra(EXTRA_DPI, 320);

        try {
            startRecording(resultCode, resultData, width, height, dpi);
        } catch (Exception e) {
            toast("録画を開始できませんでした");
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private static int Activity_RESULT_CANCELED() { return 0; }

    private void startForegroundNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "画面録画", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notif = b
                .setContentTitle("Block Destroy")
                .setContentText("画面を録画中…")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, notif);
        }
    }

    private void startRecording(int resultCode, Intent resultData,
                                int width, int height, int dpi) throws Exception {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, resultData);
        if (projection == null) throw new IllegalStateException("no projection");

        // Android 14+ は VirtualDisplay 作成前に Callback 登録が必須
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stopRecording(); }
        }, new Handler(Looper.getMainLooper()));

        // 大きすぎる解像度はエンコーダ上限を超えることがあるので必要なら縮小
        int vw = width, vh = height;
        int maxW = 1080;
        if (vw > maxW) {
            float scale = (float) maxW / vw;
            vw = maxW;
            vh = Math.round(height * scale);
        }
        // 偶数に丸める (エンコーダ要件)
        vw = vw - (vw % 2);
        vh = vh - (vh % 2);

        tempFile = new File(getExternalFilesDir(null), "rec_tmp.mp4");
        if (tempFile.exists()) tempFile.delete();

        recorder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                ? new MediaRecorder(this) : new MediaRecorder();
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setVideoSize(vw, vh);
        recorder.setVideoFrameRate(30);
        recorder.setVideoEncodingBitRate(Math.max(4_000_000, vw * vh * 4));
        recorder.setOutputFile(tempFile.getAbsolutePath());
        recorder.prepare();

        virtualDisplay = projection.createVirtualDisplay(
                "BlockDestroyRec", vw, vh, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.getSurface(), null, null);

        recorder.start();
        running = true;
    }

    private void stopRecording() {
        if (!running) {
            releaseAll();
            stopForegroundCompat();
            stopSelf();
            return;
        }
        running = false;
        boolean ok = false;
        try {
            recorder.stop();
            ok = true;
        } catch (Exception e) {
            ok = false;
        }
        releaseAll();

        if (ok && tempFile != null && tempFile.exists() && tempFile.length() > 0) {
            String name = "blockdestory_rec_" +
                    new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
            Uri u = MediaSaver.saveFile(this, tempFile, name, "video/mp4");
            toast(u != null ? "録画を保存しました: " + name : "録画の保存に失敗しました");
        } else {
            toast("録画の保存に失敗しました");
        }

        stopForegroundCompat();
        stopSelf();
    }

    private void releaseAll() {
        try { if (recorder != null) recorder.release(); } catch (Exception ignore) { }
        recorder = null;
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignore) { }
        virtualDisplay = null;
        try { if (projection != null) projection.stop(); } catch (Exception ignore) { }
        projection = null;
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    private void toast(final String msg) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                android.widget.Toast.makeText(getApplicationContext(), msg,
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onDestroy() {
        releaseAll();
        super.onDestroy();
    }
}
