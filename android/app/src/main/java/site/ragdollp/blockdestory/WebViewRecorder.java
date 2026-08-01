package site.ragdollp.blockdestory;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.HandlerThread;
import android.webkit.WebView;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * WebView の描画内容だけを直接キャプチャして mp4 に録画する。
 * MediaProjection を使わないので「録画の許可/共有アプリ選択」画面は出ず、
 * 常に自分のアプリの中身だけが録画される(他アプリ・システムUIは映らない)。
 * 音声は含まない(DOM 描画のみ)。
 */
class WebViewRecorder {

    interface DoneCallback { void onDone(boolean ok, File file); }

    private final Activity activity;
    private final WebView webView;
    private final File outFile;
    private final int fps = 15;

    private MediaCodec codec;
    private MediaMuxer muxer;
    private int trackIndex = -1;
    private boolean muxerStarted = false;
    private boolean semiPlanar = true;
    private int width, height;
    private long startNs = 0;
    private volatile boolean running = false;

    private HandlerThread thread;
    private Handler handler;
    private Bitmap frameBitmap;
    private int[] argb;
    private byte[] yuv;
    private final MediaCodec.BufferInfo bufInfo = new MediaCodec.BufferInfo();

    WebViewRecorder(Activity a, WebView w, File out) { activity = a; webView = w; outFile = out; }

    boolean isRunning() { return running; }

    boolean start() {
        try {
            int vw = webView.getWidth(), vh = webView.getHeight();
            if (vw <= 0 || vh <= 0) { vw = 720; vh = 1280; }
            int maxW = 720;
            if (vw > maxW) { float s = (float) maxW / vw; vw = maxW; vh = Math.round(vh * s); }
            width = vw - (vw % 2);
            height = vh - (vh % 2);

            String mime = "video/avc";
            codec = MediaCodec.createEncoderByType(mime);
            int cf = pickColorFormat(codec, mime);
            semiPlanar = (cf != MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar);

            MediaFormat fmt = MediaFormat.createVideoFormat(mime, width, height);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, cf);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(2_500_000, width * height * 4));
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();

            muxer = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            argb = new int[width * height];
            yuv = new byte[width * height * 3 / 2];

