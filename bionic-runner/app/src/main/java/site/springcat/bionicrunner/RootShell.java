package site.springcat.bionicrunner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 常駐するシェル 1 本。su が使えれば root シェル、使えなければ通常の sh (制限モード)。
 * 入力した行はそのままシェルの標準入力へ流すので、実行中のプログラムへの入力にもなる。
 * (疑似端末は使っていないため、vi のような全画面プログラムは動かない)
 */
public final class RootShell {

    public interface Listener {
        /** 別スレッドから呼ばれる */
        void onOutput(String text);

        /** シェルが終了した。別スレッドから呼ばれる */
        void onExit(int code);
    }

    private static final String PID_MARK = "@@BIONIC_RUNNER_PID:";

    private final Listener listener;
    private Process process;
    private OutputStream stdin;
    private volatile boolean root;
    private volatile int shellPid = -1;
    private volatile boolean closed;

    public RootShell(Listener listener) {
        this.listener = listener;
    }

    public boolean isRoot() {
        return root;
    }

    public boolean isAlive() {
        return process != null && !closed;
    }

    /** su が実際に uid 0 をくれるか確かめる。権限ダイアログが出る間はここで待つ */
    public static boolean probeRoot() {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start();
            p.getOutputStream().close();
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line = r.readLine();
            if (!p.waitFor(120, TimeUnit.SECONDS)) return false;
            return line != null && line.trim().equals("0");
        } catch (IOException e) {
            return false; // su コマンド自体が無い
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** ブロックする (su の許可待ち) ので UI スレッド以外から呼ぶこと */
    public void start(String initScript) throws IOException {
        root = probeRoot();
        process = new ProcessBuilder(root ? "su" : "sh").redirectErrorStream(true).start();
        stdin = process.getOutputStream();
        final InputStream out = process.getInputStream();
        final Process proc = process;

        Thread t = new Thread(() -> {
            StringBuilder head = new StringBuilder(); // PID 行を取り出すまでの溜め
            // マルチバイト文字が読み取りの境目で割れても壊れないよう Reader で復号する
            InputStreamReader reader = new InputStreamReader(out, StandardCharsets.UTF_8);
            char[] cbuf = new char[8192];
            try {
                int n;
                while ((n = reader.read(cbuf)) > 0) {
                    String s = new String(cbuf, 0, n);
                    if (shellPid < 0) {
                        head.append(s);
                        int at = head.indexOf(PID_MARK);
                        int nl = at < 0 ? -1 : head.indexOf("\n", at);
                        if (nl < 0) {
                            if (head.length() < 4096) continue;
                            shellPid = 0; // 目印が来ない: 諦めてそのまま流す
                            s = head.toString();
                        } else {
                            try {
                                shellPid = Integer.parseInt(
                                        head.substring(at + PID_MARK.length(), nl).trim());
                            } catch (NumberFormatException e) {
                                shellPid = 0;
                            }
                            s = head.substring(0, at) + head.substring(nl + 1);
                        }
                        if (s.isEmpty()) continue;
                    }
                    listener.onOutput(s);
                }
            } catch (IOException ignored) {
                // シェル終了時に来る
            }
            int code = -1;
            try {
                code = proc.waitFor();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (!closed) {
                closed = true;
                listener.onExit(code);
            }
        }, "shell-reader");
        t.setDaemon(true);
        t.start();

        send("echo \"" + PID_MARK + "$$\"");
        if (initScript != null && !initScript.isEmpty()) send(initScript);
    }

    /** 1 行送る。失敗 (シェルが死んでいる) なら false */
    public synchronized boolean send(String line) {
        if (stdin == null || closed) return false;
        try {
            stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 標準入力を閉じる (Ctrl-D 相当) は行わず、実行中の子プロセスに SIGINT を送る (Ctrl-C 相当) */
    public void interrupt() {
        final int pid = shellPid;
        if (pid <= 0) return;
        new Thread(() -> {
            // シェル自身は残し、その子 (実行中のコマンド) だけに送る
            String cmd = "pkill -INT -P " + pid;
            try {
                Process p = (root
                        ? new ProcessBuilder("su", "-c", cmd)
                        : new ProcessBuilder("sh", "-c", cmd)).start();
                p.waitFor(10, TimeUnit.SECONDS);
                p.destroy();
            } catch (IOException ignored) {
                // 送れなければ何もしない (利用者は「再起動」を使える)
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "shell-interrupt").start();
    }

    public void close() {
        closed = true;
        final int pid = shellPid;
        final boolean wasRoot = root;
        final Process p = process;
        if (p == null) return;
        try {
            if (stdin != null) stdin.close();
        } catch (IOException ignored) {
            // 既に閉じている
        }
        new Thread(() -> {
            // root のプロセスはアプリの uid からは kill できないので su 経由で片付ける
            if (wasRoot && pid > 0) {
                try {
                    Process k = new ProcessBuilder("su", "-c",
                            "pkill -KILL -P " + pid + "; kill -KILL " + pid).start();
                    k.waitFor(10, TimeUnit.SECONDS);
                    k.destroy();
                } catch (IOException ignored) {
                    // 片付けは最善努力
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            p.destroy();
        }, "shell-close").start();
    }
}
