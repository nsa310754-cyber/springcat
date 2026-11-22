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
# 32bit の exe のための部品 (試験中。追加機能の「32bit の exe も動かす」で入れる、i386 の Windows の DLL)
I32="$D/usr/lib/x86_64-linux-gnu/wine/i386-windows"
has32() { [ -f "$I32/ntdll.dll" ] && [ -f "$I32/kernel32.dll" ]; }
# .NET のプログラムのための部品 (追加機能の「.NET のプログラムも動かす (Wine Mono)」で入れる)
MONO="$D/usr/share/wine/mono/wine-mono-9.4.0"
hasmono() { [ -f "$MONO/bin/libmono-2.0-x86_64.dll" ]; }
# 32bit の exe を動かしたときの記録 (落ちたときに原因を調べるため。最後の 1 回ぶんだけ残す)
L32="$W/wow64-last.log"

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
if [ ! -e "$D/usr/lib/x86_64-linux-gnu/libfontconfig.so.1" ]; then
    say 'Wine の更新が必要です。X11 の画面の「追加機能」→「Windows のプログラム (Wine)」の「更新」を押してください'
    say '(足りない部品は約 0.5MB です。コマンドなら x11 addons で画面が開きます)'
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
    say '  32bit の exe は、追加機能の「32bit の exe も動かす (試験中)」を入れると動く'
    say '  .NET (C# など) のプログラムは、追加機能の「.NET のプログラムも動かす (Wine Mono)」を入れると動く'
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
export BOX64_EMULATED_LIBS='libX11.so.6:libX11-xcb.so.1:libXext.so.6:libXrender.so.1:libXrandr.so.2:libXi.so.6:libXcursor.so.1:libXfixes.so.3:libXinerama.so.1:libXcomposite.so.1:libxcb.so.1:libXau.so.6:libXdmcp.so.6:libfreetype.so.6:libpng16.so.16:libz.so.1:libbz2.so.1.0:libbz2.so.1:liblzma.so.5:libbrotlidec.so.1:libbrotlicommon.so.1:libunwind.so.8:libdbus-1.so.3:libfontconfig.so.1:libexpat.so.1:libgnutls.so.30:libXxf86vm.so.1:libGL.so.1:libEGL.so.1:libvulkan.so.1:libasound.so.2:libpulse.so.0:libudev.so.1:libusb-1.0.so.0:libgstreamer-1.0.so.0:libSDL2-2.0.so.0'
# winemenubuilder: Linux のメニューを作らせない。mscoree・mshtml: .NET (Mono) とブラウザ部品 (Gecko) を
# 入れようとしない (重く、ネットから取ろうとするため。必要なプログラムは動かない)
export WINEPREFIX="$P" WINEDEBUG=-all WINEDLLOVERRIDES='winemenubuilder.exe=d;mscoree=d;mshtml=d'
# Wine Mono を入れてあれば .NET を使えるようにする (Windows の環境づくりの間だけは止めたまま。止めないと、
# Wine が自分で Mono をネットから取ってこようとする)
hasmono && export WINEDLLOVERRIDES='winemenubuilder.exe=d;mshtml=d'
# Wine 付属の DLL (kernel32.dll など) の場所。Wine は自分の場所から探すが、念のため明示する
export WINEDLLPATH="$D/usr/lib/x86_64-linux-gnu/wine"
export DISPLAY=:1
# 文字コード: ふだんは C.UTF-8 (Windows の文字コードは英語 (1252))。設定で「日本語の環境」を選ぶと
# ja_JP.UTF-8 にする (Windows の文字コードが日本語 (932) になり、日本語の環境を前提にしたプログラムが動く)。
# Android には glibc のロケールが無いので、アプリが置いた files/wine/locale を LOCPATH で教える
LANGMODE=c
if [ -f "$W/lang-ja" ] && [ -f "$W/locale/ja_JP.UTF-8/LC_CTYPE" ]; then
    # シェル自身 (mksh) はこのロケールを知らないので、警告が出ないよう捨てる (使うのは Wine だけ)
    export LOCPATH="$W/locale" LANG=ja_JP.UTF-8 LC_ALL=ja_JP.UTF-8 2>/dev/null
    LANGMODE=ja
else
    export LC_ALL=C.UTF-8
