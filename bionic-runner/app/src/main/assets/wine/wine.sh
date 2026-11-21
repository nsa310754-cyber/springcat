# Bionic Runner: wine コマンドの本体。Windows のプログラム (64bit の exe) を、Box64 の上の Wine で動かす。
# アプリが起動のたびに作り直すので、書き換えても元に戻ります。
#   使い方: wine プログラム.exe [引数...]     wine cmd /c echo hello     wine notepad
# @…@ のところは、アプリがこの端末での場所に置き換える。
W=@WINE_DIR@
D="$W/dist"
P="$W/prefix"
SH="$W/shared"
N=@NATIVE_DIR@
B="$N/libbox64.so"
A=@GLIBC_ARM64@
X=@GLIBC_X86_64@
S=@BOX64_SUPPORT@
PROBE=@BOX64_PROBE@
XROOT=@LINUX_ROOTFS@
VERSION=@WINE_VERSION@

say() { printf '%s\n' "$*" >&2; }

if [ ! -f "$B" ]; then
    say 'Wine は使えません (この端末では Box64 が使えないためです。Wine は Box64 の上で動きます)'
    exit 1
fi
if [ ! -f "$D/usr/lib/wine/wine64" ] || [ "$(cat "$D/BR-VERSION" 2>/dev/null)" != "$VERSION" ]; then
    say 'Wine が入っていません。X11 の画面の「追加機能」→「Windows のプログラム (Wine)」の'
    say '「ダウンロード」で入れてください (コマンドなら x11 addons で画面が開きます)'
    exit 1
fi
if [ "$(id -u)" = 0 ]; then
    # root のシェルからは wine コマンド (関数) が、アプリの権限に戻してからここを呼ぶ
    say 'Wine は root では動かしません (アプリの普通の権限で動かします)。コマンドプロンプトで wine と打ってください'
    exit 1
fi
if [ $# -eq 0 ] || [ "$1" = --help ]; then
    say '使い方: wine プログラム.exe [引数...]'
    say '  例: wine cmd /c echo hello    wine notepad    wine winecfg'
    say '  wine --stop で、動いている Windows のプログラムをすべて止める'
    say '  wine --diag で、動かないときの原因を調べる'
    say '  wine --reset で、Windows の環境 (C: ドライブ) を消して作り直す'
    say '  wine --log で、前回の Windows の環境づくりの記録を Download/wine-log.txt に保存する'
    say "  D: ドライブ = $SH"
    say '  (Windows のプログラムとファイルを受け渡すときは、ここに置く)'
    exit 2
fi
if [ ! -f "$A/ld-linux-aarch64.so.1" ] || [ ! -f "$A/libbrshim.so" ] || [ ! -f "$X/ld-linux-x86-64.so.2" ] \
        || [ ! -f "$S/probe-x86_64" ]; then
    say 'Wine の準備ができていません。「追加機能」で Wine を入れ直してください'
    exit 1
fi

# 補助ライブラリが Wine に対応した版か (アプリの更新のあと、アプリを開くと入れ替わる)
if ! grep -q "arm64-v8a brshim @BRSHIM@" "${A%/lib}/VERSIONS.txt" 2>/dev/null; then
    say 'Wine の準備がまだです。アプリをいったん閉じて (タスクから消して) 開き直してから、もう一度試してください'
    exit 1
fi

# Box64 (x86_64 のプログラムを ARM64 で動かす変換器) で x86_64 のプログラムを動かす。
# root が無いので、禁止されたシステムコールで止められないよう補助ライブラリ (libbrshim.so) を先に読み込む。
# Box64 が自分を起動し直すとき (wineserver など) は、files/box64/ld (libbox64ld.so) が同じ形で起動する
box64() { "$N/libbrld.so" --preload "$A/libbrshim.so" --library-path "$A" "$B" "$@"; }

# この端末で Box64 が動くか (まだ確かめていなければ確かめる)
case $(head -n 1 "$PROBE" 2>/dev/null) in
    "ok $B") ;;
    *)
        if (box64 "$S/probe-x86_64") 2>/dev/null | grep -q box64-ok; then
            (echo "ok $B" > "$PROBE") 2>/dev/null
        else
            say 'Wine は使えません (この端末では Box64 が起動しませんでした)'
            exit 1
        fi ;;
