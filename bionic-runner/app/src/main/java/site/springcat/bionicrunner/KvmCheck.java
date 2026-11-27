package site.springcat.bionicrunner;

import android.content.Context;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 仮想マシンで KVM (CPU の仮想化の機能) を使えるかの診断。
 *
 *  1. CPU のコアの種類を調べる (MIDR_EL1。Tensor G3 なら Cortex-X3・A715・A510 が混ざっている)
 *  2. /dev/kvm をアプリの権限と root の両方で開き、仮想 CPU で実際に命令を動かしてみる
 *     (libbrkvm.so probe。SELinux に拒否されると、ファイルの権限が足りていても開けない)
 *  3. Alpine Linux の仮想マシンを KVM で何回か起動し、コアを固定しない場合と、同じ種類のコアだけに
 *     固定した場合で、毎回最後まで起動できるかと、かかった秒数を比べる
 *  4. 安定していた中で一番よい設定を保存する。アプリの権限で使えたときだけ、普段の起動でも KVM を使う
 *
 * 結果は files/home/kvm-diag.txt にも保存する。中に名前などの個人の情報は入れない。
 */
final class KvmCheck {

    interface Log {
        void line(String s);
    }

    /** 同じ種類のコアのまとまり */
    static final class Group {
        String name = "";
        int count;
        /** "4,5,6,7" の形 */
        String cpus = "";
        long maxFreqKhz;
    }

    /** KVM で動かすときの設定 */
    static final class Plan {
        /** 固定するコア (null = 固定しない) */
        String cpus;
        int smp = 1;
        boolean pmuOff;
        /** 待ち時間の記録 (steal time) を中の Linux に渡さない */
        boolean noSteal;
        /** root でしか /dev/kvm を開けなかった */
        boolean root;
        String label = "";

        String encode() {
            return "cpus=" + (cpus == null ? "" : cpus) + "\nsmp=" + smp + "\npmuoff=" + pmuOff + "\nnosteal=" + noSteal
                    + "\nroot=" + root + "\nlabel=" + label.replace('\n', ' ') + "\n";
        }

