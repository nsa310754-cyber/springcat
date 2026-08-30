package site.ragdollp.filemanager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * su (スーパーユーザー) 経由でシェルコマンドを実行するヘルパ。
 *
 * 端末が root 化されている場合のみ機能する。通常のファイルアプリでは開けない
 * /data/data/... や /system 等を閲覧するために使う。実行時に Magisk 等の
 * スーパーユーザー管理アプリが許可ダイアログを出す。
 */
final class RootShell {

    static class Result {
        int exit = -1;
        byte[] stdout = new byte[0];
        String stderr = "";
    }

    private static final String[] SU_PATHS = {
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/system/sbin/su", "/vendor/bin/su", "/system/bin/.ext/.su",
        "/data/local/xbin/su", "/data/local/bin/su", "/data/local/su"
    };

    /** su バイナリが存在するか (root 化の目安)。実際の権限付与は requestRoot で確認。 */
    static boolean binaryPresent() {
        for (String p : SU_PATHS) {
            try { if (new File(p).exists()) return true; } catch (Throwable ignored) {}
        }
        return false;
    }

    /** `su -c id` を実行して uid=0 が得られるか確認する (許可ダイアログが出る)。 */
    static boolean requestRoot() {
        try {
            Result r = exec("id");
            return r.exit == 0 && new String(r.stdout).contains("uid=0");
        } catch (Throwable t) {
            return false;
        }
    }

    /** シェルコマンドを root で実行し、stdout をバイトで返す。 */
    static Result exec(String command) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
        return pump(p, null);
    }

    /** root でコマンドを実行し、stdin に input を流し込む (書き込み用)。 */
    static Result execWithInput(String command, byte[] input) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
        return pump(p, input);
    }

    private static Result pump(Process p, byte[] input) throws Exception {
        Result res = new Result();
        // stdin
        if (input != null) {
            try (OutputStream os = p.getOutputStream()) { os.write(input); os.flush(); }
        } else {
            try { p.getOutputStream().close(); } catch (Throwable ignored) {}
        }
        // stderr を別スレッドで吸い出す (デッドロック防止)
        final StringBuilder errSb = new StringBuilder();
        Thread errT = new Thread(() -> {
            try (InputStream es = p.getErrorStream()) {
                byte[] b = new byte[4096]; int r;
                while ((r = es.read(b)) > 0) errSb.append(new String(b, 0, r));
            } catch (Throwable ignored) {}
        });
        errT.start();
        // stdout
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream is = p.getInputStream()) {
            byte[] b = new byte[64 * 1024]; int r;
            while ((r = is.read(b)) > 0) bos.write(b, 0, r);
        }
        res.exit = p.waitFor();
        try { errT.join(1000); } catch (Throwable ignored) {}
        res.stdout = bos.toByteArray();
        res.stderr = errSb.toString();
        return res;
    }

    /** シングルクォートで安全に囲む (シェルインジェクション対策)。 */
    static String shq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    // ---- 高水準 API ----

    /** ディレクトリ一覧。各要素 {name,size,mtime(ms),isDir}。 */
    static List<String[]> list(String path) throws Exception {
        String q = shq(path);
        // 名前に | が含まれても壊れないよう %n を最後に置く
        String cmd = "cd " + q + " 2>/dev/null && for e in * .*; do "
            + "[ \"$e\" = . ] && continue; [ \"$e\" = .. ] && continue; "
            + "{ [ -e \"$e\" ] || [ -L \"$e\" ]; } || continue; "
            + "stat -c '%s|%Y|%F|%n' -- \"$e\" 2>/dev/null; done";
        Result r = exec(cmd);
        List<String[]> out = new ArrayList<>();
        String text = new String(r.stdout, java.nio.charset.StandardCharsets.UTF_8);
        for (String line : text.split("\n")) {
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\|", 4);
            if (parts.length < 4) continue;
            String size = parts[0], mtime = parts[1], type = parts[2], name = parts[3];
            boolean isDir = type.contains("directory");
            out.add(new String[]{name, size, mtime, isDir ? "1" : "0"});
        }
        return out;
    }

    static byte[] readFile(String path, long maxBytes) throws Exception {
        Result r = exec("cat -- " + shq(path));
        if (r.stdout.length > maxBytes) {
            byte[] trimmed = new byte[(int) maxBytes];
            System.arraycopy(r.stdout, 0, trimmed, 0, (int) maxBytes);
            return trimmed;
        }
        return r.stdout;
    }

    static boolean writeFile(String path, byte[] data) throws Exception {
        Result r = execWithInput("cat > " + shq(path), data);
        return r.exit == 0;
    }

    static boolean delete(String path) throws Exception {
        return exec("rm -rf -- " + shq(path)).exit == 0;
    }

    static boolean mkdirs(String path) throws Exception {
        return exec("mkdir -p -- " + shq(path)).exit == 0;
    }

    static boolean move(String src, String dst) throws Exception {
        return exec("mv -f -- " + shq(src) + " " + shq(dst)).exit == 0;
    }

    static boolean copy(String src, String dst) throws Exception {
        return exec("cp -rf -- " + shq(src) + " " + shq(dst)).exit == 0;
    }

    /** stat 1件分 {size,mtime(ms),isDir}。存在しなければ null。 */
    static String[] stat(String path) throws Exception {
        Result r = exec("stat -c '%s|%Y|%F' -- " + shq(path) + " 2>/dev/null");
        String s = new String(r.stdout, java.nio.charset.StandardCharsets.UTF_8).trim();
        if (s.isEmpty()) return null;
        String[] parts = s.split("\\|", 3);
        if (parts.length < 3) return null;
        return new String[]{parts[0], parts[1], parts[2].contains("directory") ? "1" : "0"};
    }
}
