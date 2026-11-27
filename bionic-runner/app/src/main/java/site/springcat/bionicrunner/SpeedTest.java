package site.springcat.bionicrunner;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 設定の「速度を比べる」: 同梱のベンチマーク (assets/bench/ の bench4 と bench.sh。元は
 * samples/x86_64-glibc) を、ネイティブ (arm64・mkexe でビルド)・Box64・QEMU で動かして表で出す。
 *
 * x86_64 版 (bench4-x86_64) は登録しなくても測れるよう、登録したプログラムと同じ起動スクリプトを
 * 作って渡す (Box64 / QEMU の切り替えは、その中の BR_X86 で行う)。結果は文字端末の画面に出す。
 */
final class SpeedTest {

    private static final String[] FILES = {"bench4.c", "bench4-x86_64", "bench.sh"};

    private SpeedTest() {
    }

    static File dir(Context c) {
        return new File(c.getFilesDir(), "bench");
    }

    /** 準備してから、結果を出す画面を開く。準備 (展開など) は別スレッドで行う */
    static void start(final Activity a) {
        final Context app = a.getApplicationContext();
        Toast.makeText(a, "準備しています…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String error = null;
            File wrapper = new File(dir(app), "bench4-x86_64.sh");
            try {
                File d = dir(app);
                if (!d.isDirectory() && !d.mkdirs()) throw new IOException("置き場所を作れません");
                for (String name : FILES) {
                    File out = new File(d, name);
                    try (InputStream in = app.getAssets().open("bench/" + name);
                         OutputStream os = new java.io.FileOutputStream(out)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    }
                    out.setReadable(true, false);
                    out.setExecutable(true, false);
                }
                File x86 = new File(d, "bench4-x86_64");
                ElfInfo info = ElfInfo.read(x86);
                if (!Emu.available(app, info)) {
                    throw new IOException("x86_64 のプログラムを動かす仕組み (Box64・QEMU) がこの APK に入っていません");
                }
                // Box64 (入っていれば)・QEMU・x86_64 用の glibc を用意して、起動スクリプトを作る
                Emu.ensureInstalled(app, info);
                Glibc.writeText(wrapper, Emu.wrapperScript(app, info, x86));
                Mkexe.install(app);
            } catch (IOException e) {
                error = e.getMessage();
            }
            final String err = error;
            a.runOnUiThread(() -> {
                if (err != null) {
                    Toast.makeText(a, "速度を比べられません: " + err, Toast.LENGTH_LONG).show();
                    return;
                }
                String lib = app.getApplicationInfo().nativeLibraryDir;
                java.util.List<String> env = new java.util.ArrayList<>();
                env.add("BENCH_DIR=" + dir(app).getAbsolutePath());
                env.add("BENCH_X86_WRAPPER=" + wrapper.getAbsolutePath());
                if (Mkexe.available(app)) {
                    env.add("BR_MKEXE=" + Mkexe.script(app).getAbsolutePath());
                    env.add("BR_MKEXE_SUPPORT=" + Mkexe.support(app).getAbsolutePath());
                }
                a.startActivity(new Intent(a, TermGameActivity.class)
                        .putExtra(TermGameActivity.EXTRA_ARGV, new String[]{
                                new File(lib, "libbash.so").getAbsolutePath(),
                                new File(dir(app), "bench.sh").getAbsolutePath()})
                        .putExtra(TermGameActivity.EXTRA_ENV, env.toArray(new String[0]))
                        .putExtra(TermGameActivity.EXTRA_TITLE, "速度を比べる (ネイティブ・Box64・QEMU)"));
            });
        }, "speed-test").start();
    }
}
