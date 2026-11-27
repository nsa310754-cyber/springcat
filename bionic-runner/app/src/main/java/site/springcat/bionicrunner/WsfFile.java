package site.springcat.bionicrunner;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Windows Script File (.wsf) を読んで、Wine で動かせる形 (1 つの .js か .vbs) に直す。
 *
 * .wsf は XML の中に &lt;job&gt; を並べ、その中に &lt;script language="JScript"&gt; などでスクリプトを書く形式。
 * Windows では Windows Script Host (cscript / wscript) が直接動かすが、Wine 10 の cscript / wscript は
 * .wsf に対応していない (何もせずに終わる)。そこで、選んだ job の中身を次のように 1 つのファイルにまとめ、
 * Wine の cscript (または wscript) で .js / .vbs として動かす:
 *   - &lt;script&gt; の中身を、書かれた順につなげる (同じ言語のものだけ。JScript と VBScript が混ざった job は、
 *     変数を共有できないので動かさない)
 *   - &lt;object id progid&gt; は、先頭でそのオブジェクトを作る文にする
 *   - &lt;resource id&gt; は、getResource("id") で読める関数にする
 *   - src= で別のファイルを読み込む &lt;script&gt; は、そのファイルが無いので動かさない (理由を出す)
 * 中身の検査 (危険な操作の記述) もここで行う。Android の機能を使わない (PC でも同じコードを試せるように)。
 */
final class WsfFile {

    /** 読む大きさの上限 (スクリプトとしては十分大きい) */
    static final int MAX_SIZE = 4 * 1024 * 1024;

    static final class Script {
        /** 言語 (書かれたまま) */
        final String language;
        /** 中身 */
        final String code;
        /** src= の値 (無ければ null) */
        final String src;

        Script(String language, String code, String src) {
            this.language = language;
            this.code = code;
            this.src = src;
        }
    }

    static final class Job {
        /** job の id (無ければ空) */
        String id = "";
        final List<Script> scripts = new ArrayList<>();
        /** {id, progid} */
        final List<String[]> objects = new ArrayList<>();
        /** {id, 中身} */
        final List<String[]> resources = new ArrayList<>();

        /** 使っている言語の種類 (js / vbs / その他は書かれた名前)。重複なし */
        List<String> kinds() {
            List<String> out = new ArrayList<>();
            for (Script s : scripts) {
                String k = kind(s.language);
                if (!out.contains(k)) out.add(k);
            }
            return out;
        }
    }

    /** 動かせる形にした結果 */
    static final class Runnable {
        /** 拡張子 (".js" か ".vbs") */
        final String ext;
        final String text;
        /** メッセージボックスなど画面を使う (wscript で動かし、X11 の画面を開く) */
        final boolean gui;

        Runnable(String ext, String text, boolean gui) {
            this.ext = ext;
            this.text = text;
            this.gui = gui;
        }
    }

    final List<Job> jobs = new ArrayList<>();

    private WsfFile() {
    }

    // ------------------------------------------------------------ 見分ける・読む

    /** .wsf らしいか (拡張子が .wsf、または中身が &lt;job&gt; と &lt;script&gt; を持つ XML) */
    static boolean looksLike(File f, String name) {
        if (name != null && name.toLowerCase(Locale.ROOT).endsWith(".wsf")) return true;
        try (InputStream in = new FileInputStream(f)) {
            byte[] head = new byte[4096];
            int n = readFully(in, head);
            String s = decode(java.util.Arrays.copyOf(head, Math.max(n, 0))).toLowerCase(Locale.ROOT);
            return s.contains("<job") && s.contains("<script");
        } catch (IOException e) {
            return false;
        }
    }

    static WsfFile read(File f) throws IOException {
        if (f.length() > MAX_SIZE) throw new IOException("大きすぎます (4MB まで)");
        byte[] data;
        try (InputStream in = new FileInputStream(f)) {
            data = new byte[(int) f.length()];
            int n = readFully(in, data);
            if (n != data.length) throw new IOException("読み込みが途中で終わりました");
        }
        return parse(decode(data));
    }