esac

# Wine のための環境。TMPDIR は消す (Debian 版の Wine は TMPDIR があると強制終了する不具合があるため。
# 代わりに wineserver との連絡用のフォルダは $W/run の下に作る。アプリが Wine を入れるときに設定済み)
unset LD_LIBRARY_PATH LD_PRELOAD TMPDIR
# Box64 の設定ファイルは、Wine に見せる CPU の数だけを 64 から 4 に減らしたものを使う
# (CPU の数だけスレッドが作られ、メモリを多く使うため。スマホではメモリ不足でアプリごと止められる)
RC="$W/box64.box64rc"
if [ ! -f "$RC" ] || [ "$S/box64.box64rc" -nt "$RC" ]; then
    sed 's/^BOX64_MAXCPU=64/BOX64_MAXCPU=4/' "$S/box64.box64rc" > "$RC.new" 2>/dev/null && mv -f "$RC.new" "$RC"
fi
[ -f "$RC" ] || RC="$S/box64.box64rc"
export BOX64_NOBANNER=1 BOX64_LOG=0 BOX64_RCFILE="$RC" BOX64_MAXCPU=4
# Windows の DLL を「実行できる形」で読み込むのを Android が拒否したら、その印を外してやり直す
# (補助ライブラリ libbrshim.so が見る。Box64 は x86 の命令を変換して動かすので、この印は要らない)
export BR_DROP_EXEC=1
export BOX64_LD_LIBRARY_PATH="$D/usr/lib/x86_64-linux-gnu:$X:$S/x86_64"
# 次のライブラリは、ARM 用の本物 (端末には無い) を探さずに、最初から Wine と一緒に入れた x86_64 用を使う
# (探すと「Error initializing native …」が何行も出るため)
export BOX64_EMULATED_LIBS='libX11.so.6:libX11-xcb.so.1:libXext.so.6:libXrender.so.1:libXrandr.so.2:libXi.so.6:libXcursor.so.1:libXfixes.so.3:libXinerama.so.1:libXcomposite.so.1:libxcb.so.1:libXau.so.6:libXdmcp.so.6:libfreetype.so.6:libpng16.so.16:libz.so.1:libbz2.so.1.0:libbz2.so.1:liblzma.so.5:libbrotlidec.so.1:libbrotlicommon.so.1:libunwind.so.8:libdbus-1.so.3:libfontconfig.so.1:libgnutls.so.30:libXxf86vm.so.1:libGL.so.1:libEGL.so.1:libvulkan.so.1:libasound.so.2:libpulse.so.0:libudev.so.1:libusb-1.0.so.0:libgstreamer-1.0.so.0:libSDL2-2.0.so.0'
# winemenubuilder: Linux のメニューを作らせない。mscoree・mshtml: .NET (Mono) とブラウザ部品 (Gecko) を
# 入れようとしない (重く、ネットから取ろうとするため。必要なプログラムは動かない)
export WINEPREFIX="$P" WINEDEBUG=-all WINEDLLOVERRIDES='winemenubuilder.exe=d;mscoree=d;mshtml=d'
# Wine 付属の DLL (kernel32.dll など) の場所。Wine は自分の場所から探すが、念のため明示する
export WINEDLLPATH="$D/usr/lib/x86_64-linux-gnu/wine"
export LC_ALL=C.UTF-8 DISPLAY=:1
wine64() { box64 "$D/usr/lib/wine/wine64" "$@"; }

