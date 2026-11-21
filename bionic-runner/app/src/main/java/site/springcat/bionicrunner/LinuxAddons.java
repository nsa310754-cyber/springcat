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
        }
    }

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
            new Addon("python", "Python 3 と pip",
                    "Python と、そのライブラリを入れる pip。tkinter で窓を出すプログラムも動きます。",
                    "python3 py3-pip python3-tkinter", 20, null, null, null, null),
            new Addon("devtools", "開発ツール (gcc・make・git)",
                    "C / C++ のコンパイラと、git など。Linux 用のプログラムを中でビルドできます。",
                    "build-base git", 99, null, null, null, null),
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
            new Addon("nodejs", "Node.js と npm (JavaScript)",
                    "JavaScript を動かす Node.js と、ライブラリを入れる npm。",
                    "nodejs npm", 25, null, null, null, null),
            new Addon("openjdk", "OpenJDK 21 (Java)",
                    "Java のプログラムを作ったり動かしたりします (java・javac)。大きいので時間がかかります。",
                    "openjdk21", 160, null, null, null, null),
            new Addon("go", "Go 言語",
                    "Go のプログラムを作ったり動かしたりします (go build・go run)。",
                    "go", 114, null, null, null, null),
            new Addon("xeyes", "xeyes",
                    "マウスの位置を目で追う、小さな X のアプリ。動作確認用。",
                    "xeyes", 1, "xeyes", "xeyes", null, null),
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