fi
# 日本語の字体: Linux の追加機能の「日本語フォント (Noto CJK)」が入っていれば、そのファイルへの
# リンクを fonts-ja に作って使う (コピーはしない)。抜いたら、リンクも消す
JF="$XROOT/usr/share/fonts/noto"
FJ="$W/fonts-ja"
mkdir -p "$FJ"
JPFONT=
for f in NotoSansCJK-Regular.ttc NotoSansCJK-Bold.ttc NotoSerifCJK-Regular.ttc NotoSerifCJK-Bold.ttc; do
    if [ -f "$JF/$f" ]; then
        [ "$(readlink "$FJ/$f" 2>/dev/null)" = "$JF/$f" ] || ln -sfn "$JF/$f" "$FJ/$f"
        JPFONT=1
    else
        rm -f "$FJ/$f"
    fi
done
# 字体の設定 (fontconfig)。日本語フォント (fonts-ja)、Wine 付属の字体、Android の字体を使う。
# fontconfig が無いと、Wine (10.0) は画面まわりの初期化の途中で落ちる (中身の無い関数を呼ぶ不具合) ため、
# fontconfig は Wine と一緒に入れてある
FC="$W/fonts.conf"
if [ ! -f "$FC" ] || ! grep -q "br-fonts 2" "$FC" 2>/dev/null || ! grep -q "$D/usr/share/wine/fonts" "$FC" 2>/dev/null; then
    mkdir -p "$W/fontcache"
    # ヒアドキュメント (<<) は使わない (Android には一時ファイルを置く /tmp が無く、空のファイルになるため)
    printf '%s\n' '<?xml version="1.0"?>' '<!DOCTYPE fontconfig SYSTEM "urn:fontconfig:fonts.dtd">' \
        '<!-- br-fonts 2 -->' '<fontconfig>' "  <dir>$FJ</dir>" "  <dir>$D/usr/share/wine/fonts</dir>" \
        '  <dir>/system/fonts</dir>' '  <dir>/product/fonts</dir>' "  <cachedir>$W/fontcache</cachedir>" \
        '</fontconfig>' > "$FC"
fi
export FONTCONFIG_FILE="$FC"
# 日本語の字体が無いときの案内 (表示はするが、止めない)
jpnotice() {
    [ -n "$JPFONT" ] && return
    if [ -f /system/fonts/NotoSansCJK-Regular.ttc ]; then
        say '日本語を表示するには、追加機能の「日本語フォント」を入れてください (今は Android の字体で代わりに表示します)'
    else
        say '日本語を表示するには、追加機能の「日本語フォント」を入れてください (入れるまでは日本語が□になります)'
    fi
    say '(X11 の画面の「追加機能」→「日本語フォント (Noto CJK)」。コマンドなら x11 addons で画面が開きます)'
}
wine64() { box64 "$D/usr/lib/wine/wine64" "$@"; }

# 安全装置: 空きメモリが少ない状態が続いたら、Wine をすべて止める
# (Box64 の上の Windows のプログラムが増えすぎると、端末が固まり、アプリごと止められるため)。
# $1 = 見張る相手のプロセス番号。その相手が終わったら見張りも終わる
LOWMEM=250000 # kB
memfree() { sed -n 's/^MemAvailable: *\([0-9]*\).*/\1/p' /proc/meminfo; }
guard() {
    low=0
    while kill -0 "$1" 2>/dev/null; do
        sleep 2
        m=$(memfree)
        if [ -n "$m" ] && [ "$m" -lt "$LOWMEM" ]; then low=$((low + 1)); else low=0; fi
        if [ "$low" -ge 2 ]; then
            say "空きメモリが少なくなった (${m}kB) ので、端末を守るため Windows のプログラムをすべて止めました。"
            say 'ほかのアプリを閉じてから、もう一度試してください (wine --log で記録を保存できます)'
            echo "== 安全装置で止めた: MemAvailable ${m}kB" >> "$W/wineboot-mem.log"
            : > "$W/guard-fired"
            box64 "$D/usr/lib/wine/wineserver" -k 2>/dev/null
            sleep 2
            kill -9 "$1" 2>/dev/null
            return
        fi
    done
}

