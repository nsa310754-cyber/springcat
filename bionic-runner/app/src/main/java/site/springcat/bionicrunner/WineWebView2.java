package site.springcat.bionicrunner;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Microsoft Edge WebView2 ランタイムの取り込み (試験中)。
 *
 * WebView2 ランタイムは Microsoft のソフトウェアで、再配布には Microsoft の条件があるので、APK にも
 * リポジトリにも入れない。使う人が自分の Windows の PC から取り出した x64 版のフォルダを zip にして、
 * スマホに置いたものを取り込む (例: webview2-x64.zip。中身は 157.0.4341.60 のような版の名前のフォルダ)。
 *
 * 取り込むときに確かめること:
 *   - msedgewebview2.exe があり、x64 用であること (x86・ARM64 用なら理由を出して止める)
 *   - 版: フォルダの名前と msedgewebview2.exe の版の情報の両方を読み、違えば知らせる
 *   - Authenticode の署名: 中身の一致・署名の数値・Microsoft のルート証明書までの連鎖を、端末の中で確かめる
 *     ({@link Authenticode}。期限とタイムスタンプは見ない)。署名者が Microsoft Corporation でなければ止める
 *   - zip と msedgewebview2.exe の SHA-256 を記録する
 *   - 主なファイルを構造の検査 (PeInfo / ExeScanner) にかけ、結果を記録して見せる (止めはしない。
 *     Chromium は大きく、誤判定が出やすいため)
 *
 * 置き場所: files/wine/webview2/<版>/ (展開した場所をそのまま使う。コピーはしない)。Windows の環境からは
 * wine.sh が C:\Program Files (x86)\Microsoft\EdgeWebView\Application\<版> をこのフォルダへのリンクにし、
 * WebView2 が入っている印をレジストリに書く。
 */
final class WineWebView2 {

    /** 信頼するルート証明書 (assets/certs) と、その SHA-256 の指紋 */
    private static final String[][] ROOTS = {
            {"certs/microsoft-root-2011.cer", "847df6a78497943f27fc72eb93f9a637320a02b561d0a91b09e87a7807ed7c61"},
            {"certs/microsoft-root-2010.cer", "df545bf919a2439c36983b54cdfc903dfa4f37d3996d8d84b4c31eec6f3c163e"},
    };
    /** 署名を確かめるファイル (ランタイムのフォルダからの場所) */
    private static final String[] SIGNED = {"msedgewebview2.exe", "EBWebView/x64/EmbeddedBrowserWebView.dll", "msedge.dll"};
    static final String DEFAULT_ARGS = "--no-sandbox --disable-gpu";

    private WineWebView2() {
    }

    static File dir(Context c) {
        return new File(Wine.dir(c), "webview2");
    }

    /** 取り込んだ版 (無ければ null) */
    static String version(Context c) {
        String v = read(new File(dir(c), "current"));
        if (v == null || !v.matches("\\d+(\\.\\d+){3}")) return null;
        return new File(new File(dir(c), v), "msedgewebview2.exe").isFile() ? v : null;
    }

    static boolean installed(Context c) {
        return version(c) != null;
    }

    static long size(Context c) {
        String v = version(c);
        return v == null ? 0 : Archive.treeSize(new File(dir(c), v));
    }

    /** 取り込んだときの記録 (SHA-256・署名・構造の検査の結果) */
    static String info(Context c) {
        String s = read(new File(dir(c), "info.txt"));
        return s == null ? "" : s;
    }

    /** WebView2 に渡す Chromium の起動の設定 (WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS) */
    static String args(Context c) {
        String s = read(new File(Wine.dir(c), "webview2-args"));
        return s == null ? DEFAULT_ARGS : s.trim();
    }

    static void setArgs(Context c, String args) throws IOException {
        // 1 行だけ。引用符や改行など、wine.sh で問題になる文字は入れない
        String clean = args.replaceAll("[\\r\\n\"'`$\\\\;|&<>]", " ").replaceAll("\\s+", " ").trim();
        Wine.dir(c).mkdirs();
        Glibc.writeText(new File(Wine.dir(c), "webview2-args"), clean.isEmpty() ? DEFAULT_ARGS : clean);
    }

    /** ダウンロードのフォルダにある webview2*.zip (候補として見せる。読めなければ空) */
    static List<File> candidates() {
        List<File> out = new ArrayList<>();
        File dl = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
        File[] kids = dl.listFiles();
        if (kids == null) return out;
        for (File k : kids) {
            String n = k.getName().toLowerCase(Locale.ROOT);
            if (k.isFile() && n.startsWith("webview2") && n.endsWith(".zip")) out.add(k);
        }
        return out;
    }

