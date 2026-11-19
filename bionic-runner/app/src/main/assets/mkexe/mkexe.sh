#!/usr/bin/env bash
# mkexe — 端末の上で C のソースから実行ファイルを作る (Bionic Runner 同梱)
#
#   bionic 用: Android の libc で動く実行ファイル。そのまま ./名前 で実行できる (root のシェルで)
#   glibc 用 : 普通の Linux (glibc) 用の実行ファイル。アプリ同梱の glibc で動く (mkexe run 名前、
#              またはアプリの「プログラムを追加」で登録)
#
# コンパイラは TCC (Tiny C Compiler、アプリに同梱。native/build-tcc.sh でビルド)。C 言語 (C99 と C11 の
# 多く) に対応。C++ は使えない。
# libc のヘッダやライブラリは、初めて使うときに公式の配布元から取ってきて $MKEXE_HOME に置く:
#   bionic: Termux の ndk-sysroot (Android NDK のヘッダと起動用の部品)。libc 本体は端末の /system/lib64 のもの
#   glibc : Debian 13 (trixie) の libc6・libc6-dev・linux-libc-dev (glibc 2.41。アプリ同梱の 2.44 で動く)
# どちらも配布元の一覧に載っている SHA-256 で確かめてから使う。
#
# 画面は TERM=dumb の簡易端末を想定: 色やカーソル移動は使わず、質問は 1 行ずつ。

set -u

MKEXE_VERSION=1.0
MKEXE_HOME=${MKEXE_HOME:-${HOME:-/data/local/tmp}/.mkexe}
SUPPORT=${MKEXE_SUPPORT:-${BR_MKEXE_SUPPORT:-}}
TCC_BIONIC=${MKEXE_TCC_BIONIC:-${BR_NATIVE:-}/libtccbionic.so}
TCC_GLIBC=${MKEXE_TCC_GLIBC:-${BR_NATIVE:-}/libtccglibc.so}
WORKTMP=${TMPDIR:-$MKEXE_HOME/tmp}/mkexe.$$
TERMUX_REPO=https://packages.termux.dev/apt/termux-main
DEBIAN_REPO=https://deb.debian.org/debian
DEBIAN_SUITE=trixie
SEVENZIP=${MKEXE_7Z:-7z}

say() { printf '%s\n' "$*"; }
err() { printf 'mkexe: %s\n' "$*" >&2; }
die() { err "$*"; cleanup; exit 1; }
cleanup() { [ -n "${WORKTMP:-}" ] && [ -d "$WORKTMP" ] && rm -rf -- "$WORKTMP"; }
trap cleanup EXIT

usage() {
    cat <<'EOF'
mkexe — C のソースから bionic 用・glibc 用の実行ファイルを作る

使い方:
  mkexe                          質問に答えながら作る (対話)
  mkexe [-t 形式] [-o 名前] [-r] ソース.c [ソース2.c ...] [コンパイラのオプション...]
      -t bionic   Android 用 (既定)。できたものは ./名前 で実行できる
      -t glibc    Linux (glibc) 用。mkexe run 名前 で実行するか、アプリに登録する
      -o 名前     出力するファイルの名前 (既定: 最初のソースの名前から .c を除いたもの)
      -r          できたらすぐ実行する
      そのほか -O2 -Wall -DNAME=値 -Iフォルダ -lライブラリ などは、そのままコンパイラへ渡す
  mkexe run 名前 [引数...]       作った実行ファイルを動かす (glibc 用は同梱の glibc で)
  mkexe new [名前]               見本のソース (名前.c、既定 hello.c) を作る
  mkexe setup [bionic|glibc|all] ヘッダとライブラリを取ってくる (初回は自動)
  mkexe info 名前                実行ファイルの形式を表示
  mkexe status                   準備の状態を表示
  mkexe clean                    取ってきたヘッダとライブラリを消す
  mkexe help                     この説明

例:
  mkexe new hello
  mkexe hello.c                  → ./hello (bionic 用)
  mkexe -t glibc -o hello-linux hello.c
  mkexe run hello-linux
EOF
}

# ------------------------------------------------------------------ 前提の確認

