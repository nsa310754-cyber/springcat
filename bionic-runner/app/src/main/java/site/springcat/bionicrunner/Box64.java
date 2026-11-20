package site.springcat.bionicrunner;

import android.content.Context;

import java.io.File;
import java.io.IOException;

/**
 * Box64: x86_64 の Linux のプログラムを、ARM64 の命令に変換しながら動かす (QEMU より速い)。
 * 将来 Wine を載せるための土台でもある。
 *
 * Termux 版の box64-glibc (fetch-box64.sh が取ってくる) は glibc を動的リンクした arm64 のプログラムなので、
 * アプリ同梱の arm64 用 glibc の上で動かす。x86_64 のプログラムが使う x86_64 用のライブラリは、
 * assets/glibc/x86_64.zip を展開したもの (と Box64 付属の補助ライブラリ) を BOX64_LD_LIBRARY_PATH で渡す。
 *
 * どちらで動かすか (自動 / Box64 / QEMU) は、実行のたびに起動スクリプトの中で決める。設定は
 * files/box64/mode に書いてあり、変えるとすでに登録したプログラムにもすぐ効く。
 * 初めて使うとき (とアプリの更新後) に、小さな x86_64 のプログラム (probe-x86_64) を Box64 で動かして、
 * この端末で動くか確かめる。動かなければ (ページサイズが 16KB の端末など)、自動で QEMU に切り替え、
 * 理由を 1 行表示する。i386 など、ほかの CPU は今までどおり QEMU。
 */
final class Box64 {

    static final String AUTO = "auto", BOX64 = "box64", QEMU = "qemu";
    static final String[] MODES = {AUTO, BOX64, QEMU};
    static final String[] MODE_LABELS = {"自動 (Box64 があれば Box64、無ければ QEMU)", "Box64", "QEMU"};

    private Box64() {
    }

    /** x86_64 (64bit の PC 用) の ELF か */
    static boolean isX8664(ElfInfo info) {
        return info.machine == 62 && info.is64;
    }

