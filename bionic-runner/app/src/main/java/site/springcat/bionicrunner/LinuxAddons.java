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
