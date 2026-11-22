package site.springcat.bionicrunner;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 「実行ファイル」から選んだ APK を、インストールの前に調べる。調べるのは端末の中だけで、どこにも送らない。
 *
 *   - アプリの名前・パッケージ名・版・署名 (発行元)・求める権限 (注意が必要なものを目立たせる)
 *   - 同じアプリが同じ署名で入っていれば「認識済み (同じ発行元の更新)」とみなす
 *   - 中の実行できる部分 (classes.dex・lib の .so・スクリプト) を取り出し、ほかのファイルと同じ検査
 *     (危険な操作の記述・YARA ルール) をする。APK 全体はウイルスの定義 (ClamAV のハッシュ値) とも照合する
 */
final class ApkCheck {

    static final class Info {
        String label = "";
        String packageName = "";
        String versionName = "";
        long versionCode;
        /** 署名の証明書の持ち主の名前 (CN や O)。読めなければ空 */
        String signer = "";
        /** 署名の証明書の SHA-256 (16 進) */
        String signerSha256 = "";
        /** 求める権限 (短い名前) */
        final List<String> permissions = new ArrayList<>();
        /** そのうち注意が必要なもの (説明付き) */
        final List<String> risky = new ArrayList<>();
        /** 同じパッケージが同じ署名で入っている (同じ発行元の更新) */
        boolean sameSignerInstalled;
        /** 同じパッケージが入っているが署名が違う (別の発行元。インストールは失敗する) */
        boolean otherSignerInstalled;
        String installedVersion = "";
    }

    /** 注意が必要な権限と、その説明 */
    private static final String[][] RISKY = {
            {"android.permission.BIND_ACCESSIBILITY_SERVICE", "画面の内容を読み、ほかのアプリを操作できる (ユーザー補助)"},
            {"android.permission.BIND_DEVICE_ADMIN", "端末の管理 (画面のロック・データの消去など)"},
            {"android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", "すべての通知を読める"},
            {"android.permission.SEND_SMS", "SMS を送る (有料の宛先へ送られることがある)"},
            {"android.permission.READ_SMS", "SMS を読む (認証コードを含む)"},
            {"android.permission.RECEIVE_SMS", "届いた SMS を受け取る"},
            {"android.permission.READ_CALL_LOG", "通話の記録を読む"},
            {"android.permission.CALL_PHONE", "電話をかける"},
            {"android.permission.READ_CONTACTS", "連絡先を読む"},
            {"android.permission.RECORD_AUDIO", "マイクで録音する"},
            {"android.permission.CAMERA", "カメラで撮影する"},
            {"android.permission.ACCESS_FINE_LOCATION", "正確な位置情報"},
            {"android.permission.ACCESS_BACKGROUND_LOCATION", "使っていないときも位置情報を取る"},
            {"android.permission.READ_PHONE_STATE", "電話番号や端末の識別番号を読む"},
            {"android.permission.SYSTEM_ALERT_WINDOW", "ほかのアプリの上に重ねて表示する"},
            {"android.permission.REQUEST_INSTALL_PACKAGES", "ほかのアプリをインストールする"},
            {"android.permission.MANAGE_EXTERNAL_STORAGE", "すべてのファイルの読み書き"},
            {"android.permission.QUERY_ALL_PACKAGES", "入っているアプリの一覧を読む"},
    };

    private ApkCheck() {
    }

    @SuppressWarnings("deprecation")
    static Info inspect(Context c, File apk) {
        Info info = new Info();
        PackageManager pm = c.getPackageManager();
        int flags = PackageManager.GET_PERMISSIONS | PackageManager.GET_SERVICES | PackageManager.GET_SIGNATURES;
        PackageInfo pi = null;
        try {
            pi = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        } catch (RuntimeException ignored) {
            // 読めない APK
        }
        if (pi == null) return info;
        info.packageName = pi.packageName == null ? "" : pi.packageName;
        info.versionName = pi.versionName == null ? "" : pi.versionName;
        info.versionCode = android.os.Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        if (pi.applicationInfo != null) {
            pi.applicationInfo.sourceDir = apk.getAbsolutePath();
            pi.applicationInfo.publicSourceDir = apk.getAbsolutePath();
            try {
                CharSequence l = pi.applicationInfo.loadLabel(pm);
                info.label = l == null ? "" : l.toString();
            } catch (RuntimeException ignored) {
                // 名前が読めなくても続ける
            }
        }
        Set<String> perms = new HashSet<>();
        if (pi.requestedPermissions != null) {
            for (String p : pi.requestedPermissions) if (p != null) perms.add(p);
        }
        // ユーザー補助・端末管理などは、サービスに付く権限として書かれる
        if (pi.services != null) {
            for (ServiceInfo s : pi.services) if (s.permission != null) perms.add(s.permission);
        }
        for (String p : perms) info.permissions.add(p.startsWith("android.permission.") ? p.substring(19) : p);
        java.util.Collections.sort(info.permissions);
        for (String[] r : RISKY) if (perms.contains(r[0])) info.risky.add(r[1]);

        byte[] cert = pi.signatures != null && pi.signatures.length > 0 ? pi.signatures[0].toByteArray() : null;
        if (cert != null) {
            info.signerSha256 = sha256(cert);
            info.signer = subject(cert);
        }
        // 同じアプリが入っているか、署名が同じか
        try {
            PackageInfo installed = pm.getPackageInfo(info.packageName, PackageManager.GET_SIGNATURES);
            info.installedVersion = installed.versionName == null ? "" : installed.versionName;
            boolean same = false;
            if (cert != null && installed.signatures != null) {
                for (Signature s : installed.signatures) if (sha256(s.toByteArray()).equals(info.signerSha256)) same = true;
            }
            info.sameSignerInstalled = same;
            info.otherSignerInstalled = !same;
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            // 入っていない
        }
        return info;
    }