# 動かないときの調べ: 環境と、Wine と Box64 の詳しい記録を集めて、テキストファイルに保存する。
# 中身は端末の中に保存するだけで、どこにも送らない (送るかどうかは使う人が決める)
if [ "$1" = --diag ]; then
    OUT=/sdcard/Download/wine-diag.txt
    if ! touch "$OUT" 2>/dev/null; then OUT="$SH/wine-diag.txt"; fi
    echo "調べています (2〜4 分かかります。そのまま待ってください)…"
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
        WU="$D/usr/lib/x86_64-linux-gnu/wine/x86_64-unix/win32u.so"
        wb=$(od -An -tx1 -j 154866 -N 4 "$WU" 2>/dev/null | tr -d ' \n')
        case $wb in
            2a0e0000) echo "== win32u.so の書き換え: 済み ($wb)" ;;
            fa94ffff) echo "== win32u.so の書き換え: まだ ($wb)" ;;
            *) echo "== win32u.so の書き換え: 不明 ($wb)" ;;
        esac
        echo "== 権限 (Wine の主なファイル):"
        ls -lZ "$D/usr/lib/wine/wine64" "$D/usr/lib/wine/wineserver64" "$WU" \
            "$D/usr/lib/x86_64-linux-gnu/wine/x86_64-unix/ntdll.so" "$D/usr/lib/x86_64-linux-gnu/wine/x86_64-windows/user32.dll" 2>&1
        ls -ldZ "$D/usr/lib/x86_64-linux-gnu/wine/x86_64-unix" "$W/run/$(id -u)/wine" 2>&1
        echo
        echo "======== 1. wineserver の記録 (-d1。試し用の別の Windows の環境で、最大 2 分) ========"
        DP="$W/diagprefix"
        rm -rf "$DP"
        mkdir -p "$DP/drive_c/windows/system32"
        cat "$D/usr/x86_64-w64-mingw32/lib/zlib1.dll" > "$DP/drive_c/windows/system32/zlib1.dll"
        SLOG="$W/diag-server.log"
        WLOG="$W/diag-wine.log"
        (
            export WINEPREFIX="$DP"
            # 記録が大きくなりすぎないよう 30 万行で打ち切る (そこでサーバーも止まる)
            box64 "$D/usr/lib/wine/wineserver" -f -d1 2>&1 | head -n 300000 > "$SLOG" &
            sp=$!
            sleep 5
            (unset DISPLAY; cd "$SH" && WINEDLLOVERRIDES="$WINEDLLOVERRIDES;explorer.exe=d" WINEDEBUG=err+all,+process,+seh,+winstation,+loaddll BOX64_SHOWSEGV=1 BOX64_LOG=1 \
                timeout 90 sh -c '"$0" --preload "$1" --library-path "$2" "$3" "$4" wineboot --init' \
                "$N/libbrld.so" "$A/libbrshim.so" "$A" "$B" "$D/usr/lib/wine/wine64") > "$WLOG" 2>&1
            box64 "$D/usr/lib/wine/wineserver" -k 2>/dev/null
            sleep 3
            kill "$sp" 2>/dev/null
            pkill -f "wineserver" 2>/dev/null
        )
        echo "(サーバーの記録 $(wc -l < "$SLOG") 行)"
        echo "-- get_msg_queue の結果の種類と回数:"
        grep -ao 'get_msg_queue() = [A-Z_]*' "$SLOG" | sort | uniq -c
        first=$(grep -an 'get_msg_queue() = [A-Z]' "$SLOG" | head -n 1 | cut -d: -f1)
        if [ -n "$first" ]; then
            echo "-- 最初に失敗した get_msg_queue と、その前の 80 行 ($first 行目):"
            from=$((first > 80 ? first - 80 : 1))
            sed -n "${from},${first}p" "$SLOG" | grep -av 'Error initializing native'
        else
            echo "-- get_msg_queue の失敗は見つかりませんでした。サーバーの記録の最後 60 行:"
            grep -av 'Error initializing native' "$SLOG" | tail -n 60
        fi
        echo "-- 失敗した要求の種類と回数 (最初の 5 万行):"
        head -n 50000 "$SLOG" | grep -a ') = [A-Z]' | sed 's/^[0-9a-f]*: \([a-z_]*\)() = \([A-Z_]*\).*/\1 \2/' \
            | sort | uniq -c | sort -rn | head -n 30
        echo "-- 土台 (ウィンドウステーション)・デスクトップ・セッションに関わる要求 (最初の 5 万行から 80 行):"
        head -n 50000 "$SLOG" | grep -aE 'winstation|desktop|directory|session|init_first_thread|init_process_done|new_process\(' \
            | cut -c1-220 | head -n 80
        echo "-- 土台・デスクトップを作る/切り替える要求 (全体から、最初の 60 行):"
        grep -aE '(create|open|set_process|get_process|close)_winstation|(create|open|set_thread|get_thread|close)_desktop|set_user_object_info|get_user_object_info|enum_winstation|session' "$SLOG" \
            | cut -c1-260 | head -n 60
        echo "-- 失敗した要求のうち、少ないもの (回数が 5 回以下):"
        grep -a ') = [A-Z]' "$SLOG" | sed 's/^[0-9a-f]*: \([a-z_]*\)() = \([A-Z_]*\).*/\1 \2/' | sort | uniq -c \
            | grep -E '^ *[1-5] ' | head -n 40
        echo "-- 連絡用のフォルダの中:"
        ls -laR "$W/run" 2>&1 | head -n 30
        echo
        echo "======== 1b. 落ちた (例外・シグナル) 記録 ========"
        grep -aE 'SIGSEGV|SIGBUS|SIGILL|SIGABRT|Signal [0-9]+:|EmulatedBT|NativeBT|seh:|exception|Unhandled' "$WLOG" \
            | grep -avE 'Signal 17:' | cut -c1-240 | head -n 80
        echo "-- user32 の読み込みのころの記録:"
        grep -anE 'user32|win32u' "$WLOG" | cut -c1-200 | head -n 12
        echo
        echo "======== 3. wineboot.log ========"
        tail -n 100 "$W/wineboot.log" 2>&1
        echo
        echo "======== 4. 32bit の exe (試験中) ========"
        if has32; then
            echo "== 32bit の部品: 入っている ($(ls "$I32" 2>/dev/null | wc -l) 個)"
        else
            echo "== 32bit の部品: 入っていない"
        fi
        echo "== Windows の環境の 32bit の部分: $([ -f "$P/.br-wow64" ] && echo 作ってある || echo まだ)"
        if [ -f "$L32" ]; then
            echo "-- 最後に 32bit の exe を動かしたときの記録 ($L32、落ちた箇所を先に):"
            grep -aE 'Unhandled|page fault|starting debugger|c0000005|SIGSEGV|SIGBUS|SIGILL|NativeBT|EmulatedBT|Backtrace' "$L32" \
                | cut -c1-240 | head -n 40
            echo "-- 記録の最後の 120 行:"
            tail -n 120 "$L32" | cut -c1-240
        else
            echo "-- 32bit の exe を動かした記録はまだありません"
        fi
        echo
        echo "======== 5. .NET (Wine Mono) ========"
        echo "== Wine Mono: $(hasmono && echo "入っている ($MONO)" || echo 入っていない)"
        echo "== Windows の環境への登録: $([ -f "$P/.br-mono" ] && echo 済み || echo まだ)  リンク: $(readlink "$P/drive_c/windows/mono/mono-2.0" 2>/dev/null)"
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
W32=
EXE=
NET=
if [ -f "$1" ]; then
    if [ "$(u2 "$1" 0)" = 23117 ]; then # 先頭が MZ
        o=$(u4 "$1" 60)
        if [ -n "$o" ] && [ "$(u4 "$1" "$o")" = 17744 ]; then # PE の印
            # .NET のプログラムか (データの場所の表の 15 番目 = .NET の見出しがあるか。PE32 と PE32+ で場所が違う)
            case $(u2 "$1" $((o + 24))) in
                267) [ "$(u4 "$1" $((o + 232)))" != 0 ] && NET=1 ;;
                523) [ "$(u4 "$1" $((o + 248)))" != 0 ] && NET=1 ;;
            esac
            if [ -n "$NET" ] && ! hasmono; then
                say 'これは .NET (C# など) のプログラムです。動かすには、X11 の画面の「追加機能」で'
                say '「.NET のプログラムも動かす (Wine Mono)」を入れてください (コマンドなら x11 addons で画面が開きます)'
                exit 1
            fi
            case $(u2 "$1" $((o + 4))) in
                34404) ;; # x86_64 (64bit)
                332) # i386 (32bit)。部品が入っていれば WoW64 の方式で動かす (試験中)。
                    # .NET のプログラムは、どの CPU 向けでも動く (AnyCPU) ものが多く、Wine Mono が 64bit で動かす
                    # ので、32bit の部品は求めない (32bit 専用の .NET のプログラムは、部品が無いと Wine が知らせる)
                    if [ -z "$NET" ]; then
                        if ! has32; then
                            say '32bit の exe を動かすには、X11 の画面の「追加機能」で「32bit の exe も動かす (試験中)」を'
                            say '入れてください (コマンドなら x11 addons で画面が開きます)'
                            exit 1
                        fi
                        W32=1
                    fi ;;
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
    EXE="$f" # Windows の場所 (ドライブ名付き) に直すのは、ドライブを割り当てたあと
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

