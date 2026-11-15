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
 * 入力した行はそのままシェルへ流すので、実行中のプログラムへの入力にもなる。
 *
 * 補助プログラム ptyrun があればシェルを疑似端末の中で動かす (端末モード)。プログラムから見て
 * 相手が端末になるので、出力が行ごとに届き、入力の表示 (エコー) や Ctrl-C も端末が処理する。
 * ptyrun が使えないときはパイプで直接つなぐ (パイプモード)。この場合、出力をため込む
 * プログラムは終了するまで何も表示されないことがある。
 * どちらのモードでも画面は文字が流れるだけなので、vi のような全画面プログラムは動かない。
 */
public final class RootShell {

    public interface Listener {
        /** 別スレッドから呼ばれる */
        void onOutput(String text);

        /** シェルが終了した。別スレッドから呼ばれる */
        void onExit(int code);
    }

    private static final String MARK = "@@BIONIC_RUNNER_READY:";

    private final Listener listener;
    private final String ptyHelper;
    private Process process;
    private OutputStream stdin;
    private volatile boolean root;
    private volatile boolean tty;
    private volatile int shellPid = -1;
    private volatile boolean closed;
    /** 補助プログラムをアプリ側から起動できなかった理由 (起動できていれば null) */
    private volatile String ptyError;
    /** シェルの最初の応答 (PID と端末かどうか) が届いたら開く */
    private final java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
    /** root のシェルから読めるはずのファイル。読めなければ予備策を試す (未設定なら試さない) */
    private String accessProbe;
    private int appPid;

    /** @param ptyHelper ptyrun 実行ファイルのパス。無ければ null (パイプモードになる) */
    public RootShell(Listener listener, String ptyHelper) {
        this.listener = listener;
        this.ptyHelper = ptyHelper;
    }

    public boolean isRoot() {
        return root;
    }

    /** 端末モードで動いているか。真なら入力した行は端末が表示するので、呼び出し側で表示しなくてよい */
    public boolean isTty() {
        return tty;
    }

    /**
     * root のシェルがアプリの作業領域を読めるかの確認に使うファイルと、このアプリのプロセス番号。
     * start() の前に呼ぶ。
     */
    public void setAccessProbe(String file, int pid) {
        accessProbe = file;
        appPid = pid;
    }

    /** 端末モードにできなかったときの手がかり。分からなければ null */
    public String ptyError() {
        return ptyError;
    }

    public boolean isAlive() {
        return process != null && !closed;
    }

    /** su が実際に uid 0 をくれるか確かめる。権限の確認画面が出ている間はここで待つ */
    public static boolean probeRoot() {
        return RootCheck.check().state == RootCheck.State.GRANTED;
    }