    /**
     * zip を取り込む。log に経過を出し、記録 (見せる文) を返す。確かめで止めるときは IOException。
     * zip は読むだけで、消さない (選んだ元のファイル)
     */
    static String importZip(Context c, File zip, String shownName, Wine.Log log) throws IOException {
        if (!Wine.installed(c)) throw new IOException("先に Wine を入れてください");
        File base = dir(c);
        File work = new File(Wine.dir(c), "webview2.new");
        Archive.deleteTree(work);
        long free = Wine.dir(c).getUsableSpace();
        long need = estimateUnpacked(zip) + 50L * 1024 * 1024;
        log.line("zip: " + shownName + " (" + (zip.length() / 1024 / 1024) + "MB。展開すると約 " + (need / 1024 / 1024) + "MB)");
        if (free > 0 && free < need) {
            throw new IOException("空き容量が足りません (約 " + (need / 1024 / 1024) + "MB 要ります。空きは "
                    + (free / 1024 / 1024) + "MB)");
        }
        StringBuilder rec = new StringBuilder();
        String zipSha = sha256(zip);
        rec.append("取り込んだ zip: ").append(shownName).append("\n  SHA-256 ").append(zipSha).append('\n');
        log.line("zip の SHA-256: " + zipSha);
        try {
            log.line("展開しています (安全な展開: 外を指す場所・リンクは取り出しません)…");
            Archive.Result res = Archive.extract(zip, Archive.Format.ZIP, work, null);
            if (res.skipped > 0) log.line("  外を指すなどの理由で取り出さなかった項目: " + res.skipped + " 個");
            File exe = find(work, "msedgewebview2.exe", 4);
            if (exe == null) throw new IOException("zip の中に msedgewebview2.exe がありません (WebView2 ランタイムのフォルダを zip にしてください)");
            File runtime = exe.getParentFile();
            // CPU
            int machine = Wine.peMachine(exe);
            if (machine == 0x14c) throw new IOException("これは x86 (32bit) 用の WebView2 です。x64 用を取り出してください");
            if (machine == 0xaa64) throw new IOException("これは ARM64 用の WebView2 です。x64 用を取り出してください");
            if (machine != 0x8664) throw new IOException("msedgewebview2.exe が x64 用の Windows のプログラムではありません");
            // 版
            String folderVer = runtime.getName().matches("\\d+(\\.\\d+){3}") ? runtime.getName() : null;
            String peVer = peVersion(exe);
            String ver = peVer != null ? peVer : folderVer;
            if (ver == null) throw new IOException("版が分かりません (フォルダの名前も msedgewebview2.exe の版の情報も読めません)");
            rec.append("版: ").append(ver).append("  (フォルダの名前: ").append(folderVer == null ? "版の形でない" : folderVer)
                    .append(" / msedgewebview2.exe の版の情報: ").append(peVer == null ? "読めない" : peVer).append(")\n");
            if (folderVer != null && peVer != null && !folderVer.equals(peVer)) {
                rec.append("  ⚠ フォルダの名前と、msedgewebview2.exe の版の情報が違います\n");
                log.line("⚠ フォルダの名前 (" + folderVer + ") と msedgewebview2.exe の版 (" + peVer + ") が違います");
            }
            log.line("版: " + ver);
            String exeSha = sha256(exe);
            rec.append("msedgewebview2.exe の SHA-256: ").append(exeSha).append('\n');
            // 署名
            log.line("署名を確かめています (中身の一致・署名の数値・Microsoft のルートまでの連鎖)…");
            List<X509Certificate> roots = roots(c);
            rec.append("\n署名 (Authenticode。端末の中で確かめた。証明書の期限とタイムスタンプは見ていない):\n");
            for (String rel : SIGNED) {
                File f = new File(runtime, rel);
                if (!f.isFile()) {
                    rec.append("・").append(rel).append(": ファイルがありません\n");
                    continue;
                }
                Authenticode.Result a = Authenticode.verify(f, roots);
                rec.append("・").append(rel).append(": ").append(a.summary().replace("\n", "\n    ")).append('\n');
                log.line("  " + rel + ": " + (a.verified() ? "正しい署名 (" + a.signer + ")" : "確かめられない"));
                boolean ms = "Microsoft Corporation".equals(a.signerOrg) || "Microsoft Corporation".equals(a.signer);
                if (rel.equals("msedgewebview2.exe") && (!a.verified() || !ms)) {
                    throw new IOException("msedgewebview2.exe の署名を確かめられませんでした (Microsoft の正しい署名が"
                            + "必要です)。\n" + a.summary());
                }
            }
            // 構造の検査 (止めない。出た項目をそのまま記録する)
            log.line("構造の検査 (PeInfo / ExeScanner) をしています…");
            rec.append("\n構造の検査 (Chromium は大きく、誤判定が出やすい。止めはせず、出た項目を記録する。"
                    + "64MB より大きいファイルは先頭だけ):\n");
            for (String rel : SIGNED) {
                File f = new File(runtime, rel);
                if (!f.isFile()) continue;
                ExeScanner.PeReport pe = ExeScanner.scanPe(f);
                rec.append("・").append(rel).append(": ").append(pe.level.label).append(" (").append(pe.score).append(" 点)\n");
                for (ExeScanner.PeFinding x : pe.findings) {
                    rec.append("    - ").append(x.title).append(" (+").append(x.points).append(")\n");
                }
            }
            // 置く (展開した場所をそのまま移す)
            long size = Archive.treeSize(runtime);
            rec.append("\n大きさ: 約 ").append(size / 1024 / 1024).append("MB (files/wine/webview2/").append(ver).append(")\n");
            removeFiles(c);
            if (!base.isDirectory() && !base.mkdirs()) throw new IOException("保存先を作れません");
            File target = new File(base, ver);
            if (!runtime.renameTo(target)) throw new IOException("保存できません");
            Glibc.writeText(new File(base, "current"), ver);
            Glibc.writeText(new File(base, "info.txt"), rec.toString());
            log.line("取り込みました: WebView2 " + ver + " (約 " + (size / 1024 / 1024) + "MB)");
            return rec.toString();
        } finally {
            Archive.deleteTree(work);
        }
    }

