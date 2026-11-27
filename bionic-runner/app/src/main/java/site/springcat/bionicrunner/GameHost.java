package site.springcat.bionicrunner;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * 絵や音を出すプログラムとゲーム画面 (GameActivity) の橋渡し。アプリ全体で 1 つだけ存在する。
 * DisplayServer が受け取った映像を画像として保持し、音を再生し、
 * プログラムがウィンドウを作ったらゲーム画面を開く。
 */
final class GameHost implements DisplayServer.Listener {

    private static GameHost instance;

    /** 最初の呼び出しでサーバを起動する。起動できなければ null (絵と音は使えないが他は動く) */
    static synchronized GameHost get(Context context) {
        if (instance == null) {
            GameHost h = new GameHost(context.getApplicationContext());
            try {
                h.server.start();
            } catch (IOException e) {
                return null;
            }
            instance = h;
        }
        return instance;
    }

    private final Context app;
    private final DisplayServer server = new DisplayServer(this);
    private final Object lock = new Object();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private Bitmap bitmap;
    private String title = "";
    private volatile GameActivity activity;
    private volatile boolean windowOpen;
    private AudioOut audio;

    private GameHost(Context app) {
        this.app = app;
    }

    /** プログラムに渡す接続先 (環境変数 BR_DISPLAY の値) */
    String address() {
        return server.address();
    }

    void sendEvent(int type, int a, int b, int c) {
        server.sendEvent(type, a, b, c);
    }

    /** 接続を切ってプログラムに終了を促す */
    void disconnect() {
        server.dropClient();
    }

    /** プログラムのウィンドウが開いているか */
    boolean isWindowOpen() {
        return windowOpen;
    }

    String title() {
        synchronized (lock) {
            return title;
        }
    }

    /** 画面に出ている GameActivity を登録する (閉じるときは null) */
    void attach(GameActivity a) {
        activity = a;
    }

    void detach(GameActivity a) {
        if (activity == a) activity = null;
    }

    /** 現在のウィンドウの大きさ {幅, 高さ}。まだ無ければ null */
    int[] size() {
        synchronized (lock) {
            return bitmap == null ? null : new int[]{bitmap.getWidth(), bitmap.getHeight()};
        }
    }

    /** 最新のフレームを dst の範囲に描く */
    void draw(Canvas canvas, Rect dst) {
        synchronized (lock) {
            if (bitmap != null) canvas.drawBitmap(bitmap, null, dst, paint);
        }
    }

    // ------------------------------------------ DisplayServer から (通信スレッド)

    @Override
    public void onMode(int width, int height, String newTitle) {
        synchronized (lock) {
            ensureBitmap(width, height);
            title = newTitle;
        }
        windowOpen = true;
        GameActivity a = activity;
        if (a != null) {
            a.onModeChanged();
        } else {
            // ターミナルが前面にある状態で呼ばれるので、裏からの画面起動の制限には当たらない
            app.startActivity(new Intent(app, GameActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    @Override
    public void onFrame(byte[] pixels, int width, int height) {
        synchronized (lock) {
            ensureBitmap(width, height);
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels, 0, width * height * 4));
        }
        GameActivity a = activity;
        if (a != null) a.onFrame();
    }

    /** lock を持った状態で呼ぶ */
    private void ensureBitmap(int width, int height) {
        if (bitmap != null && bitmap.getWidth() == width && bitmap.getHeight() == height) return;
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        // 4 バイト目は「未使用」で値が不定なので、透明度として解釈させない
        bitmap.setHasAlpha(false);
    }

    @Override
    public synchronized void onAudioOpen(int sampleRate, int channels) {
        if (audio != null) audio.close();
        audio = null;
        try {
            audio = new AudioOut(sampleRate, channels);
        } catch (RuntimeException e) {
            // 端末がその形式を再生できない: 音なしで続ける
        }
    }

    @Override
    public synchronized void onAudio(byte[] data, int length) {
        if (audio != null) audio.write(data, length);
    }

    @Override
    public synchronized void onAudioClose() {
        if (audio != null) audio.close();
        audio = null;
    }

    @Override
    public void onDisconnect() {
        windowOpen = false;
        synchronized (lock) {
            bitmap = null;
        }
        GameActivity a = activity;
        if (a != null) a.onProgramExit();
    }

    /** 受け取った PCM を順に鳴らす。再生は専用スレッドで行い、通信スレッドを待たせない */
    private static final class AudioOut {
        private final AudioTrack track;
        private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(24);
        private volatile boolean closed;

        AudioOut(int sampleRate, int channels) {
            int mask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int min = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IllegalArgumentException("unsupported audio format");
            // 約 0.1 秒ぶん。小さすぎると途切れ、大きすぎると音が遅れる
            int size = Math.max(min, sampleRate * channels * 2 / 10);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(mask)
                            .build())
                    .setBufferSizeInBytes(size)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
                    .build();
            track.play();
            Thread t = new Thread(() -> {
                try {
                    while (!closed) {
                        byte[] chunk = queue.take();
                        if (chunk.length == 0) break; // close() からの合図
                        track.write(chunk, 0, chunk.length);
                    }
                } catch (InterruptedException | IllegalStateException ignored) {
                    // 終了時
                }
                try {
                    track.stop();
                } catch (IllegalStateException ignored) {
                    // 既に止まっている
                }
                track.release();
            }, "game-audio");
            t.setDaemon(true);
            t.start();
        }

        void write(byte[] data, int length) {
            byte[] copy = new byte[length];
            System.arraycopy(data, 0, copy, 0, length);
            // 再生が追いつかず溜まったら、古い音を捨てて遅れが積み上がらないようにする
            while (!queue.offer(copy)) queue.poll();
        }

        void close() {
            closed = true;
            queue.clear();
            queue.offer(new byte[0]);
        }
    }
}