            thread = new HandlerThread("wv-recorder");
            thread.start();
            handler = new Handler(thread.getLooper());
            running = true;
            startNs = System.nanoTime();
            handler.post(frameLoop);
            return true;
        } catch (Throwable e) {
            releaseQuietly();
            return false;
        }
    }

    private final Runnable frameLoop = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long t0 = System.nanoTime();
            try {
                captureAndEncodeFrame();
                drainEncoder(false);
            } catch (Throwable ignore) { }
            long spent = System.nanoTime() - t0;
            long frameNs = 1_000_000_000L / fps;
            long delayMs = Math.max(0, (frameNs - spent) / 1_000_000L);
            if (running) handler.postDelayed(this, delayMs);
        }
    };

    private void captureAndEncodeFrame() throws Exception {
        // WebView の描画は UI スレッドで行う必要がある
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = { false };
        final float sx = (float) width / Math.max(1, webView.getWidth());
        final float sy = (float) height / Math.max(1, webView.getHeight());
        activity.runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    Canvas c = new Canvas(frameBitmap);
                    c.drawColor(0xFFF2A0F1);        // 背景(未描画部分がゲーム背景色に)
                    c.scale(sx, sy);
                    webView.draw(c);
                    ok[0] = true;
                } catch (Throwable ignore) {
                } finally { latch.countDown(); }
            }
        });
        latch.await(300, TimeUnit.MILLISECONDS);
        if (!ok[0]) return;

        int inIndex = codec.dequeueInputBuffer(10000);
        if (inIndex >= 0) {
            ByteBuffer ib = codec.getInputBuffer(inIndex);
            ib.clear();
            frameBitmap.getPixels(argb, 0, width, 0, 0, width, height);
            argbToYuv(argb, yuv, width, height, semiPlanar);
            ib.put(yuv);
            long pts = (System.nanoTime() - startNs) / 1000;
            codec.queueInputBuffer(inIndex, 0, yuv.length, pts, 0);
        }
    }

    private void drainEncoder(boolean endOfStream) {
        while (true) {
            int outIndex = codec.dequeueOutputBuffer(bufInfo, endOfStream ? 10000 : 0);
            if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) return;
            } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                trackIndex = muxer.addTrack(codec.getOutputFormat());
                muxer.start();
                muxerStarted = true;
            } else if (outIndex >= 0) {
                ByteBuffer ob = codec.getOutputBuffer(outIndex);
                if ((bufInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) bufInfo.size = 0;
                if (bufInfo.size > 0 && muxerStarted && ob != null) {
                    ob.position(bufInfo.offset);
                    ob.limit(bufInfo.offset + bufInfo.size);
                    muxer.writeSampleData(trackIndex, ob, bufInfo);
                }
                codec.releaseOutputBuffer(outIndex, false);
                if ((bufInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }
    }

    void stop(final DoneCallback cb) {
        if (!running) { if (cb != null) cb.onDone(false, null); return; }
        running = false;
        if (handler == null) { finalizeAndCallback(cb); return; }
        handler.removeCallbacks(frameLoop);
        handler.post(new Runnable() {
            @Override public void run() { finalizeAndCallback(cb); }
        });
    }

    private void finalizeAndCallback(DoneCallback cb) {
        boolean ok = false;
        try {
            int inIndex = codec.dequeueInputBuffer(10000);
            if (inIndex >= 0) {
                long pts = (System.nanoTime() - startNs) / 1000;
                codec.queueInputBuffer(inIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
            drainEncoder(true);
            ok = true;
        } catch (Throwable ignore) {
            ok = false;
        }
        releaseQuietly();
        boolean fileOk = ok && outFile.exists() && outFile.length() > 0;
        if (cb != null) cb.onDone(fileOk, fileOk ? outFile : null);
    }

    private void releaseQuietly() {
        try { if (codec != null) { codec.stop(); } } catch (Throwable ignore) { }
        try { if (codec != null) { codec.release(); } } catch (Throwable ignore) { }
        codec = null;
        try { if (muxer != null && muxerStarted) { muxer.stop(); } } catch (Throwable ignore) { }
        try { if (muxer != null) { muxer.release(); } } catch (Throwable ignore) { }
        muxer = null;
        try { if (thread != null) thread.quitSafely(); } catch (Throwable ignore) { }
        thread = null;
        try { if (frameBitmap != null) frameBitmap.recycle(); } catch (Throwable ignore) { }
        frameBitmap = null;
    }

    private static int pickColorFormat(MediaCodec codec, String mime) {
        try {
            int[] formats = codec.getCodecInfo().getCapabilitiesForType(mime).colorFormats;
            int semi = -1, planar = -1;
            for (int f : formats) {
                if (f == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) semi = f;
                else if (f == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) planar = f;
            }
            if (semi != -1) return semi;
            if (planar != -1) return planar;
        } catch (Throwable ignore) { }
        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
    }

    /** ARGB int[] を YUV420 (NV12=semiPlanar / I420=planar) に変換。BT.601。 */
    private static void argbToYuv(int[] argb, byte[] out, int w, int h, boolean semiPlanar) {
        final int frame = w * h;
        int yi = 0;
        int uvi = frame;                    // NV12: Y の後ろに UV 交互
        int ui = frame;                     // I420: Y, U, V の順
        int vi = frame + frame / 4;
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int p = argb[j * w + i];
                int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                out[yi++] = (byte) (y < 0 ? 0 : (y > 255 ? 255 : y));
                if ((j & 1) == 0 && (i & 1) == 0) {
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    u = u < 0 ? 0 : (u > 255 ? 255 : u);
                    v = v < 0 ? 0 : (v > 255 ? 255 : v);
                    if (semiPlanar) { out[uvi++] = (byte) u; out[uvi++] = (byte) v; }
                    else { out[ui++] = (byte) u; out[vi++] = (byte) v; }
                }
            }
        }
    }
}
