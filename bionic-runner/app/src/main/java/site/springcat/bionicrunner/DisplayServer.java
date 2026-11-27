package site.springcat.bionicrunner;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * 実行中のプログラム (同梱の SDL2) から映像と音を受け取り、入力を送り返すサーバ。
 * 端末内のループバック (127.0.0.1) だけで待ち受ける。通信の形式は native/sdl/SDL_brconn.h と同じ。
 * Android の API は使っていないので、PC 上の JVM でも単体で検証できる。
 */
final class DisplayServer {

    interface Listener {
        /** ウィンドウが作られた (大きさが変わった) */
        void onMode(int width, int height, String title);

        /** 1 フレーム届いた。pixels は使い回すので、戻る前に必要な分を写し取ること */
        void onFrame(byte[] pixels, int width, int height);

        void onAudioOpen(int sampleRate, int channels);

        /** PCM (符号付き 16bit リトルエンディアン)。data は使い回すので戻る前に写し取ること */
        void onAudio(byte[] data, int length);

        void onAudioClose();

        /** プログラムが終了した、または接続が切れた */
        void onDisconnect();
    }

    static final int EV_KEY = 1;
    static final int EV_MOUSE_MOTION = 2;
    static final int EV_MOUSE_BUTTON = 3;
    static final int EV_QUIT = 4;
    static final int EV_TEXT = 5;

    private static final int MSG_HELLO = 1;
    private static final int MSG_MODE = 2;
    private static final int MSG_FRAME = 3;
    private static final int MSG_AUDIO_OPEN = 4;
    private static final int MSG_AUDIO = 5;
    private static final int MSG_AUDIO_CLOSE = 6;

    /** 1 辺の上限。これを超える大きさは不正な要求として切断する */
    private static final int MAX_SIDE = 4096;
    private static final int MAX_AUDIO_CHUNK = 1 << 20;