need_tools() {
    local t
    for t in curl tar sha256sum awk od; do
        command -v "$t" >/dev/null 2>&1 || die "コマンド $t が見つかりません"
    done
    command -v "$SEVENZIP" >/dev/null 2>&1 || die "7z が見つかりません (Bionic Runner のコマンドプロンプトから実行してください)"
}

check_compiler() { # 形式
    local tcc
    tcc=$(compiler "$1")
    if [ ! -x "$tcc" ]; then
        die "コンパイラ ($tcc) がありません。64bit の ARM 端末の Bionic Runner のコマンドプロンプトから実行してください"
    fi
    if [ -z "$SUPPORT" ] || [ ! -f "$SUPPORT/$1/libtcc1.a" ]; then
        die "コンパイラの補助ファイルがありません (BR_MKEXE_SUPPORT)。コマンドプロンプトを「再起動」してみてください"
    fi
}

compiler() { [ "$1" = glibc ] && printf '%s' "$TCC_GLIBC" || printf '%s' "$TCC_BIONIC"; }

# ------------------------------------------------------------------ ダウンロード

# 配布元の一覧 (Packages) から、パッケージの Filename と SHA256 を取り出す
# 引数: 一覧のファイル パッケージ名 → 標準出力に "Filename SHA256"
pkg_lookup() {
    awk -v want="$2" '
        /^Package: / { pkg = $2; fn = ""; sha = "" }
        /^Filename: / { fn = $2 }
        /^SHA256: / { sha = $2 }
        /^$/ { if (!done && pkg == want && fn != "" && sha != "") { print fn, sha; done = 1 } pkg = "" }
        END { if (!done && pkg == want && fn != "" && sha != "") print fn, sha }
    ' "$1"
}

fetch() { # URL 保存先
    curl -fL --retry 3 --connect-timeout 30 -sS -o "$2" "$1"
}