# Windows の環境づくり (wineboot) を、安全装置・進み具合の表示・メモリの記録を付けて動かす。
# $1 = wineboot に渡すもの (--init: 作る、-u: 更新する)、$2 = 進み具合の表示。メモリが足りずに止めたら 1 を返す
bootwine() {
    LOG="$W/wineboot.log"
    MEM="$W/wineboot-mem.log"
    : > "$MEM"
    # 優先度を下げて動かす (端末の操作が固まりにくいように)
    # 環境づくりの間はデスクトップ (explorer.exe) を起動させない。スマホでは explorer が起動に失敗し続け、
    # Windows のプログラムが 1 つ動くたびに explorer を起動し直して、メモリを使い切っていた
    (unset DISPLAY; WINEDLLOVERRIDES="$WINEDLLOVERRIDES;explorer.exe=d;mscoree=d" WINEDEBUG=err+all nice -n 10 sh -c \
        '"$0" --preload "$1" --library-path "$2" "$3" "$4" wineboot "$5"' \
        "$N/libbrld.so" "$A/libbrshim.so" "$A" "$B" "$D/usr/lib/wine/wine64" "$1" > "$LOG" 2>&1) &
    rm -f "$W/guard-fired"
    pid=$!
    guard "$pid" &
    t=0
    while kill -0 "$pid" 2>/dev/null; do
        sleep 5
        t=$((t + 5))
        [ $((t % 15)) = 0 ] && echo "  $2… ($t 秒)"
        # メモリの記録 (アプリごと止められても残るように、少しずつ書き足す)
        {
            echo "== $t 秒: $(grep -E '^(MemAvailable|SwapFree)' /proc/meminfo | tr -s ' ' | tr '\n' ' ')"
            ps -A -o PID,RSS,NAME 2>/dev/null | grep -iE 'wine|box64|brld|\.exe'
        } >> "$MEM" 2>&1
    done
    wait "$pid"
    box64 "$D/usr/lib/wine/wineserver" -w 2>/dev/null
    if [ -f "$W/guard-fired" ]; then
        rm -f "$W/guard-fired"
        return 1
    fi
    return 0
}

