package site.springcat.bionicrunner;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * ソースのまま実行できる言語の一覧と、その動かし方。
 *
 * 動かし方は 2 種類ある:
 *  - WEB: アプリの中のブラウザ部品 (WebView) で動かす (HTML・JavaScript・TypeScript・SVG)。
 *  - TERM: 同梱の処理系 (lib/&lt;ABI&gt;/lib〜.so という名前で入れてある実行ファイル) で動かす。
 *    実行モードではコマンドプロンプトの中で、ゲームモードでは画面制御のできる端末の画面で動く。
 *
 * 言語を足すときは {@link #ALL} に 1 行足す。Android の API は使っていない。
 */
final class Lang {

    enum Type { WEB, TERM }

    /** 内部の名前。WEB では harness.js に渡す種類 ("html" / "js" / "ts") でもある */
    final String id;
    /** 画面に出す名前 */
    final String label;
    final Type type;
    /** 処理系のファイル名 (TERM のみ。null は端末に元からある sh) */
    private final String tool;
    private final String[] extensions;

    private Lang(String id, String label, Type type, String tool, String... extensions) {
        this.id = id;
        this.label = label;
        this.type = type;
        this.tool = tool;
        this.extensions = extensions;
    }

    static final Lang[] ALL = {
            new Lang("html", "HTML", Type.WEB, null, ".html", ".htm", ".xhtml", ".svg"),
            new Lang("js", "JavaScript", Type.WEB, null, ".js", ".mjs", ".cjs"),
            new Lang("ts", "TypeScript", Type.WEB, null, ".ts", ".mts", ".cts"),
            new Lang("go", "Go", Type.TERM, "libgorun.so", ".go"),
            new Lang("py", "Python (MicroPython)", Type.TERM, "libmpy.so", ".py"),
            new Lang("lua", "Lua", Type.TERM, "liblua.so", ".lua"),
            new Lang("sh", "シェルスクリプト", Type.TERM, null, ".sh", ".bash", ".ksh"),
    };

    /** ファイル名から言語を決める。実行できない種類なら null */
    static Lang of(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        for (Lang l : ALL) {
            for (String ext : l.extensions) if (n.endsWith(ext)) return l;
        }
        return null;
    }

    /** 実行できる拡張子の一覧 (案内用) */
    static String supported() {
        StringBuilder sb = new StringBuilder();
        for (Lang l : ALL) {
            for (String ext : l.extensions) sb.append(sb.length() > 0 ? " " : "").append(ext);
        }
        return sb.toString();
    }

    /** この端末用の処理系が入っているか (Go は 32bit の x86 用が無い) */
    boolean available(String nativeLibraryDir) {
        return tool == null || new File(nativeLibraryDir, tool).isFile();
    }

    private String toolPath(String nativeLibraryDir) {
        return tool == null ? "/system/bin/sh" : new File(nativeLibraryDir, tool).getAbsolutePath();
    }

    /** file を実行するコマンド (TERM のみ) */
    List<String> argv(String nativeLibraryDir, String file) {
        return new ArrayList<>(Arrays.asList(toolPath(nativeLibraryDir), file));
    }

    /**
     * file を実行せずに、文法の誤りだけを調べるコマンド (TERM のみ)。
     * 誤りがあれば終了コードが 0 以外になり、内容が出力される。
     */
    List<String> checkArgv(String nativeLibraryDir, String file) {
        String t = toolPath(nativeLibraryDir);
        switch (id) {
            case "go":
                return Arrays.asList(t, "-check", file);
            case "py":
                return Arrays.asList(t, "-X", "compile-only", file);
            case "lua": {
                // 読み込む (構文を調べる) だけで実行しない。ファイル名は Lua の文字列として埋め込む
                String q = file.replace("\\", "\\\\").replace("\"", "\\\"");
                return Arrays.asList(t, "-e", "local f, e = loadfile(\"" + q + "\") "
                        + "if not f then io.stderr:write(e, \"\\n\") os.exit(1) end");
            }
            default:
                return Arrays.asList(t, "-n", file);
        }
    }

    /** エラーの文から行番号を探す (見つからなければ 0)。言語ごとに書き方が違う */
    static int errorLine(String message) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:^|line |:|\\[)(\\d+)(?:\\]|:|,|\\s|$)",
                        java.util.regex.Pattern.MULTILINE).matcher(message);
        try {
            return m.find() ? Integer.parseInt(m.group(1)) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