# パッケージ (.deb) を取ってきて確かめ、中身を展開先へ広げる
# 引数: 一覧 配布元の URL パッケージ名 展開先
get_deb() {
    local list=$1 repo=$2 name=$3 dest=$4 info fn sha deb got
    info=$(pkg_lookup "$list" "$name")
    [ -n "$info" ] || die "配布元の一覧に $name がありません"
    fn=${info% *}
    sha=${info#* }
    case $fn in *..*|/*) die "おかしなファイル名です: $fn" ;; esac
    deb=$WORKTMP/$name.deb
    say "  $name を取得しています… ($fn)"
    # Termux のファイル名の : は URL では %3a にする
    fetch "$repo/$(printf '%s' "$fn" | sed 's/:/%3a/g')" "$deb" || die "$name をダウンロードできませんでした"
    got=$(sha256sum "$deb" | cut -d' ' -f1)
    [ "$got" = "$sha" ] || die "$name が壊れているか、すり替えられています (SHA-256 が一致しません)"
    mkdir -p "$WORKTMP/ar-$name" "$dest"
    "$SEVENZIP" x -y -bso0 -bsp0 -o"$WORKTMP/ar-$name" "$deb" >/dev/null || die "$name を開けません"
    local data
    data=$(ls "$WORKTMP/ar-$name"/data.tar* 2>/dev/null | head -n 1)
    [ -n "$data" ] || die "$name の中身 (data.tar) がありません"
    case $data in
        *.tar) tar -xf "$data" -C "$dest" ;;
        *) "$SEVENZIP" x -so "$data" 2>/dev/null | tar -xf - -C "$dest" ;;
    esac || die "$name を展開できません"
    rm -rf -- "$WORKTMP/ar-$name" "$deb"
}

setup_bionic() {
    local d=$MKEXE_HOME/bionic x list
    say "bionic 用のヘッダと起動用の部品を用意します (Termux の ndk-sysroot、約 2MB)"
    mkdir -p "$WORKTMP"
    list=$WORKTMP/termux-Packages
    fetch "$TERMUX_REPO/dists/stable/main/binary-aarch64/Packages.gz" "$list.gz" || die "Termux の一覧を取得できませんでした"
    "$SEVENZIP" x -so "$list.gz" > "$list" 2>/dev/null || die "Termux の一覧を展開できません"
    x=$WORKTMP/x-bionic
    get_deb "$list" "$TERMUX_REPO" ndk-sysroot "$x"
    local usr=$x/data/data/com.termux/files/usr
    [ -d "$usr/include" ] || die "ndk-sysroot の中身がおかしいです"
    rm -rf -- "$d.new"
    mkdir -p "$d.new/sysroot" "$d.new/crt" "$d.new/lib"
    mv "$usr/include" "$d.new/sysroot/include"
    cp "$usr/lib/crtbegin_dynamic.o" "$usr/lib/crtend_android.o" "$usr/lib/crtbegin_so.o" "$usr/lib/crtend_so.o" "$d.new/crt/"
    # libpthread.so などは「libc を使え」という 1 行の指示 (bionic では libc に入っているため)
    cp "$usr/lib/libpthread.so" "$usr/lib/librt.so" "$usr/lib/libutil.so" "$d.new/lib/"
    printf 'ndk-sysroot %s\n' "$(pkg_version "$list" ndk-sysroot)" > "$d.new/VERSION"
    rm -rf -- "$d"
    mv "$d.new" "$d"
    rm -rf -- "$x"
    say "bionic 用の準備ができました"
}

setup_glibc() {
    local d=$MKEXE_HOME/glibc x list p
    say "glibc 用のヘッダとライブラリを用意します (Debian 13 の glibc 2.41。一覧と合わせて約 20MB)"
    mkdir -p "$WORKTMP"
    list=$WORKTMP/debian-Packages
    fetch "$DEBIAN_REPO/dists/$DEBIAN_SUITE/main/binary-arm64/Packages.gz" "$list.gz" || die "Debian の一覧を取得できませんでした"
    "$SEVENZIP" x -so "$list.gz" > "$list" 2>/dev/null || die "Debian の一覧を展開できません"
    x=$WORKTMP/x-glibc
    for p in libc6 libc6-dev linux-libc-dev; do get_deb "$list" "$DEBIAN_REPO" "$p" "$x"; done
    local multi=$x/usr/lib/aarch64-linux-gnu
    [ -f "$multi/crt1.o" ] && [ -f "$multi/libc.so.6" ] || die "glibc の中身がおかしいです"
    # カーネルのヘッダ (asm/) は別の場所 (usr/lib/linux/uapi) を指すリンクなので、中身を写して置き換える。
    # ほかの CPU 用のヘッダは要らないので消す
    local inc=$x/usr/include
    [ -d "$x/usr/lib/linux/uapi/arm64/asm" ] || die "linux-libc-dev の中身がおかしいです"
    rm -rf -- "$inc/aarch64-linux-gnu/asm"
    cp -r "$x/usr/lib/linux/uapi/arm64/asm" "$inc/aarch64-linux-gnu/asm"
    for p in "$inc"/*-linux-*; do
        [ "$(basename "$p")" = aarch64-linux-gnu ] || rm -rf -- "$p"
    done
    rm -rf -- "$d.new"
    mkdir -p "$d.new/sysroot" "$d.new/crt" "$d.new/lib"
    mv "$inc" "$d.new/sysroot/include"
    cp "$multi/crt1.o" "$multi/crti.o" "$multi/crtn.o" "$d.new/crt/"
    # リンクに使うもの: 共有ライブラリ本体 (libc.so.6 など)・libc_nonshared.a・libc.so などの指示ファイル。
    # 指示ファイルに書かれた /lib/... の場所は Android には無いが、TCC はその名前をここから探し直す
    cp -P "$multi"/*.so* "$multi"/*.a "$d.new/lib/" 2>/dev/null
    cp -P "$multi"/ld-linux-aarch64.so.1 "$d.new/lib/" 2>/dev/null
    # 動かすときにも使う (アプリ同梱の glibc が見つからないとき)
    printf 'glibc %s\n' "$(pkg_version "$list" libc6)" > "$d.new/VERSION"
    rm -rf -- "$d"
    mv "$d.new" "$d"
    rm -rf -- "$x"
    say "glibc 用の準備ができました"
}

pkg_version() { awk -v want="$2" '/^Package: /{p=$2} /^Version: /{ if (p == want) { print $2; exit } }' "$1"; }

# 形式ごとの -B フォルダ (TCC の補助一式) を組み立てる
prepare() { # 形式
    local v=$1 d=$MKEXE_HOME/$1
    check_compiler "$v"
    if [ ! -f "$d/VERSION" ]; then
        need_tools
        mkdir -p "$MKEXE_HOME"
        if [ "$v" = glibc ]; then setup_glibc; else setup_bionic; fi
    fi
    # TCC 自身のヘッダと補助ライブラリは、アプリの更新で場所が変わるので毎回つなぎ直す
    rm -rf -- "$d/include"
    ln -s "$SUPPORT/$v/include" "$d/include"
    ln -sf "$SUPPORT/$v/libtcc1.a" "$d/lib/libtcc1.a"
    [ "$v" = bionic ] && bionic_compat "$d"
    return 0
}

# Android (bionic) のヘッダは clang 向けの書き方を使うので、TCC で読めるよう補う
bionic_compat() {
    local d=$1
    mkdir -p "$d/compat/bits"
    cat > "$d/compat.h" <<'EOF'
/* mkexe: Android (bionic) のヘッダを TCC で読むための補い (clang 専用の書き方を無効にする) */
#define _Null_unspecified
#define _Nullable_result
/* バイト順の入れ替え (htons など)。TCC にはこの組み込み関数が無い */
#define __builtin_bswap16(x) ((unsigned short)((((unsigned short)(x)) >> 8) | (((unsigned short)(x)) << 8)))
#define __builtin_bswap32(x) ((((unsigned int)(x) & 0xff000000u) >> 24) | (((unsigned int)(x) & 0x00ff0000u) >> 8) \
    | (((unsigned int)(x) & 0x0000ff00u) << 8) | (((unsigned int)(x) & 0x000000ffu) << 24))
#define __builtin_bswap64(x) (((unsigned long long)__builtin_bswap32((unsigned int)(x)) << 32) \
    | (unsigned long long)__builtin_bswap32((unsigned int)((unsigned long long)(x) >> 32)))