# 動かないときの調べ: 環境と、Wine と Box64 の詳しい記録を集めて、テキストファイルに保存する。
# 中身は端末の中に保存するだけで、どこにも送らない (送るかどうかは使う人が決める)
if [ "$1" = --diag ]; then
    OUT=/sdcard/Download/wine-diag.txt
    if ! touch "$OUT" 2>/dev/null; then OUT="$SH/wine-diag.txt"; fi
    echo "調べています (1〜3 分かかります)…"
    {
        echo "== Bionic Runner wine --diag $(date)"
        echo "== 利用者: $(id)"
        echo "== カーネル: $(uname -a)"
        echo "== Android: $(getprop ro.build.version.release 2>/dev/null) SDK $(getprop ro.build.version.sdk 2>/dev/null)  ページサイズ: $(getconf PAGESIZE 2>/dev/null)"
        grep -E '^(Seccomp|NoNewPrivs)' /proc/self/status
        echo "== 補助ライブラリ: $(grep brshim "${A%/lib}/VERSIONS.txt" 2>/dev/null)"
        ls -l "$A/libbrshim.so" "$N/libbox64.so" "$N/libbox64ld.so" "$N/libbrld.so" 2>&1
        echo "== ローダのリンク:"; ls -l "${PROBE%/probe}/ld" 2>&1
        echo "== 場所:"; ls -ldZ "$W" "$D" "$P" "$SH" "$W/run" "$W/run/$(id -u)" 2>&1
        echo "== Box64 の確認: $(head -n 1 "$PROBE" 2>/dev/null)"
        echo
        echo "======== 1. Wine (WINEDEBUG=+process,+loaddll,err+all) ========"
        (cd "$SH" && WINEDEBUG=err+all,+process,+loaddll,warn+module timeout 300 sh -c '
            "$0" --preload "$1" --library-path "$2" "$3" "$4" cmd /c echo ok' \
            "$N/libbrld.so" "$A/libbrshim.so" "$A" "$B" "$D/usr/lib/wine/wine64") 2>&1 | tail -n 300
        echo "(終了コード $?)"
        echo
        echo "======== 2. Box64 の記録 (BOX64_LOG=1) ========"
        (cd "$SH" && BOX64_LOG=1 BOX64_SHOWSEGV=1 BOX64_DLSYM_ERROR=1 WINEDEBUG=err+all timeout 300 sh -c '
            "$0" --preload "$1" --library-path "$2" "$3" "$4" cmd /c echo ok' \
            "$N/libbrld.so" "$A/libbrshim.so" "$A" "$B" "$D/usr/lib/wine/wine64") 2>&1 \
            | grep -av 'Error initializing native' | tail -n 600
        echo
        echo "======== 3. wineboot.log ========"
        tail -n 100 "$W/wineboot.log" 2>&1
    } > "$OUT" 2>&1
    grep -E '^== (利用者|Android|補助)' "$OUT"
    echo
    echo "詳しい記録を保存しました: $OUT ($(wc -l < "$OUT") 行)"
    echo 'このファイルを送ってもらえると、原因を調べられます (中に名前などの個人の情報は入れていません)'
    exit 0
fi

# 壊れた Windows の環境を消す (次に動かすときに作り直す。D: の中身は残す)
# 前回の Windows の環境づくりの記録を Download にまとめる (アプリが止められたあとに調べるため)
if [ "$1" = --log ]; then
    OUT=/sdcard/Download/wine-log.txt
    if ! touch "$OUT" 2>/dev/null; then OUT="$SH/wine-log.txt"; fi
    {
        echo "== Bionic Runner wine --log $(date)"
        echo "== 今のメモリ: $(grep -E '^(MemTotal|MemAvailable|SwapTotal|SwapFree)' /proc/meminfo | tr -s ' ' | tr '\n' ' ')"
        echo "======== メモリの記録 (環境づくりの間、5 秒ごと) ========"
        cat "$W/wineboot-mem.log" 2>&1
        echo "======== wineboot.log ========"
        grep -av 'Error initializing native' "$W/wineboot.log" 2>&1 | tail -n 300
    } > "$OUT" 2>&1
    echo "記録を保存しました: $OUT"
    exit 0
fi

if [ "$1" = --reset ]; then
    if rm -rf "$P" "$W/run"; then
        echo 'Windows の環境を消しました (次に動かすときに作り直します。D: の中身は残っています)'
        exit 0
    fi
    say '消せないファイルがあります (前の版が root モードで作ったものです)。'
    say 'root モード (/su) にして wine --reset と打ってください'
    exit 1
fi

mkdir -p "$W/run/$(id -u)" "$SH" 2>/dev/null
chmod 700 "$W/run/$(id -u)" 2>/dev/null
# 前の版が root モードで作ったファイルが残っていると、普通の権限では書き換えられない
t="$W/run/$(id -u)/.br-write-test"
if ! touch "$t" 2>/dev/null; then
    say 'Wine の作業用のフォルダに書き込めません (前の版が root モードで作ったファイルが残っています)。'
    say 'root モード (/su) にして wine --reset と打ってから、もう一度試してください'
    exit 1
fi
rm -f "$t"

if [ "$1" = --stop ]; then
    box64 "$D/usr/lib/wine/wineserver" -k 2>/dev/null
    echo 'Windows のプログラムをすべて止めました'
    exit 0
fi

# 初めて Windows のプログラムを動かすときだけ、1 回確かめる
if [ ! -f "$W/agreed" ]; then
    echo 'Windows のプログラムはアプリの権限で動きます。信頼できないものは動かさないでください。'
    printf '続けますか？ (続けるなら y と入力して Enter): '
    read -r ans || ans=
    case $ans in
        y | Y | yes | はい) : > "$W/agreed" ;;
        *) echo 'やめました'; exit 1 ;;
    esac
