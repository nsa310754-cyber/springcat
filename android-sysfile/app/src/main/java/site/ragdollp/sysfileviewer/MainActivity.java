package site.ragdollp.sysfileviewer;

// SysFile Viewer (Android) — システムファイルをテキストとして閲覧する読み取り専用アプリ。
//   UI は WebView に読み込んだ assets/index.html。JS からは JavascriptInterface "Native"
//   経由でファイル一覧・読み取り・root 昇格を呼ぶ。
//   通常は java.io.File で読み、権限が足りないパスは (ユーザーが許可すれば) su 経由で読む。
//   ※ 端末が root 化 (Magisk 等) されていない場合、su 昇格は使えません。書き込みは一切しません。

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebSettings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Comparator;

public class MainActivity extends android.app.Activity {

    // テキスト表示の上限 (8 MiB)。巨大ログでも先頭のみ読んでフリーズを防ぐ。
    private static final long MAX_BYTES = 8L * 1024 * 1024;

    // ユーザーが root 昇格を許可済みか (requestRoot 成功後 true)。
    private volatile boolean rootActive = false;

    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);           // ネットワーク/外部 file:// は使わない (assets のみ)
        s.setAllowContentAccess(false);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "Native");
        setContentView(web);

        // 非 root 端末向けに従来の外部ストレージ読み取り権限を要求 (任意)。
        maybeRequestLegacyStorage();

        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        // 戻るキーは WebView 内の「上へ」ではなくアプリ終了 (UI 側で階層移動する)。
        super.onBackPressed();
    }

    private void maybeRequestLegacyStorage() {
        if (Build.VERSION.SDK_INT <= 32) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                try {
                    requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
                } catch (Exception ignored) {}
            }
        }
    }

    // =====================================================================
    // JS ↔ Java ブリッジ。全メソッドは JSON 文字列を返す (同期)。
    // =====================================================================
    private class Bridge {

        @JavascriptInterface
        public String info() {
            JSONObject o = new JSONObject();
            try {
                o.put("platform", "android");
                o.put("sdk", Build.VERSION.SDK_INT);
                o.put("suAvailable", suBinaryExists());
                o.put("rootActive", rootActive);
                o.put("manageAllFiles",
                        Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager());
                File ext = Environment.getExternalStorageDirectory();
                String home = (ext != null) ? ext.getAbsolutePath() : "/sdcard";
                o.put("home", home);
                o.put("root", "/");
            } catch (Exception e) {
                putErr(o, e);
            }
            return o.toString();
        }

        // root 昇格を要求。Magisk 等の許可ダイアログが出る。成功で rootActive=true。
        @JavascriptInterface
        public String requestRoot() {
            JSONObject o = new JSONObject();
            try {
                String out = suExecString("id");
                boolean ok = out != null && out.contains("uid=0");
                rootActive = ok;
                o.put("rooted", ok);
                o.put("message", ok
                        ? "スーパーユーザー権限を取得しました。"
                        : (suBinaryExists()
                            ? "root 権限が拒否されました (許可ダイアログで「許可」してください)。"
                            : "この端末では su が見つかりません (root 化されていません)。"));
            } catch (Exception e) {
                rootActive = false;
                putErr(o, e);
            }
            return o.toString();
        }

        // 端末設定の「すべてのファイルへのアクセス」画面を開く (Android 11+)。
        @JavascriptInterface
        public void requestAllFiles() {
            runOnUiThread(() -> {
                try {
                    if (Build.VERSION.SDK_INT >= 30) {
                        Intent i = new Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    } else {
                        maybeRequestLegacyStorage();
                    }
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public String list(String path) {
            return listDir(path).toString();
        }

        @JavascriptInterface
        public String read(String path) {
            return readFile(path).toString();
        }
    }

    // =====================================================================
    // ディレクトリ一覧
    // =====================================================================
    private JSONObject listDir(String path) {
        JSONObject res = new JSONObject();
        String resolved = normalize(path);
        try {
            res.put("path", resolved);
            File dir = new File(resolved);
            File[] kids = dir.listFiles();

            if (kids != null) {
                // 通常の Java で読めた。
                JSONArray arr = new JSONArray();
                Arrays.sort(kids, dirFirst());
                for (File f : kids) arr.put(entryJson(f));
                res.put("entries", arr);
                res.put("error", JSONObject.NULL);
                return res;
            }

            // 読めない。root があれば su で一覧化。
            if (rootActive) {
                JSONArray arr = suListDir(resolved);
                if (arr != null) {
                    res.put("entries", arr);
                    res.put("error", JSONObject.NULL);
                    return res;
                }
            }
            res.put("entries", new JSONArray());
            res.put("error", dir.exists()
                    ? "アクセスが拒否されました。スーパーユーザー権限を取得すると読める場合があります。"
                    : "ディレクトリが存在しないか、アクセスできません。");
        } catch (Exception e) {
            putErr(res, e);
        }
        return res;
    }

    private JSONObject entryJson(File f) {
        JSONObject e = new JSONObject();
        try {
            boolean dir = f.isDirectory();
            e.put("name", f.getName());
            e.put("type", dir ? "dir" : "file");
            e.put("symlink", isSymlink(f));
            e.put("size", dir ? JSONObject.NULL : f.length());
            e.put("mode", modeString(f));
        } catch (Exception ignored) {}
        return e;
    }

    // su 経由でディレクトリを一覧化。1 回の su 呼び出しでタブ区切り行を得る。
    private JSONArray suListDir(String path) {
        String p = shSingleQuote(path);
        // パス名展開の結果は語分割されないのでスペース入りファイル名も安全。
        String cmd =
            "cd " + p + " 2>/dev/null || exit 3\n" +
            "for name in .* *; do\n" +
            "  [ \"$name\" = \".\" ] && continue\n" +
            "  [ \"$name\" = \"..\" ] && continue\n" +
            "  [ -e \"$name\" ] || [ -L \"$name\" ] || continue\n" +
            "  if [ -d \"$name\" ]; then t=d; sz=0; else t=f; sz=$(stat -c %s \"$name\" 2>/dev/null || echo 0); fi\n" +
            "  md=$(stat -c %a \"$name\" 2>/dev/null || echo '')\n" +
            "  lk=0; [ -L \"$name\" ] && lk=1\n" +
            "  printf '%s\\t%s\\t%s\\t%s\\t%s\\n' \"$t\" \"$sz\" \"$md\" \"$lk\" \"$name\"\n" +
            "done\n";
        byte[] out = suExecBytes(cmd);
        if (out == null) return null;
        JSONArray arr = new JSONArray();
        String text = new String(out);
        for (String line : text.split("\n")) {
            if (line.isEmpty()) continue;
            String[] parts = line.split("\t", 5);
            if (parts.length < 5) continue;
            try {
                JSONObject e = new JSONObject();
                boolean dir = "d".equals(parts[0]);
                e.put("type", dir ? "dir" : "file");
                e.put("size", dir ? JSONObject.NULL : safeLong(parts[1]));
                e.put("mode", parts[2].isEmpty() ? JSONObject.NULL : "0" + parts[2]);
                e.put("symlink", "1".equals(parts[3]));
                e.put("name", parts[4]);
                arr.put(e);
            } catch (Exception ignored) {}
        }
        sortJsonEntries(arr);
        return arr;
    }

    // =====================================================================
    // ファイル読み取り (テキスト化)
    // =====================================================================
    private JSONObject readFile(String path) {
        JSONObject res = new JSONObject();
        String resolved = normalize(path);
        try {
            res.put("path", resolved);
            File f = new File(resolved);

            if (f.isDirectory()) {
                res.put("error", "これはディレクトリです。");
                return res;
            }

            byte[] data = null;
            long fullSize = f.length();
            boolean usedRoot = false;

            if (f.canRead() && f.isFile()) {
                data = readCapped(new FileInputStream(f));
            } else if (rootActive) {
                usedRoot = true;
                String p = shSingleQuote(resolved);
                String szStr = suExecString("stat -c %s " + p + " 2>/dev/null");
                fullSize = safeLong(szStr);
                data = suExecBytes("head -c " + MAX_BYTES + " " + p);
            }

            if (data == null) {
                res.put("error", "アクセスが拒否されました。スーパーユーザー権限を取得すると読める場合があります。");
                return res;
            }

            boolean binary = looksBinary(data);
            res.put("error", JSONObject.NULL);
            res.put("binary", binary);
            res.put("truncated", fullSize > MAX_BYTES);
            res.put("size", fullSize);
            res.put("mode", usedRoot ? suExecString("stat -c %a " + shSingleQuote(resolved))
                                     : modeString(f));
            res.put("text", new String(data)); // UTF-8。不正バイトは置換文字になる。
        } catch (Exception e) {
            putErr(res, e);
        }
        return res;
    }

    private byte[] readCapped(InputStream in) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while (total < MAX_BYTES && (n = in.read(buf)) != -1) {
                int take = (int) Math.min(n, MAX_BYTES - total);
                bos.write(buf, 0, take);
                total += take;
            }
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
    }

    // =====================================================================
    // su 実行ヘルパ
    // =====================================================================
    private byte[] suExecBytes(String script) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            os.writeBytes(script);
            os.writeBytes("\nexit\n");
            os.flush();
            byte[] out = readStream(p.getInputStream());
            int code = p.waitFor();
            if (code != 0 && (out == null || out.length == 0)) return null;
            return out;
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }

    private String suExecString(String script) {
        byte[] b = suExecBytes(script);
        return b == null ? null : new String(b).trim();
    }

    private byte[] readStream(InputStream in) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while (total <= MAX_BYTES && (n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
                total += n;
            }
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean suBinaryExists() {
        String[] paths = {
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/su/bin/su", "/system/sbin/su", "/vendor/bin/su",
            "/system/bin/.ext/su", "/data/adb/magisk/su"
        };
        for (String pth : paths) {
            if (new File(pth).exists()) return true;
        }
        return false;
    }

    // =====================================================================
    // ユーティリティ
    // =====================================================================
    private static boolean looksBinary(byte[] buf) {
        int n = Math.min(buf.length, 8192);
        for (int i = 0; i < n; i++) if (buf[i] == 0) return true;
        return false;
    }

    private static boolean isSymlink(File f) {
        try {
            return !f.getAbsolutePath().equals(f.getCanonicalPath());
        } catch (Exception e) {
            return false;
        }
    }

    private static String modeString(File f) {
        // Java からは正確なパーミッションビットは取りにくいので簡易表現。
        StringBuilder sb = new StringBuilder();
        sb.append(f.canRead() ? "r" : "-");
        sb.append(f.canWrite() ? "w" : "-");
        sb.append(f.canExecute() ? "x" : "-");
        return sb.toString();
    }

    private static long safeLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0L; }
    }

    private static String normalize(String path) {
        if (path == null || path.isEmpty()) return "/";
        try {
            String c = new File(path).getCanonicalPath();
            return c.isEmpty() ? "/" : c;
        } catch (Exception e) {
            return path;
        }
    }

    // シェルのシングルクォート内に安全に埋め込む: ' -> '\''
    private static String shSingleQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static Comparator<File> dirFirst() {
        return (a, b) -> {
            boolean da = a.isDirectory(), db = b.isDirectory();
            if (da != db) return da ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        };
    }

    private static void sortJsonEntries(JSONArray arr) {
        // 簡易挿入ソート (ディレクトリ優先 → 名前順)。件数は多くない想定。
        try {
            for (int i = 1; i < arr.length(); i++) {
                JSONObject key = arr.optJSONObject(i);
                int j = i - 1;
                while (j >= 0 && cmpEntry(arr.optJSONObject(j), key) > 0) {
                    arr.put(j + 1, arr.optJSONObject(j));
                    j--;
                }
                arr.put(j + 1, key);
            }
        } catch (org.json.JSONException ignored) {}
    }

    private static int cmpEntry(JSONObject a, JSONObject b) {
        boolean da = "dir".equals(a.optString("type"));
        boolean db = "dir".equals(b.optString("type"));
        if (da != db) return da ? -1 : 1;
        return a.optString("name").compareToIgnoreCase(b.optString("name"));
    }

    private static void putErr(JSONObject o, Exception e) {
        try {
            o.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
        } catch (Exception ignored) {}
    }
}