    /** 文字コードを決めて文字列にする (UTF-16 / UTF-8 の印、XML の encoding 指定、なければ UTF-8) */
    static String decode(byte[] d) {
        if (d.length >= 2 && (d[0] & 0xff) == 0xff && (d[1] & 0xff) == 0xfe) {
            return new String(d, 2, d.length - 2, StandardCharsets.UTF_16LE);
        }
        if (d.length >= 2 && (d[0] & 0xff) == 0xfe && (d[1] & 0xff) == 0xff) {
            return new String(d, 2, d.length - 2, StandardCharsets.UTF_16BE);
        }
        if (d.length >= 3 && (d[0] & 0xff) == 0xef && (d[1] & 0xff) == 0xbb && (d[2] & 0xff) == 0xbf) {
            return new String(d, 3, d.length - 3, StandardCharsets.UTF_8);
        }
        String head = new String(d, 0, Math.min(d.length, 200), StandardCharsets.ISO_8859_1);
        Matcher m = Pattern.compile("encoding\\s*=\\s*[\"']([A-Za-z0-9_.:-]+)[\"']").matcher(head);
        if (m.find()) {
            try {
                return new String(d, Charset.forName(m.group(1)));
            } catch (RuntimeException ignored) {
                // 知らない文字コード: UTF-8 として読む
            }
        }
        return new String(d, StandardCharsets.UTF_8);
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int got = 0;
        while (got < buf.length) {
            int n = in.read(buf, got, buf.length - got);
            if (n < 0) break;
            got += n;
        }
        return got;
    }

    private static final Pattern TAG = Pattern.compile(
            "<(/?)(job|script|object|resource)\\b([^>]*?)(/?)>", Pattern.CASE_INSENSITIVE);

    /**
     * 中身を読む。Windows Script Host と同じく、XML として厳密でなくても読めるようにする
     * (スクリプトの中の &lt; などをそのまま書いたものが多いため)
     */
    static WsfFile parse(String text) throws IOException {
        WsfFile w = new WsfFile();
        String s = text.replaceAll("(?s)<!--.*?-->", "");
        Matcher m = TAG.matcher(s);
        Job job = null;
        int pos = 0;
        while (pos < s.length() && m.find(pos)) {
            boolean close = !m.group(1).isEmpty();
            String tag = m.group(2).toLowerCase(Locale.ROOT);
            String attrs = m.group(3);
            boolean empty = !m.group(4).isEmpty();
            pos = m.end();
            switch (tag) {
                case "job":
                    if (close) {
                        job = null;
                    } else {
                        job = new Job();
                        job.id = attr(attrs, "id");
                        w.jobs.add(job);
                        if (empty) job = null;
                    }
                    break;
                case "script": {
                    if (close) break;
                    String body = "";
                    if (!empty) {
                        int end = indexOfIgnoreCase(s, "</script", pos);
                        if (end < 0) throw new IOException("<script> の終わり (</script>) がありません");
                        body = stripCdata(s.substring(pos, end));
                        pos = s.indexOf('>', end) + 1;
                        if (pos <= 0) pos = s.length();
                    }
                    if (job == null) {
                        // job の外のスクリプト (<package> の無い 1 つだけの job など) は、無名の job に入れる
                        if (w.jobs.isEmpty()) w.jobs.add(new Job());
                        job = w.jobs.get(w.jobs.size() - 1);
                    }
                    String src = attr(attrs, "src");
                    job.scripts.add(new Script(attr(attrs, "language"), body, src.isEmpty() ? null : src));
                    break;
                }
                case "object":
                    if (!close && job != null) {
                        String progid = attr(attrs, "progid");
                        if (!progid.isEmpty()) job.objects.add(new String[]{attr(attrs, "id"), progid});
                    }
                    break;
                case "resource":
                    if (!close && !empty && job != null) {
                        int end = indexOfIgnoreCase(s, "</resource", pos);
                        if (end < 0) throw new IOException("<resource> の終わりがありません");
                        job.resources.add(new String[]{attr(attrs, "id"), stripCdata(s.substring(pos, end)).trim()});
                        pos = s.indexOf('>', end) + 1;
                        if (pos <= 0) pos = s.length();
                    }
                    break;
                default:
                    break;
            }
        }
        if (w.jobs.isEmpty() || w.jobs.get(0).scripts.isEmpty() && w.jobs.size() == 1) {
            throw new IOException("<job> と <script> が見つかりません (Windows Script File ではないようです)");
        }
        return w;
    }

