package site.springcat.bionicrunner;

/**
 * 「このアプリで動く実行ファイルを作って」と AI に頼むための指示文を組み立てる。
 * 端末の CPU に合わせたビルドコマンドを埋め込むので、貼るだけで正しい形式のものが返ってくる。
 */
final class AiPrompt {

    private AiPrompt() {
    }

    /** ABI ごとの {説明, Go の環境変数, Rust ターゲット, zig ターゲット, NDK の clang 接頭辞, GNU の gcc} */
    private static String[] toolchain(String abi) {
        switch (abi) {
            case "armeabi-v7a":
                return new String[]{"32bit ARM (armv7 / armhf)", "GOARCH=arm GOARM=7",
                        "armv7-unknown-linux-musleabihf", "arm-linux-musleabihf",
                        "armv7a-linux-androideabi", "arm-linux-gnueabihf-gcc"};
            case "x86_64":
                return new String[]{"64bit x86 (x86_64 / amd64)", "GOARCH=amd64",
                        "x86_64-unknown-linux-musl", "x86_64-linux-musl",
                        "x86_64-linux-android", "x86_64-linux-gnu-gcc"};
            case "x86":
                return new String[]{"32bit x86 (i686)", "GOARCH=386",
                        "i686-unknown-linux-musl", "x86-linux-musl",
                        "i686-linux-android", "i686-linux-gnu-gcc"};
            default:
                return new String[]{"64bit ARM (aarch64 / arm64)", "GOARCH=arm64",
                        "aarch64-unknown-linux-musl", "aarch64-linux-musl",
                        "aarch64-linux-android", "aarch64-linux-gnu-gcc"};
        }
    }

    static String build(String request, String abi, String device, String androidRelease,
                        int api, boolean root) {
        String[] t = toolchain(abi);
        String want = request == null ? "" : request.trim();
        if (want.isEmpty()) want = "(ここに作りたいものを書いてください)";
        // NDK のコンパイラ名に付ける API レベル。端末より新しい値を指定すると起動できない
        int ndkApi = Math.max(21, Math.min(api, 24));

        return "Android 端末のアプリ「Bionic Runner」で動かすコマンドラインプログラムを作ってください。\n"
                + "このアプリは、Linux 用の実行ファイル (ELF) やシェルスクリプトを Android 上で"
                + "そのまま実行するためのものです。下の条件を必ず守ってください。\n"
                + "\n"
                + "# 作りたいもの\n"
                + want + "\n"
                + "\n"
                + "# 実行環境\n"
                + "- 端末: " + device + " / Android " + androidRelease + " (API " + api + ")\n"
                + "- CPU: " + t[0] + "。Android の ABI 名は " + abi + "\n"
                + "- " + (root ? "root 権限 (uid 0) のシェルから起動される"
                : "root 権限なしの一般アプリ権限で起動される (形式 1 か 3 のみ動く)") + "\n"
                + "- 画面は「出力が流れる欄」と「1 行ずつ送る入力欄」だけで、疑似端末 (TTY) ではない\n"
                + "  - 標準出力へ文字を出し、標準入力から 1 行ずつ読む対話形式にする\n"
                + "  - curses、カーソル移動、色などのエスケープシーケンス、1 文字単位のキー入力、"
                + "GUI は使えない\n"
                + "  - 出力がパイプ扱いでバッファされるため、入力待ちの前に必ず flush する "
                + "(C なら最初に setvbuf(stdout, NULL, _IONBF, 0) を呼ぶ)\n"
                + "- 作業ディレクトリは $HOME (書き込み可)。" + (root ? "/sdcard も通常は読み書きできる\n"
                : "\n")
                + "- /bin/bash、/usr、/etc/resolv.conf、/etc/passwd、/tmp は無い。一時ファイルは "
                + "$TMPDIR に置く\n"
                + "- 外部コマンドは /system/bin の toybox のもの (ls, cat, grep, sed など) だけを当てにする\n"
                + "\n"
                + "# 実行できるファイルの形式 (どれか 1 つで作る)\n"
                + "1. シェルスクリプト。1 行目は #!/system/bin/sh。Android の sh (mksh) と toybox で"
                + "動く書き方にし、bash 専用の機能は使わない。ビルド不要なので、実現できるならこれを優先\n"
                + "2. 静的リンクの ELF (" + t[0] + " の Linux 用)。例:\n"
                + "   - Go: CGO_ENABLED=0 GOOS=linux " + t[1] + " go build -o app\n"
                + "   - Rust: cargo build --release --target " + t[2] + "\n"
                + "   - C/C++: zig cc -target " + t[3] + " -static -O2 -o app main.c\n"
                + "3. Android NDK でビルドした ELF (bionic libc)。例:\n"
                + "   $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/" + t[4] + ndkApi
                + "-clang -O2 -o app main.c\n"
                + "   ホスト名の解決を伴う通信をするなら、Android の DNS が使えるこの形式にする\n"
                + "4. glibc を動的リンクした普通の Linux 用 ELF (" + t[5] + " -O2 -o app main.c など)。\n"
                + "   アプリ同梱の glibc 2.41 と libstdc++ (GCC 14) で動く。使える共有ライブラリは "
                + "libc, libm, libpthread, libdl, librt, libresolv, libgcc_s, libstdc++ だけなので、"
                + "それ以外は静的に取り込む。glibc 2.41 より新しい環境でビルドしない\n"
                + "\n"
                + "次のものは動かないので作らない: Windows の .exe (PE 形式)、別の CPU 用のバイナリ、"
                + "musl を動的リンクしたバイナリ、Android の APK や GUI アプリ。\n"
                + "\n"
                + "# 出してほしいもの\n"
                + "- 省略のない完全なソースコード\n"
                + "- ビルド手順 (そのまま貼って実行できるコマンド)\n"
                + "- コンパイルが必要な場合は、PC が無くても作れるよう、GitHub Actions でビルドして"
                + "実行ファイルを Artifacts からダウンロードできる .github/workflows/build.yml も付ける\n"
                + "- 使い方 (引数と、入力欄に打つ内容の例)\n"
                + "- 実行ファイルの名前は半角英数字と . _ - だけにする (その名前がコマンド名になる)\n";
    }
}