# Wine 付属のプログラム (DLL など) は、同じ中身のコピーの代わりにリンクにする (約 700MB → 約 20MB)。
# 64bit のもの (system32 など) は x86_64-windows と、32bit のもの (syswow64) は i386-windows と比べる
# (winsxs などのほかの場所は、両方と比べる)
linkdlls() {
    WD="$D/usr/lib/x86_64-linux-gnu/wine/x86_64-windows"
    find "$P/drive_c/windows" -type f | while IFS= read -r f; do
        n=${f##*/}
        case $f in
            "$P/drive_c/windows/syswow64/"*) set -- "$I32/$n" ;;
            *) set -- "$WD/$n" "$I32/$n" ;;
        esac
        for src in "$@"; do
            if [ -f "$src" ] && cmp -s "$f" "$src"; then
                ln -sf "$src" "$f"
                break
            fi
        done
    done
}

# 32bit の zlib1.dll を syswow64 に置く (32bit の user32.dll などが使う。Wine と一緒に入れた中にある)
zlib32() {
    mkdir -p "$P/drive_c/windows/syswow64" &&
        cat "$D/usr/i686-w64-mingw32/lib/zlib1.dll" > "$P/drive_c/windows/syswow64/zlib1.dll"
}

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
    # 32bit の部品が入っていれば、32bit の部分 (syswow64) も一緒に作る
    has32 && zlib32
    if ! bootwine --init '作成中'; then
        say 'メモリが足りず、Windows の環境を作れませんでした (途中まで作ったものは消しました)'
        rm -rf "$P" 2>/dev/null
        exit 1
    fi
    if [ ! -e "$K" ] || [ ! -f "$P/system.reg" ]; then
        say 'Windows の環境を作れませんでした。Wine の記録 (最後の部分):'
        grep -v '^\[BOX64\]' "$LOG" | tail -n 15 >&2
        say "(全体は $LOG。もう一度実行すると作り直します)"
        rm -rf "$P" 2>/dev/null
        exit 1
    fi
    linkdlls
    : > "$P/.br-ready"
    has32 && [ -e "$P/drive_c/windows/syswow64/kernel32.dll" ] && touch "$P/.br-wow64"
    echo 'Windows の環境を作りました'
