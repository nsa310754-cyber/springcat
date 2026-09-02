package site.ragdollp.springkey.controller;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * SpringKey 送信（キーボード側）
 *
 * WebView にリッチなキーボード UI（assets/keyboard.html）を表示し、
 * JavaScript ブリッジ経由で受け取った入力イベントを、TCP で受信側アプリへ送る。
 * ・つなぎ方は「同じ Wi-Fi / USB テザリング / テザリング(ホットスポット)」など、
 *   相手の IP とポートに届くネットワークであれば何でもよい。
 * ・本物のキーボードに近い操作感を出すため、キー押下時に軽い振動を返す。
 */
public class MainActivity extends Activity {

    private WebView web;
    private Vibrator vibrator;

    // ネットワーク（1 本の TCP 接続を張りっぱなしにして、行区切り JSON を流す）
    private volatile Socket socket;
    private volatile OutputStream out;
    private final LinkedBlockingQueue<String> sendQueue = new LinkedBlockingQueue<>();
    private Thread writerThread;
    private Thread readerThread;
    private volatile boolean connected = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 画面が消えるとキーボードとして使えないので、点灯を維持
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        web = new WebView(this);
        WebView.setWebContentsDebuggingEnabled(true);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setFocusableInTouchMode(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/keyboard.html");

        setContentView(web);
    }

    /** JS 側から呼ばれるブリッジ。すべてバインダースレッドで動く点に注意。 */
    private class Bridge {

        /** 受信側へ接続する。ip/port/pin を渡す。 */
        @JavascriptInterface
        public void connect(final String ip, final int port, final String pin) {
            disconnectInternal();
            saveConn(ip, port, pin);
            writerThread = new Thread(() -> runConnection(ip, port, pin), "sk-net");
            writerThread.start();
        }

        @JavascriptInterface
        public void disconnect() {
            disconnectInternal();
            postStatus("disconnected", "");
        }

        /** 入力イベント（行区切り JSON 文字列）を送信キューへ積む。 */
        @JavascriptInterface
        public void send(String json) {
            if (json == null) return;
            sendQueue.offer(json);
        }

        /** キーの押し心地用の軽い振動。 */
        @JavascriptInterface
        public void haptic(int ms) {
            if (vibrator == null || !vibrator.hasVibrator()) return;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(
                            Math.max(1, ms), VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(ms);
                }
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public String loadConn() {
            SharedPreferences p = getSharedPreferences("sk", MODE_PRIVATE);
            String ip = p.getString("ip", "");
            int port = p.getInt("port", 7777);
            String pin = p.getString("pin", "");
            // JSON っぽく返す（JS 側で JSON.parse）
            return "{\"ip\":\"" + esc(ip) + "\",\"port\":" + port + ",\"pin\":\"" + esc(pin) + "\"}";
        }
    }

    private void saveConn(String ip, int port, String pin) {
        getSharedPreferences("sk", MODE_PRIVATE).edit()
                .putString("ip", ip).putInt("port", port).putString("pin", pin).apply();
    }

    /** 接続本体。writerThread の中で実行される。 */
    private void runConnection(String ip, int port, String pin) {
        Socket s = new Socket();
        try {
            postStatus("connecting", "");
            s.connect(new InetSocketAddress(ip, port), 5000);
            s.setTcpNoDelay(true);
            socket = s;
            out = s.getOutputStream();

            // ハンドシェイク：PIN を送って、受信側の OK を待つ
            String hello = "{\"t\":\"hello\",\"pin\":\"" + esc(pin) + "\",\"name\":\"SpringKey\"}\n";
            out.write(hello.getBytes(StandardCharsets.UTF_8));
            out.flush();

            BufferedReader br = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String first = br.readLine();
            if (first == null || !first.contains("\"ok\"")) {
                postStatus("error", "PIN が違うか、拒否されました");
                closeQuiet(s);
                return;
            }

            connected = true;
            postStatus("connected", ip + ":" + port);

            // 受信スレッド（受信側からの通知を一応読み続ける）
            readerThread = new Thread(() -> {
                try {
                    String line;
                    while ((line = br.readLine()) != null) {
                        // 現状は片方向。将来の拡張用に読み捨て。
                    }
                } catch (Exception ignored) {
                } finally {
                    onDropped();
                }
            }, "sk-reader");
            readerThread.start();

            // 送信ループ
            while (connected) {
                String msg = sendQueue.take();
                if (!connected) break;
                OutputStream o = out;
                if (o == null) break;
                o.write((msg + "\n").getBytes(StandardCharsets.UTF_8));
                o.flush();
            }
        } catch (Exception e) {
            postStatus("error", String.valueOf(e.getMessage()));
        } finally {
            closeQuiet(s);
        }
    }

    private void onDropped() {
        if (connected) {
            connected = false;
            postStatus("disconnected", "");
        }
    }

    private void disconnectInternal() {
        connected = false;
        sendQueue.offer("{\"t\":\"bye\"}"); // take() を解除
        Socket s = socket;
        socket = null;
        out = null;
        closeQuiet(s);
    }

    private static void closeQuiet(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** JS 側の window.skStatus(state, detail) を呼ぶ。 */
    private void postStatus(final String state, final String detail) {
        runOnUiThread(() -> {
            if (web == null) return;
            String js = "window.skStatus && window.skStatus('" + state + "','" + esc(detail) + "')";
            web.evaluateJavascript(js, null);
        });
    }

    private static String esc(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    @Override
    protected void onDestroy() {
        disconnectInternal();
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
