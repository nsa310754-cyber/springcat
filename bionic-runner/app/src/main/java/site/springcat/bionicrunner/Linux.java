package site.springcat.bionicrunner;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * アプリの中の Linux 環境 (Alpine Linux)。X11 (Xサーバー) のアプリを動かすために使う。
 *
 * Android には /usr/bin や /bin/sh といった普通の Linux の置き場所が無く、X のアプリはそれを前提に
 * 作られているので、そのままでは動かない。そこで Alpine Linux の最小構成 (minirootfs、約 4MB) を
 * files/linux/rootfs に展開し、同梱の PRoot (libproot.so) で「その中にいるように見せて」動かす。
 * PRoot は root を使わない (ptrace でシステムコールの場所を書き換える) ので、root が無い端末でも動く。
 *
 * X11 は、この中で動かす Xvnc (TigerVNC の X サーバー) が描き、アプリの画面 ({@link X11Activity})
 * がそれを映す。両者は rootfs の中の UNIX ソケット (アプリ専用の場所なので、ほかのアプリからは
 * つなげない) でつなぐ。
 */
final class Linux {

    /** 中で apk add する、X11 に最低限必要なもの (X サーバー・端末・ウィンドウの枠・字体) */
    static final String X_PACKAGES = "tigervnc xterm twm xsetroot font-misc-misc";
    /** X の画面番号 (DISPLAY=:1) */
    static final String DISPLAY = ":1";
    /** rootfs の中での、ビューアとつなぐソケットの場所 */
    static final String VNC_SOCKET = "/tmp/.br-vnc";

    private Linux() {
    }

    /** この端末の CPU 用の Alpine の名前。対応していなければ null */
    static String alpineArch() {
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "";
        switch (abi) {
            case "arm64-v8a": return "aarch64";
            case "armeabi-v7a": return "armv7";
            case "x86_64": return "x86_64";
            case "x86": return "x86";
            default: return null;
        }
    }

    static boolean supported(Context c) {
        return alpineArch() != null && proot(c).isFile();
    }

    static File dir(Context c) {
        return new File(c.getFilesDir(), "linux");
    }

    static File rootfs(Context c) {
        return new File(dir(c), "rootfs");
    }

