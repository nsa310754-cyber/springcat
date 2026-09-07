package site.ragdollp.switchrec;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.io.FileDescriptor;
import java.nio.ByteBuffer;

/**
 * root 経由 V4L2 キャプチャの取得・プレビュー・録画を担うセッション。
 * 取得スレッドが 1 フレームずつ Bitmap 化し、プレビュー用 SurfaceView と
 * (録画中は)MediaCodec の入力 Surface の両方へ描画する。
 * エンコードは H.264(Surface 入力)→ MediaMuxer で MP4 出力。
 */
public class V4l2Session {

    public interface Listener {
        void onStarted(int w, int h, int pixfmt);
        void onError(String msg);
        void onStopped();
    }

    private static final String TAG = "SwitchRecV4L2Sess";

    private final V4l2Capture cap;
    private final SurfaceHolder previewHolder;
    private final Listener listener;

    private volatile boolean running = false;
    private Thread captureThread;

    // ---- 録画状態 ----
    private volatile boolean recording = false;
    private MediaCodec encoder;
    private Surface encoderSurface;
    private MediaMuxer muxer;
    private int trackIndex = -1;
    private volatile boolean muxerStarted = false;
    private final Object muxLock = new Object();
    private long startNanos = 0;
    private Thread drainThread;

    public V4l2Session(V4l2Capture cap, SurfaceHolder previewHolder, Listener l) {
        this.cap = cap;
        this.previewHolder = previewHolder;
        this.listener = l;
    }

    // ----- 取得ループ -----------------------------------------------------

    public void start() {
        if (running) return;
        running = true;
        captureThread = new Thread(this::loop, "V4l2Capture");
        captureThread.start();
    }

    public void stop() {
        running = false;
        if (recording) stopRecording();
        if (captureThread != null) {
            try { captureThread.join(1500); } catch (InterruptedException ignored) {}
            captureThread = null;
        }
        cap.close();
    }

    private void loop() {
        boolean announced = false;
        int[] pixels = null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inMutable = false;
        while (running) {
            byte[] frame = cap.grab();
            if (frame == null) continue;

            Bitmap bmp = null;
            try {
                if (cap.pixfmt == V4l2Capture.PIX_MJPEG) {
                    bmp = BitmapFactory.decodeByteArray(frame, 0, frame.length, opts);
                } else { // YUYV
                    if (pixels == null) pixels = new int[cap.width * cap.height];
                    yuyvToArgb(frame, pixels, cap.width, cap.height);
                    bmp = Bitmap.createBitmap(pixels, cap.width, cap.height, Bitmap.Config.ARGB_8888);
                }
            } catch (Throwable t) {
                Log.w(TAG, "decode", t);
            }
            if (bmp == null) continue;

            if (!announced) {
                announced = true;
                if (listener != null) listener.onStarted(cap.width, cap.height, cap.pixfmt);
            }

            drawToHolder(bmp);
            if (recording) drawToEncoder(bmp);
        }
    }

    private void drawToHolder(Bitmap bmp) {
        SurfaceHolder h = previewHolder;
        if (h == null || h.getSurface() == null || !h.getSurface().isValid()) return;
        Canvas cv = null;
        try {
            cv = h.lockCanvas();
            if (cv == null) return;
            cv.drawColor(Color.BLACK);
            drawFit(cv, bmp);
        } catch (Throwable t) {
            Log.w(TAG, "drawToHolder", t);
        } finally {
            if (cv != null) try { h.unlockCanvasAndPost(cv); } catch (Throwable ignored) {}
        }
    }

    private void drawFit(Canvas cv, Bitmap bmp) {
        int cw = cv.getWidth(), ch = cv.getHeight();
        float scale = Math.min(cw / (float) bmp.getWidth(), ch / (float) bmp.getHeight());
        int dw = Math.round(bmp.getWidth() * scale), dh = Math.round(bmp.getHeight() * scale);
        int left = (cw - dw) / 2, top = (ch - dh) / 2;
        cv.drawBitmap(bmp, null, new Rect(left, top, left + dw, top + dh), null);
    }

    // ----- 録画 -----------------------------------------------------------

