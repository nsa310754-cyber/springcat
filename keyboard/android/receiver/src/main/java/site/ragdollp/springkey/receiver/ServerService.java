package site.ragdollp.springkey.receiver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 送信側からの TCP 接続を待ち受けるフォアグラウンドサービス。
 * 行区切りの JSON を受け取り、InputDispatcher に渡す。
 * 最初の 1 行は {"t":"hello","pin":"..."} で、PIN が一致したら {"t":"ok"} を返す。
 */
public class ServerService extends Service {

    public static final int PORT = 7777;
    private static final String CH = "springkey_recv";
    private static final int NOTIF_ID = 91;

    public static volatile boolean RUNNING = false;

    /** MainActivity が状態表示のために登録する。 */
    public interface Listener {
        void onLog(String line);
        void onClient(boolean connected, String who);
    }
    private static volatile Listener listener;
    private static final Handler UI = new Handler(Looper.getMainLooper());
    public static void setListener(Listener l) { listener = l; }
    private static void log(final String s) {
        UI.post(() -> { if (listener != null) listener.onLog(s); });
    }
    private static void client(final boolean c, final String who) {
        UI.post(() -> { if (listener != null) listener.onClient(c, who); });
    }

    private Thread acceptThread;
    private volatile ServerSocket server;
    private volatile boolean alive = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (RUNNING) return START_STICKY;
        startForegroundNotif();
        RUNNING = true;
        alive = true;
        acceptThread = new Thread(this::serve, "sk-accept");
        acceptThread.start();
        log("サーバー開始（ポート " + PORT + "）");
        return START_STICKY;
    }

    private void serve() {
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(PORT));
            while (alive) {
                Socket sock;
                try {
                    sock = server.accept();
                } catch (Exception e) {
                    break;
                }
                handleClient(sock);
            }
        } catch (Exception e) {
            log("サーバーエラー: " + e.getMessage());
        } finally {
            close(server);
        }
    }

    private void handleClient(Socket sock) {
        String who = sock.getInetAddress() != null ? sock.getInetAddress().getHostAddress() : "?";
        try {
            sock.setTcpNoDelay(true);
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = sock.getOutputStream();

            String hello = br.readLine();
            String pin = prefs().getString("pin", "");
            String gotPin = extract(hello, "pin");
            if (hello == null || !hello.contains("\"hello\"") || (!pin.isEmpty() && !pin.equals(gotPin))) {
                out.write("{\"t\":\"err\"}\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                log("接続拒否（PIN 不一致）: " + who);
                close(sock);
                return;
            }
            out.write("{\"t\":\"ok\"}\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            client(true, who);
            log("接続: " + who);

            String line;
            while (alive && (line = br.readLine()) != null) {
                if (line.contains("\"bye\"")) break;
                String desc = InputDispatcher.handle(line);
                if (desc != null) log(desc);
            }
        } catch (Exception e) {
            // 切断など
        } finally {
            close(sock);
            client(false, who);
            log("切断: " + who);
        }
    }

    /** きわめて簡易な JSON 文字列値の取り出し（"key":"value"）。 */
    private static String extract(String json, String key) {
        if (json == null) return "";
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) return "";
        i = json.indexOf(':', i + needle.length());
        if (i < 0) return "";
        int q1 = json.indexOf('"', i + 1);
        if (q1 < 0) return "";
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return "";
        return json.substring(q1 + 1, q2);
    }

    private void startForegroundNotif() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CH, getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
        Notification n = b.setContentTitle("SpringKey 受信中")
                .setContentText("送信側からの入力を待っています（ポート " + PORT + "）")
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("sk_recv", MODE_PRIVATE);
    }

    private static void close(java.io.Closeable c) {
        if (c != null) try { c.close(); } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        alive = false;
        RUNNING = false;
        close(server);
        log("サーバー停止");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