    private static String attr(String attrs, String name) {
        Matcher m = Pattern.compile("\\b" + name + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",
                Pattern.CASE_INSENSITIVE).matcher(attrs);
        if (!m.find()) return "";
        String v = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
        return unescapeXml(v);
    }

    private static String stripCdata(String s) {
        String t = s.trim();
        if (t.startsWith("<![CDATA[") && t.endsWith("]]>")) return t.substring(9, t.length() - 3);
        return s;
    }

    private static String unescapeXml(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    private static int indexOfIgnoreCase(String s, String needle, int from) {
        return s.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT), from);
    }

    /** 言語の名前を js / vbs にそろえる (それ以外は書かれた名前の小文字) */
    static String kind(String language) {
        String l = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
        if (l.isEmpty() || l.equals("jscript") || l.equals("javascript") || l.equals("ecmascript")
                || l.equals("jscript.encode")) {
            return l.equals("jscript.encode") ? "jscript.encode" : "js";
        }
        if (l.equals("vbscript") || l.equals("vbs") || l.equals("vbscript.encode")) {
            return l.equals("vbscript.encode") ? "vbscript.encode" : "vbs";
        }
        return l;
    }

    // ------------------------------------------------------------ 動かせる形にする

    /** job を探す (id が空なら最初の job) */
    Job job(String id) {
        if (id == null || id.isEmpty()) return jobs.get(0);
        for (Job j : jobs) if (j.id.equalsIgnoreCase(id)) return j;
        return null;
    }

    /**
     * job を 1 つの .js / .vbs にまとめる。動かせない (言語が混ざっている・src がある・知らない言語) ときは
     * IOException (理由を分かりやすい日本語で)
     */
    static Runnable toRunnable(Job job) throws IOException {
        if (job.scripts.isEmpty()) throw new IOException("この job にはスクリプトがありません");
        for (Script s : job.scripts) {
            if (s.src != null) {
                throw new IOException("別のファイルを読み込むスクリプト (src=\"" + s.src + "\") があります。"
                        + "そのファイルが無いので動かせません");
            }
        }
        List<String> kinds = job.kinds();
        if (kinds.size() > 1) {
            throw new IOException("1 つの job の中に JScript と VBScript が混ざっています。"
                    + "変数を共有できないので、この形式は動かせません");
        }
        String k = kinds.get(0);
        if (k.endsWith(".encode")) throw new IOException("暗号化されたスクリプト (" + k + ") は動かせません");
        if (!k.equals("js") && !k.equals("vbs")) {
            throw new IOException("この言語のスクリプトは動かせません: " + k + " (動かせるのは JScript と VBScript)");
        }
        boolean js = k.equals("js");
        StringBuilder sb = new StringBuilder();
        // 改行は LF にする (Wine の VBScript は、かっこ無しの呼び出し (WScript.Echo "…") の行が CR LF で終わると、
        // 何もせずに終わる)。JScript も同じにそろえる
        String nl = "\n";
        if (js) {
            sb.append("// Bionic Runner: Windows Script File (.wsf) の job「").append(job.id)
                    .append("」を .js にしたもの (自動生成)").append(nl);
            for (String[] o : job.objects) {
                if (!o[0].isEmpty()) {
                    sb.append("var ").append(o[0]).append(" = new ActiveXObject(").append(jsString(o[1])).append(");")
                            .append(nl);
                }
            }
            if (!job.resources.isEmpty()) {
                sb.append("function getResource(n) {").append(nl);
                for (String[] r : job.resources) {
                    sb.append("  if (n == ").append(jsString(r[0])).append(") return ").append(jsString(r[1])).append(";")
                            .append(nl);
                }
                sb.append("  return \"\";").append(nl).append("}").append(nl);
            }
        } else {
            sb.append("' Bionic Runner: Windows Script File (.wsf) の job「").append(job.id)
                    .append("」を .vbs にしたもの (自動生成)").append(nl);
            for (String[] o : job.objects) {
                if (!o[0].isEmpty()) {
                    sb.append("Dim ").append(o[0]).append(nl).append("Set ").append(o[0]).append(" = CreateObject(")
                            .append(vbString(o[1])).append(")").append(nl);
                }
            }
            if (!job.resources.isEmpty()) {
                sb.append("Function GetResource(n)").append(nl).append("  GetResource = \"\"").append(nl);
                for (String[] r : job.resources) {
                    sb.append("  If n = ").append(vbString(r[0])).append(" Then GetResource = ").append(vbString(r[1]))
                            .append(nl);
                }
                sb.append("End Function").append(nl);
            }
        }
        StringBuilder all = new StringBuilder();
        for (Script s : job.scripts) {
            sb.append(s.code.replace("\r\n", "\n").replace("\n", nl)).append(nl);
            all.append(s.code).append('\n');
        }
        // メッセージボックスや入力欄を出すものは、画面 (X11) が要る
        boolean gui = Pattern.compile("(?i)\\b(?:MsgBox|InputBox)\\b|\\.Popup\\s*\\(").matcher(all).find();
        // JScript は英数字だけ (ASCII) にしておく (\\uXXXX)。VBScript はファイルにするときに文字コードを決める (bytes)
        String body = js ? asciiJs(sb.toString()) : sb.toString();
        return new Runnable(js ? ".js" : ".vbs", body, gui);
    }

    /**
     * ファイルに書く中身。Wine の cscript は、UTF-16 のファイルを読まず (何もせずに終わる)、それ以外は
     * Windows の文字コード (ANSI) で読む。JScript はもう ASCII だけなのでそのまま。VBScript は、
     * 日本語の環境 (コードページ 932) なら Shift_JIS で書き、そうでなければ日本語などを ? にする
     * (英語の文字コードでは表せないため。Wine の VBScript の ChrW は使えない)
     */
    static byte[] bytes(Runnable r, boolean japanese) {
        if (r.ext.equals(".js")) return r.text.getBytes(StandardCharsets.US_ASCII);
        Charset cs = japanese ? Charset.forName("windows-31j") : StandardCharsets.US_ASCII;
        java.nio.charset.CharsetEncoder enc = cs.newEncoder()
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                .replaceWith(new byte[]{'?'});
        try {
            java.nio.ByteBuffer b = enc.encode(java.nio.CharBuffer.wrap(r.text));
            byte[] out = new byte[b.remaining()];
            b.get(out);
            return out;
        } catch (java.nio.charset.CharacterCodingException e) {
            return r.text.replaceAll("[^\\x00-\\x7e]", "?").getBytes(StandardCharsets.US_ASCII);
        }
    }

    /** JScript の中の ASCII 以外の文字を \\uXXXX にする (文字列・名前・コメントのどこでも同じ意味になる) */
    static String asciiJs(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c > 0x7e && c != '\u00a0') sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }

    private static String jsString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                default:
                    if (c < 0x20 || c > 0x7e) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** VBScript の文字列 (改行は vbLf をつなぐ。ASCII 以外の文字はそのまま。ファイルにするときに文字コードを決める) */
    private static String vbString(String s) {
        String[] parts = s.replace("\r\n", "\n").split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(" & vbLf & ");
            sb.append('"').append(parts[i].replace("\"", "\"\"")).append('"');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 検査

    /** スクリプトの危険な使い方 (組み合わせで判断する)。{説明, 条件 1, 条件 2 (無ければ null)} */
    private static final String[][] RULES = {
            {"ネットからファイルを取ってきて保存する (ウイルスを送り込むスクリプトの典型)",
                    "(?i)MSXML2\\.(?:Server)?XMLHTTP|Microsoft\\.XMLHTTP|WinHttp\\.WinHttpRequest",
                    "(?i)ADODB\\.Stream[\\s\\S]*SaveToFile|\\.SaveToFile\\b"},
            {"ネットから取ってきたものを、ほかのプログラムとして起動する",
                    "(?i)MSXML2\\.(?:Server)?XMLHTTP|Microsoft\\.XMLHTTP|WinHttp\\.WinHttpRequest",
                    "(?i)\\.(?:Run|Exec)\\s*\\(|\\.Run\\s+\"|ShellExecute"},
            {"自動で起動するようにレジストリへ登録する",
                    "(?i)RegWrite[\\s\\S]{0,200}\\\\CurrentVersion\\\\Run(?:Once)?\\\\?", null},
            {"スタートアップ (自動で起動する場所) へファイルを置く",
                    "(?i)SpecialFolders\\s*\\(\\s*\"(?:All Users )?Startup\"", "(?i)CopyFile|CreateTextFile|SaveToFile"},
            {"中身を隠して実行する (難読化)",
                    "(?i)\\b(?:Execute|ExecuteGlobal|eval)\\s*\\(?",
                    "(?i)(?:Chr[W]?\\s*\\(\\s*\\d+\\s*\\)\\s*&\\s*){8,}|fromCharCode\\s*\\((?:\\s*\\d+\\s*,){8,}|unescape\\s*\\(\\s*[\"']%u"},
            {"ファイルやフォルダの一括削除",
                    "(?i)\\.Delete(?:File|Folder)\\s*\\(?\\s*\"[A-Za-z]:\\\\(?:\\*|Windows|Users|\")", null},
            {"保護機能を止める (Windows Defender・ファイアウォール)",
                    "(?i)DisableAntiSpyware|DisableRealtimeMonitoring|netsh\\s+(?:adv)?firewall\\s+set\\s+\\S*\\s*state\\s+off", null},
    };

    /** 危険な使い方の一覧 ({説明, 見つかった記述}) */
    List<String[]> dangers() {
        StringBuilder all = new StringBuilder();
        for (Job j : jobs) {
            for (String[] o : j.objects) all.append("CreateObject(\"").append(o[1]).append("\")\n");
            for (Script s : j.scripts) all.append(s.code).append('\n');
        }
        List<String[]> out = new ArrayList<>();
        for (String[] r : RULES) {
            Matcher a = Pattern.compile(r[1]).matcher(all);
            if (!a.find()) continue;
            String ev = a.group();
            if (r[2] != null) {
                Matcher b = Pattern.compile(r[2]).matcher(all);
                if (!b.find()) continue;
                ev = ev + " … " + b.group();
            }
            if (ev.length() > 140) ev = ev.substring(0, 140) + "…";
            out.add(new String[]{r[0], ev.replaceAll("\\s+", " ")});
        }
        return out;
    }

    /** 使っているオブジェクト (ActiveX) の名前の一覧 (確認の画面に出す) */
    List<String> objectNames() {
        List<String> out = new ArrayList<>();
        Pattern p = Pattern.compile("(?i)(?:CreateObject|ActiveXObject|GetObject)\\s*\\(\\s*\"([A-Za-z0-9_.]+)\"");
        for (Job j : jobs) {
            for (String[] o : j.objects) if (!out.contains(o[1])) out.add(o[1]);
            for (Script s : j.scripts) {
                Matcher m = p.matcher(s.code);
                while (m.find()) if (!out.contains(m.group(1))) out.add(m.group(1));
            }
        }
        return out;
    }
}
