package site.springcat.bionicrunner;

import android.content.Context;

import java.io.File;

/**
 * 同梱のコマンド (br・7z・7zz・yara) を、どこからでも呼べる本物のコマンドにする。
 *
 * files/tools/ に、APK の lib/ にある本体 (libbr.so など) を指すシンボリックリンクを置き、
 * その場所を PATH に入れる。シェルの関数や別名 (alias) にしておくと、コマンドプロンプトに直接打った
 * ときは動くが、スクリプトや別のプログラムからは見えない (別のプロセスには引き継がれない) ため。
 *
 * リンクにするのは、root が無いとアプリのデータ領域にあるファイルは実行できないが、リンクの先
 * (APK の lib/) なら実行できるから。lib/ の場所はアプリを更新するたびに変わるので、毎回作り直す。
 */
final class Tools {

    /** {コマンド名, lib/ の中の本体の名前} */
    private static final String[][] LINKS = {
            {"br", "libbr.so"},
            {"7z", "lib7zz.so"},
            {"7zz", "lib7zz.so"},
            {"yara", "libyara.so"},
    };

    private Tools() {
    }

    static File dir(Context c) {
        return new File(c.getFilesDir(), "tools");
    }

    /** リンクを作り直し、その場所を返す。作れなかったものは飛ばす (本体が無い CPU 用など) */
    static File install(Context c) {
        File d = dir(c);
        d.mkdirs();
        // root のシェル (別の利用者) からもたどれるようにする
        d.setReadable(true, false);
        d.setExecutable(true, false);
        String lib = c.getApplicationInfo().nativeLibraryDir;
        for (String[] l : LINKS) {
            File link = new File(d, l[0]);
            File target = new File(lib, l[1]);
            link.delete(); // 前の版の lib/ を指したままのリンクを消す
            if (!target.isFile()) continue;
            try {
                android.system.Os.symlink(target.getAbsolutePath(), link.getAbsolutePath());
            } catch (android.system.ErrnoException e) {
                // 作れなかった: そのコマンドは使えないが、ほかは続ける
            }
        }
        return d;
    }
}
