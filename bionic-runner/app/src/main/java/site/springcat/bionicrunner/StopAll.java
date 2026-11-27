package site.springcat.bionicrunner;

import android.app.Activity;
import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「すべて終了」: Bionic Runner から動かしているプログラムを、まとめて止める。
 * (広告を閉じる bot などを止めるのに、それぞれのコマンドプロンプトへ行かなくて済むように)
 *
 * 止めるもの:
 *   - すべてのコマンドプロンプトで実行中のプログラム (その子孫も)。コマンドプロンプト自体は残る
 *   - 画面の上への表示 (br overlay) と常駐
 *   - X11 (X サーバーと、その上の Linux のアプリ)、仮想マシン、ゲームなどの画面
 *   - それ以外の、このアプリの権限で動いている取り残されたプログラム (nohup などで切り離したもの)
 * 止めないもの:
 *   - Linux 環境への追加機能のインストールなど apk の作業 (途中で止めると Linux 環境が壊れるため)
 */
final class StopAll {

    private StopAll() {
    }

    // ------------------------------------------------- 閉じる対象の画面 (ゲーム・X11・仮想マシン)

    private static final List<WeakReference<Activity>> screens = new ArrayList<>();

    /** 「すべて終了」で閉じる画面として登録する (onCreate で呼ぶ) */
    static void register(Activity a) {
        synchronized (screens) {
            screens.add(new WeakReference<>(a));
        }
    }

    private static int finishScreens() {
        List<Activity> list = new ArrayList<>();
        synchronized (screens) {
            for (WeakReference<Activity> r : screens) {
                Activity a = r.get();
                if (a != null && !a.isFinishing()) list.add(a);
            }
            screens.clear();
        }
        for (final Activity a : list) a.runOnUiThread(a::finish);
        return list.size();
    }

    // ------------------------------------------------------------------ 本体

    /** すべて止めて、止めたものの説明を返す。su を待つことがあるので UI スレッド以外で呼ぶ */
    static String run(Context c) {
        Context app = c.getApplicationContext();
        int programs = 0;
        // 1. コマンドプロンプトで実行中のもの (root のものは su 経由で)
        for (RootShell s : RootShell.live()) programs += s.killChildren();
        // 2. 画面の上への表示と常駐
        Control.stopAll(app);
        // 3. X11
        boolean x11 = X11Activity.running();
        X11Activity.stopSession(c);
        // 4. 取り残されたもの (このアプリの権限のものだけ見える)
        programs += killStrays();
        // 5. ゲーム・X11・仮想マシンなどの画面を閉じる (中のプログラムは上で止まっている)
        int windows = finishScreens();
        StringBuilder sb = new StringBuilder();
        sb.append(programs > 0 ? programs + " 個のプログラムを終了しました" : "動いているプログラムはありませんでした");
        if (x11) sb.append("。X11 を終了しました");
        if (windows > 0) sb.append("。").append(windows).append(" 個の画面を閉じました");
        sb.append("。画面の上への表示も消しました");
        return sb.toString();
    }

    /**
     * このアプリの権限で動いているプログラムのうち、残すべきもの以外を止める。止めた数を返す。
     * 残すもの: アプリ自身、コマンドプロンプトのシェルとそれを動かす仕組み (疑似端末・su)、apk の作業
     */
    private static int killStrays() {
        int me = android.os.Process.myPid();
        int uid = android.os.Process.myUid();
        Set<Integer> keep = new HashSet<>();
        keep.add(me);
        for (RootShell s : RootShell.live()) {
            // シェルから、アプリまでの親をたどって残す (root のシェルは見えないので、su の側が残る)
            int p = s.pid();
            for (int i = 0; p > 1 && i < 16; i++) {
                keep.add(p);
                p = ppid(p);
            }
        }
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return 0;
        List<Integer> victims = new ArrayList<>();
        for (File f : procs) {
            int pid;
            try {
                pid = Integer.parseInt(f.getName());
            } catch (NumberFormatException e) {
                continue;
            }
            if (keep.contains(pid) || uidOf(pid) != uid) continue;
            String cmd = cmdline(pid);
            if (cmd == null || isInfrastructure(cmd)) continue;
            // アプリが直接起動したシェル (番号が分からなかったコマンドプロンプト) も残す
            if (ppid(pid) == me && cmd.split("\0", 2)[0].equals("sh")) continue;
            victims.add(pid);
        }
        // 先に全部止めてから消す (止めている最中に、新しい子を作られないように)
        for (int pid : victims) signal(pid, android.system.OsConstants.SIGSTOP);
        for (int pid : victims) signal(pid, android.system.OsConstants.SIGKILL);
        return victims.size();
    }

    /** 止めてはいけないもの: 疑似端末の仕組み・su・apk の作業 (Linux 環境が壊れるため) */
    private static boolean isInfrastructure(String cmd) {
        String argv0 = cmd.split("\0", 2)[0];
        String base = argv0.substring(argv0.lastIndexOf('/') + 1);
        if (base.equals("libptyrun.so") || base.equals("su") || base.equals("magisk") || base.equals("ksud")) {
            return true;
        }
        String flat = cmd.replace('\0', ' ');
        return flat.contains("apk ") || flat.contains("br-x11-setup");
    }

    private static void signal(int pid, int sig) {
        try {
            android.system.Os.kill(pid, sig);
        } catch (android.system.ErrnoException ignored) {
            // もう終わっている
        }
    }

    private static int ppid(int pid) {
        String stat = read(new File("/proc/" + pid + "/stat"));
        if (stat == null) return -1;
        int close = stat.lastIndexOf(')');
        if (close < 0) return -1;
        String[] f = stat.substring(close + 2).split(" ");
        try {
            return f.length > 1 ? Integer.parseInt(f[1]) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int uidOf(int pid) {
        String status = read(new File("/proc/" + pid + "/status"));
        if (status == null) return -1;
        for (String line : status.split("\n")) {
            if (line.startsWith("Uid:")) {
                String[] f = line.substring(4).trim().split("\\s+");
                try {
                    return Integer.parseInt(f[0]);
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static String cmdline(int pid) {
        String s = read(new File("/proc/" + pid + "/cmdline"));
        return s == null || s.isEmpty() ? null : s;
    }

    private static String read(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n = in.read(buf);
            return n <= 0 ? "" : new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
