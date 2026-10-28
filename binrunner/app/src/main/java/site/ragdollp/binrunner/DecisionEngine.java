package site.ragdollp.binrunner;

import android.os.Build;

/**
 * 解析結果から「このまま直接実行できるか / 変換が要るか / 無理か」を決める。
 *
 * ユーザー指定のロジック:
 *   1. Bionic(Android ネイティブ)かつ端末と同じ CPU → 直接実行
 *   2. そうでなく、変換できるなら変換して実行
 *      - CPU は同じだが glibc/musl    → proot で通常 Linux 環境を用意(CPU 変換は不要)
 *      - CPU が違う ELF / Windows PE  → box64/box86/Wine でバイナリ変換
 *   3. どちらも無理 → 実行不可
 */
public final class DecisionEngine {

    public enum Action {
        RUN_DIRECT,        // そのまま exec できる
        CONVERT_PROOT,     // 同一 CPU・別 libc → proot 環境で実行
        CONVERT_TRANSLATE, // 別 CPU の ELF → box64/box86 等の命令変換が必要
        CONVERT_WINE,      // Windows PE → Wine + 命令変換
        EXTRACT,           // 書庫 → 展開して中身を再判定
        UNSUPPORTED        // 実行手段なし
    }

    public static final class Plan {
        public Action action;
        public String title;   // UI の見出し
        public String detail;  // 理由と次の一手
        public boolean runnableNow; // このアプリが今すぐ試せるか
    }

    private DecisionEngine() {}

    /** 端末の第一 ABI を ELF e_machine へ写像(0=不明)。 */
    public static boolean archMatchesDevice(int eMachine) {
        for (String abi : Build.SUPPORTED_ABIS) {
            switch (abi) {
                case "arm64-v8a":    if (eMachine == 183) return true; break; // AARCH64
                case "armeabi-v7a":
                case "armeabi":      if (eMachine == 40) return true;  break; // ARM
                case "x86_64":       if (eMachine == 62) return true;  break; // X86_64
                case "x86":          if (eMachine == 3)  return true;  break; // 386
            }
        }
        return false;
    }

    public static Plan decide(BinaryAnalyzer.Result r) {
        Plan p = new Plan();
        switch (r.kind) {
            case ELF: {
                boolean sameCpu = archMatchesDevice(r.eMachine);
                boolean bionicOrStatic = (r.libc == BinaryAnalyzer.Libc.BIONIC
                        || r.libc == BinaryAnalyzer.Libc.STATIC);
                if (sameCpu && bionicOrStatic) {
                    p.action = Action.RUN_DIRECT;
                    p.title = "✅ 直接実行できます";
                    p.detail = "端末と同じ CPU("+ primaryAbi() +")で、"
                            + (r.libc == BinaryAnalyzer.Libc.BIONIC ? "Android の Bionic リンカ" : "静的リンク")
                            + "なので変換なしで exec できます。\n"
                            + "このアプリは targetSdk 28 のため、アプリ専用領域から "
                            + "root 無し・Termux 無しで直接実行できます(Termux と同じ仕組み)。\n"
                            + "端末独自の SELinux ポリシーで塞がれている場合だけ root を試します。";
                    p.runnableNow = true;
                } else if (sameCpu) { // 同一 CPU だが glibc/musl
                    p.action = Action.CONVERT_PROOT;
                    p.title = "🔁 変換が必要(proot 環境)";
                    p.detail = "CPU は同じ("+ primaryAbi() +")ですが glibc/musl 向けなので、"
                            + "Android の Bionic では直接動きません。proot で通常 Linux のルートFS("
                            + "Termux の proot-distro 等)を用意すればそこで実行できます。"
                            + "CPU 命令の変換は不要です。";
                    p.runnableNow = Executor.hasEngine("proot");
                } else { // CPU が違う ELF
                    p.action = Action.CONVERT_TRANSLATE;
                    p.title = "🔁 変換が必要(命令変換)";
                    p.detail = "このバイナリは " + r.archName + " 用で、端末は " + primaryAbi() + " です。"
                            + "CPU の命令セットが違うので、box64(x86-64)・box86(x86)・qemu-user などの"
                            + "バイナリトランスレータが必要です。root では命令形式は変えられません("
                            + "命令変換はソフトで行います)。";
                    p.runnableNow = Executor.hasEngine(r.eMachine == 62 ? "box64"
                            : r.eMachine == 3 ? "box86" : "qemu");
                }
                break;
            }
            case PE:
                p.action = Action.CONVERT_WINE;
                p.title = "🍷 変換が必要(Wine)";
                p.detail = "Windows の実行ファイルです。Windows API を変換する Wine と、"
                        + "x86/x64 命令を "+ primaryAbi() +" に変換する box64/box86 の両方が要ります"
                        + "(Winlator がこの構成の既成アプリ)。";
                p.runnableNow = Executor.hasEngine("wine");
                break;
            case DEB:
                p.action = Action.EXTRACT;
                p.title = "📦 .deb パッケージ";
                p.detail = "Debian パッケージです。「展開して実行ファイルを取り出す」で "
                        + "data.tar(.gz) を展開し、中の ELF 実行ファイルを取り出して判定・実行します"
                        + "(gzip 圧縮の data.tar に対応。xz/zstd は Termux で展開してください)。";
                p.runnableNow = true;   // アプリ内で ar+tar+gzip を展開可
                break;
            case GZIP:
            case ZIP:
            case TAR:
            case AR:
                p.action = Action.EXTRACT;
                p.title = "📦 書庫です";
                p.detail = r.kind == BinaryAnalyzer.Kind.GZIP
                        ? "gzip です。「展開して実行ファイルを取り出す」で中身(tar や ELF)を取り出して判定します。"
                        : "アーカイブです。「展開して実行ファイルを取り出す」で中の ELF を取り出します。";
                p.runnableNow = true;   // gzip/zip/tar/ar をアプリ内で展開可
                break;
            case SCRIPT:
                p.action = Action.RUN_DIRECT;
                p.title = "📜 スクリプト";
                p.detail = "#! 付きのテキストです。指定インタプリタ(sh 等)が端末にあれば実行できます。";
                p.runnableNow = true;
                break;
            case MACHO:
                p.action = Action.UNSUPPORTED;
                p.title = "⛔ 非対応";
                p.detail = "macOS/iOS 用(Mach-O)で、Android で動かす現実的な手段はありません。";
                p.runnableNow = false;
                break;
            default:
                p.action = Action.UNSUPPORTED;
                p.title = "❓ 不明な形式";
                p.detail = "既知の実行ファイル/書庫のどれにも一致しませんでした。";
                p.runnableNow = false;
        }
        return p;
    }

    public static String primaryAbi() {
        return Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0] : "?";
    }
}
