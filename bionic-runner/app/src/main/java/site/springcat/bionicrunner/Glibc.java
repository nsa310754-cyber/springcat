package site.springcat.bionicrunner;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * APK に同梱した glibc ランタイム (Debian 由来のローダと基本ライブラリ) の展開と場所の管理。
 * glibc 用の実行ファイルは「同梱ローダ --library-path 同梱lib 本体」の形で起動する。
 */
final class Glibc {

    /** APK の lib/ に入れてある glibc のローダの名前 (root なしでも実行できる場所にある) */
    static final String NATIVE_LOADER = "libbrld.so";

    private Glibc() {
    }

    /** その ABI の glibc ローダのファイル名。同梱していない ABI なら null */
    static String loaderName(String abi) {
        if (abi == null) return null;
        switch (abi) {
            case "arm64-v8a": return "ld-linux-aarch64.so.1";
            case "armeabi-v7a": return "ld-linux-armhf.so.3";
            case "x86_64": return "ld-linux-x86-64.so.2";
            case "x86": return "ld-linux.so.2";
            default: return null;
        }
    }

    /** この実行ファイルが要求するローダを同梱の glibc で賄えるか (musl 用などは false) */
    static boolean supports(ElfInfo info) {
        if (info.kind != ElfInfo.Kind.FOREIGN_LIBC || info.interpreter == null) return false;
        String want = info.interpreter.substring(info.interpreter.lastIndexOf('/') + 1);
        return want.equals(loaderName(info.abi()));
    }

    static File libDir(Context c, String abi) {
        return new File(new File(c.getFilesDir(), "glibc"), abi + "/lib");
    }

    static File loader(Context c, String abi) {
        return new File(libDir(c, abi), loaderName(abi));
    }

    /** 未展開 (または同梱版が更新された) なら展開する。数 MB あるので UI スレッド以外で呼ぶ */
    static synchronized void ensureInstalled(Context c, String abi) throws IOException {
        File base = new File(new File(c.getFilesDir(), "glibc"), abi);
        File marker = new File(base, "VERSIONS.txt");
        byte[] bundled = readAll(c.getAssets().open("glibc/VERSIONS.txt"));
        if (marker.isFile() && loader(c, abi).isFile()
                && java.util.Arrays.equals(bundled, readAll(new FileInputStream(marker)))) {
            return;
        }
        deleteTree(base);
        if (!base.mkdirs() && !base.isDirectory()) throw new IOException("作成できません: " + base);
        String root = base.getCanonicalPath() + File.separator;
        try (ZipInputStream zin = new ZipInputStream(c.getAssets().open("glibc/" + abi + ".zip"))) {
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = zin.getNextEntry()) != null) {
                File out = new File(base, e.getName());
                if (!out.getCanonicalPath().startsWith(root)) {
                    throw new IOException("不正なパス: " + e.getName());
                }
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                try (OutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = zin.read(buf)) > 0) os.write(buf, 0, n);
                }
                out.setReadable(true, false);
                out.setExecutable(true, false);
            }
        }
        // 途中で失敗した展開を「済み」と誤認しないよう、目印は最後に書く
        try (OutputStream os = new FileOutputStream(marker)) {
            os.write(bundled);
        }
    }

    /**
     * bin に置く起動用スクリプト。登録名をコマンドとして打つと glibc 経由で本体が起動する。
     *
     * root なら作業領域に展開したローダをそのまま使う。root が無いときは、アプリのデータ領域に
     * あるファイルを直接は実行できないので、APK の lib/ に入れてあるローダ (libbrld.so。場所は
     * シェルの環境変数 BR_NATIVE) を使う。あわせて、禁止されたシステムコールで強制終了させられる
     * 代わりに「未対応」を返す補助ライブラリ (libbrshim.so) を先に読み込ませる。
     */
    static String wrapperScript(Context c, String abi, File realBinary) {
        return "#!/system/bin/sh\n"
                + "# Bionic Runner: glibc 用プログラムの起動スクリプト (自動生成)\n"
                + launchLines(c, abi, realBinary);
    }

    /** 起動スクリプトのうち、同梱ローダ経由で本体を起動する部分 */
    static String launchLines(Context c, String abi, File realBinary) {
        String lib = MainActivity.sq(libDir(c, abi).getAbsolutePath());
        String real = MainActivity.sq(realBinary.getAbsolutePath());
        return "L=" + lib + "\n"
                + "if [ \"$(id -u)\" = 0 ]; then\n"
                + "  exec \"$L/" + loaderName(abi) + "\" --library-path \"$L\" " + real + " \"$@\"\n"
                + "fi\n"
                + "if [ ! -x \"$BR_NATIVE/" + NATIVE_LOADER + "\" ]; then\n"
                + "  echo 'このプログラムは Bionic Runner のコマンドプロンプトから実行してください' >&2\n"
                + "  exit 126\n"
                + "fi\n"
                + "exec \"$BR_NATIVE/" + NATIVE_LOADER + "\" --preload \"$L/libbrshim.so\""
                + " --library-path \"$L\" " + real + " \"$@\"\n";
    }

    /** bin の起動スクリプトをすべて作り直す (アプリの更新で作りが変わったときのため)。作り直した数を返す */
    static int rewriteWrappers(Context c) {
        File[] reals = new File(c.getFilesDir(), "glibc-bin").listFiles();
        if (reals == null) return 0;
        int n = 0;
        for (File real : reals) {
            try {
                ElfInfo info = ElfInfo.read(real);
                if (!supports(info)) continue;
                File wrapper = new File(new File(c.getFilesDir(), "bin"), real.getName());
                writeText(wrapper, wrapperScript(c, info.abi(), real));
                wrapper.setReadable(true, false);
                wrapper.setExecutable(true, false);
                n++;
            } catch (IOException e) {
                // その 1 つは古いまま残る。実行時にエラーが出れば登録し直せる
            }
        }
        return n;
    }

    static void writeText(File f, String text) throws IOException {
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = i.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
