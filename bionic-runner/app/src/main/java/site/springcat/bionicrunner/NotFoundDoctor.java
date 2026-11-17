package site.springcat.bionicrunner;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「inaccessible or not found」の本当の原因を調べる。
 *
 * Android のシェル (mksh) は、コマンドが見つからないときも、見つかったが実行できないときも、
 * 同じこの文言を出す。原因には、名前の打ち間違い・ファイルが無い・CPU が違う・必要なローダが無い・
 * スクリプトの 1 行目 (#!) が指す処理系が無い・Windows の改行・SD カード上にある (実行できない場所)
 * などがあり、文言からは見分けられない。そこで出力にこの文言が出たら、名前の挙がったファイルを
 * 実際に調べて、原因と直し方を返す。
 */
final class NotFoundDoctor {

    /** 「スクリプト[行]: 名前: inaccessible or not found」(スクリプトの中で起きたときは前半が付く) */
    private static final Pattern ERROR = Pattern.compile(
            "(?:(\\S+?)\\[(\\d+)\\]: )?(\\S+): (?:inaccessible or not found|not found)");

    private NotFoundDoctor() {
    }

    /** 出力の中にこのエラーがあれば {スクリプト (無ければ null), 行, 名前} を返す。無ければ null */
    static String[] find(String output) {
        Matcher m = ERROR.matcher(output);
        String[] last = null;
        while (m.find()) last = new String[]{m.group(1), m.group(2), m.group(3)};
        return last;
    }

    /**
     * 原因と直し方を調べる。name はエラーに出た名前、script はそれを呼んだスクリプト (無ければ null)。
     * pathDirs は PATH の中身 (コマンド名で呼ばれたときに探す場所)、root は root モードか。
     */
    static String diagnose(Context c, String name, String script, String[] pathDirs, boolean root) {
        String where = script == null ? "" : new File(script).getName() + " の中で呼んだ ";
        File f = null;
        if (name.contains("/")) {
            f = new File(name);
        } else {
            for (String d : pathDirs) {
                File cand = new File(d, name);
                if (cand.exists()) {
                    f = cand;
                    break;
                }
            }
        }
        if (f == null || !f.exists()) {
            StringBuilder sb = new StringBuilder("原因: " + where + "「" + name + "」が見つかりません。");
            if (!name.contains("/")) {
                // 同じアプリとみなせる名前で登録されていないか (版の番号の違いなど)
                File similar = AppNames.findRegistered(new File(c.getFilesDir(), "bin"), name);
                if (similar != null && !similar.getName().equals(name)) {
                    sb.append("「").append(similar.getName()).append("」なら登録されています。");
                } else {
                    sb.append("登録した実行ファイルの名前と、打った名前が合っているか確かめてください (help で使える命令の一覧)。");
                }
                if (script != null) {
                    sb.append("スクリプトから呼ぶ場合、そのコマンドが Android に入っていない (PC の Linux にしか無い)"
                            + " こともあります。");
                }
            } else {
                sb.append("場所 (パス) が合っているか確かめてください。");
            }
            return sb.toString();
        }
        String path = f.getAbsolutePath();
        String label = where + "「" + f.getName() + "」";
        if (f.isDirectory()) return "原因: " + label + " はフォルダです。実行できるのはファイルだけです。";
        if (path.startsWith("/sdcard") || path.startsWith("/storage") || path.startsWith("/mnt/")
                || path.startsWith("/data/media")) {
            return "原因: " + label + " は内部ストレージ / SD カードにあります。Android はこの場所のファイルを"
                    + "実行させません (root でも同じ)。「実行ファイル」で選んで登録すると、実行できる場所へ"
                    + "写して実行できます。";
        }
        if (!f.canRead() && !root) {
            return "原因: " + label + " を読む権限がありません。/su で root モードにすると読めるかもしれません。";
        }
        ElfInfo info;
        try {
            info = ElfInfo.read(f);
        } catch (IOException e) {
            return "原因: " + label + " を読めませんでした (" + e.getMessage() + ")。";
        }
        if (info.kind == ElfInfo.Kind.SCRIPT) return scriptCause(c, label, f, info);
        if (info.kind == ElfInfo.Kind.WINDOWS_PE) {
            return "原因: " + label + " は Windows 用の .exe です。Android (Linux) では実行できません。";
        }
        if (!info.isElf()) {
            return "原因: " + label + " は実行ファイルの形式ではありません (ELF でもスクリプトでもない)。";
        }
        if (!info.cpuMatches(Build.SUPPORTED_ABIS)) {
            return "原因: " + label + " は " + info.machineName() + " 用で、この端末の CPU ("
                    + Build.SUPPORTED_ABIS[0] + ") では直接動きません。「実行ファイル」で選んで登録し直すと、"
                    + "対応している CPU ならエミュレータ経由で動くようにします。";
        }
        if (info.interpreter != null && !new File(info.interpreter).exists()) {
            return "原因: " + label + " が必要とするローダ " + info.interpreter + " がこの端末にありません"
                    + " (普通の Linux 用のプログラムです)。「実行ファイル」で選んで登録し直すと、glibc 用なら"
                    + "同梱の glibc で動くようにします。musl 用なら静的リンク版を使ってください。";
        }
        if (!info.fitsPageSize(MainActivity.pageSize())) {
            return "原因: " + label + " は 4KB 単位で作られていて、この端末 (メモリの単位が "
                    + (MainActivity.pageSize() / 1024) + "KB) では読み込めません。"
                    + "-Wl,-z,max-page-size=16384 を付けてビルドし直してください。";
        }
        if (!root && path.startsWith(c.getFilesDir().getParent())) {
            return "原因: " + label + " はアプリの作業領域にあり、root が無いと直接は実行できません (Android の制限)。"
                    + "名前だけで呼ぶか、/su で root モードにしてください。";
        }
        if (!f.canExecute() && !root) {
            return "原因: " + label + " に実行の許可が付いていません。「実行ファイル」で選んで登録し直してください。";
        }
        return "原因を特定できませんでした (" + label + " は、この端末で動くはずの形式です)。"
                + "/su で root モードにして試すか、登録し直してください。";
    }

    private static String scriptCause(Context c, String label, File f, ElfInfo info) {
        String first = firstLine(f);
        if (first.endsWith("\r")) {
            return "原因: " + label + " は Windows の改行 (CR LF) で書かれていて、1 行目の終わりに見えない文字が"
                    + "付いています。「実行ファイル」で選んで登録し直すと自動で直します。";
        }
        String[] how = Scripts.interpreter(c, info.shebang == null ? "" : info.shebang);
        if (how == null) {
            return "原因: " + label + " の 1 行目 (#!" + info.shebang + ") が指す処理系が、この端末にありません。"
                    + "使えるのは sh (bash 向けの多くもそのまま動く)・python3・lua です。";
        }
        if (!how[0].equals(info.shebang.split("\\s+")[0])) {
            return "原因: " + label + " の 1 行目 (#!" + info.shebang + ") の場所は、Android にはありません。"
                    + "「実行ファイル」で選んで登録し直すと、#!" + how[0] + " に自動で直します。"
                    + "今すぐ動かすなら: " + Scripts.runCommand(c, f, info.shebang);
        }
        return "原因を特定できませんでした (" + label + " の 1 行目は正しく、処理系もあります)。";
    }

    private static String firstLine(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[512];
            int n = in.read(b);
            if (n <= 0) return "";
            String s = new String(b, 0, n, java.nio.charset.StandardCharsets.ISO_8859_1);
            int nl = s.indexOf('\n');
            return nl < 0 ? s : s.substring(0, nl);
        } catch (IOException e) {
            return "";
        }
    }
}