        static Plan decode(String s) {
            Plan p = new Plan();
            for (String line : s.split("\n")) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String k = line.substring(0, eq), v = line.substring(eq + 1).trim();
                switch (k) {
                    case "cpus":
                        p.cpus = v.matches("[0-9,\\-]{1,64}") ? v : null;
                        break;
                    case "smp":
                        try {
                            p.smp = Math.max(1, Math.min(8, Integer.parseInt(v)));
                        } catch (NumberFormatException e) {
                            p.smp = 1;
                        }
                        break;
                    case "pmuoff":
                        p.pmuOff = v.equals("true");
                        break;
                    case "nosteal":
                        p.noSteal = v.equals("true");
                        break;
                    case "root":
                        p.root = v.equals("true");
                        break;
                    case "label":
                        p.label = v;
                        break;
                    default:
                        break;
                }
            }
            return p;
        }
    }

    /** 1 回の起動の結果 */
    static final class Boot {
        boolean ok;
        boolean timedOut;
        double seconds;
        String reason = "";
        /** 拒否されたメモリの登録 (調整役の記録から) */
        String rejected = "";
        /** 失敗したときの、中の Linux の最後の出力 */
        String tail = "";
        /** 時間切れのときの、仮想 CPU の場所とスレッドの様子 */
        String debug = "";
    }

    private KvmCheck() {
    }

    static File tool(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "libbrkvm.so");
    }

    private static File planFile(Context c) {
        return new File(Vm.dir(c, Vm.Arch.AARCH64), "kvm-plan.txt");
    }

    static File reportFile(Context c) {
        return new File(new File(c.getFilesDir(), "home"), "kvm-diag.txt");
    }

    /** 診断で保存した設定 (root が要るもの・無いものは null) */
    static Plan savedAny(Context c) {
        try (InputStream in = new java.io.FileInputStream(planFile(c))) {
            return Plan.decode(new String(Icons.readAll(in), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    /** 普段の起動で使う設定。アプリの権限で KVM を使えると確かめられたときだけ返す */
    static Plan saved(Context c) {
        Plan p = savedAny(c);
        if (p == null || p.root || !tool(c).isFile()) return null;
        return p;
    }

    static void forget(Context c) {
        planFile(c).delete();
    }

    // ------------------------------------------------------------------ コア

    static List<Group> groups(String coresOutput) {
        List<Group> out = new ArrayList<>();
        for (String line : coresOutput.split("\n")) {
            if (!line.startsWith("group ")) continue;
            Group g = new Group();
            for (String part : line.substring(6).split(" ")) {
                int eq = part.indexOf('=');
                if (eq < 0) continue;
                String k = part.substring(0, eq), v = part.substring(eq + 1);
                try {
                    if (k.equals("name")) g.name = v;
                    else if (k.equals("count")) g.count = Integer.parseInt(v);
                    else if (k.equals("cpus")) g.cpus = v;
                    else if (k.equals("maxfreq")) g.maxFreqKhz = Long.parseLong(v);
                } catch (NumberFormatException ignored) {
                    // 読めない値は 0 のまま
                }
            }
            if (g.count > 0 && g.cpus.matches("[0-9,]+")) out.add(g);
        }
        return out;
    }

    /** 仮想マシンに使うコア: 数が多い (4 まで) ものを優先し、同じなら速い (最大の周波数が高い) もの */
    static Group best(List<Group> gs) {
        Group b = null;
        for (Group g : gs) {
            if (b == null || Math.min(4, g.count) > Math.min(4, b.count)
                    || (Math.min(4, g.count) == Math.min(4, b.count) && g.maxFreqKhz > b.maxFreqKhz)) {
                b = g;
            }
        }
        return b;
    }

    // ------------------------------------------------------------------ 実行の道具

    static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** コマンドを動かして出力 (標準出力と標準エラー) を返す。終了コードは out[0] に入る */
    static String run(List<String> argv, String[] env, int timeoutSec, int[] code) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
            if (env != null) {
                Map<String, String> m = pb.environment();
                for (String e : env) {
                    int eq = e.indexOf('=');
                    if (eq > 0) m.put(e.substring(0, eq), e.substring(eq + 1));
                }
            }
            p = pb.start();
            p.getOutputStream().close();
            final InputStream in = p.getInputStream();
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thread t = new Thread(() -> {
                byte[] b = new byte[4096];
                int n;
                try {
                    while ((n = in.read(b)) > 0) {
                        synchronized (buf) {
                            if (buf.size() < 256 * 1024) buf.write(b, 0, n);
                        }
                    }
                } catch (IOException ignored) {
                    // 終わった
                }
            });
            t.start();
            boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!done) p.destroyForcibly();
            t.join(2000);
            if (code != null) code[0] = done ? p.exitValue() : 124;
            synchronized (buf) {
                return buf.toString("UTF-8");
            }
        } catch (IOException e) {
            if (code != null) code[0] = 127;
            return "起動できません: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (code != null) code[0] = 130;
            return "中断されました";
        } finally {
            if (p != null) p.destroy();
        }
    }

    /**
     * 環境変数を「export 名前='値'; 」の形にする。env コマンドは使わない: env は「=」を含む引数を
     * 変数の設定と読むので、アプリの場所 (/data/app/~~…==/…) のようにパスに「=」があると、
     * そこでコマンドを読み違える (実際に起きた不具合)
     */
    static String exports(String[] env) {
        StringBuilder sb = new StringBuilder();
        if (env == null) return "";
        for (String e : env) {
            int eq = e.indexOf('=');
            if (eq <= 0 || !e.substring(0, eq).matches("[A-Za-z_][A-Za-z0-9_]*")) continue;
            sb.append("export ").append(e, 0, eq).append('=').append(q(e.substring(eq + 1))).append("; ");
        }
        return sb.toString();
    }

    private static List<String> asRoot(List<String> argv, String[] env) {
        StringBuilder sb = new StringBuilder(exports(env)).append("exec ");
        for (int i = 0; i < argv.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(q(argv.get(i)));
        }
        return Arrays.asList(RootCheck.su(), "-c", sb.toString());
    }

    /** libbrkvm.so probe を、アプリの権限か root で動かす */
    static String probe(Context c, boolean root, String cpus, int[] code) {
        List<String> argv = new ArrayList<>(Arrays.asList(tool(c).getAbsolutePath(), "probe"));
        if (cpus != null) argv.add(cpus);
        return run(root ? asRoot(argv, null) : argv, null, 60, code);
    }

    /**
     * Alpine を KVM (kvm が null なら TCG) で起動し、「login:」が出るまでの秒数を測る。
     * 出たらすぐ止める。ディスクへの書き込みは捨てる設定 (-snapshot) なので、何も残らない。
     */
    static Boot boot(Context c, Plan kvm, int timeoutSec) {
        Boot r = new Boot();
        List<String> argv = Vm.argv(c, Vm.Arch.AARCH64, false, 512, kvm);
        // 止まった場所が分かるように、中の Linux の起動の記録をすべて出させる
        for (int i = 0; i + 1 < argv.size(); i++) {
            if (argv.get(i).equals("-append")) argv.set(i + 1, argv.get(i + 1).replace(" quiet",
                    " ignore_loglevel earlycon=pl011,0x09000000"));
        }
        String[] env = Vm.env(c, kvm != null, true);
        File pidFile = new File(c.getCacheDir(), "kvm-test.pid");
        pidFile.delete();
        boolean root = kvm != null && kvm.root;
        // 止まったときに、仮想 CPU がどこにいるか (QEMU の監視の画面) と、スレッドの様子を見るため
        File monitor = new File(c.getCacheDir(), "kvm-test.mon");
        monitor.delete();
        argv.add("-pidfile");
        argv.add(pidFile.getAbsolutePath());
        argv.add("-monitor");
        argv.add("unix:" + monitor.getAbsolutePath() + ",server=on,wait=off");
        if (root) {
            // root の QEMU はアプリから直接止められないので、番号を書かせておき、root で止める。
            // 万一止め損ねても、timeout が決まった時間で止める
            List<String> withTimeout = new ArrayList<>(Arrays.asList("/system/bin/timeout", "-s", "KILL",
                    String.valueOf(timeoutSec + 30)));
            withTimeout.addAll(argv);
            argv = asRoot(withTimeout, env);
        }
        long start = System.nanoTime();
        Process p = null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
            if (!root) {
                Map<String, String> m = pb.environment();
                for (String e : env) {
                    int eq = e.indexOf('=');
                    if (eq > 0) m.put(e.substring(0, eq), e.substring(eq + 1));
                }
            }
            p = pb.start();
            final InputStream in = p.getInputStream();
            final ByteArrayOutputStream shared = out;
            Thread reader = new Thread(() -> {
                byte[] b = new byte[4096];
                int n;
                try {
                    while ((n = in.read(b)) > 0) {
                        synchronized (shared) {
                            if (shared.size() < 1024 * 1024) shared.write(b, 0, n);
                        }
                    }
                } catch (IOException ignored) {
                    // 終わった
                }
            });
            reader.start();
            long limit = start + timeoutSec * 1_000_000_000L;
            while (true) {
                String s;
                synchronized (shared) {
                    s = new String(shared.toByteArray(), StandardCharsets.UTF_8);
                }
                String fail = failure(s);
                if (s.contains("login:")) {
                    r.ok = true;
                    break;
                }
                if (fail != null) {
                    r.reason = fail;
                    break;
                }
                if (!p.isAlive()) {
                    reader.join(1000);
                    synchronized (shared) {
                        s = new String(shared.toByteArray(), StandardCharsets.UTF_8);
                    }
                    if (s.contains("login:")) {
                        r.ok = true;
                    } else {
                        fail = failure(s);
                        r.reason = fail != null ? fail : "途中で終わった (終了コード " + p.exitValue() + ")"
                                + lastLine(s);
                    }
                    break;
                }
                if (System.nanoTime() > limit) {
                    r.timedOut = true;
                    r.reason = timeoutSec + " 秒たっても起動が終わらない" + lastLine(s);
                    r.debug = inspect(c, root, monitor, pidFile);
                    break;
                }
                Thread.sleep(200);
            }
        } catch (IOException e) {
            r.reason = "起動できません: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            r.reason = "中断されました";
        } finally {
            r.seconds = (System.nanoTime() - start) / 1e9;
            String s;
            synchronized (out) {
                s = new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
            StringBuilder rej = new StringBuilder();
            List<String> console = new ArrayList<>();
            for (String l : s.split("\n")) {
                String t = l.replace("\r", "").trim();
                if (t.startsWith("brkvmshim:")) {
                    if (t.startsWith("brkvmshim: メモリの登録") && !t.endsWith("→ ok") && rej.length() < 2000) {
                        rej.append("      ").append(t).append('\n');
                    }
                } else if (!t.isEmpty()) {
                    console.add(t);
                }
            }
            r.rejected = rej.toString();
            StringBuilder tail = new StringBuilder();
            for (int i = Math.max(0, console.size() - 12); i < console.size(); i++) {
                String t = console.get(i);
                if (t.length() > 200) t = t.substring(0, 200);
                tail.append("      | ").append(t).append('\n');
            }
            r.tail = tail.toString();
            if (root) stopRootQemu(pidFile);
            if (p != null) {
                p.destroy();
                try {
                    if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return r;
    }

    /** 止まっている QEMU の中を見る: 仮想 CPU の一覧・各仮想 CPU の命令の場所 (PC) と状態・スレッドの様子 */
    private static String inspect(Context c, boolean root, File monitor, File pidFile) {
        String b = q(tool(c).getAbsolutePath()), m = q(monitor.getAbsolutePath());
        String script = "p=$(cat " + q(pidFile.getAbsolutePath()) + " 2>/dev/null); "
                + b + " hmp " + m + " 'info cpus'; echo '-- 仮想 CPU の場所:'; "
                + b + " hmp " + m + " 'info registers -a'; echo '-- スレッド:'; "
                + "[ -n \"$p\" ] && " + b + " threads \"$p\"";
        List<String> argv = root ? Arrays.asList(RootCheck.su(), "-c", script) : Arrays.asList("sh", "-c", script);
        String out = run(argv, null, 30, null);
        StringBuilder sb = new StringBuilder();
        for (String l : out.split("\n")) {
            String t = l.trim();
            if (t.isEmpty()) continue;
            // 命令の場所 (PC)・状態 (PSTATE)・仮想 CPU の見出し・一覧・スレッドの行だけを残す
            if (t.startsWith("X") || t.startsWith("Q") || t.startsWith("FPCR") || t.startsWith("FPSR")
                    || t.startsWith("Z") || t.startsWith("P0") || t.startsWith("FFR") || t.startsWith("D")) {
                if (!t.contains("PC=")) continue;
            }
            if (t.contains("PC=")) {
                int i = t.indexOf("PC=");
                t = t.substring(i, Math.min(t.length(), i + 19));
            }
            if (sb.length() < 6000) sb.append("      ").append(t).append('\n');
        }
        return sb.toString();
    }

    private static void stopRootQemu(File pidFile) {
        String pf = q(pidFile.getAbsolutePath());
        run(Arrays.asList(RootCheck.su(), "-c",
                "p=$(cat " + pf + " 2>/dev/null); [ -n \"$p\" ] && kill -9 \"$p\"; rm -f " + pf),
                null, 30, null);
    }

    /** 起動の失敗の印 (見つからなければ null) */
    static String failure(String s) {
        String[][] marks = {
                {"error registering slot", "仮想マシンのメモリの登録を KVM に拒否された (保護モードの KVM の制限)"},
                {"Exec format error", "仮想 CPU が動けないコアに移された (KVM_RUN が Exec format error)"},
                {"kvm run failed", "仮想 CPU が止まった (kvm run failed)"},
                {"kvm_arm_vcpu_init failed", "仮想 CPU を作れない (kvm_arm_vcpu_init failed)"},
                {"failed to initialize kvm", "KVM を使い始められない"},
                {"Could not access KVM kernel module", "/dev/kvm を開けない"},
                {"Kernel panic", "中の Linux が止まった (Kernel panic)"},
                {"Internal error:", "中の Linux で内部エラー"},
                {"rcu_preempt detected stalls", "中の Linux で CPU が応答しない (RCU stall)"},
        };
        for (String[] m : marks) if (s.contains(m[0])) return m[1] + lastLineWith(s, m[0]);
        return null;
    }

    private static String lastLine(String s) {
        String l = "";
        for (String x : s.split("\n")) {
            String t = x.replace("\r", "").trim();
            if (!t.isEmpty() && !t.startsWith("brkvmshim:")) l = t;
        }
        if (l.length() > 160) l = l.substring(l.length() - 160);
        return l.isEmpty() ? "" : " / 最後の出力: " + l;
    }

    private static String lastLineWith(String s, String key) {
        int i = s.lastIndexOf(key);
        int a = s.lastIndexOf('\n', i) + 1, b = s.indexOf('\n', i);
        String l = s.substring(a, b < 0 ? s.length() : b).trim();
        if (l.length() > 160) l = l.substring(0, 160);
        return " / " + l;
    }

    // ------------------------------------------------------------------ 診断の全体

    /** 診断する。時間がかかる (数分) ので UI スレッド以外で呼ぶ。結果の文章を返す */
    static String diagnose(Context c, int repeat, Log log) {
        StringBuilder rep = new StringBuilder();
        Log both = s -> {
            rep.append(s).append('\n');
            log.line(s);
        };
        both.line("== Bionic Runner KVM の診断 " + new java.util.Date());
        both.line("== 端末: " + Build.MANUFACTURER + " " + Build.MODEL
                + (Build.VERSION.SDK_INT >= 31 ? " / SoC " + Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL : "")
                + " / Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        both.line("== カーネル: " + System.getProperty("os.version"));
        if (!tool(c).isFile()) {
            both.line("この端末の種類 (ABI) 用の診断の道具がありません。KVM は arm64 の端末だけで使えます");
            return finish(c, rep);
        }

        both.line("");
        both.line("== 1. CPU のコア");
        int[] code = new int[1];
        String cores = run(Arrays.asList(tool(c).getAbsolutePath(), "cores"), null, 20, code);
        for (String l : cores.trim().split("\n")) both.line("  " + l);
        List<Group> gs = groups(cores);
        if (gs.size() > 1) {
            both.line("  → 種類の違うコアが " + gs.size() + " 種類混ざっています。仮想 CPU が別の種類のコアへ"
                    + "移されると止まることがあるので、固定した場合も試します");
        }

        both.line("");
        both.line("== 2. /dev/kvm を開けるか (仮想 CPU で命令を 4 つ動かすところまで)");
        both.line("-- アプリの権限:");
        String appOut = probe(c, false, null, code);
        boolean appOk = code[0] == 0;
        for (String l : appOut.trim().split("\n")) both.line("  " + l);
        both.line(appOk ? "  → 使えます" : "  → 使えません" + hint(appOut));

        boolean rootOk = false;
        RootCheck.Result rc = RootCheck.find() == null ? null : RootCheck.check();
        both.line("-- root:");
        if (rc == null || rc.state != RootCheck.State.GRANTED) {
            both.line("  root を使えないので省きました" + (rc == null ? " (su が見つかりません)" : " (拒否されました)"));
        } else {
            String rootOut = probe(c, true, null, code);
            rootOk = code[0] == 0;
            for (String l : rootOut.trim().split("\n")) both.line("  " + l);
            both.line(rootOk ? "  → 使えます" : "  → 使えません" + hint(rootOut));
            if (!appOk && rootOk) {
                both.line("  → アプリの権限では SELinux などに止められ、root なら使えます");
            }
        }
        if (!appOk && !rootOk) {
            both.line("");
            both.line("KVM は使えません。仮想マシンは今まで通り、CPU を真似て (TCG) 動かします");
            forget(c);
            return finish(c, rep);
        }
        boolean root = !appOk;
        // 固定したときにも命令が動くか (コアの種類ごと)
        both.line("-- コアに固定して (" + (root ? "root" : "アプリの権限") + "):");
        for (Group g : gs) {
            String o = probe(c, root, g.cpus, code);
            both.line("  " + g.name + " (" + g.cpus + "): " + (code[0] == 0 ? "ok" : "ng" + hint(o)));
        }

        both.line("");
        both.line("== 3. Alpine Linux を KVM で起動して比べる (" + (root ? "root" : "アプリの権限")
                + "、メモリ 512MB、各 " + repeat + " 回、1 回 45 秒まで)");
        both.line("(KVM を使わない場合は、前の診断で 6 分たっても起動が終わらなかったので、比べるのを省きます)");
        if (!Vm.ready(c, Vm.Arch.AARCH64)) {
            both.line("Alpine Linux (ARM64) がまだ準備されていないので省きました。準備してからもう一度診断してください");
            return finish(c, rep);
        }
        // 仮想 CPU の数ごと・コアの種類ごとに試す。数を増やしたときに止まるなら、何が原因かを
        // 見分けるため、一番よいまとまりでは、待ち時間の記録 (steal time) や性能カウンタ (PMU) を切った場合も試す
        Group bestGroup = best(gs);
        List<Plan> plans = new ArrayList<>();
        for (Group g : gs) {
            int max = Math.min(4, g.count);
            List<Integer> counts = new ArrayList<>();
            counts.add(1);
            if (g == bestGroup && max > 2) counts.add(2);
            if (max > 1) counts.add(max);
            for (int n : counts) plans.add(plan(root, g, n, false, false));
            if (g == bestGroup && max > 1) {
                plans.add(plan(root, g, max, true, false));
                plans.add(plan(root, g, max, false, true));
            }
        }
        if (bestGroup != null && bestGroup.count > 1) plans.add(plan(root, null, Math.min(4, bestGroup.count), false, false));
        Plan chosen = null;
        double chosenAvg = 0;
        for (Plan p : plans) {
            both.line("-- " + p.label);
            int ok = 0;
            double sum = 0;
            for (int i = 1; i <= repeat; i++) {
                Boot b = boot(c, p, 45);
                if (b.ok) {
                    ok++;
                    sum += b.seconds;
                }
                both.line(String.format(Locale.ROOT, "   %d 回目: %s (%.1f 秒)%s", i,
                        b.ok ? "起動した" : b.timedOut ? "時間切れ" : "失敗", b.seconds,
                        b.ok ? "" : " … " + b.reason));
                if (!b.ok && !b.tail.isEmpty()) {
                    both.line("      中の Linux の最後の出力:");
                    for (String l : b.tail.split("\n")) both.line(l);
                }
                if (!b.debug.isEmpty()) {
                    both.line("      止まっていたときの中の様子:");
                    for (String l : b.debug.split("\n")) both.line(l);
                }
                if (!b.rejected.isEmpty()) {
                    both.line("      拒否されたメモリの登録:");
                    for (String l : b.rejected.split("\n")) both.line(l);
                }
            }
            double avg = ok > 0 ? sum / ok : 0;
            both.line(String.format(Locale.ROOT, "   → %d / %d 回起動%s", ok, repeat,
                    ok > 0 ? String.format(Locale.ROOT, "、平均 %.1f 秒", avg) : ""));
            // 毎回起動できたものの中から、仮想 CPU が多いもの → 固定したもの → 速いもの、の順で選ぶ
            // (固定しない設定は、たまたま同じ種類のコアに載って動いただけのことがあるため、固定を優先する)
            if (ok == repeat) {
                boolean better;
                if (chosen == null) {
                    better = true;
                } else if (p.smp != chosen.smp) {
                    better = p.smp > chosen.smp;
                } else if ((chosen.cpus == null) != (p.cpus == null)) {
                    better = p.cpus != null;
                } else {
                    better = avg < chosenAvg;
                }
                if (better) {
                    chosen = p;
                    chosenAvg = avg;
                }
            }
        }
        both.line("");
        both.line("== 4. まとめ");
        if (chosen == null) {
            both.line("どの設定でも毎回は起動できませんでした。KVM は使わず、今まで通り CPU を真似て動かします");
            forget(c);
        } else {
            both.line("一番よかった設定: " + chosen.label + String.format(Locale.ROOT, " (平均 %.1f 秒)", chosenAvg));
            try {
                Glibc.writeText(planFile(c), chosen.encode());
            } catch (IOException e) {
                both.line("設定を保存できません: " + e.getMessage());
            }
            if (chosen.root) {
                both.line("root でしか使えないため、普段の起動ではまだ使いません (次の段階の Windows 用で使う予定)");
            } else {
                both.line("普段の起動 (「3. 起動」) でも、この設定で KVM を使います");
            }
        }
        return finish(c, rep);
    }

    private static Plan plan(boolean root, Group g, int smp, boolean pmuOff, boolean noSteal) {
        Plan p = new Plan();
        p.cpus = g == null ? null : g.cpus;
        p.smp = smp;
        p.pmuOff = pmuOff;
        p.noSteal = noSteal;
        p.root = root;
        p.label = (g == null ? "固定しない" : g.name + " (" + g.cpus + ") に固定") + "・仮想 CPU " + smp + " 個"
                + (pmuOff ? "・性能カウンタ (PMU) 無し" : "") + (noSteal ? "・待ち時間の記録 (steal time) 無し" : "");
        return p;
    }

    private static String hint(String out) {
        for (String l : out.split("\n")) {
            if (l.contains(" ng ") || l.endsWith(" ng") || l.startsWith("open ng") || l.startsWith("vm ng")
                    || l.startsWith("vcpu ng") || l.startsWith("run ng") || l.startsWith("pin ng")
                    || l.startsWith("mem ng") || l.startsWith("api ng")) {
                return " (" + l + ")";
            }
        }
        return "";
    }

    private static String finish(Context c, StringBuilder rep) {
        File f = reportFile(c);
        f.getParentFile().mkdirs();
        try {
            Glibc.writeText(f, rep.toString());
        } catch (IOException ignored) {
            // 保存できなくても画面には出ている
        }
        return rep.toString();
    }
}