fi

# 32bit の部品をあとから入れたときは、Windows の環境に 32bit の部分 (syswow64) を 1 回だけ足す
# (wineboot -u で更新する。C: の中のほかのファイルや設定はそのまま)。部品を消したら目印も消す
if has32 && [ ! -f "$P/.br-wow64" ]; then
    echo '32bit の exe のための部分を Windows の環境に足しています。1 回だけで、数分かかります…'
    if ! zlib32; then
        say '32bit の zlib1.dll を置けませんでした。wine --diag の結果を送ってください'
        [ -n "$W32" ] && exit 1
    # Wine は、前回の更新の印 (.update-timestamp) が今の Wine と同じだと更新を省くので、消してから更新する
    elif ! rm -f "$P/.update-timestamp" || ! bootwine -u '更新中'; then
        say 'メモリが足りず、32bit の部分を足せませんでした。ほかのアプリを閉じてから、もう一度試してください'
        exit 1
    elif [ -e "$P/drive_c/windows/syswow64/kernel32.dll" ]; then
        linkdlls
        touch "$P/.br-wow64"
        echo '32bit の部分を足しました'
    else
        say "32bit の部分を足せませんでした (記録: $W/wineboot.log。wine --diag で原因を調べられます)"
        [ -n "$W32" ] && exit 1
    fi
elif ! has32 && [ -f "$P/.br-wow64" ]; then
    rm -f "$P/.br-wow64"
fi

# Wine Mono (.NET): Windows の環境の中の、Mono の公式のインストーラーが使う場所 (C:\windows\mono\mono-2.0) を、
# 入れた Wine Mono へのリンクにする (コピーしない)。.NET の設定 (レジストリなど) は、Mono に付いている
# winemono-support.msi で 1 回だけ行う。Wine Mono を消したら、リンクと目印も消す
ML="$P/drive_c/windows/mono/mono-2.0"
if hasmono; then
    if [ "$(readlink "$ML" 2>/dev/null)" != "$MONO" ]; then
        mkdir -p "${ML%/*}" && rm -rf "$ML" && ln -s "$MONO" "$ML"
    fi
    if [ ! -f "$P/.br-mono" ]; then
        echo '.NET (Wine Mono) の設定を Windows の環境に書き込んでいます (1 回だけです)…'
        (unset DISPLAY; WINEDLLOVERRIDES="$WINEDLLOVERRIDES;explorer.exe=d" \
            wine64 msiexec /i 'C:\windows\mono\mono-2.0\support\winemono-support.msi' /qn) > /dev/null 2>&1
        touch "$P/.br-mono"
    fi
else
    [ -L "$ML" ] && rm -f "$ML"
    rm -f "$P/.br-mono"
fi

# 日本語の字体の設定 (レジストリ): MS ゴシックなどを Noto CJK に置き換え、Tahoma などに日本語を補う設定。
# アプリが作った jpfont.reg を、中身か文字コードの設定が変わったときだけ regedit で読み込む
if [ -f "$W/jpfont.reg" ]; then
    want="$(cksum < "$W/jpfont.reg" 2>/dev/null) $LANGMODE"
    if [ "$(cat "$P/.br-jpfont" 2>/dev/null)" != "$want" ]; then
        echo '日本語の字体の設定を書き込んでいます (設定が変わったときだけです)…'
        mkdir -p "$P/drive_c/windows/temp"
        if cat "$W/jpfont.reg" > "$P/drive_c/windows/temp/br-jpfont.reg" 2>/dev/null \
                && (unset DISPLAY; WINEDLLOVERRIDES="$WINEDLLOVERRIDES;explorer.exe=d" \
                    wine64 regedit /S 'C:\windows\temp\br-jpfont.reg') > /dev/null 2>&1; then
            echo "$want" > "$P/.br-jpfont"
        else
            say '日本語の字体の設定を書き込めませんでした (表示が□になることがあります)'
        fi
        rm -f "$P/drive_c/windows/temp/br-jpfont.reg"
    fi
