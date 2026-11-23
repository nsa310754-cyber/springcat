package site.springcat.bionicrunner;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * ファイル 1 つの検査 (リアルタイム保護と、エンジンが無い端末でのスキャン)・隔離・保護の履歴。
 *
 * 検査の中身:
 *   - ウイルスの定義 (ClamAV) のうち「ファイル全体のハッシュ値」の定義との照合 ({@link AvDefs#lookup})
 *   - ウイルス検査用のテスト文字列 (EICAR)
 *   - 利用者が追加した YARA ルール
 *   - 実行ファイル・スクリプトに書かれた危険な操作 ({@link ExeScanner})。これは「疑わしい」止まり
 *     (正当な管理ツールでも当てはまることがあるため、自動では隔離しない)
 */
final class AvScanner {

    /** 脅威 (自動で隔離してよい確かなもの) / 疑わしい (利用者に判断してもらう) */
    static final int THREAT = 2, SUSPICIOUS = 1, CLEAN = 0;

    static final class Verdict {
        final int level;
        final String name;
        final String engine;

        Verdict(int level, String name, String engine) {
            this.level = level;
            this.name = name;
            this.engine = engine;
        }
    }

    /** これより大きいファイルはハッシュ値を計算しない (ウイルスの定義にもまず無い) */
    private static final long MAX_HASH = 200L * 1024 * 1024;

    private AvScanner() {
    }

    /** 実行できる形のファイル (ELF・#! で始まるスクリプト・スクリプトの拡張子) か */
    static boolean executableLike(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        // Linux / Android のスクリプトとプログラムに加えて、Windows のスクリプト (bat・PowerShell・VBScript・WSF・HTA) も
        if (n.matches(".*\\.(sh|bash|py|pl|rb|lua|js|ts|php|bin|elf|run|so|bat|cmd|ps1|psm1|vbs|vbe|wsf|hta|jse)$")) {
            return true;
        }
        try (InputStream in = new FileInputStream(f)) {
            byte[] h = new byte[4];
            int got = AvDefs.readFully(in, h);
            if (got >= 4 && h[0] == 0x7f && h[1] == 'E' && h[2] == 'L' && h[3] == 'F') return true;
            if (got >= 2 && h[0] == 'M' && h[1] == 'Z') return true; // Windows のプログラム (exe・dll。拡張子を変えたものも)
            return got >= 2 && h[0] == '#' && h[1] == '!';
        } catch (IOException e) {
            return false;
        }
    }

    /** f を検査する (クリーンなら level が CLEAN)。読めないときは null */
    static Verdict scan(Context c, File f) {
        if (!f.isFile() || !f.canRead()) return null;
        long size = f.length();
        try {
            if (size <= MAX_HASH && size > 0) {
                byte[][] h = AvDefs.hashes(f);
                if (Prefs.avAllowed(c, AvDefs.hex(h[2]))) return new Verdict(CLEAN, "", "");
                String hit = AvDefs.lookup(c, h, size);
                if (hit != null) return new Verdict(THREAT, hit, "ウイルスの定義 (ClamAV)");
            }
            boolean exe = executableLike(f);
            // EICAR (検査用のテスト文字列) は、ファイルの頭にあるもの
            if (size <= 4096 && isEicar(f)) return new Verdict(THREAT, "Eicar-Test-Signature", "テスト文字列");
            for (File rule : Yara.rules(c)) {
                try {
                    List<Yara.Match> m = Yara.scan(c, rule, f);
                    if (!m.isEmpty()) {
                        return new Verdict(THREAT, "YARA:" + m.get(0).rule, "YARA ルール (" + rule.getName() + ")");
                    }
                } catch (IOException ignored) {
                    // そのルールでは調べられなかった
                }
            }
            // zip (APK を含む) の中の EICAR (検査用のテスト文字列。eicar_com.zip など)
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (isZip(f)) {
                String inner = eicarInZip(f);
                if (inner != null) return new Verdict(THREAT, "Eicar-Test-Signature", "テスト文字列 (" + inner + " の中)");
            }
            if (exe) {
                ExeScanner.Report r = ExeScanner.scan(f);
                for (ExeScanner.Finding x : r.findings) {
                    if (x.title.contains("EICAR")) continue;
                    return new Verdict(SUSPICIOUS, x.title, "危険な操作の記述");
                }
                // Windows のプログラム: 構造の検査 (中身を隠して読み込む作りなど)。「危険」のときだけ
                if (isPe(f)) {
                    ExeScanner.PeReport pe = ExeScanner.scanPe(f);
                    if (pe.level == ExeScanner.PeLevel.DANGER) {
                        String why = pe.findings.isEmpty() ? "" : " (" + pe.findings.get(0).title + " など)";
                        return new Verdict(SUSPICIOUS, "構造の検査: 危険 " + pe.score + " 点" + why, "Windows のプログラムの構造の検査");
                    }
                }
                // Windows Script File: スクリプトの危険な使い方 (ネットから取ってきて保存・起動など)
                if (n.endsWith(".wsf")) {
                    try {
                        List<String[]> d = WsfFile.read(f).dangers();
                        if (!d.isEmpty()) return new Verdict(SUSPICIOUS, "スクリプト: " + d.get(0)[0], "Windows Script File の検査");
                    } catch (IOException ignored) {
                        // WSF として読めなければ省く
                    }
                }
            }
            // Android のアプリ (APK): 中の dex・.so・スクリプトを取り出して調べる
            if (n.endsWith(".apk") && isZip(f)) {
                // このアプリ自身の、同じ署名の更新は、中に検査の見本 (危険な操作の記述) を持っているので調べない
                ApkCheck.Info info = ApkCheck.inspect(c, f);
                if (info.sameSignerInstalled && c.getPackageName().equals(info.packageName)) {
                    return new Verdict(CLEAN, "", "");
                }
                File work = new File(c.getCacheDir(), "avapk-" + System.nanoTime());
                try {
                    ExeScanner.Report r = ApkCheck.scan(c, f, work);
                    for (ExeScanner.Finding x : r.findings) {
                        if (x.title.startsWith("ウイルスの定義") || x.title.startsWith("YARA")) {
                            return new Verdict(THREAT, x.title, "APK の中身の検査");
                        }
                        return new Verdict(SUSPICIOUS, x.title, "APK の中身の検査 (" + x.evidence + ")");
                    }
                } finally {
                    Archive.deleteTree(work);
                }
            }
        } catch (IOException e) {
            return null;
        }
        return new Verdict(CLEAN, "", "");
    }

    private static boolean isZip(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] h = new byte[4];
            return AvDefs.readFully(in, h) == 4 && h[0] == 'P' && h[1] == 'K' && h[2] == 3 && h[3] == 4;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isPe(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] h = new byte[2];
            return AvDefs.readFully(in, h) == 2 && h[0] == 'M' && h[1] == 'Z';
        } catch (IOException e) {
            return false;
        }
    }

    /** zip の中に、EICAR で始まる小さなファイルがあればその名前 (中のフォルダや zip は 1 段だけ見る) */
    private static String eicarInZip(File f) {
        if (f.length() > 64L * 1024 * 1024) return null;
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(f)) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> es = z.entries();
            int count = 0;
            while (es.hasMoreElements() && count++ < 2000) {
                java.util.zip.ZipEntry e = es.nextElement();
                if (e.isDirectory() || e.getSize() > 4096) continue;
                try (InputStream in = z.getInputStream(e)) {
                    byte[] b = new byte[4096];
                    int n = AvDefs.readFully(in, b);
                    if (new String(b, 0, n, StandardCharsets.ISO_8859_1)
                            .startsWith("X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR")) {
                        return e.getName();
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // zip として読めない
        }
        return null;
    }

    private static boolean isEicar(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 4096)];
            int n = AvDefs.readFully(in, b);
            return new String(b, 0, n, StandardCharsets.ISO_8859_1).startsWith("X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR");
        }
    }

    // ------------------------------------------------------------------ 隔離

    static File quarantineDir(Context c) {
        return new File(AvDefs.dir(c), "quarantine");
    }

    static final class Quarantined {
        String id, path, threat;
        long time, size;
    }

    /**
     * f を隔離する: 中身を変換して (そのままでは実行も検出もされない形にして) アプリの中へ移し、
     * 元のファイルを消す。戻り値は隔離の番号
     */
    static String quarantine(Context c, File f, String threat) throws IOException {
        File q = quarantineDir(c);
        if (!q.isDirectory() && !q.mkdirs()) throw new IOException("隔離場所を作れません");
        String id = Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 1296), 36);
        File data = new File(q, id + ".bin");
        try (InputStream in = new FileInputStream(f); OutputStream os = new FileOutputStream(data)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                for (int i = 0; i < n; i++) buf[i] ^= (byte) 0xA5;
                os.write(buf, 0, n);
            }
        }
        long size = f.length();
        if (!f.delete()) {
            data.delete();
            throw new IOException("元のファイルを消せません (読み取り専用の場所かもしれません)");
        }
        Glibc.writeText(new File(q, id + ".info"), f.getAbsolutePath() + "\n" + threat + "\n"
                + System.currentTimeMillis() + "\n" + size + "\n");
        history(c, "隔離", f.getAbsolutePath(), threat);
        return id;
    }

    static List<Quarantined> quarantined(Context c) {
        List<Quarantined> out = new ArrayList<>();
        File[] files = quarantineDir(c).listFiles((d, n) -> n.endsWith(".info"));
        if (files == null) return out;
        for (File info : files) {
            try (BufferedReader r = new BufferedReader(new FileReader(info))) {
                Quarantined q = new Quarantined();
                q.id = info.getName().replace(".info", "");
                q.path = r.readLine();
                q.threat = r.readLine();
                q.time = Long.parseLong(r.readLine());
                q.size = Long.parseLong(r.readLine());
                out.add(q);
            } catch (IOException | RuntimeException ignored) {
                // 壊れた記録は飛ばす
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.time, a.time));
        return out;
    }

    /** 隔離したものを元の場所へ戻す (同じ名前があれば別の名前で) */
    static File restore(Context c, Quarantined q) throws IOException {
        File data = new File(quarantineDir(c), q.id + ".bin");
        File dst = new File(q.path);
        if (dst.exists()) dst = new File(dst.getParentFile(), "復元-" + dst.getName());
        File parent = dst.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("元の場所を作れません");
        try (InputStream in = new FileInputStream(data); OutputStream os = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                for (int i = 0; i < n; i++) buf[i] ^= (byte) 0xA5;
                os.write(buf, 0, n);
            }
        }
        // 戻したものはリアルタイム保護で再び隔離しないよう「許可」に入れる
        Prefs.avAllow(c, AvDefs.hex(AvDefs.hashes(dst)[2]));
        deleteQuarantined(c, q, false);
        history(c, "復元 (許可)", dst.getAbsolutePath(), q.threat);
        return dst;
    }

    static void deleteQuarantined(Context c, Quarantined q, boolean log) {
        new File(quarantineDir(c), q.id + ".bin").delete();
        new File(quarantineDir(c), q.id + ".info").delete();
        if (log) history(c, "削除", q.path, q.threat);
    }

    // ------------------------------------------------------------------ 保護の履歴

    private static File historyFile(Context c) {
        return new File(AvDefs.dir(c), "history.txt");
    }

    static synchronized void history(Context c, String action, String path, String threat) {
        File f = historyFile(c);
        f.getParentFile().mkdirs();
        String line = System.currentTimeMillis() + "\t" + action + "\t" + path.replace('\t', ' ').replace('\n', ' ')
                + "\t" + threat.replace('\t', ' ').replace('\n', ' ') + "\n";
        try (FileWriter w = new FileWriter(f, true)) {
            w.write(line);
        } catch (IOException ignored) {
            // 記録できなくても検査は続ける
        }
        // 大きくなりすぎたら古いものを捨てる
        if (f.length() > 256 * 1024) {
            List<String[]> all = history(c, 500);
            Collections.reverse(all);
            StringBuilder sb = new StringBuilder();
            for (String[] h : all) sb.append(String.join("\t", h)).append('\n');
            try {
                Glibc.writeText(f, sb.toString());
            } catch (IOException ignored) {
                // そのまま
            }
        }
    }

    /** 新しい順に最大 max 件。各項目は {時刻, 処置, 場所, 脅威} */
    static synchronized List<String[]> history(Context c, int max) {
        List<String[]> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(historyFile(c)))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t");
                if (p.length == 4) out.add(p);
            }
        } catch (IOException ignored) {
            // まだ無い
        }
        Collections.reverse(out);
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }
}
