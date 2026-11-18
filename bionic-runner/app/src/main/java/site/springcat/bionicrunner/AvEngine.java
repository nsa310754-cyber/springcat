package site.springcat.bionicrunner;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ClamAV のエンジン (clamscan)。Termux の Android 用ビルドを同梱している (fetch-clamav.sh)。
 * 64bit の ARM 端末だけ。定義は {@link AvDefs} が用意する。
 */
final class AvEngine {

    private AvEngine() {
    }

    static File tool(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "libclamscan.so");
    }

    static boolean available(Context c) {
        return tool(c).isFile();
    }

    private static File libDir(Context c) {
        return new File(new File(new File(c.getFilesDir(), "clamav"), "arm64-v8a"), "lib");
    }

    /** clamscan を起動する。args は検査の対象など (定義の場所などはこちらで付ける) */
    static Process start(Context c, String... args) throws IOException {
        // エンジンが読み込むライブラリを作業領域へ展開する (初回と、アプリの更新後だけ)
        Glibc.installAsset(c, "clamav", "arm64-v8a", new File(libDir(c), "libclamav.so"));
        File tmp = new File(c.getCacheDir(), "clamav-tmp");
        tmp.mkdirs();
        List<String> cmd = new ArrayList<>(Arrays.asList(tool(c).getAbsolutePath(),
                "--database=" + AvDefs.dbDir(c).getAbsolutePath(),
                "--tempdir=" + tmp.getAbsolutePath(),
                "--no-summary",
                "--max-filesize=200M", "--max-scansize=400M",
                "--follow-dir-symlinks=0", "--follow-file-symlinks=0"));
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("LD_LIBRARY_PATH", libDir(c).getAbsolutePath());
        pb.environment().put("TMPDIR", tmp.getAbsolutePath());
        Process p = pb.start();
        p.getOutputStream().close();
        return p;
    }
}
