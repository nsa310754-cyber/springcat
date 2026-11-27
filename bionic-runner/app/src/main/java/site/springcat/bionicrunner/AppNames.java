package site.springcat.bionicrunner;

import java.io.File;
import java.util.Locale;

/**
 * 「同じアプリか」を名前から判定する。版の番号や CPU の種類が付いた別名
 * (game-1.2.exe と game-1.3-arm64.exe など) を、同じアプリとして見つけるため。
 * Android の API は使っていないので、PC 上の JVM でも単体で検証できる。
 */
final class AppNames {

    private AppNames() {
    }

    /** 実行ファイルによく付く拡張子 (取り除いて比べる) */
    private static final String[] EXTENSIONS = {".exe", ".bin", ".belf", ".gelf", ".elf", ".run", ".out", ".appimage",
            ".sh", ".x86_64", ".arm64", ".aarch64"};

    /** 名前の後ろに付く、アプリの名前そのものではない語 */
    private static final String EXTRA = "(?:arm64|aarch64|arm64-v8a|armeabi-v7a|armeabi|armv7a?|armhf|arm|"
            + "x86_64|x86|amd64|i686|linux|android|bionic|glibc|musl|gnu|static|release|debug|"
            + "final|new|latest|update|copy|コピー)";

    /**
     * 比べるための名前にする: 小文字にし、拡張子・版の番号・CPU の種類・「(2)」のような
     * 重複の印を取り除く。何も残らなければ元の名前 (小文字) を返す。
     */
    static String key(String name) {
        String s = name.toLowerCase(Locale.ROOT).trim();
        String before;
        do {
            before = s;
            for (String ext : EXTENSIONS) {
                if (s.endsWith(ext) && s.length() > ext.length()) s = s.substring(0, s.length() - ext.length());
            }
            s = s.replaceAll("[\\s._-]*\\(\\d+\\)$", "");                   // 「 (2)」
            s = s.replaceAll("[\\s._-]+" + EXTRA + "$", "");               // -arm64、_linux
            // -1.2.3、_v2、-1.0b。区切り無しで数字が続く名前 (doom2、blocks2048) は別のアプリかも
            // しれないので、そのままにする
            s = s.replaceAll("[\\s._-]+v?\\d+(?:[._]\\d+)*[a-z]?$", "");
            s = s.replaceAll("[\\s._-]+$", "");
        } while (!s.equals(before) && !s.isEmpty());
        return s.isEmpty() ? name.toLowerCase(Locale.ROOT).trim() : s;
    }

    /**
     * 登録済み (binDir の中) から、name と同じアプリとみなせるものを探す。
     * まったく同じ名前があればそれを、無ければ比べるための名前が同じものを返す。無ければ null。
     */
    static File findRegistered(File binDir, String name) {
        File exact = new File(binDir, name);
        if (exact.isFile()) return exact;
        File[] files = binDir.listFiles();
        if (files == null) return null;
        java.util.Arrays.sort(files);
        String want = key(name);
        for (File f : files) {
            if (f.isFile() && key(f.getName()).equals(want)) return f;
        }
        return null;
    }
}
