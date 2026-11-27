package site.springcat.bionicrunner;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Linux 環境の「追加機能」。利用者が一覧から選んで「ダウンロード」を押したものだけを、
 * Alpine Linux の公式の配布元から apk で入れる (アプリには同梱しない)。
 *
 * 入れたもののうち画面を持つアプリは、X11 の画面の背景をタップしたときのメニュー (twm) に出る。
 */
final class LinuxAddons {

    static final class Addon {
        final String id, title, description;
        /** apk add するもの */
        final String[] packages;
        /** ダウンロードの大きさの目安 (MB) */
        final int megabytes;
        /** X11 のメニューに出す名前と起動コマンド (無ければ null) */
        final String menuLabel, command;
        /** 入れたあとに中で実行する設定 (無ければ null) */
        final String after;
        /** 消すときに一緒に消す設定ファイル (rootfs の中の場所。無ければ null) */
        final String afterRemove;
        /** プログラミング言語か (一覧で「プログラミング言語」の見出しの下にまとめる) */
        final boolean language;
        /** 使い方の例 (コマンドプロンプトで打つもの。無ければ null) */
        final String usage;

        Addon(String id, String title, String description, String packages, int megabytes,
              String menuLabel, String command, String after, String afterRemove) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.packages = packages.split(" ");
            this.megabytes = megabytes;
            this.menuLabel = menuLabel;
            this.command = command;
            this.after = after;
            this.afterRemove = afterRemove;
            this.language = false;
            this.usage = null;
        }

