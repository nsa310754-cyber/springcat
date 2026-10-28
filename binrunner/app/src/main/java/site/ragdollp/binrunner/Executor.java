package site.ragdollp.binrunner;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 実際にバイナリを起動する。結果(stdout/stderr/終了コード/OS エラー)は偽らず返す。
 *
 * このアプリは targetSdk 28 なので、SELinux の実行制限が免除され、アプリ専用領域
 * (codeCacheDir / filesDir)からネイティブバイナリを <b>root 無しで直接 exec</b> できる
 * (Termux が root 無しで動くのと同じ仕組み)。まず直接実行し、通らない端末でだけ root を試す。
 */
public final class Executor {

    public static final class Out {
        public boolean launched;   // プロセスを起動できたか
        public int exitCode = -1;
        public String log = "";
    }

    /** su の候補パス。Magisk/SuperSU は端末により置き場所が違うため総当たりする。 */
    private static final String[] SU_CANDIDATES = {
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/magisk/.core/bin/su", "/debug_ramdisk/su", "/sbin/bin/su",
            "/system/sbin/su", "/data/local/xbin/su", "/data/local/bin/su",
            "/data/adb/su/su", "/vendor/bin/su"
    };

    private Executor() {}

    /**
     * 直接実行。targetSdk 28 のおかげで通常はこれが root 無しで通る。
     * preferRoot=true なら最初から su を使う。通常実行が拒否された時も su を試す。
     */
    public static Out runDirect(File bin, boolean preferRoot) {
        StringBuilder sb = new StringBuilder();
        chmodExec(bin);
        String path = bin.getAbsolutePath();

        if (!preferRoot) {
            sb.append("$ ").append(path).append('\n');
            Out o = exec(new String[]{path}, bin.getParentFile());
            sb.append(o.log);
            if (o.launched) { o.log = sb.toString(); return o; }   // 起動できた(終了コードは別)
            sb.append("\n直接実行が通りませんでした(端末の SELinux 設定次第)。root を試します…\n");
        }

        Out r = runViaSu(path, sb);
        if (r != null) return r;

        sb.append('\n').append(rootHelp());
        Out fail = new Out();
        fail.launched = false;
        fail.log = sb.toString();
        return fail;
    }

    /** su 候補を順に試して実行。1 つでも起動できたらその結果を返す。全滅なら null。 */
    private static Out runViaSu(String path, StringBuilder sb) {
        List<String> tried = candidateSuList();
        for (String su : tried) {
            sb.append("$ ").append(su).append(" -c '…").append(shortName(path)).append("'\n");
            // uid 確認も兼ねて id を出す → chmod → 実行
            String cmd = "id; chmod 755 '" + path + "' && exec '" + path + "'";
            Out r = exec(new String[]{su, "-c", cmd}, new File(path).getParentFile());
            if (r.launched) {
                sb.append(r.log);
                r.log = sb.toString();
                return r;
            }
            sb.append("  → ").append(su).append(": ").append(firstLine(r.log)).append('\n');
        }
        return null;
    }

    /** 変換エンジン経由で実行(エンジンが入っていれば)。 */
    public static Out runVia(String engine, File bin) {
        chmodExec(bin);
        String path = resolveEngine(engine);
        if (path == null) {
            Out o = new Out();
            o.launched = false;
            o.log = engine + " が端末に見つかりません。\n"
                    + "このアプリ単体では別アーキ/別OS 用バイナリの命令変換まではできません。\n"
                    + "対応エンジンの入手先:\n"
                    + "  box64/box86 … Termux の tur-repo / Winlator 同梱\n"
                    + "  proot       … Termux: pkg install proot proot-distro\n"
                    + "  wine        … Winlator の Wine パッケージ\n"
                    + "導入後、PATH の通る場所に置けばこのアプリから呼び出せます。";
            return o;
        }
        return exec(new String[]{path, bin.getAbsolutePath()}, bin.getParentFile());
    }

    public static boolean hasEngine(String engine) {
        return resolveEngine(engine) != null;
    }

    private static String resolveEngine(String engine) {
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

    /** root の見込み(実際の取得は実行時に su を叩いて確認)。絶対パスに su があれば true。 */
    public static boolean hasRoot() {
        for (String p : SU_CANDIDATES) if (new File(p).exists()) return true;
        return false;
    }

    /** 実際に su が通るか(uid=0 を取得できるか)を軽く確認する。 */
    public static String probeRoot() {
        for (String su : candidateSuList()) {
            Out r = exec(new String[]{su, "-c", "id -u"}, null);
            if (r.launched) {
                String out = firstLine(r.log);
                if (out.contains("0")) return "root OK(" + su + " で uid=0)";
                return su + " は起動したが uid=" + out;
            }
        }
        return "su を実行できませんでした(未インストール/許可未承認/非標準パス)";
    }

    /** 実在する su 候補 + 最後に PATH 頼みの "su"。 */
    private static List<String> candidateSuList() {
        List<String> list = new ArrayList<>();
        for (String p : SU_CANDIDATES) if (new File(p).exists()) list.add(p);
        list.add("su"); // PATH 解決の保険
        return list;
    }

    private static String rootHelp() {
        return "root でも実行できませんでした。確認してください:\n"
                + "・Magisk/SuperSU の『スーパーユーザー要求』を許可したか(初回にダイアログが出ます)\n"
                + "・root 管理アプリのアプリ一覧に BinRunner があり、許可されているか\n"
                + "・su が標準パスに無い端末では自動検出できないことがあります\n"
                + "・直接実行は targetSdk 28 のため通常 root 無しで通りますが、"
                + "端末メーカー独自の SELinux ポリシーで塞がれている場合は root が必要です。";
    }

    private static void chmodExec(File f) {
        try { f.setReadable(true, true); f.setExecutable(true, true); } catch (Exception ignore) {}
    }

    private static Out exec(String[] cmd, File workdir) {
        Out o = new Out();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (workdir != null && workdir.isDirectory()) pb.directory(workdir);
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
            sb.append("[終了コード ").append(o.exitCode).append("]");
            o.log = sb.toString();
        } catch (Exception e) {
            o.launched = false;
            o.log = "起動不可: " + e.getMessage()
                    + " (error=13=SELinux実行禁止, error=2=ファイル/su無し)";
        }
        return o;
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private static String shortName(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }
}
