package site.ragdollp.binrunner;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 実際にバイナリを起動する。結果(stdout/stderr/終了コード/OS エラー)は
 * 偽らずそのまま返す。Android の制約でよくある「Permission denied(13)」も
 * そのまま表示して、なぜ動かないかが分かるようにする。
 */
public final class Executor {

    public static final class Out {
        public boolean launched;   // プロセスを起動できたか
        public int exitCode = -1;
        public String log = "";
    }

    private Executor() {}

    /**
     * 直接実行。まず通常 exec を試し、ダメなら root(su)で再試行する。
     * @param bin      実行するファイル(アプリの実行可能領域へコピー済みのもの)
     * @param preferRoot true なら最初から su を使う
     */
    public static Out runDirect(File bin, boolean preferRoot) {
        StringBuilder sb = new StringBuilder();
        Out out = new Out();

        chmodExec(bin);

        if (!preferRoot) {
            sb.append("$ ").append(bin.getAbsolutePath()).append('\n');
            try {
                out = exec(new String[]{bin.getAbsolutePath()}, bin.getParentFile());
                sb.append(out.log);
                if (out.launched) { out.log = sb.toString(); return out; }
            } catch (Exception e) {
                sb.append("[通常実行] 失敗: ").append(e.getMessage()).append('\n');
            }
            // Android 10+ ではここで Permission denied(13)になることが多い
            sb.append("\n通常実行が通らないため root を試します…\n");
        }

        if (hasRoot()) {
            sb.append("$ su -c '").append(bin.getAbsolutePath()).append("'\n");
            Out r = exec(new String[]{"su", "-c",
                    "chmod 755 '" + bin.getAbsolutePath() + "'; '" + bin.getAbsolutePath() + "'"}, null);
            sb.append(r.log);
            r.log = sb.toString();
            return r;
        }

        sb.append("\nroot(su)が見つからないため直接実行はできません。\n"
                + "Android 10 以降はアプリ保存領域のファイルの実行が SELinux で禁じられています。\n"
                + "・端末を root 化する、または\n"
                + "・Termux など実行可能領域を持つ環境へ置く、\n"
                + "のいずれかが必要です。");
        out.launched = false;
        out.log = sb.toString();
        return out;
    }

    /** 変換エンジン経由で実行(エンジンが入っていれば)。 */
    public static Out runVia(String engine, File bin) {
        chmodExec(bin);
        String path = resolveEngine(engine);
        if (path == null) {
            Out o = new Out();
            o.launched = false;
            o.log = engine + " が端末に見つかりません。\n"
                    + "対応エンジンの入手先:\n"
                    + "  box64/box86 … Termux の『tur-repo』や pkg、または Winlator 同梱\n"
                    + "  proot       … Termux: pkg install proot proot-distro\n"
                    + "  wine        … Winlator / Box の Wine パッケージ\n"
                    + "入れた後にこのアプリから PATH の通った場所を指定してください。";
            return o;
        }
        return exec(new String[]{path, bin.getAbsolutePath()}, bin.getParentFile());
    }

    /** そのエンジンが端末で使えるか。 */
    public static boolean hasEngine(String engine) {
        return resolveEngine(engine) != null;
    }

    private static String resolveEngine(String engine) {
        // PATH 上と Termux の標準パスを探す
        List<String> dirs = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null) for (String d : path.split(":")) dirs.add(d);
        dirs.add("/data/data/com.termux/files/usr/bin");
        dirs.add("/system/bin");
        for (String d : dirs) {
            File f = new File(d, engine);
            if (f.canExecute()) return f.getAbsolutePath();
        }
        return null;
    }

    public static boolean hasRoot() {
        for (String d : new String[]{"/system/bin", "/system/xbin", "/sbin",
                "/data/data/com.termux/files/usr/bin"}) {
            if (new File(d, "su").exists()) return true;
        }
        return false;
    }

    private static void chmodExec(File f) {
        try { f.setExecutable(true, true); } catch (Exception ignore) {}
    }

    private static Out exec(String[] cmd, File workdir) {
        Out o = new Out();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (workdir != null) pb.directory(workdir);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            o.launched = true;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int lines = 0;
                while ((line = br.readLine()) != null) {
                    sb.append(line).append('\n');
                    if (++lines > 2000) { sb.append("…(出力が多いので打ち切り)\n"); break; }
                }
            }
            boolean done = p.waitFor(20, TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); sb.append("…(20 秒でタイムアウト)\n"); o.exitCode = -2; }
            else o.exitCode = p.exitValue();
            sb.append("\n[終了コード ").append(o.exitCode).append("]");
            o.log = sb.toString();
        } catch (Exception e) {
            o.launched = false;
            o.log = "起動できませんでした: " + e.getMessage()
                    + "\n(error=13 は SELinux による実行禁止、error=2 はファイル無し/インタプリタ無し)";
        }
        return o;
    }
}