    /** Box64 が入っているか (fetch-box64.sh を実行してビルドした APK だけ) */
    static boolean available(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "libbox64.so").isFile();
    }

    static File dir(Context c) {
        return new File(c.getFilesDir(), "box64");
    }

    /** 付属のもの (x86_64 用の補助ライブラリ・設定ファイル・確認用のプログラム) の展開先 */
    private static File support(Context c) {
        return new File(dir(c), "arm64-v8a");
    }

    private static File modeFile(Context c) {
        return new File(dir(c), "mode");
    }

    private static File probeFile(Context c) {
        return new File(dir(c), "probe");
    }

    /** 今の設定 (auto / box64 / qemu) */
    static String mode(Context c) {
        try {
            String m = new String(Icons.readAll(new java.io.FileInputStream(modeFile(c))),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            for (String x : MODES) if (x.equals(m)) return m;
        } catch (IOException ignored) {
            // まだ無い
        }
        return AUTO;
    }

    /** 設定を変える。起動スクリプトが読むファイルに書くので、登録済みのプログラムにもすぐ効く */
    static void setMode(Context c, String mode) throws IOException {
        dir(c).mkdirs();
        Glibc.writeText(modeFile(c), mode + "\n");
        modeFile(c).setReadable(true, false);
    }

    /** この端末で Box64 が動くかを確かめた結果 (まだなら null) */
    static String probeResult(Context c) {
        try {
            String s = new String(Icons.readAll(new java.io.FileInputStream(probeFile(c))),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            String bin = new File(c.getApplicationInfo().nativeLibraryDir, "libbox64.so").getAbsolutePath();
            if (s.startsWith("ok " + bin)) return "この端末で動くことを確かめました";
            if (s.startsWith("ng " + bin + " ")) {
                return "この端末では動きませんでした (2 回続けて): " + s.substring(4 + bin.length());
            }
            if (s.startsWith("retry " + bin + " ")) {
                return "1 回動きませんでした。次の実行でもう 1 回だけ確かめます: " + s.substring(7 + bin.length());
            }
        } catch (IOException ignored) {
            // まだ試していない
        }
        return null;
    }

    /** 確かめた結果を消す (次の実行で、もう一度確かめる) */
    static void resetProbe(Context c) {
        probeFile(c).delete();
    }

    /** Box64 と、それが使う arm64 用・x86_64 用の glibc を展開する。UI スレッド以外で呼ぶ */
    static void ensureInstalled(Context c) throws IOException {
        Glibc.installAsset(c, "box64", "arm64-v8a", new File(support(c), "probe-x86_64"));
        Glibc.ensureInstalled(c, "arm64-v8a");
        Glibc.ensureInstalled(c, "x86_64");
        dir(c).setReadable(true, false);
        dir(c).setExecutable(true, false);
    }

    /**
     * 起動スクリプトのうち、Box64 で動かす部分。Box64 が使えれば exec で置き換わり、使えなければ
     * 何もせずに続きへ進む (続きに QEMU で動かす部分を置く)。real は本体の場所 (引用符付き)。
     * 環境変数 BR_X86 (auto / box64 / qemu) で、その 1 回だけ設定を上書きできる (速さの比べ方などに使う)
     */
    static String launchLines(Context c, String real) {
        String q = "'";
        String a = MainActivity.sq(Glibc.libDir(c, "arm64-v8a").getAbsolutePath());
        String x = MainActivity.sq(Glibc.libDir(c, "x86_64").getAbsolutePath());
        String s = MainActivity.sq(support(c).getAbsolutePath());
        String mode = MainActivity.sq(modeFile(c).getAbsolutePath());
        String probe = MainActivity.sq(probeFile(c).getAbsolutePath());
        return ""
                + "# x86_64 の実行方法 (自動 / Box64 / QEMU)。設定は " + modeFile(c).getName() + " に書いてある\n"
                + "M=${BR_X86:-$(cat " + mode + " 2>/dev/null)}\n"
                + "M=${M:-auto}\n"
                + "if [ \"$M\" != qemu ]; then\n"
                + "  B=\"$BR_NATIVE/libbox64.so\"\n"
                + "  A=" + a + "\n"
                + "  X=" + x + "\n"
                + "  S=" + s + "\n"
                + "  WHY=\n"
                + "  if [ ! -f \"$B\" ]; then\n"
                + "    WHY='Box64 が入っていません'\n"
                + "  elif [ ! -f \"$A/ld-linux-aarch64.so.1\" ] || [ ! -f \"$X/ld-linux-x86-64.so.2\" ] || [ ! -f \"$S/probe-x86_64\" ]; then\n"
                + "    WHY='Box64 の準備ができていません (プログラムを登録し直してください)'\n"
                + "  fi\n"
                + "  unset LD_LIBRARY_PATH\n"
                + "  export BOX64_NOBANNER=1 BOX64_LOG=0 BOX64_RCFILE=\"$S/box64.box64rc\"\n"
                + "  export BOX64_LD_LIBRARY_PATH=\"$X:$S/x86_64\" BOX64_PATH=\"$PATH\"\n"
                + "  # Box64 は arm64 用の glibc の上で動く。root が無いときは、禁止されたシステムコールで\n"
                + "  # 強制終了されないよう、補助ライブラリ (libbrshim.so) を先に読み込ませる\n"
                + "  if [ \"$(id -u)\" = 0 ]; then\n"
                + "    _br64() { \"$A/ld-linux-aarch64.so.1\" --library-path \"$A\" \"$B\" \"$@\"; }\n"
                + "  else\n"
                + "    _br64() { \"$BR_NATIVE/libbrld.so\" --preload \"$A/libbrshim.so\" --library-path \"$A\" \"$B\" \"$@\"; }\n"
                + "  fi\n"
                + "  # 初めて (とアプリの更新後) だけ、この端末で Box64 が動くか確かめる。\n"
                + "  # 失敗しても (たまたまメモリが足りなかっただけかもしれないので) 次の実行でもう 1 回だけ試し、\n"
                + "  # 2 回続けて失敗したら「この端末では動かない」と記録して、以後は QEMU で動かす\n"
                + "  if [ -z \"$WHY\" ]; then\n"
                + "    P=" + probe + "\n"
                + "    R=$(head -n 1 \"$P\" 2>/dev/null)\n"
                + "    case $R in\n"
                + "      \"ok $B\") ;;\n"
                + "      \"ng $B \"*) WHY=${R#\"ng $B \"} ;;\n"
                + "      *)\n"
                + "        if (_br64 \"$S/probe-x86_64\") 2>/dev/null | grep -q box64-ok; then\n"
                + "          echo \"ok $B\" > \"$P\"\n"
                + "        else\n"
                + "          PS=$(getconf PAGESIZE 2>/dev/null)\n"
                + "          if [ -n \"$PS\" ] && [ \"$PS\" != 4096 ]; then\n"
                + "            WHY=\"ページサイズが ${PS} バイトの端末なので Box64 が起動しませんでした\"\n"
                + "          else\n"
                + "            WHY='Box64 がこの端末で起動しませんでした'\n"
                + "          fi\n"
                + "          case $R in\n"
                + "            \"retry $B \"*) echo \"ng $B $WHY\" > \"$P\" ;;\n"
                + "            *) echo \"retry $B $WHY\" > \"$P\" ;;\n"
                + "          esac\n"
                + "        fi ;;\n"
                + "    esac\n"
                + "  fi\n"
                + "  if [ -z \"$WHY\" ]; then\n"
                + "    if [ \"$(id -u)\" = 0 ]; then\n"
                + "      exec \"$A/ld-linux-aarch64.so.1\" --library-path \"$A\" \"$B\" " + real + " \"$@\"\n"
                + "    fi\n"
                + "    exec \"$BR_NATIVE/libbrld.so\" --preload \"$A/libbrshim.so\" --library-path \"$A\" \"$B\" "
                + real + " \"$@\"\n"
                + "  fi\n"
                + "  # 自動のときに「入っていない」だけなら黙って QEMU へ。それ以外は理由を 1 行出す\n"
                + "  if [ \"$M\" = box64 ] || [ \"$WHY\" != " + q + "Box64 が入っていません" + q + " ]; then\n"
                + "    echo \"Box64 が使えないため QEMU で実行します ($WHY)\" >&2\n"
                + "  fi\n"
                + "  unset BOX64_NOBANNER BOX64_LOG BOX64_RCFILE BOX64_LD_LIBRARY_PATH BOX64_PATH\n"
                + "fi\n";
    }
}
