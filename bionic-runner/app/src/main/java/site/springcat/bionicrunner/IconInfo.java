package site.springcat.bionicrunner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ホーム画面のアイコンを長押ししたときの「アプリ情報」。
 * 種類 (APK・AAB・PWA・ショートカット・bionic・glibc・スクリプト・圧縮ファイルなど)・セーブデータ・
 * アンインストール・その他の操作をまとめて出す。
 */
final class IconInfo {

    /** 何の情報か */
    static final class Target {
        String title;
        Drawable icon;
        /** Android アプリ (ショートカットなら、それを作ったアプリ) */
        String packageName;
        /** ショートカットなら真 */
        boolean shortcut;
        /** 登録したプログラムなら、その bin のファイル */
        File program;
        /** 組み込みの機能なら真 */
        boolean builtin;
    }

    /** ホーム画面側にしてもらう操作 (使わないものは null) */
    static final class Actions {
        Runnable open, openWithArgs, uninstall, changeIcon, resetIcon, unpin;
    }

    private final Activity a;
    private final Target t;
    private final Actions act;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private AlertDialog dialog;
    private LinearLayout body;
    private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy/M/d HH:mm", Locale.JAPAN);

    private IconInfo(Activity a, Target t, Actions act) {
        this.a = a;
        this.t = t;
        this.act = act;
    }

    static void show(Activity a, Target t, Actions act) {
        new IconInfo(a, t, act).build();
    }

    private int dp(float v) {
        return GameKeys.dp(a, v);
    }

    // ------------------------------------------------------------------ 部品

    private TextView text(String s, float size, int color, boolean bold) {
        TextView v = new TextView(a);
        v.setText(s);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(0, dp(2), 0, dp(2));
        v.setTextIsSelectable(true);
        return v;
    }

    private void section(String title) {
        TextView h = text(title, 15, 0xFF93C5FD, true);
        h.setPadding(0, dp(14), 0, dp(4));
        body.addView(h);
    }