EOF
    cat > "$d/compat/bits/bionic_multibyte_result.h" <<'EOF'
/* mkexe: TCC は C23 の「型を指定した enum」を読めないので、同じ値の定義に置き換えたもの */
#pragma once
#include <sys/cdefs.h>
#include <stddef.h>
#define BIONIC_MULTIBYTE_RESULT_ILLEGAL_SEQUENCE ((size_t)-1)
#define BIONIC_MULTIBYTE_RESULT_INCOMPLETE_SEQUENCE ((size_t)-2)
#define BIONIC_MULTIBYTE_RESULT_NO_BYTES_CONSUMED ((size_t)-3)
EOF
}

# ------------------------------------------------------------------ 実行ファイルの形式

# ELF の頭を読んで "bionic" / "glibc" / "static" / "other" / "notelf" を返す
elf_kind() {
    local f=$1 magic
    magic=$(head -c 4 "$f" 2>/dev/null | od -An -c | tr -d ' \n')
    [ "$magic" = '177ELF' ] || { printf notelf; return; }
    if grep -aq '/system/bin/linker64' "$f"; then printf bionic
    elif grep -aq '/lib/ld-linux-aarch64.so.1' "$f"; then printf glibc
    elif grep -aq 'ld-linux\|ld-musl' "$f"; then printf other
    else printf static; fi
}

glibc_dir() {
    # アプリ同梱の glibc (2.44)。「プログラムを追加」で glibc 用を登録したことがあれば展開済み
    local app=${HOME:-}/../glibc/arm64-v8a/lib
    if [ -x "$app/ld-linux-aarch64.so.1" ]; then printf '%s' "$app"; return; fi
    if [ -f "$MKEXE_HOME/glibc/lib/ld-linux-aarch64.so.1" ]; then printf '%s' "$MKEXE_HOME/glibc/lib"; return; fi
    printf ''
}

