package site.springcat.bionicrunner;

import java.util.ArrayList;
import java.util.List;

/**
 * /proc の内容から CPU 使用率とプロセスごとの負荷を求める (タスクマネージャー用)。
 * 2 回の計測の差から使用率を出す。Android の API は使っていない。
 */
final class ProcStats {

    static final class Proc {
        int pid;
        /** 実行ファイル名 (カーネルが持つ短い名前。15 文字まで) */
        String name;
        /** これまでに使った CPU 時間 (ティック) */
        long ticks;
        /** 使用中の実メモリ (ページ数) */
        long rssPages;
    }

    /** 1 回ぶんの計測結果 */
    static final class Sample {
        /** 全 CPU の経過時間の合計と、そのうち何もしていなかった時間 (ティック)。取れなければ -1 */
        long total = -1;
        long idle = -1;
        final List<Proc> procs = new ArrayList<>();
    }

    /** 計測用のシェルコマンド。1 行目が /proc/stat の先頭行、区切り行のあと各プロセスの stat */
    static final String COMMAND = "head -n 1 /proc/stat 2>/dev/null; echo ---; "
            + "for p in /proc/[0-9]*; do read -r l < \"$p/stat\" && echo \"$l\"; done 2>/dev/null";

    private ProcStats() {
    }

    static Sample parse(String output) {
        Sample s = new Sample();
        boolean procs = false;
        for (String line : output.split("\n")) {
            if (line.equals("---")) {
                procs = true;
            } else if (!procs) {
                parseCpu(line, s);
            } else {
                Proc p = parseProc(line);
                if (p != null) s.procs.add(p);
            }
        }
        return s;
    }

    /** "cpu  user nice system idle iowait irq softirq steal ..." */
    private static void parseCpu(String line, Sample s) {
        String[] f = line.trim().split("\\s+");
        if (f.length < 5 || !f[0].equals("cpu")) return;
        try {
            long total = 0;
            // guest 時間 (9 番目以降) は user に含まれているので足さない
            for (int i = 1; i < Math.min(f.length, 9); i++) total += Long.parseLong(f[i]);
            long idle = Long.parseLong(f[4]) + (f.length > 5 ? Long.parseLong(f[5]) : 0);
            s.total = total;
            s.idle = idle;
        } catch (NumberFormatException ignored) {
            // 取れなかった扱いのまま
        }
    }

    /** "pid (name) state ppid ... utime stime ... rss ..."。名前に空白や括弧が入ることがある */
    private static Proc parseProc(String line) {
        int open = line.indexOf('(');
        int close = line.lastIndexOf(')');
        if (open < 1 || close < open) return null;
        String[] f = line.substring(close + 1).trim().split(" ");
        // close の後ろは 3 番目の項目 (state) から始まる: utime は 14 番目、stime は 15 番目、rss は 24 番目
        if (f.length < 22) return null;
        try {
            Proc p = new Proc();
            p.pid = Integer.parseInt(line.substring(0, open).trim());
            p.name = line.substring(open + 1, close);
            p.ticks = Long.parseLong(f[11]) + Long.parseLong(f[12]);
            p.rssPages = Long.parseLong(f[21]);
            return p;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 2 回の計測の間の CPU 使用率 (0〜100)。求められなければ -1 */
    static int cpuPercent(Sample before, Sample after) {
        if (before == null || before.total < 0 || after.total < 0) return -1;
        long dt = after.total - before.total;
        if (dt <= 0) return -1;
        long busy = dt - (after.idle - before.idle);
        return (int) Math.max(0, Math.min(100, Math.round(busy * 100.0 / dt)));
    }

    /**
     * 各プロセスが 2 回の計測の間に使った CPU の割合 (全コア合計に対する %) を、
     * after.procs と同じ並びで返す。前回いなかったプロセスは 0。
     */
    static double[] procPercent(Sample before, Sample after) {
        double[] out = new double[after.procs.size()];
        if (before == null || before.total < 0 || after.total <= before.total) return out;
        long dt = after.total - before.total;
        java.util.HashMap<Integer, Long> prev = new java.util.HashMap<>();
        for (Proc p : before.procs) prev.put(p.pid, p.ticks);
        for (int i = 0; i < out.length; i++) {
            Proc p = after.procs.get(i);
            Long was = prev.get(p.pid);
            if (was != null && p.ticks >= was) out[i] = (p.ticks - was) * 100.0 / dt;
        }
        return out;
    }
}