fi

# 動かすものの種類を調べる (ファイルなら exe の中身、名前だけなら Wine 付属のプログラム)
u2() { od -An -tu2 -j "$2" -N 2 "$1" 2>/dev/null | tr -d ' \n'; }
u4() { od -An -tu4 -j "$2" -N 4 "$1" 2>/dev/null | tr -d ' \n'; }
GUI=
if [ -f "$1" ]; then
    if [ "$(u2 "$1" 0)" = 23117 ]; then # 先頭が MZ
        o=$(u4 "$1" 60)
        if [ -n "$o" ] && [ "$(u4 "$1" "$o")" = 17744 ]; then # PE の印
            case $(u2 "$1" $((o + 4))) in
                34404) ;; # x86_64 (64bit)
                332) say '32bit の exe はまだ動きません (動くのは 64bit の exe だけです)'; exit 1 ;;
                43620) say 'ARM64 用の exe は動きません (動くのは x86_64 用の 64bit の exe だけです)'; exit 1 ;;
                *) say 'この exe の種類 (CPU) には対応していません'; exit 1 ;;
            esac
            [ "$(u2 "$1" $((o + 92)))" = 2 ] && GUI=1 # ウィンドウを出すプログラム
        fi
    fi
    # 作業フォルダを exe のあるフォルダにする (同じフォルダの DLL やデータを見つけられるように)
    f=$(cd "$(dirname "$1")" 2>/dev/null && pwd)/$(basename "$1")
    [ -n "${f%/*}" ] && cd "${f%/*}" 2>/dev/null
    shift
    set -- "$f" "$@"
else
    name=$(basename "$1" | tr 'A-Z' 'a-z')
    case ${name%.exe} in
        cmd | reg | whoami | hostname | ipconfig | ping | xcopy | where | tasklist | taskkill | attrib \
        | find | findstr | more | expand | cscript | net | sc | systeminfo | fc | timeout | schtasks \
        | wineboot | winepath | wmic | netstat | robocopy | sort | icacls | chcp | ver) ;;
        *) GUI=1 ;;
    esac
    # Windows のコマンドは D: ドライブ (共有フォルダ) から始める
    cd "$SH" 2>/dev/null
fi

# 前に作った Windows の環境 (C: ドライブ) が無ければ作る (初回だけ)。
# 作ったはずなのに中身 (kernel32.dll) が無いときは、途中で失敗した環境なので作り直す
K="$P/drive_c/windows/system32/kernel32.dll"
if [ -f "$P/.br-ready" ] && [ ! -e "$K" ]; then
    echo '前に作った Windows の環境が壊れているので、作り直します'
