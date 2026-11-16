package site.springcat.bionicrunner;

import android.content.Context;

import java.io.File;
import java.io.IOException;

/**
 * 32bit の ARM 用プログラム (armeabi / armeabi-v7a) を、64bit 専用の端末で動かすための
 * CPU エミュレータ (QEMU のユーザーモード、qemu-arm) の呼び出し。
 *
 * 最近の端末は 32bit のプログラムを OS がまったく実行できない (対応 CPU が arm64-v8a だけ)。
 * そこで、32bit の命令を 1 つずつ 64bit の命令に置き換えながら実行するエミュレータを挟む。
 * 本体は lib/arm64-v8a/libqemuarm.so、本体が使う共有ライブラリは assets/qemu/arm64-v8a.zip
 * (fetch-qemu.sh が用意する)。
 *
 * 動かせるのは、OS の部品に頼らないもの:
 *  - 静的リンクの ELF
 *  - glibc を動的リンクした ELF (アプリ同梱の 32bit 用 glibc を使う。SDL2 のゲームも動く)
 * 32bit の Android 用 (bionic を動的リンクしたもの) は動かせない。64bit 専用の端末には、それが
 * 読み込む 32bit 版のシステムライブラリ (/system/lib) が入っていないため。
 */
final class Emu {

    private static final String TOOL = "libqemuarm.so";
    /** エミュレータ本体は 64bit の ARM 用だけを同梱している */
    private static final String HOST_ABI = "arm64-v8a";
    private static final int EM_ARM = 40;

    private Emu() {
    }

    static boolean available(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, TOOL).isFile();
    }

    /** この端末が直接は実行できない 32bit の ARM 用プログラムか */
    static boolean isForeignArm(ElfInfo info, String[] supportedAbis) {
        return info.isElf() && !info.is64 && info.machine == EM_ARM && !info.cpuMatches(supportedAbis);
    }

    /** エミュレータ経由なら実行できるか */
    static boolean supports(Context c, ElfInfo info, String[] supportedAbis) {
        return isForeignArm(info, supportedAbis) && available(c)
                && (info.kind == ElfInfo.Kind.STATIC || Glibc.supports(info));
    }

    /** エミュレータで動かすプログラムの本体の置き場所 (bin には起動スクリプトを置く) */
    static File binDir(Context c) {
        return new File(c.getFilesDir(), "emu-bin");
    }

    private static File libDir(Context c) {
        return new File(new File(c.getFilesDir(), "qemu"), HOST_ABI + "/lib");
    }

    /** エミュレータのライブラリと、(glibc 用なら) 32bit 用の glibc を展開する。UI スレッド以外で呼ぶ */
    static void ensureInstalled(Context c, ElfInfo info) throws IOException {
        Glibc.installAsset(c, "qemu", HOST_ABI, new File(libDir(c), "libglib-2.0.so.0"));
        if (Glibc.supports(info)) Glibc.ensureInstalled(c, info.abi());
    }

    /** bin に置く起動スクリプト。登録名をコマンドとして打つと、エミュレータ経由で本体が起動する */
    static String wrapperScript(Context c, ElfInfo info, File realBinary) {
        return "#!/system/bin/sh\n"
                + "# Bionic Runner: 32bit ARM 用プログラムの起動スクリプト (自動生成)\n"
                + launchLines(c, info, realBinary);
    }

    /**
     * 起動スクリプトのうち、エミュレータ経由で本体を起動する部分。
     * LD_LIBRARY_PATH はエミュレータ自身のライブラリ探し用。中で動くプログラムには渡さない
     * (-U で外す)。渡すと、そのプログラムが呼び出す端末のコマンドまで別のライブラリを読んでしまう。
     */
    static String launchLines(Context c, ElfInfo info, File realBinary) {
        String real = MainActivity.sq(realBinary.getAbsolutePath());
        StringBuilder sb = new StringBuilder();
        sb.append("Q=\"$BR_NATIVE/").append(TOOL).append("\"\n")
                .append("if [ ! -x \"$Q\" ]; then\n")
                .append("  echo 'このプログラムは Bionic Runner のコマンドプロンプトから実行してください' >&2\n")
                .append("  exit 126\n")
                .append("fi\n")
                .append("export LD_LIBRARY_PATH=").append(MainActivity.sq(libDir(c).getAbsolutePath()))
                .append("\n");
        if (!Glibc.supports(info)) {
            sb.append("exec \"$Q\" -U LD_LIBRARY_PATH ").append(real).append(" \"$@\"\n");
            return sb.toString();
        }
        // glibc 用: 32bit 用の glibc のローダに本体を読み込ませる (Glibc.launchLines と同じ形)。
        // root が無いときは、禁止されたシステムコールで強制終了されないよう補助ライブラリを先に読ませる
        String abi = info.abi();
        String loader = "\"$L/" + Glibc.loaderName(abi) + "\"";
        sb.append("L=").append(MainActivity.sq(Glibc.libDir(c, abi).getAbsolutePath())).append("\n")
                .append("if [ \"$(id -u)\" = 0 ]; then\n")
                .append("  exec \"$Q\" -U LD_LIBRARY_PATH ").append(loader)
                .append(" --library-path \"$L\" ").append(real).append(" \"$@\"\n")
                .append("fi\n")
                .append("exec \"$Q\" -U LD_LIBRARY_PATH ").append(loader)
                .append(" --preload \"$L/libbrshim.so\" --library-path \"$L\" ").append(real)
                .append(" \"$@\"\n");
        return sb.toString();
    }
}
