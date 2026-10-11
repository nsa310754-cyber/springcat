package site.ragdollp.bigclip;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** su 経由でコマンドを実行する最小ヘルパー (Magisk / KernelSU 等)。 */
final class RootShell {
    static final class Result {
        final int code;
        final String out;
        Result(int code, String out) { this.code = code; this.out = out; }
        boolean ok() { return code == 0; }
    }

    private RootShell() {}

    /** 必ずバックグラウンドスレッドから呼ぶこと (su の許可ダイアログ待ちでブロックする)。 */
    static Result run(String script) {
        try {
            Process p = new ProcessBuilder("su").redirectErrorStream(true).start();
            try (OutputStream os = p.getOutputStream()) {
                os.write((script + "\nexit\n").getBytes(StandardCharsets.UTF_8));
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream is = p.getInputStream()) {
                byte[] b = new byte[4096];
                int n;
                while ((n = is.read(b)) > 0) buf.write(b, 0, n);
            }
            return new Result(p.waitFor(), buf.toString("UTF-8").trim());
        } catch (Exception e) {
            return new Result(-1, "su を実行できません: " + e.getMessage());
        }
    }

    static boolean available() {
        Result r = run("id -u");
        return r.ok() && r.out.endsWith("0");
    }
}