    private final Listener listener;
    private final String token;
    private ServerSocket server;
    private volatile Client current;
    /** プログラムへの送信専用のスレッド */
    private final java.util.concurrent.ExecutorService sender =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "display-send");
                t.setDaemon(true);
                return t;
            });

    DisplayServer(Listener listener) {
        this.listener = listener;
        // 端末内の他のアプリも 127.0.0.1 へは接続できるので、合言葉を知っている相手だけ受け付ける
        byte[] raw = new byte[16];
        new SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder();
        for (byte b : raw) sb.append(String.format("%02x", b));
        token = sb.toString();
    }

    void start() throws IOException {
        server = new ServerSocket(0, 4, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
        Thread t = new Thread(() -> {
            while (true) {
                final Socket s;
                try {
                    s = server.accept();
                } catch (IOException e) {
                    return; // サーバを閉じた
                }
                Thread c = new Thread(() -> serve(s), "display-client");
                c.setDaemon(true);
                c.start();
            }
        }, "display-accept");
        t.setDaemon(true);
        t.start();
    }

    /** プログラムに渡す接続先。環境変数 BR_DISPLAY に入れる */
    String address() {
        return "127.0.0.1:" + server.getLocalPort() + ":" + token;
    }

    boolean hasClient() {
        return current != null;
    }

    /**
     * 入力イベントを送る。接続中のプログラムが無ければ何もしない。
     * 実際の送信は専用のスレッドで行う。この関数は画面のタッチ処理 (メインスレッド) から呼ばれるが、
     * Android はメインスレッドでの通信を禁止していて、行うとアプリが強制終了するため。
     * 1 本のスレッドで順に送るので、イベントの順序は保たれる。
     */
    void sendEvent(final int type, final int a, final int b, final int c) {
        final Client cl = current;
        if (cl != null) sender.execute(() -> cl.send(type, a, b, c));
    }

    /** 接続を切る。プログラム側は「画面が閉じられた」として終了処理に入る (これも専用スレッドで行う) */
    void dropClient() {
        final Client cl = current;
        if (cl != null) sender.execute(cl::close);
    }

    private final class Client {
        final Socket socket;
        final OutputStream out;

        Client(Socket socket) throws IOException {
            this.socket = socket;
            this.out = socket.getOutputStream();
        }

        synchronized void send(int type, int a, int b, int c) {
            byte[] m = new byte[16];
            putLe(m, 0, type);
            putLe(m, 4, a);
            putLe(m, 8, b);
            putLe(m, 12, c);
            try {
                out.write(m);
                out.flush();
            } catch (IOException e) {
                close();
            }
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 既に閉じている
            }
        }
    }

    private void serve(Socket socket) {
        Client client = null;
        try {
            socket.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(
                    new java.io.BufferedInputStream(socket.getInputStream(), 1 << 16));
            // 最初のメッセージは合言葉でなければならない
            int type = readLe(in);
            int len = readLe(in);
            if (type != MSG_HELLO || len < 0 || len > 256) return;
            byte[] hello = new byte[len];
            in.readFully(hello);
            if (!token.equals(new String(hello, StandardCharsets.US_ASCII))) return;

            client = new Client(socket);
            Client old = current;
            current = client; // 画面は 1 つなので、後から来たプログラムが引き継ぐ
            if (old != null) old.close();

            byte[] frame = new byte[0];
            byte[] audio = new byte[0];
            while (true) {
                type = readLe(in);
                len = readLe(in);
                if (len < 0) throw new IOException("不正な長さ");
                switch (type) {
                    case MSG_MODE: {
                        if (len < 8 || len > 8 + 1024) throw new IOException("不正な MODE");
                        int w = readLe(in), h = readLe(in);
                        byte[] title = new byte[len - 8];
                        in.readFully(title);
                        checkSize(w, h);
                        listener.onMode(w, h, new String(title, StandardCharsets.UTF_8));
                        break;
                    }
                    case MSG_FRAME: {
                        if (len < 8) throw new IOException("不正な FRAME");
                        int w = readLe(in), h = readLe(in);
                        checkSize(w, h);
                        int size = w * h * 4;
                        if (len - 8 != size) throw new IOException("FRAME の大きさが合わない");
                        if (frame.length != size) frame = new byte[size];
                        in.readFully(frame);
                        if (current == client) listener.onFrame(frame, w, h);
                        break;
                    }
                    case MSG_AUDIO_OPEN: {
                        if (len != 8) throw new IOException("不正な AUDIO_OPEN");
                        int rate = readLe(in), channels = readLe(in);
                        if (rate < 4000 || rate > 192000 || channels < 1 || channels > 2) {
                            throw new IOException("対応していない音声形式");
                        }
                        if (current == client) listener.onAudioOpen(rate, channels);
                        break;
                    }
                    case MSG_AUDIO: {
                        if (len > MAX_AUDIO_CHUNK) throw new IOException("AUDIO が大きすぎる");
                        if (audio.length < len) audio = new byte[len];
                        in.readFully(audio, 0, len);
                        if (current == client) listener.onAudio(audio, len);
                        break;
                    }
                    case MSG_AUDIO_CLOSE:
                        skip(in, len);
                        if (current == client) listener.onAudioClose();
                        break;
                    default:
                        skip(in, len); // 将来の拡張: 知らない種別は読み飛ばす
                }
            }
        } catch (IOException e) {
            // プログラムの終了 (EOF) を含む。下で後始末する
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 既に閉じている
            }
            // 別のプログラムに引き継がれた後なら、その画面を閉じてしまわないよう何も通知しない
            if (client != null && current == client) {
                current = null;
                listener.onAudioClose();
                listener.onDisconnect();
            }
        }
    }

    private static void checkSize(int w, int h) throws IOException {
        if (w < 1 || h < 1 || w > MAX_SIDE || h > MAX_SIDE) throw new IOException("不正な画面サイズ");
    }

    private static void skip(DataInputStream in, int len) throws IOException {
        if (len > (1 << 26)) throw new IOException("メッセージが大きすぎる");
        while (len > 0) {
            int n = in.skipBytes(len);
            if (n <= 0) {
                if (in.read() < 0) throw new EOFException();
                n = 1;
            }
            len -= n;
        }
    }

    private static int readLe(DataInputStream in) throws IOException {
        return Integer.reverseBytes(in.readInt());
    }

    private static void putLe(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >> 8);
        b[o + 2] = (byte) (v >> 16);
        b[o + 3] = (byte) (v >> 24);
    }
}
