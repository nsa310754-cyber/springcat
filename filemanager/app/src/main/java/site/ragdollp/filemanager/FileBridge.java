package site.ragdollp.filemanager;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.core.content.FileProvider;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * WebView から呼ばれるネイティブのファイル操作ブリッジ。
 *
 * 全メソッドは JSON 文字列を返す (成功時 {"ok":true,...}, 失敗時 {"ok":false,"error":"..."})。
 * @JavascriptInterface のメソッドは WebView 専用スレッドで動くため、ここでの
 * 同期的なファイル I/O は UI をブロックしない。インストールや共有・権限要求など
 * Activity/UI を伴う処理だけ UI スレッドへ post する。
 */
public class FileBridge {

    static final int REQ_ALL_FILES = 4101;

    private final MainActivity act;
    private final WebView web;

    FileBridge(MainActivity act, WebView web) {
        this.act = act;
        this.web = web;
    }

    // ------------------------------------------------------------------ 権限/環境

    @JavascriptInterface
    public boolean isOnline() {
        try {
            android.net.ConnectivityManager cm =
                (android.net.ConnectivityManager) act.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.net.Network n = cm.getActiveNetwork();
                if (n == null) return false;
                android.net.NetworkCapabilities c = cm.getNetworkCapabilities(n);
                return c != null && c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
            } else {
                android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
                return ni != null && ni.isConnected();
            }
        } catch (Throwable t) { return false; }
    }

    @JavascriptInterface
    public boolean hasAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return true;
    }

    /** Android 11+ の「すべてのファイルへのアクセス」設定画面を開く。 */
    @JavascriptInterface
    public void requestAllFilesAccess() {
        act.runOnUiThread(() -> {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    i.setData(Uri.parse("package:" + act.getPackageName()));
                    act.startActivityForResult(i, REQ_ALL_FILES);
                }
            } catch (Throwable t) {
                try {
                    act.startActivityForResult(
                        new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), REQ_ALL_FILES);
                } catch (Throwable ignored) {}
            }
        });
    }

    // ------------------------------------------------------------------ 一覧/ストレージ

    /** よく使うルート (内部ストレージ / SD / Download 等) を返す。 */
    @JavascriptInterface
    public String roots() {
        JsonBuilder j = new JsonBuilder().obj().kv("ok", true).arr("roots");
        addRoot(j, "内部ストレージ", Environment.getExternalStorageDirectory());
        addRoot(j, "Download", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
        addRoot(j, "DCIM", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM));
        addRoot(j, "Documents", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS));
        addRoot(j, "アプリ専用領域", act.getExternalFilesDir(null));
        // 追加の外部ボリューム (SDカード等)
        try {
            for (File f : act.getExternalFilesDirs(null)) {
                if (f == null) continue;
                // /storage/XXXX/Android/data/pkg/files → /storage/XXXX を推定
                String p = f.getAbsolutePath();
                int idx = p.indexOf("/Android/");
                if (idx > 0) {
                    File vol = new File(p.substring(0, idx));
                    if (vol.canRead() && !vol.equals(Environment.getExternalStorageDirectory())) {
                        addRoot(j, "SD/外部: " + vol.getName(), vol);
                    }
                }
            }
        } catch (Throwable ignored) {}
        j.endArr().endObj();
        return j.toString();
    }

    private void addRoot(JsonBuilder j, String name, File f) {
        if (f == null) return;
        j.objInArr().kv("name", name).kv("path", f.getAbsolutePath())
            .kv("exists", f.exists()).endObjInArr();
    }

    @JavascriptInterface
    public String list(String path) {
        try {
            File dir = new File(path);
            if (!dir.exists()) return err("パスが存在しません: " + path);
            if (!dir.isDirectory()) return err("ディレクトリではありません: " + path);
            File[] files = dir.listFiles();
            if (files == null) files = new File[0];
            // ディレクトリ優先 → 名前順
            Arrays.sort(files, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            JsonBuilder j = new JsonBuilder().obj().kv("ok", true)
                .kv("path", dir.getAbsolutePath());
            File parent = dir.getParentFile();
            j.kv("parent", parent == null ? "" : parent.getAbsolutePath());
            j.arr("entries");
            for (File f : files) {
                j.objInArr()
                    .kv("name", f.getName())
                    .kv("path", f.getAbsolutePath())
                    .kv("isDir", f.isDirectory())
                    .kvNum("size", f.isDirectory() ? 0 : f.length())
                    .kvNum("mtime", f.lastModified())
                    .kv("ext", ext(f.getName()))
                    .kv("readable", f.canRead())
                    .endObjInArr();
            }
            j.endArr().endObj();
            return j.toString();
        } catch (Throwable t) {
            return err(t);
        }
    }

    @JavascriptInterface
    public String storageInfo() {
        try {
            JsonBuilder j = new JsonBuilder().obj().kv("ok", true).arr("volumes");
            addVolume(j, "内部ストレージ", Environment.getExternalStorageDirectory());
            addVolume(j, "システム(データ)", Environment.getDataDirectory());
            try {
                for (File f : act.getExternalFilesDirs(null)) {
                    if (f == null) continue;
                    String p = f.getAbsolutePath();
                    int idx = p.indexOf("/Android/");
                    if (idx > 0) {
                        File vol = new File(p.substring(0, idx));
                        if (vol.canRead() && !vol.equals(Environment.getExternalStorageDirectory())) {
                            addVolume(j, "SD/外部: " + vol.getName(), vol);
                        }
                    }
                }
            } catch (Throwable ignored) {}
            j.endArr().endObj();
            return j.toString();
        } catch (Throwable t) {
            return err(t);
        }
    }

    private void addVolume(JsonBuilder j, String name, File path) {
        try {
            if (path == null || !path.exists()) return;
            StatFs st = new StatFs(path.getAbsolutePath());
            long total = st.getTotalBytes();
            long free = st.getAvailableBytes();
            j.objInArr().kv("name", name).kv("path", path.getAbsolutePath())
                .kvNum("total", total).kvNum("free", free).kvNum("used", total - free)
                .endObjInArr();
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ 読み書き

    @JavascriptInterface
    public String readText(String path) {
        try {
            File f = new File(path);
            if (!f.exists() || !f.isFile()) return err("ファイルがありません");
            long max = 4L * 1024 * 1024; // 4MB まで
            boolean truncated = f.length() > max;
            long toRead = Math.min(f.length(), max);
            byte[] buf = new byte[(int) toRead];
            try (FileInputStream in = new FileInputStream(f)) {
                int off = 0, r;
                while (off < buf.length && (r = in.read(buf, off, buf.length - off)) > 0) off += r;
            }
            String text = new String(buf, java.nio.charset.StandardCharsets.UTF_8);
            return new JsonBuilder().obj().kv("ok", true).kv("text", text)
                .kv("truncated", truncated).endObj().toString();
        } catch (Throwable t) {
            return err(t);
        }
    }

    /** バイナリを data URL 用に base64 で返す (画像プレビュー等)。上限は maxBytes。 */
    @JavascriptInterface
    public String readBase64(String path, double maxBytes) {
        try {
            File f = new File(path);
            if (!f.exists() || !f.isFile()) return err("ファイルがありません");
            long max = (long) maxBytes;
            if (f.length() > max) return err("大きすぎます (" + f.length() + " bytes)");
            byte[] buf = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int off = 0, r;
                while (off < buf.length && (r = in.read(buf, off, buf.length - off)) > 0) off += r;
            }
            String b64 = android.util.Base64.encodeToString(buf, android.util.Base64.NO_WRAP);
            return new JsonBuilder().obj().kv("ok", true).kv("base64", b64).endObj().toString();
        } catch (Throwable t) {
            return err(t);
        }
    }

    @JavascriptInterface
    public String writeText(String path, String content) {
        try {
            File f = new File(path);
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(f))) {
                out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return okPath(f);
        } catch (Throwable t) {
            return err(t);
        }
    }

    /** ファイル新規作成 (名前 + 拡張子は name に含める)。既存なら失敗。 */
    @JavascriptInterface
    public String createFile(String dir, String name, String content) {
        try {
            File f = new File(dir, name);
            if (f.exists()) return err("既に存在します: " + name);
            File p = f.getParentFile();
            if (p != null && !p.exists()) p.mkdirs();
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(f))) {
                if (content != null) out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return okPath(f);
        } catch (Throwable t) {
            return err(t);
        }
    }

    @JavascriptInterface
    public String createDir(String dir, String name) {
        try {
            File f = new File(dir, name);
            if (f.exists()) return err("既に存在します: " + name);
            if (!f.mkdirs()) return err("作成できませんでした");
            return okPath(f);
        } catch (Throwable t) {
            return err(t);
        }
    }

    /** リネーム。拡張子変更 (.md→.txt, .zip→.apk 等) もこれで行う。 */
    @JavascriptInterface
    public String rename(String path, String newName) {
        try {
            File f = new File(path);
            if (!f.exists()) return err("ファイルがありません");
            File dest = new File(f.getParentFile(), newName);
            if (dest.exists()) return err("同名が既に存在します: " + newName);
            if (!f.renameTo(dest)) return err("リネームに失敗しました");
            return okPath(dest);
        } catch (Throwable t) {
            return err(t);
        }
    }

    /** コピー (拡張子を変えて別名保存する「〜として開く」用途にも使う)。 */
    @JavascriptInterface
    public String copyTo(String path, String destPath) {
        try {
            File src = new File(path);
            File dst = new File(destPath);
            if (dst.getParentFile() != null && !dst.getParentFile().exists()) dst.getParentFile().mkdirs();
            copyFile(src, dst);
            return okPath(dst);
        } catch (Throwable t) {
            return err(t);
        }
    }

    @JavascriptInterface
    public String delete(String path) {
        try {
            File f = new File(path);
            boolean ok = deleteRecursive(f);
            return ok ? okMsg("削除しました") : err("削除に失敗しました");
        } catch (Throwable t) {
            return err(t);
        }
    }

    // ------------------------------------------------------------------ 圧縮

    /**
     * 複数ファイル/フォルダをまとめて圧縮する。
     * @param pathsJson  ["/path/a", "/path/b", ...] の JSON 配列
     * @param destDir    出力先ディレクトリ
     * @param baseName   出力ファイル名 (拡張子なし)
     * @param format     zip / jar / tar / gz / tgz(tar.gz) / 7z / rar
     */
    @JavascriptInterface
    public String compress(String pathsJson, String destDir, String baseName, String format) {
        try {
            List<File> sources = parsePaths(pathsJson);
            if (sources.isEmpty()) return err("対象がありません");
            format = format.toLowerCase();

            if (format.equals("rar")) {
                return err("RAR は独自形式のため作成はできません (展開のみ対応)。zip/7z/tar/gz をご利用ください。");
            }

            File out;
            switch (format) {
                case "zip":
                case "jar":
                    out = new File(destDir, baseName + "." + format);
                    zipCompress(sources, out);
                    break;
                case "tar":
                    out = new File(destDir, baseName + ".tar");
                    tarCompress(sources, out, false);
                    break;
                case "tgz":
                case "targz":
                case "tar.gz":
                    out = new File(destDir, baseName + ".tar.gz");
                    tarCompress(sources, out, true);
                    break;
                case "gz":
                    // 単一ファイルなら .gz、複数なら自動的に tar.gz にする。
                    if (sources.size() == 1 && sources.get(0).isFile()) {
                        out = new File(destDir, sources.get(0).getName() + ".gz");
                        gzipSingle(sources.get(0), out);
                    } else {
                        out = new File(destDir, baseName + ".tar.gz");
                        tarCompress(sources, out, true);
                    }
                    break;
                case "7z":
                    out = new File(destDir, baseName + ".7z");
                    sevenZCompress(sources, out);
                    break;
                default:
                    return err("未対応の形式: " + format);
            }
            return okPath(out);
        } catch (Throwable t) {
            return err(t);
        }
    }

    private void zipCompress(List<File> sources, File out) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
            for (File src : sources) addToZip(zos, src, src.getName());
        }
    }

    private void addToZip(ZipOutputStream zos, File f, String entryName) throws Exception {
        if (f.isDirectory()) {
            ZipEntry e = new ZipEntry(entryName.endsWith("/") ? entryName : entryName + "/");
            zos.putNextEntry(e);
            zos.closeEntry();
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) addToZip(zos, k, entryName + "/" + k.getName());
        } else {
            zos.putNextEntry(new ZipEntry(entryName));
            copyStream(new FileInputStream(f), zos, false);
            zos.closeEntry();
        }
    }

    private void tarCompress(List<File> sources, File out, boolean gzip) throws Exception {
        OutputStream fos = new BufferedOutputStream(new FileOutputStream(out));
        OutputStream base = gzip ? new GzipCompressorOutputStream(fos) : fos;
        try (TarArchiveOutputStream tos = new TarArchiveOutputStream(base)) {
            tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (File src : sources) addToTar(tos, src, src.getName());
        }
    }

    private void addToTar(TarArchiveOutputStream tos, File f, String entryName) throws Exception {
        TarArchiveEntry entry = new TarArchiveEntry(f, entryName);
        tos.putArchiveEntry(entry);
        if (f.isFile()) {
            copyStream(new FileInputStream(f), tos, false);
        }
        tos.closeArchiveEntry();
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) addToTar(tos, k, entryName + "/" + k.getName());
        }
    }

    private void gzipSingle(File src, File out) throws Exception {
        try (GzipCompressorOutputStream gos =
                 new GzipCompressorOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
            copyStream(new FileInputStream(src), gos, false);
        }
    }

    private void sevenZCompress(List<File> sources, File out) throws Exception {
        try (SevenZOutputFile sz = new SevenZOutputFile(out)) {
            for (File src : sources) addTo7z(sz, src, src.getName());
        }
    }

    private void addTo7z(SevenZOutputFile sz, File f, String entryName) throws Exception {
        SevenZArchiveEntry entry = sz.createArchiveEntry(f, entryName);
        sz.putArchiveEntry(entry);
        if (f.isFile()) {
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) sz.write(buf, 0, r);
            }
        }
        sz.closeArchiveEntry();
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) addTo7z(sz, k, entryName + "/" + k.getName());
        }
    }

    // ------------------------------------------------------------------ 解凍

    /** 圧縮ファイルを destDir/<name>/ 以下に展開する。対応: zip/jar/apk/7z/tar/gz/tgz/rar。 */
    @JavascriptInterface
    public String extract(String path, String destDir) {
        try {
            File src = new File(path);
            if (!src.exists() || !src.isFile()) return err("ファイルがありません");
            String low = src.getName().toLowerCase();

            File outDir = new File(destDir, stripArchiveExt(src.getName()));
            int n = 1;
            while (outDir.exists()) { outDir = new File(destDir, stripArchiveExt(src.getName()) + "_" + (n++)); }
            outDir.mkdirs();

            int count;
            if (low.endsWith(".zip") || low.endsWith(".jar") || low.endsWith(".apk")) {
                count = extractZip(src, outDir);
            } else if (low.endsWith(".7z")) {
                count = extract7z(src, outDir);
            } else if (low.endsWith(".tar.gz") || low.endsWith(".tgz")) {
                count = extractTar(new GzipCompressorInputStream(new BufferedInputStream(new FileInputStream(src))), outDir);
            } else if (low.endsWith(".tar")) {
                count = extractTar(new BufferedInputStream(new FileInputStream(src)), outDir);
            } else if (low.endsWith(".gz")) {
                count = extractGzSingle(src, outDir);
            } else if (low.endsWith(".rar")) {
                count = extractRar(src, outDir);
            } else {
                // 拡張子不明: zip として試す
                count = extractZip(src, outDir);
            }
            return new JsonBuilder().obj().kv("ok", true).kv("dir", outDir.getAbsolutePath())
                .kvNum("count", count).endObj().toString();
        } catch (Throwable t) {
            return err(t);
        }
    }

    private int extractZip(File src, File outDir) throws Exception {
        int count = 0;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(src)))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                File target = safeTarget(outDir, e.getName());
                if (e.isDirectory()) {
                    target.mkdirs();
                } else {
                    if (target.getParentFile() != null) target.getParentFile().mkdirs();
                    copyStream(zis, new BufferedOutputStream(new FileOutputStream(target)), true, false);
                    count++;
                }
                zis.closeEntry();
            }
        }
        return count;
    }

    private int extract7z(File src, File outDir) throws Exception {
        int count = 0;
        try (SevenZFile sz = new SevenZFile(src)) {
            SevenZArchiveEntry e;
            while ((e = sz.getNextEntry()) != null) {
                File target = safeTarget(outDir, e.getName());
                if (e.isDirectory()) { target.mkdirs(); continue; }
                if (target.getParentFile() != null) target.getParentFile().mkdirs();
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                    byte[] buf = new byte[64 * 1024];
                    int r;
                    while ((r = sz.read(buf)) > 0) out.write(buf, 0, r);
                }
                count++;
            }
        }
        return count;
    }

    private int extractTar(InputStream in, File outDir) throws Exception {
        int count = 0;
        try (TarArchiveInputStream tis = new TarArchiveInputStream(in)) {
            TarArchiveEntry e;
            while ((e = tis.getNextEntry()) != null) {
                File target = safeTarget(outDir, e.getName());
                if (e.isDirectory()) { target.mkdirs(); continue; }
                if (target.getParentFile() != null) target.getParentFile().mkdirs();
                copyStream(tis, new BufferedOutputStream(new FileOutputStream(target)), true, false);
                count++;
            }
        }
        return count;
    }

    private int extractGzSingle(File src, File outDir) throws Exception {
        String name = src.getName();
        String outName = name.toLowerCase().endsWith(".gz") ? name.substring(0, name.length() - 3) : name + ".out";
        File target = new File(outDir, outName);
        try (GzipCompressorInputStream gis =
                 new GzipCompressorInputStream(new BufferedInputStream(new FileInputStream(src)))) {
            copyStream(gis, new BufferedOutputStream(new FileOutputStream(target)), true);
        }
        return 1;
    }

    private int extractRar(File src, File outDir) throws Exception {
        int count = 0;
        try (com.github.junrar.Archive arch = new com.github.junrar.Archive(src)) {
            com.github.junrar.rarfile.FileHeader fh;
            while ((fh = arch.nextFileHeader()) != null) {
                String name = fh.getFileName();
                if (name == null || name.isEmpty()) continue;
                File target = safeTarget(outDir, name.replace('\\', '/'));
                if (fh.isDirectory()) { target.mkdirs(); continue; }
                if (target.getParentFile() != null) target.getParentFile().mkdirs();
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                    arch.extractFile(fh, out);
                }
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ APK 検査 / インストール

    private static final List<String> DANGEROUS = Arrays.asList(
        "android.permission.SEND_SMS", "android.permission.RECEIVE_SMS", "android.permission.READ_SMS",
        "android.permission.CALL_PHONE", "android.permission.READ_CONTACTS", "android.permission.READ_CALL_LOG",
        "android.permission.ACCESS_FINE_LOCATION", "android.permission.RECORD_AUDIO", "android.permission.CAMERA",
        "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.REQUEST_INSTALL_PACKAGES", "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.PACKAGE_USAGE_STATS", "android.permission.BIND_ACCESSIBILITY_SERVICE",
        "android.permission.RECEIVE_BOOT_COMPLETED", "android.permission.QUERY_ALL_PACKAGES");

    /**
     * APK を解析して簡易セキュリティ情報を返す。実インストールはユーザー判断に委ねる
     * (「マルウェアでもそれでもインストールを押せば入る」= 警告は出すが最終判断はユーザー)。
     */
    @JavascriptInterface
    public String inspectApk(String path) {
        try {
            File f = new File(path);
            if (!f.exists()) return err("ファイルがありません");
            PackageManager pm = act.getPackageManager();
            int flags = PackageManager.GET_PERMISSIONS;
            PackageInfo pi = pm.getPackageArchiveInfo(f.getAbsolutePath(), flags);
            if (pi == null) return err("APK として解析できませんでした (壊れているか非APK)");

            // ラベル取得のため sourceDir/publicSourceDir を設定
            ApplicationInfo ai = pi.applicationInfo;
            String label = pi.packageName;
            if (ai != null) {
                ai.sourceDir = f.getAbsolutePath();
                ai.publicSourceDir = f.getAbsolutePath();
                try { label = pm.getApplicationLabel(ai).toString(); } catch (Throwable ignored) {}
            }

            List<String> perms = new ArrayList<>();
            List<String> dangerous = new ArrayList<>();
            if (pi.requestedPermissions != null) {
                for (String p : pi.requestedPermissions) {
                    perms.add(p);
                    if (DANGEROUS.contains(p)) dangerous.add(p);
                }
            }

            boolean installed = false;
            String installedVer = "";
            try {
                PackageInfo cur = pm.getPackageInfo(pi.packageName, 0);
                installed = true;
                installedVer = cur.versionName;
            } catch (PackageManager.NameNotFoundException ignored) {}

            // 署名の有無 (未署名 APK はインストール不可 = 疑わしい指標)
            boolean signed = hasSignature(pm, f);

            // 簡易リスクスコア (目安)。危険権限数と未署名・デバッグ可否から算出。
            int risk = dangerous.size() * 12;
            if (!signed) risk += 40;
            if (ai != null && (ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) risk += 15;
            if (risk > 100) risk = 100;
            String verdict = risk >= 60 ? "危険度: 高" : (risk >= 30 ? "危険度: 中" : "危険度: 低");

            JsonBuilder j = new JsonBuilder().obj().kv("ok", true)
                .kv("package", pi.packageName)
                .kv("label", label)
                .kv("versionName", pi.versionName == null ? "" : pi.versionName)
                .kvNum("versionCode", legacyVersionCode(pi))
                .kvNum("minSdk", ai != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ? ai.minSdkVersion : 0)
                .kvNum("targetSdk", ai != null ? ai.targetSdkVersion : 0)
                .kv("signed", signed)
                .kv("installed", installed)
                .kv("installedVersion", installedVer)
                .kvNum("risk", risk)
                .kv("verdict", verdict)
                .kvNum("size", f.length());
            j.arr("permissions");
            for (String p : perms) j.strInArr(p);
            j.endArr();
            j.arr("dangerous");
            for (String p : dangerous) j.strInArr(p);
            j.endArr();
            j.endObj();
            return j.toString();
        } catch (Throwable t) {
            return err(t);
        }
    }

    private boolean hasSignature(PackageManager pm, File f) {
        try {
            PackageInfo sp;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                sp = pm.getPackageArchiveInfo(f.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
                return sp != null && sp.signingInfo != null;
            } else {
                sp = pm.getPackageArchiveInfo(f.getAbsolutePath(), PackageManager.GET_SIGNATURES);
                return sp != null && sp.signatures != null && sp.signatures.length > 0;
            }
        } catch (Throwable t) { return false; }
    }

    @SuppressWarnings("deprecation")
    private long legacyVersionCode(PackageInfo pi) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return pi.getLongVersionCode();
        return pi.versionCode;
    }

    /**
     * APK をインストールする。任意ファイルを「APKとして」入れる用途にも使える
     * (例: zip を .apk 拡張子にリネームしてこれを呼ぶ)。実際のインストール確認は
     * Android のパッケージインストーラが行う。
     */
    @JavascriptInterface
    public void installApk(String path) {
        act.runOnUiThread(() -> {
            try {
                File f = new File(path);
                // 提供元不明アプリの許可が無い場合は設定へ誘導。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        && !act.getPackageManager().canRequestPackageInstalls()) {
                    Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + act.getPackageName()));
                    act.startActivity(s);
                    toastJs("提供元不明アプリのインストールを許可してから、もう一度インストールしてください。");
                    return;
                }
                Uri uri = FileProvider.getUriForFile(act, act.getPackageName() + ".fileprovider", f);
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(i);
            } catch (Throwable t) {
                toastJs("インストールを開始できませんでした: " + t.getMessage());
            }
        });
    }

    /** 任意の MIME でファイルを開く (「〜として開く」の外部アプリ委譲用)。 */
    @JavascriptInterface
    public void openAs(String path, String mime) {
        act.runOnUiThread(() -> {
            try {
                File f = new File(path);
                Uri uri = FileProvider.getUriForFile(act, act.getPackageName() + ".fileprovider", f);
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, mime == null || mime.isEmpty() ? "*/*" : mime);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(Intent.createChooser(i, "開くアプリを選択")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable t) {
                toastJs("開けませんでした: " + t.getMessage());
            }
        });
    }

    @JavascriptInterface
    public void shareFile(String path, String mime) {
        act.runOnUiThread(() -> {
            try {
                File f = new File(path);
                Uri uri = FileProvider.getUriForFile(act, act.getPackageName() + ".fileprovider", f);
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType(mime == null || mime.isEmpty() ? "*/*" : mime);
                i.putExtra(Intent.EXTRA_STREAM, uri);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                act.startActivity(Intent.createChooser(i, "共有").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable t) {
                toastJs("共有できませんでした: " + t.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------ 内部ユーティリティ

    private void toastJs(String msg) {
        act.jsCallback("window.toast && window.toast(" + JsonBuilder.quote(msg) + ");");
    }

    private List<File> parsePaths(String json) {
        List<File> out = new ArrayList<>();
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) out.add(new File(arr.getString(i)));
        } catch (Throwable ignored) {}
        return out;
    }

    /** Zip Slip 対策: 展開先が outDir 配下に収まることを保証する。 */
    private File safeTarget(File outDir, String name) throws Exception {
        File target = new File(outDir, name);
        String canonicalDir = outDir.getCanonicalPath();
        String canonicalTarget = target.getCanonicalPath();
        if (!canonicalTarget.startsWith(canonicalDir + File.separator) && !canonicalTarget.equals(canonicalDir)) {
            throw new SecurityException("不正なエントリパス: " + name);
        }
        return target;
    }

    private void copyFile(File src, File dst) throws Exception {
        try (InputStream in = new BufferedInputStream(new FileInputStream(src));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dst))) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        }
    }

    /**
     * in から out へコピー。in は常にクローズする (ファイル記述子リーク防止)。
     * out は closeOut=true のときのみクローズする (アーカイブストリームは閉じない)。
     * closeIn=false のときは in を閉じない ( zip/tar/7z の共有入力ストリーム用)。
     */
    private void copyStream(InputStream in, OutputStream out, boolean closeOut) throws Exception {
        copyStream(in, out, closeOut, true);
    }
    private void copyStream(InputStream in, OutputStream out, boolean closeOut, boolean closeIn) throws Exception {
        try {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            out.flush();
        } finally {
            if (closeOut) { try { out.close(); } catch (Throwable ignored) {} }
            if (closeIn)  { try { in.close(); }  catch (Throwable ignored) {} }
        }
    }

    private boolean deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        return f.delete();
    }

    private static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return (dot < 0 || dot == name.length() - 1) ? "" : name.substring(dot + 1).toLowerCase();
    }

    private static String stripArchiveExt(String name) {
        String low = name.toLowerCase();
        if (low.endsWith(".tar.gz")) return name.substring(0, name.length() - 7);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    // ---- JSON helpers ----
    private String okPath(File f) {
        return new JsonBuilder().obj().kv("ok", true).kv("path", f.getAbsolutePath())
            .kv("name", f.getName()).endObj().toString();
    }
    private String okMsg(String m) {
        return new JsonBuilder().obj().kv("ok", true).kv("message", m).endObj().toString();
    }
    private String err(String m) {
        return new JsonBuilder().obj().kv("ok", false).kv("error", m).endObj().toString();
    }
    private String err(Throwable t) {
        return err(t.getClass().getSimpleName() + ": " + (t.getMessage() == null ? "" : t.getMessage()));
    }
}