run_exe() { # ファイル [引数...]
    local f=$1 kind L
    shift
    case $f in */*) ;; *) f=./$f ;; esac
    [ -f "$f" ] || die "見つかりません: $f"
    kind=$(elf_kind "$f")
    case $kind in
        glibc)
            L=$(glibc_dir)
            [ -n "$L" ] || die "glibc が見つかりません。先に mkexe setup glibc を実行してください"
            "$L/ld-linux-aarch64.so.1" --library-path "$L" "$f" "$@"
            ;;
        bionic|static)
            "$f" "$@"
            ;;
        notelf) die "実行ファイル (ELF) ではありません: $f" ;;
        *) die "この形式は mkexe run では動かせません: $f" ;;
    esac
}

info_exe() {
    local f=$1 kind size
    [ -f "$f" ] || die "見つかりません: $f"
    kind=$(elf_kind "$f")
    size=$(wc -c < "$f" | tr -d ' ')
    case $kind in
        bionic) say "$f: bionic (Android) 用の実行ファイル、$size バイト。./$(basename "$f") で実行できます (root のシェル)" ;;
        glibc) say "$f: glibc (Linux) 用の実行ファイル、$size バイト。mkexe run $(basename "$f") で実行できます" ;;
        static) say "$f: 静的リンクの実行ファイル、$size バイト" ;;
        notelf) say "$f: 実行ファイル (ELF) ではありません" ;;
        *) say "$f: ほかの libc 用の実行ファイル、$size バイト" ;;
    esac
}

# ------------------------------------------------------------------ 見本

new_sample() {
    local name=${1:-hello}
    name=${name%.c}
    local f=$name.c
    if [ -e "$f" ]; then die "$f はもうあります (別の名前を指定してください)"; fi
    cat > "$f" <<'EOF'
/* mkexe の見本: 名前を聞いて挨拶し、数の計算をする */
#include <stdio.h>
#include <string.h>
#include <math.h>

int main(int argc, char **argv)
{
    char name[128];
    int i;
    double sum = 0;

    printf("こんにちは! 引数は %d 個です。\n", argc - 1);
    for (i = 1; i < argc; i++)
        printf("  引数 %d: %s\n", i, argv[i]);

    printf("お名前を入力してください:\n");
    fflush(stdout);
    if (fgets(name, sizeof name, stdin) == NULL)
        strcpy(name, "名無し");
    name[strcspn(name, "\r\n")] = '\0';
    printf("%s さん、ようこそ。\n", name);

    for (i = 1; i <= 10; i++)
        sum += sqrt((double)i);
    printf("1 から 10 の平方根の合計は %.4f です。\n", sum);
    return 0;
}
EOF
    say "見本を作りました: $f"
    say "作るには: mkexe $f   (glibc 用は: mkexe -t glibc $f)"
}

# ------------------------------------------------------------------ コンパイル

build() { # 形式 出力 実行するか ソースとオプション...
    local v=$1 out=$2 run=$3
    shift 3
    prepare "$v"
    local tcc extra=()
    tcc=$(compiler "$v")
    if [ "$v" = bionic ]; then
        # Android のヘッダは「どの版の Android 用か」の指定が要る。この端末の版にする
        # (その版までに増えた関数がすべて使える。古い端末でも動かすなら -D__ANDROID_API__=24 などで上書き)
        local api
        api=$(getprop ro.build.version.sdk 2>/dev/null)
        case $api in ''|*[!0-9]*) api=24 ;; esac
        # メモリの単位が 16KB の端末でも読み込めるよう、区切りを 16KB にそろえる
        extra=(-D__ANDROID_API__="$api" -D__ANDROID_MIN_SDK_VERSION__="$api" -I"$MKEXE_HOME/bionic/compat"
               -include "$MKEXE_HOME/bionic/compat.h" -Wl,-section-alignment=0x4000)
    fi
    say "コンパイルしています ($v 用): $*"
    if ! "$tcc" -B"$MKEXE_HOME/$v" "${extra[@]}" -o "$out" "$@" -lm; then
        err "コンパイルできませんでした (上のエラーを見てください)"
        return 1
    fi
    chmod 755 "$out" 2>/dev/null
    say "できました: $out"
    info_exe "$out"
    if [ "$run" = 1 ]; then
        say "---- 実行します ----"
        run_exe "$out"
        say "---- 終了しました (終了コード $?) ----"
    fi
}

