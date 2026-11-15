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

    /** 絵や音を出すプログラムの作り方 (同梱の SDL2 を使う) */
    private static String graphics(String abi, String[] t) {
        return "# 絵や音を出したい場合 (ゲームなど)\n"
                + "アプリに同梱の SDL2 を使う。ウィンドウの中身がアプリのゲーム画面に表示され、"
                + "音は端末のスピーカーから出る。\n"
                + "- 形式 4 (glibc 動的リンク) で作り、SDL2 を動的リンクする (静的リンクしない)。"
                + "使えるのは SDL2 2.32、SDL2_image (PNG・JPG・BMP・GIF)、"
                + "SDL2_mixer (WAV・OGG・MP3・FLAC)、SDL2_ttf\n"
                + "- 描画はソフトウェア描画のみ。SDL_CreateRenderer(window, -1, 0) か "
                + "SDL_GetWindowSurface を使う。OpenGL・Vulkan・SDL_RENDERER_ACCELERATED の指定は不可\n"
                + "- ウィンドウは 640x360 程度までの小さめにする (毎フレーム全画素を転送するため)。"
                + "アプリ側が画面に合わせて拡大表示する\n"
                + "- 入力: 画面へのタッチはマウスの左ボタンとして届く。画面上のボタンは "
                + "矢印キー・Z・X・Space・Enter・Esc。これらだけで遊べる操作にする "
                + "(ほかのキーは画面キーボードから入力できるが、押しっぱなしはできない)\n"
                + "- SDL_QUIT を受け取ったら必ず終了する\n"
                + "- 実行ファイル 1 個だけを端末に入れるので、画像・音・フォントは別ファイルにせず、"
                + "ソースに埋め込むかプログラムで生成する。文字は端末のフォント "
                + "/system/fonts/Roboto-Regular.ttf (日本語は /system/fonts/NotoSansCJK-Regular.ttc) "
                + "を SDL2_ttf で開いてもよい\n"
                + "- ビルド例: " + t[5] + " -O2 -o game main.c $(pkg-config --cflags --libs sdl2 "
                + "SDL2_image SDL2_mixer SDL2_ttf) -lm\n"
                + ("arm64-v8a".equals(abi)
                ? "  GitHub Actions なら runs-on: ubuntu-24.04-arm (ARM64 のランナー) で "
                + "libsdl2-dev libsdl2-image-dev libsdl2-mixer-dev libsdl2-ttf-dev を apt で入れ、"
                + "普通の gcc でビルドするのが簡単\n"
                : "")
                + "\n";
    }

    static String build(String request, String abi, String device, String androidRelease,
                        int api, long pageSize, boolean root) {
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
                + (pageSize > 4096
                ? "- メモリのページは " + (pageSize / 1024) + "KB。ELF は " + (pageSize / 1024)
                + "KB 以上の境界で作ること (4KB 境界だと起動できない)。リンク時に "
                + "-Wl,-z,max-page-size=16384 を付ける。Rust は "
                + "RUSTFLAGS=\"-C link-arg=-Wl,-z,max-page-size=16384\"、zig cc も同じ -Wl 指定。"
                + "GNU の gcc と Go は既定で満たしている\n"
                : "")
                + "- " + (root ? "root 権限 (uid 0) のシェルから起動される"
                : "root 権限なしの一般アプリ権限で起動される (形式 1 か 3 のみ動く)") + "\n"
                + "- 文字だけのプログラムの画面は「出力が流れる欄」と「1 行ずつ送る入力欄」だけの"
                + "簡易端末 (TERM=dumb)\n"
                + "  - 標準出力へ文字を出し、標準入力から 1 行ずつ読む対話形式にする\n"
                + "  - curses、カーソル移動、画面消去、色などのエスケープシーケンス、"
                + "1 文字単位のキー入力 (Enter なしの操作)、GUI、画像や音は使えない\n"
                + "  - 入力待ちの文章は改行で終えるか、直後に flush する\n"
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
                + (root ? graphics(abi, t) : "")
                + "次のものは動かないので作らない: Windows の .exe (PE 形式)、別の CPU 用のバイナリ、"
                + "musl を動的リンクしたバイナリ、Android の APK、OpenGL や X11 を使うプログラム。\n"
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