    /** fd か path のどちらか一方を渡す(fd 優先)。成功時 true。 */
    public boolean startRecording(FileDescriptor fd, String path) {
        if (recording) return false;
        try {
            int w = cap.width, h = cap.height;
            MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            int bitrate = (int) Math.max(6_000_000L, Math.min((long) w * h * 30 / 8, 20_000_000L));
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderSurface = encoder.createInputSurface();
            encoder.start();

            if (fd != null) {
                muxer = new MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } else {
                muxer = new MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            }
            trackIndex = -1;
            muxerStarted = false;
            startNanos = System.nanoTime();
            recording = true;

            drainThread = new Thread(this::drainLoop, "V4l2Encoder");
            drainThread.start();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "startRecording", t);
            releaseEncoder();
            if (listener != null) listener.onError("録画開始に失敗しました");
            return false;
        }
    }

    private void drawToEncoder(Bitmap bmp) {
        Surface s = encoderSurface;
        if (s == null || !s.isValid()) return;
        Canvas cv = null;
        try {
            cv = s.lockCanvas(null);
            cv.drawColor(Color.BLACK);
            drawFit(cv, bmp);
        } catch (Throwable t) {
            Log.w(TAG, "drawToEncoder", t);
        } finally {
            if (cv != null) try { s.unlockCanvasAndPost(cv); } catch (Throwable ignored) {}
        }
    }

    private void drainLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int outIndex = -1;
            try {
                outIndex = encoder.dequeueOutputBuffer(info, 10_000);
            } catch (Throwable t) {
                Log.w(TAG, "dequeue", t);
                break;
            }
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!recording) break; // stop 済みで残りが無い
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized (muxLock) {
                    trackIndex = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                }
            } else if (outIndex >= 0) {
                ByteBuffer encoded = encoder.getOutputBuffer(outIndex);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0 && muxerStarted && encoded != null) {
                    encoded.position(info.offset);
                    encoded.limit(info.offset + info.size);
                    synchronized (muxLock) { muxer.writeSampleData(trackIndex, encoded, info); }
                }
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                try { encoder.releaseOutputBuffer(outIndex, false); } catch (Throwable ignored) {}
                if (eos) break;
            }
        }
        finishMuxer();
    }

    public void stopRecording() {
        if (!recording) return;
        recording = false;
        try { if (encoder != null) encoder.signalEndOfInputStream(); } catch (Throwable ignored) {}
        // drainLoop が EOS を検出して finishMuxer(muxer.stop→fd フラッシュ)を行う。
        // fd を閉じる前に確実に締めるため、drain スレッドの完了を待つ。
        if (drainThread != null) {
            try { drainThread.join(3000); } catch (InterruptedException ignored) {}
            drainThread = null;
        }
    }

    private void finishMuxer() {
        synchronized (muxLock) {
            try {
                if (muxer != null && muxerStarted) { muxer.stop(); }
            } catch (Throwable t) {
                Log.w(TAG, "muxer.stop", t);
            }
            try { if (muxer != null) muxer.release(); } catch (Throwable ignored) {}
            muxer = null;
            muxerStarted = false;
        }
        releaseEncoder();
        if (listener != null) listener.onStopped();
    }

    private void releaseEncoder() {
        try { if (encoder != null) encoder.stop(); } catch (Throwable ignored) {}
        try { if (encoder != null) encoder.release(); } catch (Throwable ignored) {}
        encoder = null;
        if (encoderSurface != null) { try { encoderSurface.release(); } catch (Throwable ignored) {} encoderSurface = null; }
    }

    public boolean isRecording() { return recording; }

    // ----- YUYV(YUY2) → ARGB ---------------------------------------------

    private static void yuyvToArgb(byte[] yuyv, int[] out, int w, int h) {
        int frameSize = w * h * 2;
        int oi = 0;
        for (int i = 0; i + 3 < frameSize && oi + 1 < out.length; i += 4) {
            int y0 = yuyv[i] & 0xff;
            int u  = yuyv[i + 1] & 0xff;
            int y1 = yuyv[i + 2] & 0xff;
            int v  = yuyv[i + 3] & 0xff;
            out[oi++] = yuv(y0, u, v);
            out[oi++] = yuv(y1, u, v);
        }
    }

    private static int yuv(int y, int u, int v) {
        int c = y - 16, d = u - 128, e = v - 128;
        int r = (298 * c + 409 * e + 128) >> 8;
        int g = (298 * c - 100 * d - 208 * e + 128) >> 8;
        int b = (298 * c + 516 * d + 128) >> 8;
        if (r < 0) r = 0; else if (r > 255) r = 255;
        if (g < 0) g = 0; else if (g > 255) g = 255;
        if (b < 0) b = 0; else if (b > 255) b = 255;
        return 0xff000000 | (r << 16) | (g << 8) | b;
    }
}