fi

# ドライブの割り当て: C: (Windows の環境)、D: (共有フォルダ)、P: (登録した Windows のプログラムの置き場所)。
# Wine のふつうの設定の Z: (端末全体) は作らない (毎回確かめる。Wine が作り直すことがあるため)
rm -f "$P/dosdevices/z:" "$P/dosdevices/z::"
ln -sfn "$SH" "$P/dosdevices/d:"
mkdir -p "$W/programs" 2>/dev/null
ln -sfn "$W/programs" "$P/dosdevices/p:"
# 動かす exe を、Windows の場所 (ドライブ名付き) で渡す。Z: が無いので、どのドライブにも無い場所の exe は
# Wine が見つけられない (「ファイルが見つかりません」になる)。C: / D: / P: の中ならそのドライブで、
# それ以外の場所なら、その exe のフォルダを E: にして渡す (E: は動かすたびに付け替える)
if [ -n "$EXE" ]; then
    case $EXE in
        "$SH"/*) r=${EXE#"$SH"/}; WEXE="D:\\${r//\//\\}" ;;
        "$P/drive_c"/*) r=${EXE#"$P/drive_c"/}; WEXE="C:\\${r//\//\\}" ;;
        "$W/programs"/*) r=${EXE#"$W/programs"/}; WEXE="P:\\${r//\//\\}" ;;
        *) ln -sfn "${EXE%/*}" "$P/dosdevices/e:"; WEXE="E:\\${EXE##*/}" ;;
    esac
    shift
    set -- "$WEXE" "$@"
fi

jpnotice

# ウィンドウを出すプログラムなら、X サーバーを裏で起動して (画面はまだ切り替えない)、準備ができるのを待つ。
# X11 の画面に切り替えるのは、プログラムのウィンドウが本当に出てから (それまでは、ここに経過やエラーを出す)
if [ -n "$GUI" ]; then
    xok() {
        [ -S "$XROOT/tmp/.X11-unix/X1" ] || return 1
        xp=$(tr -dc 0-9 < "$XROOT/tmp/.X1-lock" 2>/dev/null)
        [ -n "$xp" ] && kill -0 "$xp" 2>/dev/null
    }
    printf '\036BRX11|start\037'
    if ! xok; then
        echo 'X サーバーを裏で起動しています…'
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
    echo 'プログラムのウィンドウが出たら、自動で X11 の画面に切り替えます (先に見るなら x11 と入力)'
fi

# Shift_JIS を UTF-8 に直すフィルター (日本語の環境の Wine や、日本語版 Windows 用のプログラムの出力のため)。
# UTF-8 の行はそのまま通す。フィルターが無ければ何もしない
CP="$D/usr/share/wine/nls/c_932.nls"
cpf() {
    if [ -x "$N/libbrcp932.so" ] && [ -f "$CP" ]; then "$N/libbrcp932.so" "$CP"; else cat; fi
}
set -o pipefail 2>/dev/null

# Box64 が「端末に無い ARM 用の部品を探した」ことを知らせるだけの行は、エラーの出力から取り除く
EF="$W/run/$(id -u)/stderr.$$"
rm -f "$EF"
if [ -n "$W32" ]; then
    # 32bit の exe (試験中): Wine のエラーと Box64 の「落ちた」知らせを記録に残す (画面には出さない)
    echo '32bit の exe を動かします (試験中です。ときどき落ちることがあります)'
    export WINEDEBUG=err+all
    printf '== %s 32bit の exe: %s\n' "$(date)" "$*" > "$L32" 2>/dev/null