    /** 証明書の持ち主の名前 (CN と O) */
    private static String subject(byte[] der) {
        try {
            java.security.cert.X509Certificate x = (java.security.cert.X509Certificate)
                    java.security.cert.CertificateFactory.getInstance("X.509")
                            .generateCertificate(new java.io.ByteArrayInputStream(der));
            String dn = x.getSubjectX500Principal().getName();
            StringBuilder sb = new StringBuilder();
            for (String part : dn.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
                String p = part.trim();
                if (p.startsWith("CN=") || p.startsWith("O=")) {
                    if (sb.length() > 0) sb.append(" / ");
                    sb.append(p.substring(p.indexOf('=') + 1));
                }
            }
            return sb.length() > 0 ? sb.toString() : dn;
        } catch (Exception e) {
            return "";
        }
    }

    static String sha256(byte[] b) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "";
        }
    }

    // ------------------------------------------------------------ 中身の検査

    /** 取り出して調べる上限 (1 個・合計・個数)。巨大な APK や zip 爆弾で端末を埋めないため */
    private static final long MAX_ENTRY = 64L * 1024 * 1024;
    private static final long MAX_TOTAL = 256L * 1024 * 1024;
    private static final int MAX_COUNT = 400;

    /** 実行できる部分か (名前で判断する) */
    static boolean executablePart(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.matches("classes\\d*\\.dex")) return true;
        if (n.startsWith("lib/") && n.endsWith(".so")) return true;
        if ((n.startsWith("assets/") || n.startsWith("res/raw/") || !n.contains("/"))
                && n.matches(".*\\.(sh|bash|js|py|lua|pl|rb|bin|elf|so|dex|jar|apk|exe|dll|bat|cmd|ps1|vbs|wsf)$")) {
            return true;
        }
        return false;
    }

    /**
     * APK の中の実行できる部分を work に取り出して調べ、見つかった危険な記述をまとめる
     * (どの部分のものかが分かる形で)。APK 全体はウイルスの定義とも照合する
     */
    static ExeScanner.Report scan(Context c, File apk, File work) {
        ExeScanner.Report all = new ExeScanner.Report();
        // ウイルスの定義 (ハッシュ値) との照合
        try {
            if (AvDefs.ready(c) && apk.length() <= 200L * 1024 * 1024) {
                String hit = AvDefs.lookup(c, AvDefs.hashes(apk), apk.length());
                if (hit != null) {
                    all.findings.add(new ExeScanner.Finding("ウイルスの定義 (ClamAV) に一致: " + hit,
                            "APK 全体が既知の脅威と同じファイルです"));
                }
            }
        } catch (IOException ignored) {
            // 照合できなければ省く
        }
        Set<String> seen = new HashSet<>();
        long total = 0;
        int count = 0;
        work.mkdirs();
        try (ZipFile z = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> es = z.entries();
            while (es.hasMoreElements()) {
                ZipEntry e = es.nextElement();
                String name = e.getName();
                if (e.isDirectory() || name.contains("..") || name.startsWith("/") || !executablePart(name)) continue;
                long size = e.getSize();
                if (size > MAX_ENTRY || count >= MAX_COUNT || total + Math.max(size, 0) > MAX_TOTAL) {
                    all.truncated = true;
                    continue;
                }
                File out = new File(work, (count++) + "-" + name.replaceAll("[^A-Za-z0-9._-]", "_"));
                long wrote = copyLimited(z.getInputStream(e), out, MAX_ENTRY);
                if (wrote < 0) {
                    out.delete();
                    all.truncated = true;
                    continue;
                }
                total += wrote;
                try {
                    ExeScanner.Report one = ExeScanner.scan(out);
                    all.truncated |= one.truncated;
                    for (ExeScanner.Finding f : one.findings) {
                        if (seen.add(f.title)) all.findings.add(new ExeScanner.Finding(f.title, name + ": " + f.evidence));
                    }
                } catch (IOException ignored) {
                    // 読めなかった部分は検査なし
                }
            }
        } catch (IOException ignored) {
            // zip として読めなければ、中身の検査は省く (インストールも失敗する)
        }
        // 追加してある YARA ルールでも調べる (取り出した部分を対象に)
        Yara.addFindings(c, all, work, work);
        return all;
    }

    /** 上限までだけ写す。上限を超えたら -1 */
    private static long copyLimited(InputStream in, File out, long limit) throws IOException {
        long n = 0;
        try (InputStream src = in; OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = src.read(buf)) > 0) {
                n += r;
                if (n > limit) return -1;
                os.write(buf, 0, r);
            }
        }
        return n;
    }
}
