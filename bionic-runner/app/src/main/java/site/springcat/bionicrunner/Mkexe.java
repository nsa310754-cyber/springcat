package site.springcat.bionicrunner;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * mkexe (端末の上で C のソースから bionic 用・glibc 用の実行ファイルを作るコマンド) の準備。
 *
 * コンパイラ TCC は APK の lib/ (libtccbionic.so・libtccglibc.so、native/build-tcc.sh でビルド)、
 * その補助 (TCC のヘッダと libtcc1.a) は assets/mkexe/arm64-v8a.zip、コマンド本体は
 * assets/mkexe/mkexe.sh (bash のスクリプト)。シェルの起動のたびに作業領域へ置き直す。
 * libc のヘッダやライブラリは、mkexe が初めて使うときに公式の配布元から取ってくる。
 */
final class Mkexe {

    private Mkexe() {
    }

    static File dir(Context c) {
        return new File(c.getFilesDir(), "mkexe");
    }

    /** 補助一式 (bionic/・glibc/ の include と libtcc1.a) の場所 */
    static File support(Context c) {
        return new File(dir(c), "arm64-v8a");
    }

    static File script(Context c) {
        return new File(dir(c), "mkexe.sh");
    }

    /** 64bit の ARM 端末用のコンパイラが入っているか */
    static boolean available(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "libtccbionic.so").isFile();
    }

    /** 補助一式とスクリプトを置く。使えない端末や失敗したときは false */
    static boolean install(Context c) {
        if (!available(c)) return false;
        try {
            Glibc.installAsset(c, "mkexe", "arm64-v8a", new File(new File(support(c), "glibc"), "libtcc1.a"));
            File s = script(c);
            try (InputStream in = c.getAssets().open("mkexe/mkexe.sh");
                 OutputStream os = new java.io.FileOutputStream(s)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            s.setReadable(true, false);
            dir(c).setReadable(true, false);
            dir(c).setExecutable(true, false);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
