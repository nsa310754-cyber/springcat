package site.ragdollp.switchrec;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.usb.UsbManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * SwitchRec — USB キャプチャーカード(UVC / 外部カメラ)経由で Nintendo Switch などの
 * HDMI 映像をプレビューし、MP4 として録画するアプリ。
 *
 * 構成: Switch → ドック → HDMI → USB キャプチャーカード → Android(USB-OTG)
 * キャプチャーカードは Camera2 の LENS_FACING_EXTERNAL カメラとして開く(ネイティブ不要)。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "SwitchRec";
    private static final int REQ_PERMS = 1001;

    private TextureView previewView;
    private SurfaceView previewV4l2;
    private TextView statusView;
    private TextView hintView;
    private Button btnRecord;
    private Button btnAudio;

    private CameraManager cameraManager;
    private String externalCameraId;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private MediaRecorder mediaRecorder;

    private Size videoSize = new Size(1280, 720);

    private HandlerThread bgThread;
    private Handler bgHandler;

    private boolean recording = false;
    private boolean audioEnabled = true;
    private boolean opening = false;

    // root V4L2 フォールバック
    private boolean v4l2Mode = false;
    private V4l2Capture v4l2Capture;
    private V4l2Session v4l2Session;

    private Uri pendingUri;                 // MediaStore(API29+)の録画中エントリ
    private ParcelFileDescriptor pendingPfd; // その FileDescriptor
    private File legacyFile;                // API28 以下の出力ファイル

    // USB キャプチャーカードの抜き差しを監視して自動で開き直す。
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                if (cameraDevice == null) tryOpenExternalCamera();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                if (recording) stopRecording();
                closeCamera();
                showHint(true, getString(R.string.status_waiting));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.preview);
        previewV4l2 = findViewById(R.id.previewV4l2);
        statusView  = findViewById(R.id.status);
        hintView    = findViewById(R.id.hint);
        btnRecord   = findViewById(R.id.btnRecord);
        btnAudio    = findViewById(R.id.btnAudio);

        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        btnRecord.setOnClickListener(v -> {
            if (recording) stopRecording();
            else startRecording();
        });
        btnAudio.setOnClickListener(v -> {
            if (recording) return; // 録画中は切り替え不可
            audioEnabled = !audioEnabled;
            btnAudio.setText(audioEnabled ? R.string.btn_audio_on : R.string.btn_audio_off);
        });

        previewView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture s, int w, int h) {
                tryOpenExternalCamera();
            }
            @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture s, int w, int h) {
                configureTransform(w, h);
            }
            @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture s) { return true; }
            @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture s) { }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter();
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(this, usbReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBgThread();
        if (cameraDevice == null && v4l2Session == null) tryOpenExternalCamera();
    }

    @Override
    protected void onPause() {
        if (recording) stopRecording();
        closeCamera();
        stopBgThread();
        super.onPause();
    }

    @Override
    protected void onStop() {
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) {}
        super.onStop();
    }

    // ----- 権限 -----------------------------------------------------------

    private boolean ensurePermissions() {
        List<String> need = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.CAMERA);
        if (audioEnabled && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT <= 28
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (need.isEmpty()) return true;
        ActivityCompat.requestPermissions(this, need.toArray(new String[0]), REQ_PERMS);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        if (req == REQ_PERMS) {
            boolean cameraOk = false;
            for (int i = 0; i < p.length; i++) {
                if (Manifest.permission.CAMERA.equals(p[i]) && r[i] == PackageManager.PERMISSION_GRANTED) {
                    cameraOk = true;
                }
            }
            if (cameraOk) tryOpenExternalCamera();
            else Toast.makeText(this, R.string.msg_perm_needed, Toast.LENGTH_LONG).show();
        }
    }

    // ----- カメラ検出/オープン --------------------------------------------

    /** LENS_FACING_EXTERNAL のカメラ(= USB キャプチャーカード)を探す。 */
    @Nullable
    private String findExternalCameraId() {
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics ch = cameraManager.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                    return id;
                }
            }
        } catch (CameraAccessException | IllegalArgumentException e) {
            Log.w(TAG, "findExternalCameraId", e);
        }
        return null;
    }

    private void tryOpenExternalCamera() {
        if (opening || cameraDevice != null || v4l2Session != null) return;
        if (!ensurePermissions()) return;

        // Camera2 の外部カメラ検出は TextureView が使えるときだけ試す。
        externalCameraId = previewView.isAvailable() ? findExternalCameraId() : null;
        if (externalCameraId != null) {
            showHint(false, null);
            openCamera(externalCameraId);
            return;
        }
        // Camera2 に外部カメラが出ない端末 → root があれば V4L2 直接読みを試す。
        if (tryStartV4l2Fallback()) return;
        if (previewView.isAvailable()) {
            showHint(true, getString(R.string.status_waiting));
            statusView.setText(R.string.status_waiting);
        }
    }

    private void openCamera(String id) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) return;
        try {
            CameraCharacteristics ch = cameraManager.getCameraCharacteristics(id);
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) videoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder.class));
            opening = true;
            cameraManager.openCamera(id, stateCallback, bgHandler);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            opening = false;
            Log.e(TAG, "openCamera", e);
            showHint(true, getString(R.string.msg_no_external));
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(@NonNull CameraDevice cam) {
            opening = false;
            cameraDevice = cam;
            runOnUiThread(() -> {
                configureTransform(previewView.getWidth(), previewView.getHeight());
                startPreview();
                statusView.setText(R.string.status_ready);
            });
        }
        @Override public void onDisconnected(@NonNull CameraDevice cam) {
            opening = false;
            cam.close();
            if (cameraDevice == cam) cameraDevice = null;
            runOnUiThread(() -> showHint(true, getString(R.string.status_waiting)));
        }
        @Override public void onError(@NonNull CameraDevice cam, int err) {
            opening = false;
            cam.close();
            if (cameraDevice == cam) cameraDevice = null;
            Log.e(TAG, "camera error " + err);
            runOnUiThread(() -> showHint(true, getString(R.string.msg_no_external)));
        }
    };

    private Size chooseVideoSize(Size[] choices) {
        // 16:9 を優先し、1920x1080 以下で最大のものを選ぶ。無ければ最初の候補。
        Size best = null;
        for (Size s : choices) {
            if (s.getWidth() > 1920 || s.getHeight() > 1080) continue;
            boolean w169 = Math.abs(s.getWidth() * 9 - s.getHeight() * 16) < 8;
            if (!w169) continue;
            if (best == null || (long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) {
                best = s;
            }
        }
        if (best != null) return best;
        for (Size s : choices) {
            if (s.getWidth() <= 1920 && s.getHeight() <= 1080) {
                if (best == null || (long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) best = s;
            }
        }
        return best != null ? best : (choices.length > 0 ? choices[0] : new Size(1280, 720));
    }

    // ----- プレビュー -----------------------------------------------------

    private void startPreview() {
        if (cameraDevice == null || !previewView.isAvailable()) return;
        try {
            closeSession();
            SurfaceTexture texture = previewView.getSurfaceTexture();
            texture.setDefaultBufferSize(videoSize.getWidth(), videoSize.getHeight());
            Surface previewSurface = new Surface(texture);

            final CaptureRequest.Builder b = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(previewSurface);

            cameraDevice.createCaptureSession(java.util.Collections.singletonList(previewSurface),
                new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(@NonNull CameraCaptureSession s) {
                        captureSession = s;
                        try {
                            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                            s.setRepeatingRequest(b.build(), null, bgHandler);
                        } catch (CameraAccessException | IllegalStateException e) {
                            Log.e(TAG, "preview repeating", e);
                        }
                    }
                    @Override public void onConfigureFailed(@NonNull CameraCaptureSession s) {
                        Log.e(TAG, "preview configure failed");
                    }
                }, bgHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, "startPreview", e);
        }
    }

    // ----- 録画 -----------------------------------------------------------

    // 録画の入口: モードに応じて Camera2 / V4L2 に振り分ける。
    private void startRecording() {
        if (v4l2Mode) { startV4l2Recording(); return; }
        startCamera2Recording();
    }

    private void stopRecording() {
        if (v4l2Mode) { stopV4l2Recording(); return; }
        stopCamera2Recording();
    }

    private void startCamera2Recording() {
        if (cameraDevice == null || recording || !previewView.isAvailable()) {
            if (cameraDevice == null) tryOpenExternalCamera();
            return;
        }
        if (audioEnabled && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            if (!ensurePermissions()) return;
        }
        try {
            closeSession();
            setUpMediaRecorder();

            SurfaceTexture texture = previewView.getSurfaceTexture();
            texture.setDefaultBufferSize(videoSize.getWidth(), videoSize.getHeight());
            Surface previewSurface = new Surface(texture);
            Surface recorderSurface = mediaRecorder.getSurface();

            final CaptureRequest.Builder b = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.addTarget(previewSurface);
            b.addTarget(recorderSurface);

            List<Surface> surfaces = Arrays.asList(previewSurface, recorderSurface);
            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(@NonNull CameraCaptureSession s) {
                    captureSession = s;
                    try {
                        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                        s.setRepeatingRequest(b.build(), null, bgHandler);
                        runOnUiThread(() -> {
                            mediaRecorder.start();
                            recording = true;
                            btnRecord.setText(R.string.btn_stop);
                            statusView.setText(R.string.status_recording);
                        });
                    } catch (CameraAccessException | IllegalStateException e) {
                        Log.e(TAG, "record repeating", e);
                        runOnUiThread(() -> abortRecordingSetup());
                    }
                }
                @Override public void onConfigureFailed(@NonNull CameraCaptureSession s) {
                    Log.e(TAG, "record configure failed");
                    runOnUiThread(() -> abortRecordingSetup());
                }
            }, bgHandler);
        } catch (Exception e) {
            Log.e(TAG, "startRecording", e);
            abortRecordingSetup();
        }
    }

    private void abortRecordingSetup() {
        releaseRecorder(false);
        recording = false;
        btnRecord.setText(R.string.btn_record);
        statusView.setText(R.string.status_ready);
        Toast.makeText(this, "録画を開始できませんでした", Toast.LENGTH_SHORT).show();
        startPreview();
    }

    private void stopCamera2Recording() {
        if (!recording) return;
        recording = false;
        try {
            if (captureSession != null) captureSession.stopRepeating();
        } catch (Exception ignored) {}
        boolean ok = true;
        try {
            mediaRecorder.stop();
        } catch (RuntimeException e) {
            // 直後に stop すると "stop failed" になることがある(短すぎる録画など)
            Log.w(TAG, "recorder stop", e);
            ok = false;
        }
        String name = finalizeOutput(ok);
        releaseRecorder(false);
        btnRecord.setText(R.string.btn_record);
        statusView.setText(R.string.status_ready);
        if (ok && name != null) {
            Toast.makeText(this, getString(R.string.msg_saved, name), Toast.LENGTH_LONG).show();
        }
        startPreview();
    }

    private void setUpMediaRecorder() throws Exception {
        mediaRecorder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                ? new MediaRecorder(this) : new MediaRecorder();

        boolean withAudio = audioEnabled && ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;

        if (withAudio) mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

        openOutput(); // pendingPfd / legacyFile を設定
        if (pendingPfd != null) mediaRecorder.setOutputFile(pendingPfd.getFileDescriptor());
        else mediaRecorder.setOutputFile(legacyFile.getAbsolutePath());

        int bitrate = (int) (videoSize.getWidth() * videoSize.getHeight() * 30 * 0.15);
        bitrate = Math.max(6_000_000, Math.min(bitrate, 20_000_000));
        mediaRecorder.setVideoEncodingBitRate(bitrate);
        mediaRecorder.setVideoFrameRate(30);
        mediaRecorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        if (withAudio) {
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioEncodingBitRate(128_000);
            mediaRecorder.setAudioSamplingRate(44_100);
        }
        // 外部カメラの SENSOR_ORIENTATION は通常 0。横向き固定なので回転補正なし。
        mediaRecorder.setOrientationHint(0);
        mediaRecorder.prepare();
    }

    private String currentName;

    private void openOutput() throws Exception {
        currentName = "SwitchRec_" +
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        pendingUri = null; pendingPfd = null; legacyFile = null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Video.Media.DISPLAY_NAME, currentName);
            cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            cv.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SwitchRec");
            cv.put(MediaStore.Video.Media.IS_PENDING, 1);
            pendingUri = getContentResolver().insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
            if (pendingUri == null) throw new Exception("MediaStore insert failed");
            pendingPfd = getContentResolver().openFileDescriptor(pendingUri, "rw");
            if (pendingPfd == null) throw new Exception("openFileDescriptor failed");
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_MOVIES), "SwitchRec");
            if (!dir.exists()) dir.mkdirs();
            legacyFile = new File(dir, currentName);
        }
    }

    /** 録画終了後にファイルを確定させる。@return 表示名(成功時) */
    private String finalizeOutput(boolean ok) {
        try {
            if (pendingUri != null) {
                if (pendingPfd != null) { try { pendingPfd.close(); } catch (Exception ignored) {} pendingPfd = null; }
                if (ok) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Video.Media.IS_PENDING, 0);
                    getContentResolver().update(pendingUri, cv, null, null);
                } else {
                    getContentResolver().delete(pendingUri, null, null);
                    pendingUri = null;
                    return null;
                }
            } else if (legacyFile != null) {
                if (ok) {
                    android.media.MediaScannerConnection.scanFile(
                            this, new String[]{legacyFile.getAbsolutePath()}, null, null);
                } else {
                    legacyFile.delete();
                    return null;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "finalizeOutput", e);
        }
        return currentName;
    }

    private void releaseRecorder(boolean keepFile) {
        if (mediaRecorder != null) {
            try { mediaRecorder.reset(); } catch (Exception ignored) {}
            try { mediaRecorder.release(); } catch (Exception ignored) {}
            mediaRecorder = null;
        }
        if (!keepFile) {
            if (pendingPfd != null) { try { pendingPfd.close(); } catch (Exception ignored) {} pendingPfd = null; }
        }
    }

    // ----- root V4L2 フォールバック ---------------------------------------

    /** root があれば /dev/videoN を直接読むモードを開始。試行を始めたら true。 */
    private boolean tryStartV4l2Fallback() {
        if (v4l2Mode || v4l2Session != null) return true;
        if (!RootHelper.hasRoot()) return false;
        showHint(true, "root検出 — USBキャプチャを検索中…");
        if (bgHandler == null) startBgThread();
        bgHandler.post(() -> {
            List<String> devs = RootHelper.listVideoDevices();
            V4l2Capture cap = new V4l2Capture();
            boolean ok = false;
            for (String d : devs) {
                RootHelper.chmodOpen(d);
                if (cap.open(d, 1920, 1080) || cap.open(d, 1280, 720)) { ok = true; break; }
            }
            if (!ok) {
                runOnUiThread(() -> showHint(true, getString(R.string.msg_no_external)));
                return;
            }
            final V4l2Capture fcap = cap;
            runOnUiThread(() -> startV4l2Session(fcap));
        });
        return true;
    }

    private void startV4l2Session(V4l2Capture cap) {
        v4l2Capture = cap;
        v4l2Mode = true;
        previewView.setVisibility(View.GONE);
        previewV4l2.setVisibility(View.VISIBLE);
        showHint(false, null);
        statusView.setText(R.string.status_ready);
        btnAudio.setEnabled(false); // V4L2 直接読みは映像のみ(音声なし)
        v4l2Session = new V4l2Session(cap, previewV4l2.getHolder(), new V4l2Session.Listener() {
            @Override public void onStarted(int w, int h, int pixfmt) { }
            @Override public void onError(String msg) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
            }
            @Override public void onStopped() { }
        });
        v4l2Session.start();
    }

    private void startV4l2Recording() {
        if (recording || v4l2Session == null) return;
        try {
            openOutput();
        } catch (Exception e) {
            Log.e(TAG, "openOutput", e);
            Toast.makeText(this, "保存先を作成できませんでした", Toast.LENGTH_SHORT).show();
            return;
        }
        java.io.FileDescriptor fd = (pendingPfd != null) ? pendingPfd.getFileDescriptor() : null;
        String path = (legacyFile != null) ? legacyFile.getAbsolutePath() : null;
        boolean ok = v4l2Session.startRecording(fd, path);
        if (ok) {
            recording = true;
            btnRecord.setText(R.string.btn_stop);
            statusView.setText(R.string.status_recording);
        } else {
            finalizeOutput(false);
            Toast.makeText(this, "録画を開始できませんでした", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopV4l2Recording() {
        if (!recording) return;
        recording = false;
        v4l2Session.stopRecording(); // drain 完了までブロック
        String name = finalizeOutput(true);
        btnRecord.setText(R.string.btn_record);
        statusView.setText(R.string.status_ready);
        if (name != null) {
            Toast.makeText(this, getString(R.string.msg_saved, name), Toast.LENGTH_LONG).show();
        }
    }

    private void closeV4l2() {
        if (v4l2Session != null) {
            try { v4l2Session.stop(); } catch (Exception ignored) {}
            v4l2Session = null;
        }
        v4l2Capture = null;
        if (v4l2Mode) {
            v4l2Mode = false;
            runOnUiThread(() -> {
                previewV4l2.setVisibility(View.GONE);
                previewView.setVisibility(View.VISIBLE);
                btnAudio.setEnabled(true);
            });
        }
    }

    // ----- 後片付け -------------------------------------------------------

    private void closeSession() {
        if (captureSession != null) {
            try { captureSession.close(); } catch (Exception ignored) {}
            captureSession = null;
        }
    }

    private void closeCamera() {
        closeSession();
        if (cameraDevice != null) {
            try { cameraDevice.close(); } catch (Exception ignored) {}
            cameraDevice = null;
        }
        releaseRecorder(false);
        closeV4l2();
    }

    private void startBgThread() {
        if (bgThread != null) return;
        bgThread = new HandlerThread("SwitchRecBg");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
    }

    private void stopBgThread() {
        if (bgThread == null) return;
        bgThread.quitSafely();
        try { bgThread.join(500); } catch (InterruptedException ignored) {}
        bgThread = null;
        bgHandler = null;
    }

    // ----- 表示補助 -------------------------------------------------------

    private void showHint(boolean show, @Nullable String status) {
        runOnUiThread(() -> {
            hintView.setVisibility(show ? View.VISIBLE : View.GONE);
            if (status != null) statusView.setText(status);
        });
    }

    /** TextureView をアスペクト比 videoSize に合わせてレターボックス表示する。 */
    private void configureTransform(int viewW, int viewH) {
        if (previewView == null || viewW == 0 || viewH == 0) return;
        float vw = videoSize.getWidth();
        float vh = videoSize.getHeight();
        // fit-center: 映像全体が収まる倍率
        float scale = Math.min(viewW / vw, viewH / vh);
        float dw = vw * scale, dh = vh * scale;
        Matrix m = new Matrix();
        RectF viewRect = new RectF(0, 0, viewW, viewH);
        RectF bufRect = new RectF(0, 0, dw, dh);
        bufRect.offset(viewRect.centerX() - bufRect.centerX(), viewRect.centerY() - bufRect.centerY());
        // TextureView は buffer(viewW×viewH 基準)を描くので、比率補正の行列を作る
        m.setRectToRect(new RectF(0, 0, viewW, viewH), bufRect, Matrix.ScaleToFit.FILL);
        previewView.setTransform(m);
    }
}
