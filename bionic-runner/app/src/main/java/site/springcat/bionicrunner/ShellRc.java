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
                + "unalias dir cls copy move ren del erase md rd type ver help 2>/dev/null\n"
                + "dir() { ls -la \"$@\"; }\n"
                + "cls() { printf '\\033[2J'; }\n"
                + "copy() { cp -R \"$@\"; }\n"
                + "move() { mv \"$@\"; }\n"
                + "ren() { mv \"$@\"; }\n"
                + "del() { rm \"$@\"; }\n"
                + "erase() { rm \"$@\"; }\n"
                + "md() { mkdir -p \"$@\"; }\n"
                + "rd() { rmdir \"$@\"; }\n"
                + "type() { cat \"$@\"; }\n"
                + "ver() { echo 'Bionic Runner コマンドプロンプト'; uname -srm; }\n"
                // アプリ同梱の YARA。追加済みのルールの置き場所は $BR_YARA_RULES
                + "yara() { \"$BR_NATIVE/libyara.so\" \"$@\"; }\n"
                // アプリ同梱の 7-Zip。数字で始まる名前は関数にできないシェルがあるので、別名で定義する
                + "alias 7z='\"$BR_NATIVE/lib7zz.so\"' 7zz='\"$BR_NATIVE/lib7zz.so\"' 2>/dev/null\n"
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
                + "  7z x ファイル     圧縮ファイルを解凍 (7z・rar・zip・cab・iso・exe など)\n"
                + "  7z a 名前.7z 対象 圧縮 (7z l で中身の一覧)\n"
                + "  yara ルール 対象  YARA ルールでファイルを調べる (フォルダの中すべては yara -r)\n"
                + "                    例: yara $BR_YARA_RULES/my.yar /sdcard/Download/app\n"
                + "  exit              シェルを終了 (「再起動」で起動し直せる)\n"
                + "区切りは \\ ではなく / です。ドライブ名 (C:) はありません。\n"
                + "Linux のコマンド (ls, cat, grep, ps, mount, pm, getprop など) もそのまま使えます。\n"
                + "BR_HELP_END\n"
                + "}\n";
    }
}
