package site.springcat.bionicrunner;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * スキャン (クイック・フル・カスタム)。アプリ全体で 1 つだけ動き、画面を閉じても続く。
 *
 * 64bit の ARM 端末で定義があれば、ClamAV のエンジン (clamscan、定義のすべて = 約 360 万件) で調べる。
 * あわせて、実行ファイルに書かれた危険な操作 ({@link ExeScanner}) と、追加した YARA ルールも調べる。
 * エンジンが使えない端末や、メモリ不足でエンジンが止められたときは、アプリ内蔵の検査
 * ({@link AvScanner}、ハッシュ値の定義との照合など) で調べる。
 */
final class AvScan {

    enum Type {
        QUICK("クイックスキャン"), FULL("フルスキャン"), CUSTOM("カスタムスキャン");
        final String label;

        Type(String label) {
            this.label = label;
        }
    }

    static final class Result {
        final String path, name, engine;
        final int level;
        /** インストール済みのアプリなら、そのパッケージ名 (隔離ではなくアンインストールで消す) */
        final String packageName;
        /** 処置済み (隔離・削除・許可) */
        String handled;

        Result(String path, int level, String name, String engine, String packageName) {
            this.path = path;
            this.level = level;
            this.name = name;
            this.engine = engine;
            this.packageName = packageName;
        }
    }

    // ------------------------------------------------------------------ 状態 (画面から読む)

    static volatile boolean running;
    static volatile boolean cancel;
    static volatile String phase = "";
    static volatile String current = "";
    static volatile int total, done;
    static volatile Type type;
    static volatile long started, finished;
    static volatile String summary = "";
    static final List<Result> results = new ArrayList<>();
    private static volatile Process engineProcess;

    private AvScan() {
    }

    /** スキャンを始める。もう動いていれば false */
    static synchronized boolean start(Context c, Type t, List<File> customRoots) {
        if (running) return false;
        final Context app = c.getApplicationContext();
        running = true;
        cancel = false;
        type = t;
        total = done = 0;
        phase = "準備しています…";
        current = "";
        summary = "";
        started = System.currentTimeMillis();
        synchronized (results) {
            results.clear();
        }
        new Thread(() -> {
            try {
                run(app, t, customRoots);
            } catch (IOException | RuntimeException e) {
                summary = "スキャンできませんでした: " + (e.getMessage() == null ? e.toString() : e.getMessage());
            }
            finished = System.currentTimeMillis();
            int threats = count(AvScanner.THREAT), sus = count(AvScanner.SUSPICIOUS);
            if (!cancel && summary.isEmpty()) {
                Prefs.setAvLastScan(app, t.label + "\t" + finished + "\t" + done + "\t" + (threats + sus));
            }
            running = false;
        }, "av-scan").start();
        return true;
    }

    static void stop() {
        cancel = true;
        Process p = engineProcess;
        if (p != null) p.destroy();
    }

    static int count(int level) {
        int n = 0;
        synchronized (results) {
            for (Result r : results) if (r.level == level) n++;
        }
        return n;
    }

    static List<Result> results() {
        synchronized (results) {
            return new ArrayList<>(results);
        }
    }

    private static void add(Context c, Result r) {
        synchronized (results) {
            for (Result x : results) {
                if (x.path.equals(r.path)) return; // 同じファイルは 1 回だけ (より確かなものが先に入る)
            }
            results.add(r);
        }
        AvScanner.history(c, r.level == AvScanner.THREAT ? "検出 (脅威)" : "検出 (疑わしい)", r.path, r.name);
    }

    // ------------------------------------------------------------------ 対象

    /** クイックスキャンで見る場所: ダウンロードしたものが置かれやすい場所と、このアプリに登録したもの */
    static List<File> quickRoots(Context c) {
        File sd = Environment.getExternalStorageDirectory();
        List<File> roots = new ArrayList<>();
        for (String d : new String[]{"Download", "Documents", "Bluetooth", "Android/media"}) {
            roots.add(new File(sd, d));
        }
        roots.add(new File(c.getFilesDir(), "bin"));
        roots.add(new File(c.getFilesDir(), "pkg"));
        roots.add(new File(c.getFilesDir(), "home"));
        return roots;
    }