fi
if mkfifo "$EF" 2>/dev/null; then
    if [ -n "$W32" ]; then
        # Box64 の知らせは色付きのことがあるので、色の印があっても取り除く
        tee -a "$L32" < "$EF" | cpf | grep -avE 'Error initializing native|^[^ ]{0,12}BOX64[^ ]{0,6}\] |^[0-9a-f]{4}:(err|warn|fixme|trace):' >&2 &
    else
        # .NET のプログラムは、Box64 の上では終わるときに強制終了 (SIGKILL) の形になることがある (出力は最後まで
        # 出ている)。そのときシェルが出す「Killed」は、.NET のときだけ取り除く (終了コードも下で 0 に直す)
        if [ -n "$NET" ]; then KL='|^Killed *$'; else KL=; fi
        cpf < "$EF" | grep -avE "Error initializing native|^[^ ]{0,12}BOX64[^ ]{0,6}\\] $KL" >&2 &
    fi
    exec 2> "$EF"
    rm -f "$EF"
fi

# 32bit の exe が落ちていたら、記録の場所と調べ方を案内する。$1 = 終了コード
crashnote() {
    [ -n "$W32" ] || return 0
    sleep 1
    if [ "$1" -ge 128 ] 2>/dev/null \
            || grep -qaE 'Unhandled (page fault|exception)|starting debugger|SIGSEGV|SIGBUS|SIGILL' "$L32" 2>/dev/null; then
        say '32bit のプログラムが異常終了したようです (32bit の実行は試験中で、ときどき落ちることがあります)。'
        say "落ちたときの記録: $L32"
        say 'wine --diag と打つと、この記録も Download/wine-diag.txt にまとめます (送ってもらえれば原因を調べます)'
    elif grep -qaE 'err:module:(import_dll|loader_init)' "$L32" 2>/dev/null; then
        say "32bit のプログラムを起動できませんでした (必要な DLL が見つからないなど)。記録: $L32"
        say 'wine --diag と打つと、この記録も Download/wine-diag.txt にまとめます'
    fi
}

# 安全装置を付けてから動かす
guard $$ &
if [ -n "$GUI" ]; then
    # ウィンドウの見張り (libbrxwin.so) を先に動かして、今出ているウィンドウ (端末など) を覚えさせ、
    # 新しいウィンドウが出たら X11 の画面に切り替える。見張りは、このスクリプトが終わると自分も終わる
    SHOWN="$W/run/$(id -u)/shown.$$"
    rm -f "$SHOWN"
    XW=
    if [ -x "$N/libbrxwin.so" ]; then
        (
            if "$N/libbrxwin.so" "$XROOT/tmp/.X11-unix/X1" $$; then
                touch "$SHOWN"
                printf '\036BRX11|show\037'
                echo 'ウィンドウが出たので、X11 の画面に切り替えました'
            fi
        ) &
        XW=$!
        sleep 1
    else
        printf '\036BRX11|show\037'
        touch "$SHOWN"
    fi
    # 終わったあとに、ウィンドウが出たか・落ちたかを確かめるので、exec しない。
    # Wine の知らせ (日本語の環境では Shift_JIS) が化けないよう、出力もフィルターに通す
    "$N/libbrld.so" --preload "$A/libbrshim.so" --library-path "$A" "$B" "$D/usr/lib/wine/wine64" "$@" | cpf
    code=$?
    [ -n "$NET" ] && [ "$code" = 137 ] && code=0
    [ -n "$XW" ] && kill "$XW" 2>/dev/null
    if [ ! -f "$SHOWN" ]; then
        say "プログラムはウィンドウを出さずに終わりました (終了コード $code)。上に出た記録を見てください"
        say '(動かないときは wine --diag で原因を調べられます)'
    fi
    rm -f "$SHOWN"
    crashnote $code
    exit $code
fi
# 文字だけのプログラムは、出力をフィルターに通す。端末に直接つなぐと、Wine は出力を「Windows のコンソール」として
# 描き直し (空白をカーソルの移動に置き換える・文字を英語の文字コードで読み直す)、空白が消えたり
# 日本語が化けたりするため。フィルター (libbrcp932.so) は、UTF-8 の行はそのまま通し、
# 日本語版 Windows 用のプログラムが出す Shift_JIS の行だけを UTF-8 に直す (変換表は Wine の c_932.nls)。
# フィルターが無ければ cat に通す (プログラムが出したとおりのバイトがそのまま届く)
"$N/libbrld.so" --preload "$A/libbrshim.so" --library-path "$A" "$B" "$D/usr/lib/wine/wine64" "$@" | cpf
code=$?
[ -n "$NET" ] && [ "$code" = 137 ] && code=0
crashnote $code
exit $code
