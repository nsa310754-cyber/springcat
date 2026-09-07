package site.ragdollp.switchrec;

import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/** su 経由の最小ヘルパー。/dev/video* の列挙と権限付与に使う。 */
public final class RootHelper {
    private static final String TAG = "SwitchRecRoot";

    /** su が使えるか(root 端末か)。 */
    public static boolean hasRoot() {
        for (String p : new String[]{"/system/bin/su", "/system/xbin/su", "/sbin/su",
                "/su/bin/su", "/system/sbin/su", "/vendor/bin/su"}) {
            if (new File(p).exists()) return true;
        }
        return runSu("id").contains("uid=0");
    }

    /** su で一連のコマンドを実行し stdout を返す。失敗時は空文字。 */
    public static String runSu(String... cmds) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            for (String c : cmds) { os.writeBytes(c + "\n"); }
            os.writeBytes("exit\n");
            os.flush();
            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "runSu", e);
            return "";
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** /dev/video* を列挙(存在するものだけ)。 */
    public static List<String> listVideoDevices() {
        List<String> out = new ArrayList<>();
        // まずは通常の File で見えるか
        File dev = new File("/dev");
        File[] fs = dev.listFiles((d, name) -> name.startsWith("video"));
        if (fs != null) {
            for (File f : fs) out.add(f.getAbsolutePath());
        }
        if (out.isEmpty()) {
            // root で ls して拾う
            String r = runSu("ls /dev/video* 2>/dev/null");
            for (String s : r.split("\\s+")) {
                s = s.trim();
                if (s.startsWith("/dev/video")) out.add(s);
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** root で対象デバイスを 0666 にしてアプリから open 可能にする。 */
    public static void chmodOpen(String path) {
        runSu("chmod 666 " + path);
    }
}