    private Button button(String label, int color, View.OnClickListener l) {
        Button b = GameKeys.button(a, label);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(8));
        b.setBackground(bg);
        b.setTextSize(14);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        lp.topMargin = dp(4);
        b.setLayoutParams(lp);
        return b;
    }

    private void addButton(String label, int color, Runnable r) {
        body.addView(button(label, color, v -> r.run()));
    }

    /** ダイアログを閉じてから行う操作 */
    private void addClosing(String label, int color, Runnable r) {
        addButton(label, color, () -> {
            dialog.dismiss();
            r.run();
        });
    }

    private void toast(String s) {
        Toast.makeText(a, s, Toast.LENGTH_LONG).show();
    }

    private static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024);
        return String.format(Locale.ROOT, "%.2f GB", bytes / 1024.0 / 1024 / 1024);
    }

    private void bg(Runnable r) {
        new Thread(r, "icon-info").start();
    }

    private void post(Runnable r) {
        ui.post(() -> {
            if (!a.isFinishing()) r.run();
        });
    }

    // ------------------------------------------------------------------ 本体

    private void build() {
        ScrollView sv = new ScrollView(a);
        body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(14), dp(18), dp(8));
        sv.addView(body);

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        if (t.icon != null) {
            ImageView iv = new ImageView(a);
            iv.setImageDrawable(t.icon);
            head.addView(iv, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }
        LinearLayout names = new LinearLayout(a);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(12), 0, 0, 0);
        names.addView(text(t.title, 18, 0xFFFFFFFF, true));
        names.addView(text(typeLabel(), 13, 0xFFFDE68A, true));
        head.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        body.addView(head);

        if (t.program != null) programDetails();
        else if (t.packageName != null) appDetails();
        else body.addView(text("Bionic Runner に最初から入っている機能です", 13, 0xFFCBD5E1, false));

        section("セーブデータ");
        if (t.program != null) programData();
        else if (t.shortcut) body.addView(text("ショートカットそのものにデータはありません。データは元のアプリ ("
                + t.packageName + ") の中にあります。", 13, 0xFFCBD5E1, false));
        else if (t.packageName != null) appData();
        else body.addView(text("ありません", 13, 0xFFCBD5E1, false));

        section("アンインストール");
        uninstall();

        section("その他");
        others();

        GradientDrawable panel = new GradientDrawable();
        panel.setColor(0xFF1E293B);
        sv.setBackground(panel);
        dialog = new AlertDialog.Builder(a)
                .setView(sv)
                .setPositiveButton("閉じる", null)
                .show();
    }

    private String typeLabel() {
        if (t.builtin) return "種類: 組み込みの機能";
        if (t.shortcut) return "種類: ホーム画面のショートカット (Chrome の「ホーム画面に追加」など)";
        if (t.program != null) return "種類: " + ProgramKind.of(a, t.program).label();
        try {
            return "種類: " + ProgramKind.appType(a.getPackageManager().getApplicationInfo(t.packageName, 0));
        } catch (PackageManager.NameNotFoundException e) {
            return "種類: Android アプリ";
        }
    }

    // ------------------------------------------------------------------ 種類の詳しい情報

    private void programDetails() {
        ProgramKind.Info k = ProgramKind.of(a, t.program);
        List<String> lines = new ArrayList<>();
        lines.add("登録名: " + t.program.getName());
        lines.add("本体: " + k.real.getAbsolutePath());
        lines.add("大きさ: " + size(k.real.length()));
        if (k.type == ProgramKind.Type.ARCHIVE) {
            File pkg = Archive.pkgDir(a.getFilesDir(), t.program.getName());
            lines.add("展開した一式: " + pkg.getAbsolutePath() + " (" + size(Archive.treeSize(pkg)) + ")");
        }
        try {
            ElfInfo e = ElfInfo.read(k.real);
            if (e.abi() != null) lines.add("CPU: " + e.abi());
            if (e.interpreter != null) lines.add("ローダ: " + e.interpreter);
        } catch (IOException ignored) {
            // ELF ではない
        }
        lines.add("登録日時: " + fmt.format(new Date(t.program.lastModified())));
        body.addView(text(String.join("\n", lines), 13, 0xFFE2E8F0, false));
    }

    private void appDetails() {
        PackageManager pm = a.getPackageManager();
        List<String> lines = new ArrayList<>();
        lines.add("パッケージ名: " + t.packageName);
        try {
            PackageInfo pi = pm.getPackageInfo(t.packageName, 0);
            ApplicationInfo ai = pi.applicationInfo;
            lines.add("版: " + pi.versionName + " (" + (android.os.Build.VERSION.SDK_INT >= 28
                    ? pi.getLongVersionCode() : pi.versionCode) + ")");
            long apk = new File(ai.sourceDir).length();
            if (ai.splitSourceDirs != null) for (String s : ai.splitSourceDirs) apk += new File(s).length();
            lines.add("アプリの大きさ (APK): " + size(apk));
            lines.add("対象の Android: API " + ai.targetSdkVersion);
            lines.add("インストール: " + fmt.format(new Date(pi.firstInstallTime))
                    + "  更新: " + fmt.format(new Date(pi.lastUpdateTime)));
            String installer = null;
            try {
                installer = android.os.Build.VERSION.SDK_INT >= 30
                        ? pm.getInstallSourceInfo(t.packageName).getInstallingPackageName()
                        : pm.getInstallerPackageName(t.packageName);
            } catch (Exception ignored) {
                // 分からない
            }
            lines.add("入手元: " + installerName(installer));
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) lines.add("システムアプリ (端末に最初から入っている)");
        } catch (PackageManager.NameNotFoundException e) {
            lines.add("このアプリは見つかりません (アンインストールされた可能性があります)");
        }
        body.addView(text(String.join("\n", lines), 13, 0xFFE2E8F0, false));
    }

    private String installerName(String pkg) {
        if (pkg == null) return "不明 (APK を直接入れたなど)";
        switch (pkg) {
            case "com.android.vending": return "Google Play ストア";
            case "com.android.chrome": return "Chrome (PWA)";
            case "com.google.android.packageinstaller":
            case "com.android.packageinstaller": return "パッケージインストーラ (APK を直接入れた)";
            default: break;
        }
        if (pkg.equals(a.getPackageName())) return "Bionic Runner";
        return pkg;
    }

    // ------------------------------------------------------------------ セーブデータ

    /** 登録したプログラムがデータを置きそうな場所 (Linux のプログラムの慣習) */
    private List<File> programDataDirs() {
        String name = t.program.getName();
        File home = new File(a.getFilesDir(), "home");
        List<File> out = new ArrayList<>();
        for (File f : new File[]{new File(home, "." + name), new File(home, ".config/" + name),
                new File(home, ".local/share/" + name), new File(home, ".cache/" + name)}) {
            if (f.exists()) out.add(f);
        }
        return out;
    }

    private void programData() {
        final List<File> dirs = programDataDirs();
        final TextView info = text("調べています…", 13, 0xFFCBD5E1, false);
        body.addView(info);
        bg(() -> {
            final StringBuilder sb = new StringBuilder();
            long total = 0;
            for (File d : dirs) {
                long s = Archive.treeSize(d);
                total += s;
                sb.append(d.getAbsolutePath()).append(" (").append(size(s)).append(")\n");
            }
            final long sum = total;
            post(() -> info.setText(dirs.isEmpty()
                    ? "このプログラム専用の保存場所 (~/.名前・~/.config/名前 など) は見つかりませんでした。"
                    + "作業フォルダ (HOME: " + new File(a.getFilesDir(), "home") + ") に保存している可能性があります。"
                    : "合計 " + size(sum) + "\n" + sb.toString().trim()));
        });
        File home = new File(a.getFilesDir(), "home");
        final File openAt = dirs.isEmpty() ? home : (dirs.get(0).isDirectory() ? dirs.get(0) : home);
        addClosing("保存場所をエクスプローラーで開く", 0xFF374151, () -> a.startActivity(
                new Intent(a, FilesActivity.class).putExtra(FilesActivity.EXTRA_DIR, openAt.getAbsolutePath())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        if (dirs.isEmpty()) return;
        addButton("バックアップ (内部ストレージの Download へ写す)", 0xFF1D4ED8, () -> bg(() -> {
            File dst = new File(backupDir(), t.program.getName() + "-" + stamp());
            String msg;
            try {
                for (File d : dirs) copyTree(d, new File(dst, d.getAbsolutePath()
                        .substring(home.getAbsolutePath().length() + 1).replace('/', '_')));
                msg = "バックアップしました: " + dst;
            } catch (IOException e) {
                msg = "バックアップできませんでした: " + e.getMessage();
            }
            final String m = msg;
            post(() -> toast(m));
        }));
        addButton("セーブデータを消去", 0xFF991B1B, () -> new AlertDialog.Builder(a)
                .setTitle("セーブデータを消去")
                .setMessage(t.program.getName() + " の保存場所 (上の " + dirs.size() + " か所) を消します。元に戻せません。")
                .setPositiveButton("消去", (d, w) -> {
                    for (File f : dirs) Archive.deleteTree(f);
                    toast("消去しました");
                    dialog.dismiss();
                })
                .setNegativeButton("キャンセル", null)
                .show());
    }

    private void appData() {
        final String pkg = t.packageName;
        String dataDir;
        try {
            dataDir = a.getPackageManager().getApplicationInfo(pkg, 0).dataDir;
        } catch (PackageManager.NameNotFoundException e) {
            body.addView(text("アプリが見つかりません", 13, 0xFFCBD5E1, false));
            return;
        }
        final String data = dataDir;
        final String ext = Environment.getExternalStorageDirectory() + "/Android/data/" + pkg;
        final TextView info = text("大きさを調べています (root を使います)…", 13, 0xFFCBD5E1, false);
        body.addView(info);
        bg(() -> {
            String out = rootOutput("du -sk " + RootSession.sq(data) + " " + RootSession.sq(ext) + " 2>/dev/null");
            final String msg;
            if (out == null) {
                msg = "保存場所: " + data + "\nroot が使えないため、大きさは Android の設定 (下の「その他」→ Android の"
                        + "アプリ情報 → ストレージ) で確認してください。";
            } else {
                StringBuilder sb = new StringBuilder();
                for (String line : out.split("\n")) {
                    String[] p = line.trim().split("\\s+", 2);
                    if (p.length == 2 && p[0].matches("\\d+")) {
                        sb.append(p[1]).append(" (").append(size(Long.parseLong(p[0]) * 1024)).append(")\n");
                    }
                }
                msg = sb.length() == 0 ? "保存場所: " + data : sb.toString().trim();
            }
            post(() -> info.setText(msg));
        });
        addButton("バックアップ (内部ストレージの Download へ。root)", 0xFF1D4ED8, () -> bg(() -> {
            File dst = new File(backupDir(), pkg + "-" + stamp() + ".tar.gz");
            String parent = new File(data).getParent(), name = new File(data).getName();
            boolean ok = Control.root("mkdir -p " + RootSession.sq(backupDir().getAbsolutePath()) + " && tar -czf "
                    + RootSession.sq(dst.getAbsolutePath()) + " -C " + RootSession.sq(parent) + " " + RootSession.sq(name));
            post(() -> toast(ok ? "バックアップしました: " + dst : "バックアップできませんでした (root が必要です)"));
        }));
        addButton("セーブデータを消去 (アプリを初期状態にする)", 0xFF991B1B, () -> new AlertDialog.Builder(a)
                .setTitle("セーブデータを消去")
                .setMessage(t.title + " のデータ (ログイン情報・設定・セーブなど) をすべて消します。元に戻せません。")
                .setPositiveButton("消去", (d, w) -> bg(() -> {
                    boolean ok = Control.root("pm clear " + pkg);
                    post(() -> {
                        if (ok) {
                            toast("消去しました");
                        } else {
                            toast("root が使えないため、Android の設定から消去してください");
                            openAppSettings();
                        }
                    });
                }))
                .setNegativeButton("キャンセル", null)
                .show());
    }

    // ------------------------------------------------------------------ アンインストール

    private void uninstall() {
        if (t.builtin) {
            body.addView(text("組み込みの機能なので削除できません", 13, 0xFFCBD5E1, false));
        } else if (t.shortcut) {
            if (act.unpin != null) addClosing("ホーム画面から外す", 0xFF991B1B, act.unpin);
        } else if (t.program != null) {
            if (act.uninstall != null) addClosing("アンインストール (登録を消す)", 0xFF991B1B, act.uninstall);
        } else {
            boolean system = false;
            try {
                system = (a.getPackageManager().getApplicationInfo(t.packageName, 0).flags
                        & ApplicationInfo.FLAG_SYSTEM) != 0;
            } catch (PackageManager.NameNotFoundException ignored) {
                // 無い
            }
            if (system) {
                body.addView(text("システムアプリなので、アンインストールはできません (Android の設定で無効化はできます)",
                        13, 0xFFCBD5E1, false));
            } else {
                addClosing("アンインストール", 0xFF991B1B, () -> {
                    try {
                        a.startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + t.packageName))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (ActivityNotFoundException e) {
                        openAppSettings();
                    }
                });
            }
        }
    }

    // ------------------------------------------------------------------ その他

    private void others() {
        if (act.open != null) addClosing(t.program != null ? "実行" : "開く", 0xFF2563EB, act.open);
        if (act.openWithArgs != null) addClosing("引数を付けて実行", 0xFF374151, act.openWithArgs);
        if (t.program != null) {
            if (act.changeIcon != null) addClosing("アイコンを変更", 0xFF374151, act.changeIcon);
            if (act.resetIcon != null) addClosing("アイコンを元に戻す", 0xFF374151, act.resetIcon);
            final File real = ProgramKind.of(a, t.program).real;
            addButton("ウイルス検査 (セキュリティ)", 0xFF374151, () -> bg(() -> {
                AvScanner.Verdict v = AvScanner.scan(a, real);
                final String m = v == null ? "読めませんでした" : v.level == AvScanner.CLEAN ? "脅威は見つかりませんでした"
                        : (v.level == AvScanner.THREAT ? "脅威: " : "疑わしい: ") + v.name + " (" + v.engine + ")";
                post(() -> toast(m));
            }));
            addButton("場所をコピー", 0xFF374151, () -> copy(real.getAbsolutePath()));
            return;
        }
        if (t.packageName == null) return;
        if (!t.shortcut) {
            addButton("強制停止", 0xFF374151, () -> bg(() -> {
                boolean ok = Control.root("am force-stop " + t.packageName);
                post(() -> {
                    if (ok) toast("強制停止しました");
                    else openAppSettings();
                });
            }));
            addButton("APK を取り出す (内部ストレージの Download へ)", 0xFF374151, this::extractApk);
            addClosing("Play ストアで見る", 0xFF374151, () -> {
                try {
                    a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + t.packageName))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException e) {
                    a.startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/apps/details?id=" + t.packageName))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                }
            });
        }
        addClosing(t.shortcut ? "元のアプリの情報 (Android の設定)" : "Android のアプリ情報 (設定)", 0xFF374151,
                this::openAppSettings);
        addButton("パッケージ名をコピー", 0xFF374151, () -> copy(t.packageName));
    }

    private void openAppSettings() {
        try {
            a.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + t.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            toast("アプリ情報を開けませんでした");
        }
    }

    private void copy(String s) {
        ClipboardManager cm = (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Bionic Runner", s));
        toast("コピーしました: " + s);
    }

    /** APK (分割 APK なら全部) を内部ストレージの Download/BionicRunner-apk へ写す */
    private void extractApk() {
        bg(() -> {
            String msg;
            try {
                ApplicationInfo ai = a.getPackageManager().getApplicationInfo(t.packageName, 0);
                File dst = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "BionicRunner-apk/" + t.packageName);
                if (!dst.isDirectory() && !dst.mkdirs()) throw new IOException("保存先を作れません (ファイルへのアクセス許可を確認してください)");
                copyFile(new File(ai.sourceDir), new File(dst, "base.apk"));
                int n = 1;
                if (ai.splitSourceDirs != null) {
                    for (String s : ai.splitSourceDirs) {
                        copyFile(new File(s), new File(dst, new File(s).getName()));
                        n++;
                    }
                }
                msg = n + " 個の APK を写しました: " + dst;
            } catch (PackageManager.NameNotFoundException | IOException e) {
                msg = "取り出せませんでした: " + e.getMessage();
            }
            final String m = msg;
            post(() -> toast(m));
        });
    }

    // ------------------------------------------------------------------ 補助

    private static File backupDir() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "BionicRunner-backup");
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
    }

    private static void copyFile(File from, File to) throws IOException {
        File parent = to.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("保存先を作れません");
        try (InputStream in = new FileInputStream(from); OutputStream os = new FileOutputStream(to)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    private static void copyTree(File from, File to) throws IOException {
        if (Archive.isSymlink(from)) return;
        if (from.isDirectory()) {
            File[] kids = from.listFiles();
            if (!to.isDirectory() && !to.mkdirs()) throw new IOException("保存先を作れません");
            if (kids != null) for (File k : kids) copyTree(k, new File(to, k.getName()));
        } else if (from.isFile()) {
            copyFile(from, to);
        }
    }

    /** root でコマンドを実行して出力を返す。root が使えない・失敗したら null */
    private static String rootOutput(String command) {
        try {
            Process p = new ProcessBuilder(RootCheck.su(), "-c", command).redirectErrorStream(false).start();
            p.getOutputStream().close();
            byte[] all = Icons.readAll(p.getInputStream());
            int code = p.waitFor();
            String s = new String(all, java.nio.charset.StandardCharsets.UTF_8).trim();
            return code == 0 || !s.isEmpty() ? s : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
