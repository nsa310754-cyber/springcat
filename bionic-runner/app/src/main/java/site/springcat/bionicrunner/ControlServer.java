package site.springcat.bionicrunner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * 実行中のプログラムからの依頼 (br コマンド) を受ける窓口。
 *
 * この端末の中だけ (127.0.0.1) で待ち受け、起動のたびに変わる合言葉を知っている相手だけに応える。
 * 接続先と合言葉は環境変数 BR_API="ホスト:ポート:合言葉" でシェルに渡すので、
 * このアプリのコマンドプロンプトから起動したプログラムだけが使える。
 *
 * 決まり (native/br.c と同じ):
 *   受ける: 合言葉 + 改行、続けて引数をタブで区切った 1 行 (\ タブ 改行は \\ \t \n と書かれている)
 *   返す:   1 行目が OK か ERR、2 行目以降が結果 (ERR のときは理由)
 * 依頼の中身の処理は {@link Handler} に任せる。Android の API は使っていないので、
 * PC 上の JVM でも単体で検証できる。
 */
final class ControlServer {

    /** 依頼を処理する側。結果の文字列を返す。できなければ {@link Refused} を投げる */
    interface Handler {
        String handle(String[] args) throws Refused;
    }

    /** 依頼に応えられない (理由を br が表示する) */
    static final class Refused extends Exception {
        Refused(String message) {
            super(message);
        }
    }

    private final Handler handler;
    private final String token;
    private ServerSocket server;

    ControlServer(Handler handler) {
        this.handler = handler;
        byte[] raw = new byte[16];
        new SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder();
        for (byte b : raw) sb.append(String.format("%02x", b));
        token = sb.toString();
    }

    void start() throws IOException {
        server = new ServerSocket(0, 16, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
        Thread t = new Thread(() -> {
            while (true) {
                final Socket s;
                try {
                    s = server.accept();
                } catch (IOException e) {
                    return; // 閉じられた
                }
                Thread c = new Thread(() -> serve(s), "control-client");
                c.setDaemon(true);
                c.start();
            }
        }, "control-accept");
        t.setDaemon(true);
        t.start();
    }

    /** プログラムに渡す接続先 (環境変数 BR_API の値) */
    String address() {
        return "127.0.0.1:" + server.getLocalPort() + ":" + token;
    }

    private void serve(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(10_000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String given = in.readLine();
            // 合言葉の照合は、一致する文字数で時間が変わらない比べ方にする
            if (given == null || !MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
                    token.getBytes(StandardCharsets.UTF_8))) {
                return;
            }
            String line = in.readLine();
            if (line == null) return;
            s.setSoTimeout(0);
            String status, body;
            try {
                body = handler.handle(parse(line));
                status = "OK";
            } catch (Refused e) {
                status = "ERR";
                body = e.getMessage();
            } catch (RuntimeException e) {
                status = "ERR";
                body = "内部エラー: " + e;
            }
            if (body == null) body = "";
            if (!body.isEmpty() && !body.endsWith("\n")) body += "\n";
            OutputStream out = s.getOutputStream();
            out.write((status + "\n" + body).getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) {
            // 相手が途中で切った
        }
    }

    /** タブ区切りの 1 行を引数に戻す */
    static String[] parse(String line) {
        List<String> args = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\t') {
                args.add(cur.toString());
                cur.setLength(0);
            } else if (c == '\\' && i + 1 < line.length()) {
                char n = line.charAt(++i);
                cur.append(n == 't' ? '\t' : n == 'n' ? '\n' : n);
            } else {
                cur.append(c);
            }
        }
        args.add(cur.toString());
        if (args.size() == 1 && args.get(0).isEmpty()) return new String[0];
        return args.toArray(new String[0]);
    }
}