    /** フルスキャンで見る場所: 内部ストレージ全体と、このアプリの作業領域 */
    static List<File> fullRoots(Context c) {
        List<File> roots = new ArrayList<>();
        roots.add(Environment.getExternalStorageDirectory());
        roots.add(c.getFilesDir());
        return roots;
    }

    /** 調べない場所: 隔離したもの・定義そのもの・公式の配布物から作った Linux 環境や仮想マシン */
    private static boolean excluded(Context c, File dir) {
        String p = dir.getAbsolutePath();
        String files = c.getFilesDir().getAbsolutePath();
        return p.equals(AvDefs.dir(c).getAbsolutePath())
                || p.equals(files + "/linux") || p.startsWith(files + "/vm")
                || p.equals(files + "/qemu") || p.equals(files + "/clamav") || p.equals(files + "/glibc");
    }

    private static void walk(Context c, File dir, List<File> out, int depth) {
        if (cancel || depth > 40 || excluded(c, dir) || Archive.isSymlink(dir)) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (cancel) return;
            if (Archive.isSymlink(k)) continue;
            if (k.isDirectory()) {
                walk(c, k, out, depth + 1);
            } else if (k.isFile()) {
                out.add(k);
                if (out.size() % 500 == 0) {
                    total = out.size();
                    current = k.getAbsolutePath();
                }
            }
        }
    }

    // ------------------------------------------------------------------ 本体

    private static void run(Context c, Type t, List<File> customRoots) throws IOException {
        List<File> roots = t == Type.QUICK ? quickRoots(c) : t == Type.FULL ? fullRoots(c) : customRoots;
        phase = "ファイルを数えています…";
        List<File> files = new ArrayList<>();
        for (File r : roots) {
            if (r.isFile()) files.add(r);
            else if (r.isDirectory()) walk(c, r, files, 0);
        }
        // フルスキャンでは、インストール済みのアプリ (利用者が入れたもの) の APK も調べる
        Map<String, String> apkOwner = new HashMap<>();
        if (t == Type.FULL) {
            for (ApplicationInfo ai : c.getPackageManager().getInstalledApplications(0)) {
                if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 || ai.sourceDir == null) continue;
                if (ai.packageName.equals(c.getPackageName())) continue;
                File apk = new File(ai.sourceDir);
                if (apk.canRead()) {
                    files.add(apk);
                    apkOwner.put(apk.getAbsolutePath(), ai.packageName);
                }
            }
        }
        if (cancel) {
            summary = "中止しました";
            return;
        }
        total = files.size();
        File list = new File(c.getCacheDir(), "av-scan-list.txt");
        try (Writer w = new FileWriter(list)) {
            for (File f : files) w.write(f.getAbsolutePath() + "\n");
        }
        String note = "";
        // 1. 実行ファイルに書かれた危険な操作 (疑わしい)
        phase = "実行ファイルを調べています…";
        for (int i = 0; i < files.size() && !cancel; i++) {
            File f = files.get(i);
            if (f.length() > 64L * 1024 * 1024 || apkOwner.containsKey(f.getAbsolutePath())) continue;
            if (!AvScanner.executableLike(f)) continue;
            current = f.getAbsolutePath();
            try {
                for (ExeScanner.Finding x : ExeScanner.scan(f).findings) {
                    if (x.title.contains("EICAR")) continue; // 脅威として下で見つける
                    add(c, new Result(f.getAbsolutePath(), AvScanner.SUSPICIOUS, x.title, "危険な操作の記述", null));
                    break;
                }
            } catch (IOException ignored) {
                // 読めない
            }
        }
        // 2. ウイルスの定義 (ClamAV のエンジン。使えなければ内蔵の照合)
        done = 0;
        boolean engineOk = false;
        if (!cancel && AvEngine.available(c) && AvDefs.ready(c)) {
            phase = "ClamAV のエンジンで調べています (定義の読み込みに 1 分ほどかかります)…";
            String err = runEngine(c, list, apkOwner);
            if (err == null) {
                engineOk = true;
            } else if (!cancel) {
                note = "。ClamAV のエンジンが使えなかったため、内蔵の検査で調べました (" + err + ")";
            }
        }
        if (!engineOk && !cancel) {
            phase = AvDefs.ready(c) ? "定義と照合しています…" : "調べています (定義がありません。「定義の更新」を押してください)…";
            done = 0;
            for (File f : files) {
                if (cancel) break;
                current = f.getAbsolutePath();
                AvScanner.Verdict v = AvScanner.scan(c, f);
                done++;
                if (v != null && v.level != AvScanner.CLEAN) {
                    add(c, new Result(f.getAbsolutePath(), v.level, v.name, v.engine, apkOwner.get(f.getAbsolutePath())));
                }
            }
        } else if (engineOk && !cancel) {
            // 3. YARA ルール (エンジンを使ったときは別に。内蔵の検査は YARA も含む)
            for (File rule : Yara.rules(c)) {
                if (cancel) break;
                phase = "YARA ルールで調べています (" + rule.getName() + ")…";
                try {
                    for (Yara.Match m : Yara.scanList(c, rule, list)) {
                        add(c, new Result(m.path, AvScanner.THREAT, "YARA:" + m.rule,
                                "YARA ルール (" + rule.getName() + ")", apkOwner.get(m.path)));
                    }
                } catch (IOException ignored) {
                    // そのルールでは調べられなかった
                }
            }
        }
        list.delete();
        int threats = count(AvScanner.THREAT), sus = count(AvScanner.SUSPICIOUS);
        if (cancel) {
            summary = "中止しました (" + done + " 個まで調べました)";
        } else {
            summary = files.size() + " 個のファイルを調べました。"
                    + (threats + sus == 0 ? "脅威は見つかりませんでした" : "脅威 " + threats + " 件、疑わしいもの " + sus + " 件")
                    + note;
        }
    }

    /** clamscan で list のファイルを調べる。成功なら null、だめなら理由 */
    private static String runEngine(Context c, File list, Map<String, String> apkOwner) {
        try {
            Process p = AvEngine.start(c, "--file-list=" + list.getAbsolutePath());
            engineProcess = p;
            // エラー出力 (警告) は読み捨てつつ、メモリ不足などの印だけ覚える
            final StringBuilder err = new StringBuilder();
            Thread errReader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.contains("rror") && err.length() < 2000) err.append(line).append('\n');
                    }
                } catch (IOException ignored) {
                    // 終了
                }
            }, "clamscan-err");
            errReader.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                boolean first = true;
                while ((line = r.readLine()) != null) {
                    if (first) {
                        first = false;
                        phase = "ClamAV のエンジンで調べています…";
                    }
                    int colon = line.lastIndexOf(": ");
                    if (colon < 0) continue;
                    String path = line.substring(0, colon), what = line.substring(colon + 2);
                    done++;
                    current = path;
                    if (what.endsWith(" FOUND")) {
                        String name = what.substring(0, what.length() - 6);
                        add(c, new Result(path, AvScanner.THREAT, name, "ClamAV", apkOwner.get(path)));
                    }
                }
            }
            int code = p.waitFor();
            errReader.join(2000);
            engineProcess = null;
            if (cancel) return "中止";
            // clamscan の終了コード: 0 = 何も無し、1 = 見つかった、2 = エラー (一部のファイルが読めないときも)
            if (code == 0 || code == 1) return null;
            if (code == 2 && done > 0) return null;
            String e = err.toString();
            if (code > 128 || e.contains("memory") || e.contains("alloc")) {
                return "メモリが足りずに止められました";
            }
            return e.isEmpty() ? "終了コード " + code : e.trim().split("\n")[0];
        } catch (IOException e) {
            return e.getMessage();
        } catch (InterruptedException e) {
            return "中断";
        } finally {
            engineProcess = null;
        }
    }

    /** 見つかったものを処置済みにする */
    static void markHandled(Result r, String how) {
        synchronized (results) {
            r.handled = how;
        }
    }

    /** 表示用: 処置していない脅威 (THREAT) の数 */
    static int unhandledThreats() {
        int n = 0;
        synchronized (results) {
            for (Result r : results) if (r.handled == null && r.level == AvScanner.THREAT) n++;
        }
        return n;
    }
}
