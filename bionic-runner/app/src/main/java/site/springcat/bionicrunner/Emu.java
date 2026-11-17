package site.springcat.bionicrunner;

import android.content.Context;

import java.io.File;
import java.io.IOException;

/**
 * 端末の CPU では直接動かないプログラムを動かすための CPU エミュレータ (QEMU のユーザーモード) の呼び出し。
 * Termux が Android 用にビルドしている、ユーザーモードのエミュレータをすべて同梱している:
 *  - 32bit の ARM (armeabi / armeabi-v7a): qemu-arm。最近の端末は 32bit のプログラムを OS がまったく
 *    実行できない (対応 CPU が arm64-v8a だけ)
 *  - PC 用の x86_64 と 32bit の x86 (i386): qemu-x86_64・qemu-i386
 *  - RISC-V (64bit / 32bit): qemu-riscv64・qemu-riscv32
 *  - PowerPC (32bit / 64bit。どちらもビッグエンディアン): qemu-ppc・qemu-ppc64
 *  - m68k (68000 系): qemu-m68k
 * 命令を 1 つずつ端末の CPU の命令に置き換えながら実行する。本体が使う共有ライブラリは
 * assets/qemu/arm64-v8a.zip (fetch-qemu.sh が用意する)。エミュレータは 64bit の ARM 端末用だけを同梱している。
 *
 * 動かせるのは、OS の部品に頼らないもの:
 *  - 静的リンクの ELF
 *  - glibc を動的リンクした ELF (アプリ同梱のその CPU 用の glibc を使う。SDL2 のゲームも動く)
 * その CPU 用の Android 向け (bionic を動的リンクしたもの) は動かせない。それが読み込む
 * システムライブラリ (その CPU 用の /system/lib など) が端末に入っていないため。
 */
final class Emu {

    /** エミュレータ本体は 64bit の ARM 用だけを同梱している */
    private static final String HOST_ABI = "arm64-v8a";

    private Emu() {
    }

    /** {ELF の e_machine, 64bit か, ビッグエンディアンか, エミュレータの名前 (lib/ の中), 人に見せる名前} */
    private static final Object[][] TABLE = {
            {40, false, false, "libqemuarm.so", "32bit の ARM"},
            {62, true, false, "libqemux8664.so", "PC 用の 64bit (x86_64)"},
            {3, false, false, "libqemui386.so", "PC 用の 32bit (x86 / i386)"},
            {243, true, false, "libqemuriscv64.so", "RISC-V (64bit)"},
            {243, false, false, "libqemuriscv32.so", "RISC-V (32bit)"},
            {20, false, true, "libqemuppc.so", "PowerPC (32bit)"},
            {21, true, true, "libqemuppc64.so", "PowerPC64 (ビッグエンディアン)"},
            {4, false, true, "libqemum68k.so", "m68k (68000 系)"},
    };

    private static Object[] row(ElfInfo info) {
        for (Object[] r : TABLE) {
            if ((Integer) r[0] == info.machine && (Boolean) r[1] == info.is64
                    && (Boolean) r[2] == info.bigEndian) {
                return r;
            }
        }
        return null;
    }

    /** そのプログラムの CPU 用のエミュレータの名前 (lib/ の中)。用意していない CPU なら null */
    private static String tool(ElfInfo info) {
        Object[] r = row(info);
        return r == null ? null : (String) r[3];
    }

    /** そのプログラムの CPU の、人に見せる名前 */
    static String cpuLabel(ElfInfo info) {
        Object[] r = row(info);
        return r == null ? info.machineName() : (String) r[4];
    }

    /** 対応している CPU の一覧 (案内用) */
    static String supportedList() {
        StringBuilder sb = new StringBuilder();
        for (Object[] r : TABLE) sb.append(sb.length() > 0 ? "・" : "").append(r[4]);
        return sb.toString();
    }

    /** そのプログラムの CPU 用の glibc のローダの名前 (案内用) */
    static String glibcLoader(ElfInfo info) {
        return Glibc.loaderName(info.abi());
    }

    /** この端末が直接は実行できず、エミュレータの対象になる CPU のプログラムか */
    static boolean isForeign(ElfInfo info, String[] supportedAbis) {
        return info.isElf() && tool(info) != null && !info.cpuMatches(supportedAbis);
    }

    /** そのプログラム用のエミュレータが入っているか */
    static boolean available(Context c, ElfInfo info) {
        String t = tool(info);
        return t != null && new File(c.getApplicationInfo().nativeLibraryDir, t).isFile();
    }

    /** エミュレータ経由なら実行できるか */
    static boolean supports(Context c, ElfInfo info, String[] supportedAbis) {
        return isForeign(info, supportedAbis) && available(c, info)
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
                + "# Bionic Runner: エミュレータで動かすプログラムの起動スクリプト (自動生成)\n"
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
        sb.append("Q=\"$BR_NATIVE/").append(tool(info)).append("\"\n")
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
