package site.springcat.bionicrunner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * エクスプローラー用の、コマンドを 1 つずつ実行して結果を受け取るシェル。
 * su が使えれば root、使えなければ通常の sh で動く (その場合はアプリが読める範囲しか見えない)。
 * su を操作のたびに起動すると許可の通知が毎回出るので、1 本を開いたまま使い回す。
 * ファイルの中身は base64 でやり取りするので、どんなバイト列でも壊れない。
 * Android の API は使っていないので、PC 上の JVM でも単体で検証できる。
 */
final class RootSession {

    static final class Result {
        final int code;
        final String output;

        Result(int code, String output) {
            this.code = code;
            this.output = output;
        }

        boolean ok() {
            return code == 0;
        }
    }

    /** フォルダの中の 1 項目 */
    static final class Entry {
        String name;
        /** stat の %F ("directory"、"regular file"、"symbolic link" など) */
        String type;
        long size;
        /** 更新時刻 (秒) */
        long mtime;

        boolean isDir() {
            return "directory".equals(type);
        }

        boolean isLink() {
            return "symbolic link".equals(type);
        }

        boolean isRegular() {
            return type != null && type.startsWith("regular");
        }
    }

    /** フォルダの一覧。path はリンクを解決した本当の場所 */
    static final class Listing {
        String path;
        final List<Entry> entries = new ArrayList<>();
    }

    private final String mark;
    private Process process;
    private OutputStream stdin;
    private BufferedReader stdout;
    private boolean root;

    RootSession() {
        byte[] raw = new byte[8];
        new SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder("@@BRX");
        for (byte b : raw) sb.append(String.format("%02x", b));
        mark = sb.append(':').toString();
    }

    /** ブロックする (su の許可待ち) ので UI スレッド以外から呼ぶこと */
    synchronized void start() throws IOException {
        root = RootShell.probeRoot();
        process = new ProcessBuilder(root ? "su" : "sh").redirectErrorStream(true).start();
        stdin = process.getOutputStream();
        stdout = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        // 古い Android には timeout コマンドが無い。その場合は時間制限なしで実行する
        exec("command -v timeout >/dev/null 2>&1 || timeout() { shift; \"$@\"; }");
    }

    boolean isRoot() {
        return root;
    }

    synchronized void close() {
        if (process == null) return;
        try {
            stdin.close();
        } catch (IOException ignored) {
            // 既に閉じている
        }
        process.destroy();
        process = null;
    }

    /** コマンドを実行し、終了コードと出力 (標準エラーを含む) を返す */
    synchronized Result exec(String command) throws IOException {
        if (process == null) throw new IOException("シェルが動いていません");
        // 出力の終わりを見分けるため、最後に「目印 + 終了コード」の行を出させる。
        // 目印はセッションごとの乱数なので、ファイル名などと偶然一致することはない。
        String line = "{ " + command + "\n} 2>&1; printf '\\n" + mark + "%d\\n' $?\n";
        stdin.write(line.getBytes(StandardCharsets.UTF_8));
        stdin.flush();
        StringBuilder out = new StringBuilder();
        while (true) {
            String l = stdout.readLine();
            if (l == null) {
                close();
                throw new IOException("シェルが終了しました");
            }
            if (l.startsWith(mark)) {
                int code;
                try {
                    code = Integer.parseInt(l.substring(mark.length()).trim());
                } catch (NumberFormatException e) {
                    code = -1;
                }
                // 目印の前に入れた改行 1 つぶんを取り除く
                if (out.length() > 0) out.setLength(out.length() - 1);
                if (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
                    out.setLength(out.length() - 1);
                }
                return new Result(code, out.toString());
            }
            out.append(l).append('\n');
        }
    }

    /** シェルの単一引用符で安全に囲む */
    static String sq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    // ------------------------------------------------------------ ファイル操作

    /** フォルダの中身を調べる。フォルダとして開けなければ null */
    Listing list(String dir) throws IOException {
        // 1 行目に本当の場所、2 行目以降に「種類|大きさ|更新時刻|./名前」。
        // 特殊なファイルで固まらないよう時間制限を付ける。
        Result r = exec("cd -- " + sq(dir) + " && pwd -P && timeout 30 find . -mindepth 1 -maxdepth 1 "
                + "-exec stat -c '%F|%s|%Y|%n' {} + 2>/dev/null; true");
        if (!r.ok() || r.output.isEmpty() || !r.output.startsWith("/")) return null;
        String[] lines = r.output.split("\n");
        Listing listing = new Listing();
        listing.path = lines[0];
        for (int i = 1; i < lines.length; i++) {
            String[] f = lines[i].split("\\|", 4);
            if (f.length < 4 || !f[3].startsWith("./")) continue;
            Entry e = new Entry();
            e.type = f[0];
            try {
                e.size = Long.parseLong(f[1]);
                e.mtime = Long.parseLong(f[2]);
            } catch (NumberFormatException ex) {
                continue;
            }
            e.name = f[3].substring(2);
            listing.entries.add(e);
        }
        return listing;
    }

