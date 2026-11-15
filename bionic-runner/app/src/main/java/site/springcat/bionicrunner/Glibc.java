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

    /** bin に置く起動用スクリプト。登録名をコマンドとして打つと glibc 経由で本体が起動する */
    static String wrapperScript(Context c, String abi, File realBinary) {
        File lib = libDir(c, abi);
        return "#!/system/bin/sh\n"
                + "# Bionic Runner: glibc 用プログラムの起動スクリプト\n"
                + "exec " + MainActivity.sq(loader(c, abi).getAbsolutePath())
                + " --library-path " + MainActivity.sq(lib.getAbsolutePath())
                + " " + MainActivity.sq(realBinary.getAbsolutePath()) + " \"$@\"\n";
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
