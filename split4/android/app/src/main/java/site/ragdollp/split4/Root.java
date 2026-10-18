package site.ragdollp.split4;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;

/** su 経由でシェルコマンドを実行する小さなヘルパー。 */
final class Root {
    private static Boolean available;

    private Root() {}

    /** su が使えるか (初回のみ実際に su を呼んで確認し、結果をキャッシュ)。 */
    static synchronized boolean isAvailable() {
        if (available == null) {
            String out = run("id");
            available = out != null && out.contains("uid=0");
        }
        return available;
    }

    /** 結果キャッシュを捨てて再判定させる (ユーザーが後から root 許可した場合用)。 */
    static synchronized void reset() {
        available = null;
    }

    /** コマンドを順に実行し、標準出力+標準エラーを返す。su が無ければ null。 */
    static String run(String... cmds) {
        Process p = null;
        try {
            p = new ProcessBuilder("su").redirectErrorStream(true).start();
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            for (String c : cmds) {
                os.writeBytes(c + "\n");
            }
            os.writeBytes("exit\n");
            os.flush();
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** シェル用に単一引用符でクオートする。 */
    static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
