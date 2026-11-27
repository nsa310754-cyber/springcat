package site.springcat.bionicrunner;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * ホーム画面のアイコンが「何か」を見分ける (表示の切り替えと、アイコンの情報に使う)。
 *
 * 登録したプログラム (files/bin の名前) は、登録のときの置き方で見分ける:
 *   glibc 用      … 本体が files/glibc-bin にある (bin には起動スクリプト)
 *   エミュレータ … 本体が files/emu-bin にある (32bit ARM や x86_64 など、この端末と違う CPU 用)
 *   圧縮ファイル … 一式が files/pkg/<名前> にある (tar.gz・zip などから展開したもの)
 *   それ以外      … bin のファイルそのものを読んで、ELF (bionic・静的リンク) かスクリプトかを見る
 */
final class ProgramKind {

    /** ホーム画面の表示の切り替え: 1 全部 / 2 APK・AAB・PWA / 3 bionic・glibc / 4 その他 */
    static final int FILTER_ALL = 0, FILTER_ANDROID = 1, FILTER_ELF = 2, FILTER_OTHER = 3;
    static final String[] FILTER_LABELS = {
            "全部 (Chrome の「ホーム画面に追加」も)",
            "APK・AAB・PWA のみ",
            "bionic・glibc のみ",
            "その他 (sh・tar.gz など) のみ",
    };
    static final String[] FILTER_SHORT = {"全部", "APK・PWA", "bionic・glibc", "その他"};

    enum Type {
        BIONIC("bionic (Android 用の実行ファイル)", FILTER_ELF),
        GLIBC("glibc (Linux 用の実行ファイル)", FILTER_ELF),
        STATIC("静的リンクの実行ファイル (ELF)", FILTER_ELF),
        EMULATED("ほかの CPU 用の実行ファイル (エミュレータで実行)", FILTER_ELF),
        ARCHIVE("圧縮ファイルから展開したプログラム", FILTER_OTHER),
        SCRIPT("スクリプト", FILTER_OTHER),
        OTHER("その他のファイル", FILTER_OTHER);

        final String label;
        final int filter;

        Type(String label, int filter) {
            this.label = label;
            this.filter = filter;
        }
    }

    /** 判定の結果: 種類と、細かい説明 (スクリプトの言語・圧縮の形式など) */
    static final class Info {
        final Type type;
        final String detail;
        /** 実際に動く本体 (起動スクリプトの先) */
        final File real;

        Info(Type type, String detail, File real) {
            this.type = type;
            this.detail = detail;
            this.real = real;
        }

        String label() {
            return detail == null || detail.isEmpty() ? type.label : type.label + " — " + detail;
        }
    }

    private ProgramKind() {
    }

    /** 登録したプログラム (bin のファイル) の種類 */
    static Info of(Context c, File binFile) {
        String name = binFile.getName();
        File files = c.getFilesDir();
        File glibc = new File(new File(files, "glibc-bin"), name);
        if (glibc.isFile()) return new Info(Type.GLIBC, cpu(glibc), glibc);
        File emu = new File(Emu.binDir(c), name);
        if (emu.isFile()) return new Info(Type.EMULATED, cpu(emu), emu);
        File pkg = Archive.pkgDir(files, name);
        if (pkg.isDirectory()) {
            File exe = Archive.packageExe(files, name);
            String inner = "";
            if (exe != null) {
                Info in = ofFile(exe);
                inner = "中身: " + in.label();
            }
            return new Info(Type.ARCHIVE, inner, exe != null ? exe : binFile);
        }
        return ofFile(binFile);
    }

    /** ファイルそのものを読んで見分ける */
    static Info ofFile(File f) {
        try {
            ElfInfo e = ElfInfo.read(f);
            switch (e.kind) {
                case BIONIC_DYNAMIC: return new Info(Type.BIONIC, cpu(f), f);
                case STATIC: return new Info(Type.STATIC, cpu(f), f);
                case FOREIGN_LIBC:
                    String interp = e.interpreter == null ? "" : e.interpreter;
                    if (interp.contains("ld-linux")) return new Info(Type.GLIBC, cpu(f), f);
                    return new Info(Type.OTHER, "Android 以外の libc 用 (" + interp + ")", f);
                case SCRIPT: return new Info(Type.SCRIPT, scriptLanguage(f), f);
                default: break;
            }
        } catch (IOException ignored) {
            // 読めない
        }
        String n = f.getName().toLowerCase(Locale.ROOT);
        if (n.endsWith(".sh") || n.endsWith(".py") || n.endsWith(".lua")) return new Info(Type.SCRIPT, scriptLanguage(f), f);
        return new Info(Type.OTHER, "", f);
    }

    private static String cpu(File f) {
        try {
            ElfInfo e = ElfInfo.read(f);
            String abi = e.abi();
            return abi == null ? "" : abi;
        } catch (IOException e) {
            return "";
        }
    }

    /** スクリプトの 1 行目 (#!) から言語を見る */
    static String scriptLanguage(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[200];
            int n = in.read(b);
            if (n <= 2 || b[0] != '#' || b[1] != '!') return "";
            String line = new String(b, 2, n - 2, StandardCharsets.UTF_8).split("\n")[0].trim();
            String prog = line.split("\\s+")[0];
            if (prog.endsWith("/env") && line.split("\\s+").length > 1) prog = line.split("\\s+")[1];
            prog = prog.substring(prog.lastIndexOf('/') + 1);
            if (prog.equals("libbash.so")) prog = "bash";
            if (prog.equals("libmpy.so")) prog = "python3";
            if (prog.equals("liblua.so")) prog = "lua";
            if (prog.equals("sh") || prog.equals("mksh")) return "シェルスクリプト (sh)";
            return prog;
        } catch (IOException e) {
            return "";
        }
    }

    /** Android アプリの種類 (APK / AAB から入れた分割 APK / PWA) */
    static String appType(ApplicationInfo ai) {
        if (ai.packageName.startsWith("org.chromium.webapk.") || ai.packageName.contains(".webapk.")) {
            return "PWA (Chrome などが作った WebAPK)";
        }
        String[] splits = ai.splitSourceDirs;
        if (splits != null && splits.length > 0) {
            return "AAB から入れたアプリ (分割 APK " + (splits.length + 1) + " 個)";
        }
        return "APK";
    }
}