compile_cmd() {
    local v=bionic out= run=0 first=
    local args=()
    while [ $# -gt 0 ]; do
        case $1 in
            -t) [ $# -ge 2 ] || die "-t の後ろに bionic か glibc を書いてください"; v=$2; shift 2 ;;
            -t*) v=${1#-t}; shift ;;
            -o) [ $# -ge 2 ] || die "-o の後ろに名前を書いてください"; out=$2; shift 2 ;;
            -r|--run) run=1; shift ;;
            *)
                case $1 in
                    -*) ;;
                    *) [ -z "$first" ] && first=$1 ;;
                esac
                args+=("$1"); shift ;;
        esac
    done
    case $v in bionic|glibc) ;; *) die "形式は bionic か glibc です: $v" ;; esac
    [ -n "$first" ] || die "ソースファイル (.c) を指定してください (mkexe help で使い方)"
    [ -f "$first" ] || die "見つかりません: $first"
    if [ -z "$out" ]; then
        out=$(basename "$first")
        out=${out%.*}
    fi
    case $(basename "$out") in
        *[!A-Za-z0-9._-]*) die "出力の名前に使えるのは半角英数字と . _ - だけです: $out" ;;
    esac
    build "$v" "$out" "$run" "${args[@]}"
}

ask() { # 質問 → 答え (変数 REPLY)
    say "$1"
    REPLY=
    IFS= read -r REPLY || exit 0
}

interactive() {
    say "mkexe $MKEXE_VERSION — C のソースから実行ファイルを作ります (終わるには空のまま q)"
    ask "作る形式を番号で選んでください: 1 = bionic (Android 用。そのまま実行できる)  2 = glibc (Linux 用)"
    local v=bionic
    case $REPLY in
        q|Q) return 0 ;;
        2|glibc) v=glibc ;;
    esac
    ask "ソースファイル (.c) の場所を入力してください (何も入れずに Enter で見本 hello.c を作って使う)"
    local src=$REPLY
    case $src in q|Q) return 0 ;; esac
    if [ -z "$src" ]; then
        src=hello.c
        [ -e "$src" ] || new_sample hello
    fi
    [ -f "$src" ] || die "見つかりません: $src"
    local def
    def=$(basename "$src")
    def=${def%.*}
    [ "$v" = glibc ] && def=$def-linux
    ask "出力する名前を入力してください (何も入れずに Enter で $def)"
    local out=${REPLY:-$def}
    case $out in *[!A-Za-z0-9._-]*) die "名前に使えるのは半角英数字と . _ - だけです: $out" ;; esac
    ask "追加のオプションがあれば入力してください (例: -O2 -Wall。無ければ Enter)"
    local opts=$REPLY
    # shellcheck disable=SC2086
    build "$v" "$out" 0 "$src" $opts || return 1
    ask "いま実行しますか? (y = はい / Enter = いいえ)"
    case $REPLY in
        y|Y|yes|はい)
            say "---- 実行します ----"
            run_exe "$out"
            say "---- 終了しました (終了コード $?) ----"
            ;;
    esac
}

status() {
    say "mkexe $MKEXE_VERSION"
    say "作業場所: $MKEXE_HOME"
    local v
    for v in bionic glibc; do
        if [ -f "$MKEXE_HOME/$v/VERSION" ]; then
            say "  $v: 準備済み ($(cat "$MKEXE_HOME/$v/VERSION"))"
        else
            say "  $v: 未準備 (初めて使うときに自動で取ってきます)"
        fi
        if [ -x "$(compiler "$v")" ]; then say "    コンパイラ: $(compiler "$v")"; else say "    コンパイラ: 見つかりません"; fi
    done
    local L
    L=$(glibc_dir)
    say "  glibc の実行に使うもの: ${L:-まだありません}"
}

main() {
    case ${1:-} in
        '') interactive ;;
        help|-h|--help) usage ;;
        run) shift; [ $# -ge 1 ] || die "mkexe run 名前 [引数...]"; run_exe "$@" ;;
        new) shift; new_sample "${1:-hello}" ;;
        info) shift; [ $# -ge 1 ] || die "mkexe info 名前"; info_exe "$1" ;;
        status) status ;;
        clean) rm -rf -- "$MKEXE_HOME/bionic" "$MKEXE_HOME/glibc"; say "消しました" ;;
        setup)
            shift
            case ${1:-all} in
                bionic) check_compiler bionic; need_tools; setup_bionic; prepare bionic ;;
                glibc) check_compiler glibc; need_tools; setup_glibc; prepare glibc ;;
                all) check_compiler bionic; need_tools; setup_bionic; prepare bionic; setup_glibc; prepare glibc ;;
                *) die "mkexe setup [bionic|glibc|all]" ;;
            esac
            ;;
        *) compile_cmd "$@" ;;
    esac
}

main "$@"