    private static File proot(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "libproot.so");
    }

    /** proot が一時ファイルを置く場所 (rootfs の外) */
    private static File tmp(Context c) {
        return new File(dir(c), "tmp");
    }

    private static File versionFile(Context c) {
        return new File(dir(c), "version");
    }

    /** Linux 環境が展開済みか */
    static boolean ready(Context c) {
        return new File(rootfs(c), "bin/busybox").exists() && versionFile(c).isFile();
    }

    /** X11 に必要なもの (Xvnc) が入っているか */
    static boolean hasX(Context c) {
        return new File(rootfs(c), "usr/bin/Xvnc").exists();
    }

    static String version(Context c) {
        try {
            return new String(Icons.readAll(new java.io.FileInputStream(versionFile(c))),
                    StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    /** 端末で rootfs が使っている大きさ */
    static long size(Context c) {
        return Archive.treeSize(dir(c));
    }

    /** Linux 環境を消す (中で入れたものも全部消える) */
    static void remove(Context c) {
        Archive.deleteTree(dir(c));
    }

    // ------------------------------------------------------------------ 準備

    /** Alpine の minirootfs を取ってきて確かめ、展開する。時間がかかるので UI スレッド以外で呼ぶ */
    static void prepare(Context c, Vm.Progress progress) throws IOException {
        String arch = alpineArch();
        if (arch == null) throw new IOException("この端末の CPU には対応していません");
        File d = dir(c);
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("保存先を作れません");
        String base = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/" + arch + "/";
        progress.update("最新版を確認しています…", 0);
        String[] release = Vm.latest(new String(Vm.fetchSmall(base + "latest-releases.yaml"),
                StandardCharsets.UTF_8), "alpine-minirootfs", ".tar.gz");
        String file = release[0], sha256 = release[1], ver = release[2];
        long size = Long.parseLong(release[3]);
        long free = d.getUsableSpace();
        if (free > 0 && free < 600L * 1024 * 1024) {
            throw new IOException("空き容量が足りません (X11 まで入れると 600MB ほど必要です)");
        }
        File part = new File(d, "rootfs.tar.gz.part");
        Vm.download(base + file, part, size, sha256, (m, p) -> progress.update(
                "Alpine Linux " + ver + " (" + arch + ") をダウンロード中… " + p + "%", p));
        progress.update("展開しています…", 100);
        File fresh = new File(d, "rootfs.new");
        Archive.deleteTree(fresh);
        try {
            Archive.extractRootfs(part, fresh);
        } finally {
            part.delete();
        }
        if (!new File(fresh, "bin/busybox").exists()) {
            Archive.deleteTree(fresh);
            throw new IOException("展開した中に Linux の基本のコマンド (busybox) がありません");
        }
        File root = rootfs(c);
        Archive.deleteTree(root);
        if (!fresh.renameTo(root)) throw new IOException("保存できません");
        Glibc.writeText(versionFile(c), ver + "\n");
        configure(c);
    }

    /**
     * 中の設定を Android に合わせる: 名前解決 (DNS) の問い合わせ先と、ホスト名。
     * 起動のたびに呼ぶ (Wi-Fi とモバイル通信を切り替えると、問い合わせ先が変わるため)
     */
    static void configure(Context c) {
        File root = rootfs(c);
        if (!root.isDirectory()) return;
        StringBuilder rc = new StringBuilder("# Bionic Runner が起動のたびに書き直します\n");
        for (String dns : dnsServers(c)) rc.append("nameserver ").append(dns).append('\n');
        try {
            File etc = new File(root, "etc");
            File resolv = new File(etc, "resolv.conf");
            if (!Archive.isSymlink(resolv)) Glibc.writeText(resolv, rc.toString());
            File hosts = new File(etc, "hosts");
            if (!hosts.isFile() || hosts.length() == 0) {
                Glibc.writeText(hosts, "127.0.0.1 localhost\n::1 localhost\n");
            }
            new File(root, "tmp").mkdirs();
            tmp(c).mkdirs();
            writeScripts(root);
        } catch (IOException ignored) {
            // 書けなくても、中に入ること自体はできる
        }
    }

    /** いまつながっているネットワークの DNS と、予備の公開 DNS */
    private static List<String> dnsServers(Context c) {
        Set<String> out = new LinkedHashSet<>();
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    c.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.Network net = cm == null ? null : cm.getActiveNetwork();
            android.net.LinkProperties lp = net == null ? null : cm.getLinkProperties(net);
            if (lp != null) {
                for (InetAddress a : lp.getDnsServers()) {
                    String h = a.getHostAddress();
                    if (h != null && !h.contains("%")) out.add(h);
                }
            }
        } catch (RuntimeException ignored) {
            // 調べられなければ予備だけを使う
        }
        out.add("8.8.8.8");
        out.add("1.1.1.1");
        return new ArrayList<>(out);
    }

    /** X11 の準備と起動に使うスクリプトを rootfs の中に置く */
    private static void writeScripts(File root) throws IOException {
        File bin = new File(root, "usr/local/bin");
        if (!bin.isDirectory() && !bin.mkdirs()) return;
        File setup = new File(bin, "br-x11-setup");
        Glibc.writeText(setup, "#!/bin/sh\n"
                + "# Bionic Runner: X11 に必要なものを入れる\n"
                + "set -e\n"
                + "echo '>>> apk update'\n"
                + "apk update\n"
                + "echo '>>> apk add " + X_PACKAGES + "'\n"
                + "apk add " + X_PACKAGES + "\n"
                + "echo '>>> 完了しました'\n");
        File session = new File(bin, "br-x11-session");
        Glibc.writeText(session, "#!/bin/sh\n"
                + "# Bionic Runner: X サーバー (Xvnc) と、最初の画面 (ウィンドウの枠と端末) を起動する。\n"
                + "# /root/.xsession があれば、代わりにそれを動かす (例: apk add xfce4 のあと、\n"
                + "# echo startxfce4 > /root/.xsession)\n"
                + "G=${1:-1024x768}\n"
                + "rm -f /tmp/.X1-lock /tmp/.X11-unix/X1 " + VNC_SOCKET + "\n"
                + "mkdir -p /tmp/.X11-unix && chmod 1777 /tmp/.X11-unix\n"
                // TCP では待ち受けない (同じ端末のほかのアプリからつながれないように)。
                // ビューアとは、アプリだけが入れる場所の UNIX ソケットでつなぐ
                + "Xvnc " + DISPLAY + " -geometry \"$G\" -depth 24 -SecurityTypes None"
                + " -rfbunixpath " + VNC_SOCKET + " -rfbunixmode 0600 -rfbport -1"
                + " -nolisten tcp -nolisten local -AlwaysShared &\n"
                + "XPID=$!\n"
                + "i=0\n"
                + "while [ ! -S " + VNC_SOCKET + " ] && [ $i -lt 200 ]; do sleep 0.1; i=$((i + 1)); done\n"
                + "export DISPLAY=" + DISPLAY + "\n"
                + "cd /root\n"
                + "if [ -f /root/.xsession ]; then\n"
                + "  sh /root/.xsession &\n"
                + "else\n"
                + "  xsetroot -solid '#1e3a5f' 2>/dev/null\n"
                + "  twm &\n"
                + "  xterm -geometry 80x24+16+16 &\n"
                + "fi\n"
                + "echo BR_X11_READY\n"
                + "wait $XPID\n");
        for (File f : new File[]{setup, session}) {
            try {
                android.system.Os.chmod(f.getAbsolutePath(), 0755);
            } catch (android.system.ErrnoException ignored) {
                // sh で呼ぶので実行の権限が無くても動く
            }
        }
    }

    // ------------------------------------------------------------------ 起動

    /**
     * Linux 環境の中で command (null ならログインシェル) を動かすコマンド。
     * 環境変数は {@link #env} で足すこと。
     */
    static List<String> argv(Context c, List<String> command) {
        File root = rootfs(c);
        List<String> a = new ArrayList<>(Arrays.asList(proot(c).getAbsolutePath(),
                "--kill-on-exit", "--link2symlink", "--sysvipc", "-0",
                "-r", root.getAbsolutePath(), "-w", "/root",
                "-b", "/dev", "-b", "/proc", "-b", "/sys",
                "-b", "/proc/self/fd:/dev/fd"));
        // 端末の内部ストレージを /sdcard として見せる (読み書きできる場合)
        File sd = android.os.Environment.getExternalStorageDirectory();
        if (sd != null && sd.canRead()) a.addAll(Arrays.asList("-b", sd.getAbsolutePath() + ":/sdcard"));
        // Android 側の設定 (PATH など) は持ち込まず、普通の Linux の値で始める
        a.addAll(Arrays.asList("/usr/bin/env", "-i", "HOME=/root", "USER=root", "LOGNAME=root",
                "TERM=xterm-256color", "LANG=C.UTF-8", "TMPDIR=/tmp", "DISPLAY=" + DISPLAY,
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"));
        if (command == null) a.addAll(Arrays.asList("/bin/sh", "-l"));
        else a.addAll(command);
        return a;
    }

    /** proot が使う環境変数 (ローダの場所と、一時ファイルの場所) を足す */
    static void env(Context c, Map<String, String> env) {
        String lib = c.getApplicationInfo().nativeLibraryDir;
        env.put("PROOT_LOADER", new File(lib, "libproot-loader.so").getAbsolutePath());
        File l32 = new File(lib, "libproot-loader32.so");
        if (l32.isFile()) env.put("PROOT_LOADER_32", l32.getAbsolutePath());
        tmp(c).mkdirs();
        env.put("PROOT_TMP_DIR", tmp(c).getAbsolutePath());
        env.remove("LD_PRELOAD");
    }

    /** シェルの関数 linux が使う環境変数の設定 (1 行。コマンドプロンプトの起動時に送る) */
    static String shellExports(Context c) {
        Map<String, String> e = new java.util.LinkedHashMap<>();
        env(c, e);
        StringBuilder sb = new StringBuilder("export BR_LINUX=" + MainActivity.sq(rootfs(c).getAbsolutePath()));
        for (Map.Entry<String, String> kv : e.entrySet()) {
            sb.append("; export ").append(kv.getKey()).append('=').append(MainActivity.sq(kv.getValue()));
        }
        File sd = android.os.Environment.getExternalStorageDirectory();
        sb.append("; export BR_LINUX_SD=").append(MainActivity.sq(sd == null ? "" : sd.getAbsolutePath()));
        return sb.toString();
    }
}