        /** プログラミング言語 */
        Addon(String id, String title, String description, String packages, int megabytes, String usage,
              String after, String afterRemove) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.packages = packages.split(" ");
            this.megabytes = megabytes;
            this.menuLabel = null;
            this.command = null;
            this.after = after;
            this.afterRemove = afterRemove;
            this.language = true;
            this.usage = usage;
        }
    }

    private static Addon lang(String id, String title, String description, String packages, int megabytes,
                              String usage) {
        return new Addon(id, title, description, packages, megabytes, usage, null, null);
    }

    /**
     * .NET は、使った情報を Microsoft に送る仕組み (テレメトリ) が初めは有効なので、切ってから起動する
     * スクリプトを /usr/local/bin/dotnet に置く (PATH で /usr/bin より先に見つかる)
     */
    private static final String DOTNET_WRAPPER = "/usr/local/bin/dotnet";

    /**
     * Alpine の OpenJDK は java だけを /usr/bin に置き、javac などは JDK の場所にしかないので、
     * /usr/local/bin にリンクを作る (suffix は名前の後ろに付ける文字。2 つの版を両方入れても分けられるように)
     */
    private static String jdkLinks(String version, String suffix) {
        StringBuilder sb = new StringBuilder("mkdir -p /usr/local/bin");
        for (String t : JDK_TOOLS) {
            sb.append(" && ln -sf /usr/lib/jvm/java-").append(version).append("-openjdk/bin/").append(t)
                    .append(" /usr/local/bin/").append(t).append(suffix);
        }
        return sb.toString();
    }

    private static String jdkLinkFiles(String suffix) {
        StringBuilder sb = new StringBuilder();
        for (String t : JDK_TOOLS) sb.append(sb.length() > 0 ? " " : "").append("/usr/local/bin/").append(t).append(suffix);
        return sb.toString();
    }

    private static final String[] JDK_TOOLS = {"java", "javac", "jar", "jshell", "javadoc"};


    /** Chromium は proot の中ではサンドボックス (名前空間による隔離) を使えないので、外して起動する設定 */
    private static final String CHROMIUM_CONF = "/etc/chromium/zz-bionic-runner.conf";

    static final Addon[] ALL = {
            new Addon("jpfont", "日本語フォント (Noto CJK)",
                    "X のアプリで日本語の文字を表示できるようにします。ブラウザなどで日本語のページを見るなら、"
                            + "先に入れてください。",
                    "font-noto-cjk", 75, null, null, "fc-cache -f >/dev/null 2>&1 || true", null),
            new Addon("chromium", "Chromium (ブラウザ)",
                    "Google Chrome の元になっているオープンソースのブラウザ。proot の中ではブラウザの"
                            + "サンドボックス (ページごとの隔離) が使えないため、外した状態 (--no-sandbox) で動きます。"
                            + "信用できないサイトは開かないでください。日本語のページを見るなら「日本語フォント」も入れてください。",
                    "chromium font-noto", 230, "Chromium", "chromium",
                    "mkdir -p /etc/chromium && printf '%s\\n' '# Bionic Runner: proot ではサンドボックスが使えない'"
                            + " 'CHROMIUM_FLAGS=\"$CHROMIUM_FLAGS --no-sandbox --disable-dev-shm-usage --disable-gpu"
                            + " --no-first-run --password-store=basic\"' > " + CHROMIUM_CONF,
                    CHROMIUM_CONF),
            new Addon("firefox", "Firefox (ブラウザ)",
                    "Mozilla のブラウザ。日本語のページを見るなら「日本語フォント」も入れてください。",
                    "firefox font-noto", 160, "Firefox", "firefox", null, null),
            new Addon("xfce", "Xfce デスクトップ",
                    "タスクバーやスタートメニューのある、Windows に近いデスクトップ。入れると、X11 の画面が"
                            + "この画面で始まるようになります (次に X を起動したとき)。",
                    "xfce4 xfce4-terminal dbus-x11 adwaita-icon-theme", 165, null, null, null, null),
            new Addon("mousepad", "Mousepad (テキストエディタ)",
                    "メモ帳のような、軽いテキストエディタ。",
                    "mousepad", 21, "Mousepad (editor)", "mousepad", null, null),
            new Addon("geany", "Geany (プログラミング用エディタ)",
                    "色分け表示やビルドの機能が付いた、プログラムを書くためのエディタ。",
                    "geany", 21, "Geany (IDE)", "geany", null, null),
            new Addon("pcmanfm", "PCManFM (ファイルマネージャ)",
                    "Linux 環境の中のファイルを、エクスプローラーのように見て操作します。/sdcard が端末の"
                            + "内部ストレージです。",
                    "pcmanfm", 17, "PCManFM (files)", "pcmanfm", null, null),
            new Addon("gimp", "GIMP (画像編集)",
                    "写真の加工や絵を描くための、多機能な画像編集ソフト。",
                    "gimp", 96, "GIMP", "gimp", null, null),
            new Addon("libreoffice", "LibreOffice (オフィス)",
                    "文書・表計算・プレゼンテーション。Word や Excel のファイルも開けます。大きいので時間がかかります。",
                    "libreoffice", 320, "LibreOffice", "libreoffice", null, null),
            new Addon("vlc", "VLC (動画)",
                    "動画や音楽を再生します。音は出ません (Linux 環境から端末のスピーカーへはつながっていません)。",
                    "vlc", 91, "VLC", "vlc", null, null),
            new Addon("mesademos", "3D の試し (glxgears)",
                    "回る歯車で 3D の描画を試します (CPU で描くので速くはありません)。",
                    "mesa-demos", 13, "glxgears (3D)", "glxgears", null, null),
            new Addon("thunderbird", "Thunderbird (メール)",
                    "Mozilla のメールソフト。Gmail などのアカウントを設定して読み書きできます。日本語のメールを読むなら"
                            + "「日本語フォント」も入れてください。",
                    "thunderbird font-noto", 125, "Thunderbird (mail)", "thunderbird", null, null),
            new Addon("evince", "Evince (PDF を見る)",
                    "PDF を開いて読む、軽いビューアー。",
                    "evince", 32, "Evince (PDF)", "evince", null, null),
            new Addon("ristretto", "Ristretto (画像を見る)",
                    "写真や画像を開いて見る、軽いビューアー。",
                    "ristretto", 12, "Ristretto (images)", "ristretto", null, null),
            new Addon("krita", "Krita (お絵描き)",
                    "ペンや筆で絵を描くための、本格的なお絵描きソフト。大きいので時間がかかります。",
                    "krita", 155, "Krita (paint)", "krita", null, null),
            new Addon("inkscape", "Inkscape (ベクター画像)",
                    "ロゴや図など、拡大してもぼやけない絵 (SVG) を作るソフト。",
                    "inkscape", 48, "Inkscape (SVG)", "inkscape", null, null),
            new Addon("xournalpp", "Xournal++ (手書きノート・PDF への書き込み)",
                    "手書きでノートを取ったり、PDF に書き込んだりできます。",
                    "xournalpp", 26, "Xournal++ (notes)", "xournalpp", null, null),
            new Addon("abiword", "AbiWord (文書・軽い)",
                    "Word に近い文書を作る、軽いワープロ。LibreOffice より小さく、すぐ起動します。",
                    "abiword", 20, "AbiWord (writer)", "abiword", null, null),
            new Addon("gnumeric", "Gnumeric (表計算・軽い)",
                    "Excel に近い表計算ソフト。LibreOffice より小さく、すぐ起動します。",
                    "gnumeric", 27, "Gnumeric (sheet)", "gnumeric", null, null),
            new Addon("galculator", "Galculator (電卓)",
                    "関数電卓にもなる電卓。",
                    "galculator", 11, "Galculator (calc)", "galculator", null, null),
            new Addon("keepassxc", "KeePassXC (パスワード管理)",
                    "パスワードを暗号化したファイルにまとめて管理します。ファイルは /sdcard に置けば Android 側からも見えます。",
                    "keepassxc", 47, "KeePassXC (passwords)", "keepassxc", null, null),
            new Addon("filezilla", "FileZilla (ファイル転送)",
                    "FTP や SFTP で、サーバーとファイルをやり取りします。",
                    "filezilla", 35, "FileZilla (FTP)", "filezilla", null, null),
            new Addon("xarchiver", "Xarchiver (圧縮・解凍)",
                    "zip・tar.gz・7z などの中身を見て、取り出したり作ったりします。",
                    "xarchiver 7zip", 13, "Xarchiver (archives)", "xarchiver", null, null),
            new Addon("thunar", "Thunar (ファイルマネージャ)",
                    "Xfce のファイルマネージャ。PCManFM より機能が多めです。/sdcard が端末の内部ストレージです。",
                    "thunar", 13, "Thunar (files)", "thunar", null, null),
            new Addon("dosbox", "DOSBox (昔の PC のゲーム)",
                    "MS-DOS の昔のゲームやソフトを動かします。音は出ません。ゲームのファイルは /sdcard に置き、"
                            + "DOSBox の中で mount c /sdcard/dos のようにして使います。",
                    "dosbox", 23, "DOSBox (DOS games)", "dosbox", null, null),
            new Addon("cli", "端末の道具 (neovim・htop・tmux)",
                    "コマンドで使う道具: 文字を書く neovim (nvim)、動いているものを見る htop、端末を分ける tmux。"
                            + "コマンドプロンプトから linux nvim のように使います。",
                    "neovim htop tmux", 9, null, null, null, null),
            new Addon("xeyes", "xeyes",
                    "マウスの位置を目で追う、小さな X のアプリ。動作確認用。",
                    "xeyes", 1, "xeyes", "xeyes", null, null),

            // ---------------------------------------------------------------- プログラミング言語
            lang("python", "Python 3 と pip",
                    "Python と、そのライブラリを入れる pip。tkinter で窓を出すプログラムも動きます。",
                    "python3 py3-pip python3-tkinter", 14, "linux python3 hello.py"),
            lang("devtools", "C / C++ (gcc・make・git)",
                    "C / C++ のコンパイラと、make・git など。Linux 用のプログラムを中でビルドできます。"
                            + "ほかの言語 (Nim・Haskell など) が裏で使うこともあります。",
                    "build-base git", 89, "linux gcc hello.c -o hello && linux ./hello"),
            lang("clang", "Clang / LLVM (C・C++)",
                    "gcc とは別の C / C++ のコンパイラ (LLVM)。エラーの説明が分かりやすいことで知られています。",
                    "clang22 lld", 48, "linux clang hello.c -o hello"),
            lang("nodejs", "Node.js と npm (JavaScript)",
                    "JavaScript を動かす Node.js と、ライブラリを入れる npm。",
                    "nodejs npm", 25, "linux node app.js"),
            lang("typescript", "TypeScript (tsc)",
                    "型の付いた JavaScript。tsc で JavaScript に直して、Node.js で動かします。",
                    "typescript nodejs", 27, "linux tsc app.ts && linux node app.js"),
            lang("deno", "Deno (TypeScript・JavaScript)",
                    "TypeScript をそのまま動かせる、Node.js の作者による実行環境。",
                    "deno", 45, "linux deno run app.ts"),
            new Addon("openjdk", "Java (OpenJDK 21)",
                    "Java のプログラムを作ったり動かしたりします (java・javac・jar・jshell)。大きいので時間がかかります。",
                    "openjdk21", 160, "linux javac Hello.java && linux java Hello",
                    jdkLinks("21", ""), jdkLinkFiles("")),
            new Addon("openjdk25", "Java (OpenJDK 25・新しい版)",
                    "Java の新しい長期サポート版。21 と分けられるよう、コマンドの名前に 25 が付きます (java25・javac25・"
                            + "jshell25 など)。java25 Hello.java のように、そのまま動かすこともできます。",
                    "openjdk25", 178, "linux java25 Hello.java",
                    jdkLinks("25", "25"), jdkLinkFiles("25")),
            lang("javabuild", "Maven と Gradle (Java のビルド)",
                    "Java のプロジェクトをまとめてビルドする道具。Java も一緒に入ります。とても大きいです。",
                    "maven gradle", 439, "linux mvn package / linux gradle build"),
            lang("clojure", "Clojure (Java の上の Lisp)",
                    "Java の上で動く、Lisp の仲間の関数型言語。",
                    "clojure", 151, "linux clojure"),
            lang("go", "Go 言語",
                    "Go のプログラムを作ったり動かしたりします (go build・go run)。",
                    "go", 114, "linux go run hello.go"),
            lang("rust", "Rust (rustc・cargo)",
                    "速くて安全なプログラムを作る言語。cargo でプロジェクトを作ってビルドします。大きいです。",
                    "rust cargo", 142, "linux cargo new app && cd app && linux cargo run"),
            new Addon("dotnet", ".NET 10 SDK (C#・F#)",
                    "C# や F# のプログラムを作ったり動かしたりします。使った情報を送る仕組み (テレメトリ) は切って入れます。"
                            + "とても大きいです。",
                    "dotnet10-sdk", 221, "linux dotnet new console -o app && cd app && linux dotnet run",
                    "mkdir -p /usr/local/bin && printf '%s\\n' '#!/bin/sh' '# Bionic Runner: 使った情報を送る仕組みを切る'"
                            + " 'export DOTNET_CLI_TELEMETRY_OPTOUT=1 DOTNET_NOLOGO=1 DOTNET_SKIP_FIRST_TIME_EXPERIENCE=1'"
                            + " 'exec /usr/bin/dotnet \"$@\"' > " + DOTNET_WRAPPER + " && chmod 755 " + DOTNET_WRAPPER,
                    DOTNET_WRAPPER),
            lang("ruby", "Ruby と Bundler",
                    "書きやすさを大事にした言語。Bundler でライブラリ (gem) をそろえます。",
                    "ruby ruby-bundler", 7, "linux ruby hello.rb"),
            lang("php", "PHP 8.5",
                    "Web でよく使われる言語。php -S 0.0.0.0:8000 で、試しの Web サーバーにもなります。",
                    "php85 php85-phar php85-openssl php85-mbstring", 5, "linux php85 hello.php"),
            lang("perl", "Perl",
                    "文字の処理が得意な言語。",
                    "perl", 10, "linux perl hello.pl"),
            lang("raku", "Raku (Rakudo)",
                    "Perl の後継として作られた言語。",
                    "rakudo", 17, "linux raku hello.raku"),
            lang("lua", "Lua 5.4 と LuaRocks",
                    "小さくて速い、組み込み向けの言語。LuaRocks でライブラリを入れます。",
                    "lua5.4 luarocks5.4", 1, "linux lua5.4 hello.lua"),
            lang("micropython", "MicroPython",
                    "マイコン向けの小さな Python。",
                    "micropython", 1, "linux micropython hello.py"),
            lang("tcl", "Tcl / Tk",
                    "簡単な言語と、窓を出す Tk (X11 の画面に出ます)。",
                    "tcl tk", 2, "linux tclsh hello.tcl / linux wish"),
            lang("zig", "Zig",
                    "C の代わりを目指す、小さくて速い言語。C のコンパイラとしても使えます。",
                    "zig", 135, "linux zig run hello.zig"),
            lang("nim", "Nim",
                    "Python に似た書き方で、C に直して速く動く言語。gcc も使います。",
                    "nim", 6, "linux nim c -r hello.nim"),
            lang("dlang", "D 言語 (LDC)",
                    "C++ の後を目指した言語。LLVM を使うコンパイラ LDC。",
                    "ldc", 82, "linux ldc2 hello.d"),
            lang("ada", "Ada (GNAT)",
                    "安全性が大事な分野 (航空・鉄道など) で使われる言語。",
                    "gcc-gnat", 111, "linux gnatmake hello.adb"),
            lang("fortran", "Fortran (gfortran)",
                    "科学技術計算で長く使われている言語。",
                    "gfortran", 77, "linux gfortran hello.f90 -o hello"),
            lang("cobol", "COBOL (GnuCOBOL)",
                    "事務処理で長く使われている言語。",
                    "gnucobol", 64, "linux cobc -x hello.cob"),
            lang("vala", "Vala",
                    "C# に似た書き方で、GNOME のアプリを作る言語。",
                    "vala", 103, "linux valac hello.vala"),
            lang("haxe", "Haxe",
                    "1 つのソースから、JavaScript・C++ などいろいろな言語に直せる言語。",
                    "haxe", 25, "linux haxe --run Main"),
            lang("hare", "Hare",
                    "小さくて分かりやすい、システム向けの新しい言語。",
                    "hare", 62, "linux hare run hello.ha"),
            lang("haskell", "Haskell (GHC・cabal)",
                    "純粋な関数型言語。とても大きい (入れたあと約 1.7GB) ので、空きに気をつけてください。",
                    "ghc cabal", 402, "linux runghc hello.hs"),
            lang("ocaml", "OCaml と opam",
                    "型推論のある関数型言語。opam でライブラリを入れます。",
                    "ocaml opam", 130, "linux ocaml hello.ml"),
            lang("idris", "Idris 2",
                    "型で正しさを確かめられる、関数型言語。",
                    "idris2", 25, "linux idris2 hello.idr -x main"),
            lang("elixir", "Elixir (と Erlang)",
                    "Erlang の仕組みの上で動く、並行処理が得意な言語。Erlang も一緒に入ります。",
                    "elixir erlang28", 82, "linux elixir hello.exs"),
            lang("gleam", "Gleam (と Erlang)",
                    "Erlang の仕組みの上で動く、型の付いた言語。",
                    "gleam erlang28", 81, "linux gleam new app && cd app && linux gleam run"),
            lang("sbcl", "Common Lisp (SBCL)",
                    "Lisp の代表的な処理系。速いネイティブコードを作ります。",
                    "sbcl", 14, "linux sbcl --script hello.lisp"),
            lang("scheme", "Scheme (Guile)",
                    "小さな Lisp の仲間。GNU の Guile。",
                    "guile", 11, "linux guile hello.scm"),
            lang("racket", "Racket",
                    "Scheme から生まれた、教育や言語づくりに使われる言語。",
                    "racket", 43, "linux racket hello.rkt"),
            lang("r", "R (統計)",
                    "統計とデータ分析のための言語。",
                    "R", 43, "linux Rscript hello.R"),
            lang("octave", "GNU Octave (数値計算)",
                    "MATLAB に近い書き方の数値計算の言語。",
                    "octave", 125, "linux octave --no-gui"),
    };

    private LinuxAddons() {
    }

    /** Linux 環境に入っているもの (apk の記録 /lib/apk/db/installed から) */
    static Set<String> installedPackages(Context c) {
        Set<String> out = new HashSet<>();
        File db = new File(Linux.rootfs(c), "lib/apk/db/installed");
        if (!db.isFile()) return out;
        try (InputStream in = new FileInputStream(db);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("P:")) out.add(line.substring(2).trim());
            }
        } catch (IOException ignored) {
            // 読めなければ、何も入っていない扱い
        }
        return out;
    }

    static boolean installed(Addon a, Set<String> packages) {
        return packages.containsAll(Arrays.asList(a.packages));
    }

    /** 入れるときに中で実行するスクリプト */
    static String installScript(Addon a) {
        return "set -e\n"
                + "echo '>>> apk update'\n"
                + "apk --wait 600 update\n"
                + "echo '>>> apk add " + String.join(" ", a.packages) + "'\n"
                + "apk --wait 600 add " + String.join(" ", a.packages) + "\n"
                + (a.after == null ? "" : a.after + "\n")
                + "/bin/sh /usr/local/bin/br-twm-menu\n"
                + "echo '>>> 完了しました'\n";
    }

    /** 消すときに中で実行するスクリプト。ほかに使われていない関連のものも一緒に消える */
    static String removeScript(Addon a) {
        return "set -e\n"
                + "echo '>>> apk del " + String.join(" ", a.packages) + "'\n"
                + "apk --wait 600 del " + String.join(" ", a.packages) + "\n"
                + (a.afterRemove == null ? "" : "rm -f " + a.afterRemove + "\n")
                + "/bin/sh /usr/local/bin/br-twm-menu\n"
                + "echo '>>> 完了しました'\n";
    }

    /** X11 のメニューに出す候補 ("名前|コマンド" の行)。入っているものだけがメニューに出る */
    static String menuList() {
        StringBuilder sb = new StringBuilder();
        for (Addon a : ALL) {
            if (a.menuLabel != null) sb.append(a.menuLabel).append('|').append(a.command).append('\n');
        }
        return sb.toString();
    }
}
