package site.springcat.bionicrunner;

/**
 * シェルの起動時に読み込ませる設定。Windows のコマンドプロンプトと同じ名前の命令を、
 * 対応する Linux コマンドを呼ぶ関数として定義する。
 */
final class ShellRc {

    private ShellRc() {
    }

    static String text() {
        return ""
                // シェルが同じ名前の別名 (alias) を持っていると関数の定義が壊れるので、先に外す
                + "unalias dir cls copy move ren del erase md rd type ver help 7z 7zz 2>/dev/null\n"
                // 前の版はこれらを関数にしていた。残っていると PATH の本物より優先されるので消す
                + "unset -f br yara 2>/dev/null\n"
                + "dir() { ls -la \"$@\"; }\n"
                // 画面を消す。ログ表示を消す合図 (AnsiLog.CLEAR、U+E001 の UTF-8) を出す。
                // ESC[2J を出すと、全画面で描くプログラムと見分けがつかないため
                + "cls() { printf '\\356\\200\\201'; }\n"
                + "copy() { cp -R \"$@\"; }\n"
                + "move() { mv \"$@\"; }\n"
                + "ren() { mv \"$@\"; }\n"
                + "del() { rm \"$@\"; }\n"
                + "erase() { rm \"$@\"; }\n"
                + "md() { mkdir -p \"$@\"; }\n"
                + "rd() { rmdir \"$@\"; }\n"
                + "type() { cat \"$@\"; }\n"
                + "ver() { echo 'Bionic Runner コマンドプロンプト'; uname -srm; }\n"
                // root でコマンドを実行する。シェル自体が root なら、そのまま実行するだけ。
                // asroot は 1 行のコマンド文字列を、sudo は「コマンド 引数...」を受け取る。
                // BR_SU は su の場所、BR_SUDO_PRE は root 側のシェルに同じ環境を作るための前置き。
                // 引数を単一引用符で囲み直すための部品 (' を '\'' に置き換える)。置き換えの式の中に
                // 引用符を直接書くとシェルによって解釈が違うので、変数に入れて使う
                + "_BRQ=\"'\"; _BRR=\"'\\\\''\"\n"
                + "asroot() {\n"
                + "  if [ \"$(id -u)\" = 0 ]; then eval \"$1\"; return; fi\n"
                + "  if ! command -v \"${BR_SU:-su}\" >/dev/null 2>&1; then\n"
                + "    echo 'root を使えません (su が見つかりません)。「root 確認」で原因を表示できます' >&2\n"
                + "    return 126\n"
                + "  fi\n"
                + "  \"${BR_SU:-su}\" -c \"$BR_SUDO_PRE\n"
                + "cd '${PWD//$_BRQ/$_BRR}' 2>/dev/null\n"
                + "$1\"\n"
                + "}\n"
                + "sudo() {\n"
                + "  if [ $# -eq 0 ]; then echo '使い方: sudo コマンド [引数...]'; return 2; fi\n"
                // 別名で定義している同梱ツールは、root 側のシェルでも見つかるよう場所に直す
                + "  case \"$1\" in\n"
                + "    7z|7zz) shift; set -- \"$BR_NATIVE/lib7zz.so\" \"$@\";;\n"
                + "    yara) shift; set -- \"$BR_NATIVE/libyara.so\" \"$@\";;\n"
                + "  esac\n"
                + "  if [ \"$(id -u)\" = 0 ]; then \"$@\"; return; fi\n"
                + "  _c=\n"
                + "  for _a in \"$@\"; do _c=\"$_c '${_a//$_BRQ/$_BRR}'\"; done\n"
                + "  asroot \"$_c\"\n"
                + "}\n"
                // /run 用: ファイルの本当の場所を調べ、アプリが読める一時置き場へ写してから、アプリへ
                // 「これを実行して」と合図を送る (合図は画面には出ない制御文字で挟んである)。
                // $1 = モード (game / run / 空なら選んでもらう)、$2 = ファイル
                + "__br_run() {\n"
                + "  if [ ! -f \"$2\" ]; then echo \"見つかりません: $2\" >&2; return 1; fi\n"
                + "  _d=$(cd \"$(dirname \"$2\")\" 2>/dev/null && pwd -P) || return 1\n"
                + "  _f=\"$_d/$(basename \"$2\")\"\n"
                + "  mkdir -p \"$TMPDIR/br-run\" 2>/dev/null\n"
                + "  if ! cp \"$_f\" \"$TMPDIR/br-run/src\" 2>/dev/null; then\n"
                + "    echo \"読み込めませんでした: $_f\" >&2; return 1\n"
                + "  fi\n"
                + "  chmod 644 \"$TMPDIR/br-run/src\" 2>/dev/null\n"
                + "  printf '\\036BRRUN|%s|%s\\037' \"$1\" \"$_f\"\n"
                + "}\n"
                // Linux 環境 (Alpine Linux) に入る。引数があれば、そのコマンドを中で動かす。
                // 中身は proot (root 不要)。BR_LINUX などの場所はアプリが起動時に設定する。
                // root のシェルからは、アプリの権限に戻して動かす (中のファイルの持ち主を揃えるため)
                + "linux() {\n"
                + "  if [ ! -e \"$BR_LINUX/bin/busybox\" ]; then\n"
                + "    echo 'Linux 環境がまだありません。x11 と打つか、デスクトップの「X11」を開くと準備します' >&2\n"
                + "    return 1\n"
                + "  fi\n"
                + "  if [ \"$(id -u)\" = 0 ] && [ -n \"$BR_APP_UID\" ] && [ \"$BR_APP_UID\" != 0 ]; then\n"
                + "    _c=\n"
                + "    for _a in \"$@\"; do _c=\"$_c '${_a//$_BRQ/$_BRR}'\"; done\n"
                + "    \"${BR_SU:-su}\" \"$BR_APP_UID\" -c \"$BR_SUDO_PRE; linux $_c\"\n"
                + "    return\n"
                + "  fi\n"
                + "  [ $# -eq 0 ] && set -- /bin/sh -l\n"
                + "  set -- \"$BR_NATIVE/libproot.so\" --kill-on-exit --link2symlink --sysvipc -0 \\\n"
                + "    -r \"$BR_LINUX\" -w /root -b /dev -b /proc -b /sys -b /proc/self/fd:/dev/fd \\\n"
                + "    ${BR_LINUX_SD:+-b \"$BR_LINUX_SD:/sdcard\"} \\\n"
                + "    /usr/bin/env -i HOME=/root USER=root LOGNAME=root TERM=\"${TERM:-xterm-256color}\" \\\n"
                + "    LANG=C.UTF-8 TMPDIR=/tmp DISPLAY=:1 \\\n"
                + "    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \\\n"
                + "    \"$@\"\n"
                + "  \"$@\"\n"
                + "}\n"
                // X11 の画面を開く (初回は Linux 環境と X サーバーを用意する)。x11 stop で X を終了
                + "x11() {\n"
                + "  case \"$1\" in\n"
                + "    stop) printf '\\036BRX11|stop\\037'; echo 'X サーバーを終了しました' ;;\n"
                + "    addons) printf '\\036BRX11|addons\\037'; echo 'Linux の追加機能の画面を開きます' ;;\n"
                + "    '') printf '\\036BRX11|open\\037'; echo 'X11 の画面を開きます。X のアプリは linux で中に入ってから起動します (例: linux xterm &)' ;;\n"
                + "    *) echo '使い方: x11 (画面を開く) / x11 addons (追加機能) / x11 stop (X を終了)' >&2; return 2 ;;\n"
                + "  esac\n"
                + "}\n"
                // C のソースから bionic 用・glibc 用の実行ファイルを作る (中身は bash のスクリプト)
                + "mkexe() {\n"
                + "  if [ -z \"$BR_MKEXE\" ] || [ ! -f \"$BR_MKEXE\" ]; then\n"
                + "    echo 'mkexe は 64bit の ARM 端末だけで使えます' >&2; return 1\n"
                + "  fi\n"
                + "  \"$BR_NATIVE/libbash.so\" \"$BR_MKEXE\" \"$@\"\n"
                + "}\n"
                + "help() {\n"
                + "cat <<'BR_HELP_END'\n"
                + "使える主な命令 (Windows 風の名前 = 実際に動くコマンド)\n"
                + "  dir [場所]        一覧を表示 (ls -la)\n"
                + "  cd 場所           フォルダを移動   例: cd /system   cd /sdcard\n"
                + "  type ファイル     中身を表示 (cat)\n"
                + "  copy 元 先        コピー (cp -R)\n"
                + "  move 元 先        移動 (mv)\n"
                + "  ren 元 新しい名前 名前の変更 (mv)\n"
                + "  del ファイル      削除 (rm)\n"
                + "  md フォルダ       フォルダを作る (mkdir -p)\n"
                + "  rd フォルダ       空のフォルダを消す (rmdir)\n"
                + "  cls               画面を消す\n"
                + "  ver               バージョンを表示\n"
                + "  /su               root モードに切り替える (シェルを root で起動し直す)\n"
                + "  /exsu             普通のモード (アプリの権限) に戻す\n"
                + "  /run ファイル     ソースをそのまま実行 (html js ts go py lua sh)。\n"
                + "                    後ろに game でゲームモード、run で実行モード (省くと選べる)\n"
                + "  br help           画面の上への表示・自動操作・強制終了 (br コマンドの一覧)\n"
                + "  sudo コマンド     そのコマンドだけ root 権限で実行 (root 化された端末のみ)\n"
                + "  bash              bash 5.3 (bash 用のスクリプトは bash ファイル名 か、そのまま実行)\n"
                + "  curl URL          通信 (HTTPS 可。curl -O URL でファイルを保存)\n"
                + "  7z x ファイル     圧縮ファイルを解凍 (7z・rar・zip・cab・iso・exe など)\n"
                + "  7z a 名前.7z 対象 圧縮 (7z l で中身の一覧)\n"
                + "  yara ルール 対象  YARA ルールでファイルを調べる (フォルダの中すべては yara -r)\n"
                + "                    例: yara $BR_YARA_RULES/my.yar /sdcard/Download/app\n"
                + "  mkexe [ソース.c]  C のソースから実行ファイルを作る (bionic 用・glibc 用)。mkexe help で使い方\n"
                + "  x11               X11 (Xサーバー) の画面を開く。初回は Linux 環境と X を用意する\n"
                + "  x11 addons        Linux の追加機能 (ブラウザなど) を選んでダウンロードする画面\n"
                + "  linux [コマンド]  Linux 環境 (Alpine) に入る / 中でコマンドを動かす。\n"
                + "                    X のアプリもここから: linux xterm &   linux apk add xeyes\n"
                + "  exit              シェルを終了 (「再起動」で起動し直せる)\n"
                + "区切りは \\ ではなく / です。ドライブ名 (C:) はありません。\n"
                + "Linux のコマンド (ls, cat, grep, ps, mount, pm, getprop など) もそのまま使えます。\n"
                + "BR_HELP_END\n"
                + "}\n";
    }
}