    /** 取り込んだ WebView2 を消す (Windows の環境のリンクとレジストリの印は、次に wine を動かしたときに wine.sh が消す) */
    static void remove(Context c) {
        removeFiles(c);
        Archive.deleteTree(new File(Wine.dir(c), "webview2.new"));
    }

    private static void removeFiles(Context c) {
        Archive.deleteTree(dir(c));
    }

    // ------------------------------------------------------------------ 確かめるための道具

    private static List<X509Certificate> roots(Context c) throws IOException {
        List<X509Certificate> out = new ArrayList<>();
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            for (String[] r : ROOTS) {
                byte[] der;
                try (InputStream in = c.getAssets().open(r[0])) {
                    der = Icons.readAll(in);
                }
                if (!hex(MessageDigest.getInstance("SHA-256").digest(der)).equals(r[1])) {
                    throw new IOException("同梱のルート証明書の指紋が合いません: " + r[0]);
                }
                out.add((X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(der)));
            }
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("ルート証明書を読めません: " + e.getMessage());
        }
        return out;
    }

    /**
     * PE の版の情報 (VS_FIXEDFILEINFO の FileVersion) を「157.0.4341.60」の形で返す。読めなければ null。
     * .rsrc (リソース) の中の印 0xFEEF04BD を探して、その後ろの版を読む
     */
    static String peVersion(File f) {
        PeInfo pe = PeInfo.read(f);
        if (!pe.valid) return null;
        for (PeInfo.Section s : pe.sections) {
            if (!s.name.equals(".rsrc") || s.rawSize <= 0 || s.rawSize > 64L * 1024 * 1024) continue;
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                byte[] b = new byte[(int) s.rawSize];
                raf.seek(s.rawPointer);
                raf.readFully(b);
                for (int i = 0; i + 16 <= b.length; i += 4) {
                    if ((b[i] & 0xff) == 0xbd && (b[i + 1] & 0xff) == 0x04 && (b[i + 2] & 0xff) == 0xef
                            && (b[i + 3] & 0xff) == 0xfe) {
                        long ms = u32(b, i + 8), ls = u32(b, i + 12);
                        return (ms >>> 16) + "." + (ms & 0xffff) + "." + (ls >>> 16) + "." + (ls & 0xffff);
                    }
                }
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    private static long u32(byte[] b, int i) {
        return (b[i] & 0xffL) | (b[i + 1] & 0xffL) << 8 | (b[i + 2] & 0xffL) << 16 | (b[i + 3] & 0xffL) << 24;
    }

    /** zip の中身を展開したときの大きさの目安 */
    private static long estimateUnpacked(File zip) {
        long total = 0;
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(zip)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> es = z.entries();
            while (es.hasMoreElements()) {
                long s = es.nextElement().getSize();
                if (s > 0) total += s;
            }
        } catch (IOException e) {
            return zip.length() * 3;
        }
        return total;
    }

    private static File find(File dir, String name, int depth) {
        File[] kids = dir.listFiles();
        if (kids == null) return null;
        for (File k : kids) if (k.isFile() && k.getName().equalsIgnoreCase(name)) return k;
        if (depth <= 0) return null;
        for (File k : kids) {
            if (k.isDirectory()) {
                File f = find(k, name, depth - 1);
                if (f != null) return f;
            }
        }
        return null;
    }

    static String sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e.getMessage());
        }
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static String read(File f) {
        if (!f.isFile() || f.length() > 1 << 20) return null;
        try (InputStream in = new FileInputStream(f)) {
            return new String(Icons.readAll(in), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }
}
