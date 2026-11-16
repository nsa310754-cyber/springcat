package site.springcat.bionicrunner;

import android.content.Context;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * YARA ルール (ファイルの中身の特徴を書いた検出ルール) による検査。
 *
 * 判定はアプリに同梱した本物の YARA (コマンド yara を lib/&lt;ABI&gt;/libyara.so として入れてある) に
 * 任せ、その出力を読む。利用者が追加したルールは files/yara/ に置き、実行ファイルや圧縮ファイルを
 * 開くときの検査 ({@link ExeScanner} の簡易検査) とあわせて使う。
 */
final class Yara {

    /** ルールに一致した 1 件 */
    static final class Match {
        /** 一致したルールの名前 */
        final String rule;
        /** 一致したファイル */
        final String path;
        /** 一致した文字列の最初の 1 つ ("$a: text" の形)。条件だけのルールでは空 */
        String detail = "";

        Match(String rule, String path) {
            this.rule = rule;
            this.path = path;
        }
    }

    private static final String TOOL = "libyara.so";
    /** 1 回の検査にかける時間の上限 (秒)。作りの悪いルールで画面が止まったままにならないように */
    private static final int TIMEOUT = 60;
    private static final int MAX_DETAIL = 100;

    private Yara() {
    }

    static File tool(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, TOOL);
    }

    static boolean available(Context c) {
        return tool(c).isFile();
    }

    static File rulesDir(Context c) {
        return new File(c.getFilesDir(), "yara");
    }

    /** 追加済みのルール (名前順) */
    static File[] rules(Context c) {
        File[] fs = rulesDir(c).listFiles((d, n) -> isRuleName(n));
        if (fs == null) return new File[0];
        Arrays.sort(fs);
        return fs;
    }

    /** ファイル名が YARA ルールのもの (.yar / .yara) か */
    static boolean isRuleName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".yar") || n.endsWith(".yara");
    }

    /** ルールの書き方を確かめる。正しければ null、誤りがあれば YARA の出したメッセージ */
    static String check(Context c, File rule) throws IOException {
        if (!available(c)) throw new IOException("YARA がこのアプリに入っていません");
        // 中身の無いファイルを相手に 1 回走らせる。ルールを読み込めなければ、そこで誤りが報告される
        Process p = new ProcessBuilder(tool(c).getAbsolutePath(), "-w",
                rule.getAbsolutePath(), "/dev/null").redirectErrorStream(true).start();
        try {
            p.getOutputStream().close();
            String out = new String(readAll(p.getInputStream()), StandardCharsets.UTF_8).trim();
            if (!waitFor(p)) return "確認が時間内に終わりませんでした";
            if (p.exitValue() == 0) return null;
            // メッセージに出る一時ファイルの場所は利用者には意味が無いので、名前だけにする
            out = out.replace(rule.getAbsolutePath(), rule.getName());
            return out.isEmpty() ? "ルールを読み込めません" : out;
        } finally {
            p.destroy();
        }
    }

    /** rule で target (ファイル、またはフォルダの中すべて) を調べ、一致したものを返す */
    static List<Match> scan(Context c, File rule, File target) throws IOException {
        if (!available(c)) throw new IOException("YARA がこのアプリに入っていません");
        List<String> cmd = new ArrayList<>(Arrays.asList(tool(c).getAbsolutePath(),
                "-w", "-s", "-N", "-a", String.valueOf(TIMEOUT)));
        if (target.isDirectory()) cmd.add("-r");
        cmd.add(rule.getAbsolutePath());
        cmd.add(target.getAbsolutePath());
        Process p = new ProcessBuilder(cmd).start();
        try {
            p.getOutputStream().close();
            drain(p.getErrorStream());
            List<Match> out = parse(new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)));
            if (!waitFor(p)) throw new IOException("検査が時間内に終わりませんでした");
            return out;
        } finally {
            p.destroy();
        }
    }

    /**
     * yara -s の出力を読む。一致ごとに「ルール名 ファイル」の行があり、続けてその一致した文字列が
     * 「0x位置:$名前: 中身」の行で並ぶ。
     */
    static List<Match> parse(BufferedReader in) throws IOException {
        List<Match> out = new ArrayList<>();
        Match last = null;
        String line;
        while ((line = in.readLine()) != null) {
            if (line.startsWith("0x") && line.contains(":$")) {
                if (last != null && last.detail.isEmpty()) {
                    String d = line.substring(line.indexOf(":$") + 1).trim();
                    last.detail = d.length() > MAX_DETAIL ? d.substring(0, MAX_DETAIL) + "…" : d;
                }
                continue;
            }
            int sp = line.indexOf(' ');
            if (sp <= 0) continue;
            last = new Match(line.substring(0, sp), line.substring(sp + 1));
            out.add(last);
        }
        return out;
    }

    /**
     * 追加済みのルールすべてで target を調べ、一致を検査結果 report に加える。
     * base が null でなければ、ファイルの場所を base からの相対で示す (圧縮ファイルを展開した一式用)。
     */
    static void addFindings(Context c, ExeScanner.Report report, File target, File base) {
        if (!available(c)) return;
        String prefix = base == null ? null : base.getAbsolutePath() + "/";
        for (File rule : rules(c)) {
            try {
                for (Match m : scan(c, rule, target)) {
                    String where = prefix != null && m.path.startsWith(prefix)
                            ? m.path.substring(prefix.length()) + ": " : "";
                    report.findings.add(new ExeScanner.Finding(
                            "YARA ルールに一致: " + m.rule + " (" + rule.getName() + ")",
                            where + (m.detail.isEmpty() ? "ルールの条件に一致" : m.detail)));
                }
            } catch (IOException e) {
                // そのルールでは調べられなかった。黙って「問題なし」にはしない
                report.findings.add(new ExeScanner.Finding(
                        "YARA ルールで検査できませんでした (" + rule.getName() + ")",
                        String.valueOf(e.getMessage())));
            }
        }
    }

    /** 確認済みのルール tmp を、検知で使うルールとして name の名前で置く。置いた名前を返す */
    static String install(Context c, File tmp, String name) throws IOException {
        File dir = rulesDir(c);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("置き場所を作れません");
        File dst = new File(dir, name);
        dst.delete();
        if (!tmp.renameTo(dst)) {
            // 一時置き場が別の記憶領域にあると移動できないので、写してから消す
            byte[] data;
            try (InputStream in = new java.io.FileInputStream(tmp)) {
                data = readAll(in);
            }
            try (java.io.OutputStream os = new java.io.FileOutputStream(dst)) {
                os.write(data);
            }
            tmp.delete();
        }
        // root のシェルで yara コマンドから使うときにも読めるようにする
        dir.setReadable(true, false);
        dir.setExecutable(true, false);
        dst.setReadable(true, false);
        return dst.getName();
    }

    private static boolean waitFor(Process p) throws IOException {
        try {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT + 10);
            while (System.nanoTime() < end) {
                try {
                    p.exitValue();
                    return true;
                } catch (IllegalThreadStateException running) {
                    Thread.sleep(50);
                }
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("中断されました");
        }
    }

    /** 読まずに放っておくと相手が詰まって止まるので、別スレッドで読み捨てる */
    static void drain(final InputStream in) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[4096];
            try {
                while (in.read(buf) > 0) {
                    // 捨てる
                }
            } catch (IOException ignored) {
                // 相手が終了した
            }
        }, "drain");
        t.setDaemon(true);
        t.start();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }
}