fi
if [ ! -f "$P/.br-ready" ]; then
    echo 'Windows の環境 (C: ドライブ) を作っています。初回だけで、数分かかります…'
    if ! rm -rf "$P" 2>/dev/null; then
        say '前の Windows の環境を消せません (前の版が root モードで作ったファイルが残っています)。'
        say 'root モード (/su) にして wine --reset と打ってから、もう一度試してください'
        exit 1
    fi
    if ! mkdir -p "$P/drive_c/windows/system32"; then
        say "フォルダを作れませんでした: $P (利用者: $(id -u))"
        exit 1
    fi
    # user32.dll などが使う zlib1.dll を先に置く (Debian の Wine は別のパッケージから取るため)
    if ! cat "$D/usr/x86_64-w64-mingw32/lib/zlib1.dll" > "$P/drive_c/windows/system32/zlib1.dll"; then
        say "zlib1.dll を置けませんでした。wine --diag の結果を送ってください"
        exit 1
    fi
    LOG="$W/wineboot.log"
    MEM="$W/wineboot-mem.log"
    : > "$MEM"
    # 優先度を下げて動かす (端末の操作が固まりにくいように)
    (unset DISPLAY; WINEDEBUG=err+all nice -n 10 sh -c \
        '"$0" --preload "$1" --library-path "$2" "$3" "$4" wineboot --init' \
        "$N/libbrld.so" "$A/libbrshim.so" "$A" "$B" "$D/usr/lib/wine/wine64" > "$LOG" 2>&1) &
    pid=$!
    t=0
    while kill -0 "$pid" 2>/dev/null; do
        sleep 5
        t=$((t + 5))
        [ $((t % 15)) = 0 ] && echo "  作成中… ($t 秒)"
        # メモリの記録 (アプリごと止められても残るように、少しずつ書き足す)
        {
            echo "== $t 秒: $(grep -E '^(MemAvailable|SwapFree)' /proc/meminfo | tr -s ' ' | tr '\n' ' ')"
            ps -o PID,RSS,NAME 2>/dev/null | grep -i 'wine\|box64\|brld\|\.exe'
        } >> "$MEM" 2>&1
    done
    wait "$pid"
    box64 "$D/usr/lib/wine/wineserver" -w 2>/dev/null
    if [ ! -e "$K" ] || [ ! -f "$P/system.reg" ]; then
        say 'Windows の環境を作れませんでした。Wine の記録 (最後の部分):'
        grep -v '^\[BOX64\]' "$LOG" | tail -n 15 >&2
        say "(全体は $LOG。もう一度実行すると作り直します)"
        rm -rf "$P" 2>/dev/null
        exit 1
    fi
    # Wine 付属のプログラム (DLL など) は、同じ中身のコピーの代わりにリンクにする (約 700MB → 約 20MB)
    WD="$D/usr/lib/x86_64-linux-gnu/wine/x86_64-windows"
    find "$P/drive_c/windows" -type f | while IFS= read -r f; do
        n=${f##*/}
        [ -f "$WD/$n" ] && cmp -s "$f" "$WD/$n" && ln -sf "$WD/$n" "$f"
    done
    : > "$P/.br-ready"
    echo 'Windows の環境を作りました'
fi

# ドライブの割り当て: C: のほかは、共有フォルダを D: にするだけ。
# Wine のふつうの設定の Z: (端末全体) は作らない (毎回確かめる。Wine が作り直すことがあるため)
rm -f "$P/dosdevices/z:" "$P/dosdevices/z::"
ln -sfn "$SH" "$P/dosdevices/d:"

# ウィンドウを出すプログラムなら、X11 の画面を開いて X サーバーの準備ができるのを待つ
if [ -n "$GUI" ]; then
    xok() {
        [ -S "$XROOT/tmp/.X11-unix/X1" ] || return 1
        xp=$(tr -dc 0-9 < "$XROOT/tmp/.X1-lock" 2>/dev/null)
        [ -n "$xp" ] && kill -0 "$xp" 2>/dev/null
    }
    printf '\036BRX11|open\037'
    if ! xok; then
        echo 'X11 の画面 (X サーバー) の準備を待っています…'
        t=0
        while ! xok; do
            sleep 2
            t=$((t + 2))
            if [ $t -ge 600 ]; then
                say 'X サーバーが起動しませんでした。X11 の画面を開いて確かめてください'
                exit 1
            fi
        done
    fi
    echo 'ウィンドウは X11 の画面に出ます'
fi

exec "$N/libbrld.so" --preload "$A/libbrshim.so" --library-path "$A" "$B" "$D/usr/lib/wine/wine64" "$@"