    /** ファイルの先頭 limit バイトまでを読む。読めなければ IOException (理由つき) */
    byte[] read(String file, int limit) throws IOException {
        Result r = exec("timeout 10 head -c " + limit + " -- " + sq(file) + " | base64");
        byte[] data = r.ok() ? decodeBase64(r.output) : null;
        if (data == null) {
            throw new IOException(r.output.isEmpty() ? "読み込めませんでした" : firstLine(r.output));
        }
        return data;
    }

    /** ファイルを書き換える (無ければ作る) */
    void write(String file, byte[] data) throws IOException {
        // 引用符つきのヒアドキュメントなので、中身はシェルに解釈されずそのまま渡る
        String eof = "BR_B64_END";
        check(exec("base64 -d > " + sq(file) + " <<'" + eof + "'\n" + encodeBase64(data) + eof));
    }

    void makeDir(String path) throws IOException {
        check(exec("mkdir -- " + sq(path)));
    }

    /** 空のファイルを作る。同じ名前があれば失敗させる */
    void makeFile(String path) throws IOException {
        check(exec(notExists(path) + " && : > " + sq(path)));
    }

    /** 名前の変更・移動。移動先に同じ名前があれば失敗させる (上書きしない) */
    void move(String from, String to) throws IOException {
        check(exec(notExists(to) + " && mv -- " + sq(from) + " " + sq(to)));
    }

    /** コピー (フォルダは中身ごと)。コピー先に同じ名前があれば失敗させる */
    void copy(String from, String to) throws IOException {
        check(exec(notExists(to) + " && cp -R -- " + sq(from) + " " + sq(to)));
    }

    /** 削除 (フォルダは中身ごと) */
    void delete(String path) throws IOException {
        check(exec("rm -rf -- " + sq(path)));
    }

    private static String notExists(String path) {
        String q = sq(path);
        return "{ [ ! -e " + q + " ] && [ ! -L " + q + " ] || { echo '同じ名前のものが既にあります'; false; }; }";
    }

    private static void check(Result r) throws IOException {
        if (!r.ok()) {
            throw new IOException(r.output.isEmpty() ? "失敗しました (終了コード " + r.code + ")"
                    : firstLine(r.output));
        }
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    /**
     * その場所 (リンク解決済み) の中身を変更してよいか。
     * 内部ストレージ・外部 SD カード・このアプリの作業領域だけを許可する。
     */
    static boolean isEditable(String path, String appFiles) {
        if (under(path, appFiles, 0)) return true;
        if (under(path, "/storage/emulated", 1)) return true; // /storage/emulated/<ユーザー番号>/…
        if (under(path, "/data/media", 1)) return true;
        if (under(path, "/mnt/media_rw", 1)) return true;     // /mnt/media_rw/<SD カード>/…
        // /storage/<SD カード>/…  (emulated と self は上の規則か、リンク解決後の場所で判定する)
        return under(path, "/storage", 1) && !path.startsWith("/storage/emulated")
                && !path.startsWith("/storage/self");
    }

    /** path が base の下にあり、base から数えて少なくとも depth 階層ぶん下っているか */
    private static boolean under(String path, String base, int depth) {
        if (!path.equals(base) && !path.startsWith(base + "/")) return false;
        String rest = path.substring(base.length());
        int count = 0;
        for (String part : rest.split("/")) if (!part.isEmpty()) count++;
        return count >= depth;
    }

    // ----------------------------------------------------------------- base64
    // java.util.Base64 は Android 8 以降でしか使えないので、自前で持つ

    private static final char[] B64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    /** 76 文字ごとに改行した base64 (末尾にも改行を付ける) */
    static String encodeBase64(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 4 / 3 + d.length / 57 + 8);
        int col = 0;
        for (int i = 0; i < d.length; i += 3) {
            int b0 = d[i] & 255;
            int b1 = i + 1 < d.length ? d[i + 1] & 255 : 0;
            int b2 = i + 2 < d.length ? d[i + 2] & 255 : 0;
            sb.append(B64[b0 >> 2]).append(B64[((b0 & 3) << 4) | (b1 >> 4)]);
            sb.append(i + 1 < d.length ? B64[((b1 & 15) << 2) | (b2 >> 6)] : '=');
            sb.append(i + 2 < d.length ? B64[b2 & 63] : '=');
            col += 4;
            if (col >= 76) {
                sb.append('\n');
                col = 0;
            }
        }
        if (col > 0 || d.length == 0) sb.append('\n');
        return sb.toString();
    }

    /** 空白と改行は無視する。base64 でない文字があれば null */
    static byte[] decodeBase64(String s) {
        byte[] out = new byte[s.length() * 3 / 4 + 3];
        int n = 0, acc = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int v;
            if (c >= 'A' && c <= 'Z') v = c - 'A';
            else if (c >= 'a' && c <= 'z') v = c - 'a' + 26;
            else if (c >= '0' && c <= '9') v = c - '0' + 52;
            else if (c == '+') v = 62;
            else if (c == '/') v = 63;
            else if (c == '=' || c == '\n' || c == '\r' || c == ' ') continue;
            else return null;
            acc = (acc << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[n++] = (byte) (acc >> bits);
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }
}