    /** ブロックする (su の許可待ち) ので UI スレッド以外から呼ぶこと */
    public void start(String initScript) throws IOException {
        root = probeRoot();
        String sh = root ? RootCheck.su() : "sh";
        Process started = null;
        // root のときは su をパイプで起動し、疑似ターミナルには下で root のシェルの中から入る。
        // su を疑似ターミナル付きで起動すると、root 管理アプリ側の端末中継を経由することになり、
        // その経路の root シェルがアプリの作業領域に入れなくなる端末があったため。
        if (ptyHelper != null && !root) {
            try {
                started = new ProcessBuilder(ptyHelper, sh).redirectErrorStream(true).start();
            } catch (IOException e) {
                // アプリ側からは起動できなかった。下でシェルの中から起動し直してみる
                ptyError = e.getMessage();
            }
        }
        if (started == null) {
            started = new ProcessBuilder(sh).redirectErrorStream(true).start();
        }
        process = started;
        stdin = process.getOutputStream();
        final InputStream out = process.getInputStream();
        final Process proc = process;

        Thread t = new Thread(() -> {
            StringBuilder head = new StringBuilder(); // 準備完了の目印を取り出すまでの溜め
            // マルチバイト文字が読み取りの境目で割れても壊れないよう Reader で復号する
            InputStreamReader reader = new InputStreamReader(out, StandardCharsets.UTF_8);
            char[] cbuf = new char[8192];
            try {
                int n;
                while ((n = reader.read(cbuf)) > 0) {
                    String s = new String(cbuf, 0, n);
                    if (shellPid < 0) {
                        head.append(s);
                        s = takeReadyLine(head);
                        if (s == null || s.isEmpty()) continue;
                    }
                    listener.onOutput(s);
                }
            } catch (IOException ignored) {
                // シェル終了時に来る
            }
            if (shellPid < 0 && head.length() > 0) {
                listener.onOutput(head.toString()); // 目印の前に終了した: 理由が書いてあるはず
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

        // root のシェルからアプリの作業領域が読めない場合の予備策: アプリのプロセスと同じ
        // 「ファイルの見え方」(マウント名前空間) に入り直す。読めていれば何もしない。
        if (root && accessProbe != null && appPid > 0) {
            String probe = RootSession.sq(accessProbe);
            String ns = "nsenter -t " + appPid + " -m";
            send("[ -r " + probe + " ] || { " + ns + " /system/bin/true >/dev/null 2>&1 && exec "
                    + ns + " /system/bin/sh; }");
            pause(300);
        }

        // まだ端末の中にいなければ、シェルの中から補助プログラムを起動して入り直す。
        // アプリからは実行できなくても、root のシェルからなら実行できることがあるため。
        // 先に空のコマンドで試し、動くと分かってから置き換える (失敗するとシェルごと終わるので)。
        if (ptyHelper != null) {
            String h = RootSession.sq(ptyHelper);
            send("[ -t 0 ] || { " + h + " true >/dev/null 2>&1 && exec " + h + " sh; }");
            pause(400); // 置き換わった後のシェルが次の行を読むようにする
        }

        // 最初の 1 行で、行編集を切り、PID と「端末かどうか」を報告させ、初期設定をして、
        // プロンプトを付ける。目印は文字列を途中で切って書く: 端末モードではこの行自体が
        // エコーされるので、そのまま書くとエコーの方を目印と見間違えてしまう。
        String half = MARK.substring(0, 8);
        String rest = MARK.substring(8);
        send("set +o emacs +o vi 2>/dev/null; PS1=''; export TERM=dumb; "
                + "if [ -t 0 ]; then _t=1; else _t=0; fi; "
                + "echo \"" + half + "\"\"" + rest + "$$:$_t\"; "
                // プロンプトには今いるフォルダを出す (単一引用符なので、表示のたびに展開される)。
                // 初期設定より先に済ませる: 初期設定の途中で失敗しても、プロンプトは出るように。
                + "if [ $_t = 1 ]; then PS1='${PWD} " + (root ? "# " : "$ ") + "'; fi"
                + (initScript == null || initScript.isEmpty() ? "" : "; " + initScript));

        // 端末モードかどうかが分かるまで待ってから戻る。呼び出し側は戻った直後にコマンドを
        // 送ることがあり、そのとき「入力を自分で表示するか」を正しく判断できるようにするため。
        try {
            ready.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 溜めた出力から「目印:PID:端末フラグ」の行を探して状態を設定する。
     * まだ届いていなければ null。届いたら、画面に出すべき残りの文字列を返す。
     */
    private String takeReadyLine(StringBuilder head) {
        int at = head.indexOf(MARK);
        int nl = at < 0 ? -1 : head.indexOf("\n", at);
        if (nl < 0) {
            if (head.length() < 8192) return null;
            shellPid = 0; // 目印が来ない: 諦めてそのまま流す
            ready.countDown();
            return head.toString();
        }
        String[] f = head.substring(at + MARK.length(), nl).trim().split(":");
        try {
            shellPid = Integer.parseInt(f[0]);
        } catch (NumberFormatException e) {
            shellPid = 0;
        }
        tty = f.length > 1 && f[1].equals("1");
        ready.countDown();
        // 端末モードでは目印より前に「最初のプロンプト」と「初期設定行のエコー」が来るので捨てる。
        // パイプモードでは目印より前に来るのは su などの警告なので残す。
        return (tty ? "" : head.substring(0, at)) + head.substring(nl + 1);
    }

    /** 1 行送る。失敗 (シェルが死んでいる) なら false */
    public boolean send(String line) {
        return write(line + "\n");
    }

    private synchronized boolean write(String text) {
        if (stdin == null || closed) return false;
        try {
            stdin.write(text.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 実行中のコマンドを中断する (Ctrl-C 相当)。シェル自身は残る */
    public void interrupt() {
        if (tty) {
            write("\u0003"); // 端末が SIGINT に変えて前面のプログラムへ届ける
            return;
        }
        final int pid = shellPid;
        if (pid <= 0) return;
        new Thread(() -> {
            // シェル自身は残し、その子 (実行中のコマンド) だけに送る
            String cmd = "pkill -INT -P " + pid;
            try {
                Process p = (root
                        ? new ProcessBuilder(RootCheck.su(), "-c", cmd)
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
                    Process k = new ProcessBuilder(RootCheck.su(), "-c",
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
