#!/bin/sh
# glibc ランタイム (ローダ + 基本ライブラリ) を集め、native/build-sdl.sh が作った SDL2 一式
# (アプリの画面と音へ出力する版) と合わせて、ABI ごとの zip にして app/src/main/assets/glibc/ へ置く。
# ビルド前に 1 回実行する。先に native/build-sdl.sh と native/build-shim.sh を実行しておくこと。
#
# glibc 本体は Termux プロジェクトの配布物 (termux-pacman の glibc パッケージ) を使う。
# Android のアプリのプロセスでは一部のシステムコール (clone3、rseq、set_robust_list など) が
# 禁止されていて、普通の glibc はそれを呼んで強制終了させられる。Termux 版はそれらを呼ばないよう
# 調整されているので、root が無くても動く。
# libgcc / libstdc++ / SDL 1.2 互換は Debian の公式パッケージから取る。
#
# ローダ (ld-linux-*.so.*) は jniLibs にも libbrld.so という名前で置く。APK の lib/ に入れた
# ファイルだけが、root なしでも実行を許されるため。
# 必要なコマンド: curl, xz, tar, dpkg-deb, zip, python3
set -eu

SUITE=trixie
MIRROR=https://deb.debian.org/debian
# libsdl1.2debian は SDL 1.2 用の古いゲームを SDL2 の上で動かす互換ライブラリ
PACKAGES="libgcc-s1 libstdc++6 libsdl1.2debian"
TERMUX=https://service.termux-pacman.dev/gpkg
TERMUX_LIB=data/data/com.termux/files/usr/glibc/lib

cd "$(dirname "$0")"
OUT=app/src/main/assets/glibc
SDL=native/sdl-out
JNI=app/src/main/jniLibs
[ -f "$SDL/VERSION.txt" ] || { echo "先に native/build-sdl.sh を実行してください" >&2; exit 1; }
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT"
: > "$OUT/VERSIONS.txt"

# Android の ABI 名 : Debian のアーキテクチャ名 : Termux のアーキテクチャ名 : ローダのファイル名
for entry in arm64-v8a:arm64:aarch64:ld-linux-aarch64.so.1 armeabi-v7a:armhf:arm:ld-linux-armhf.so.3 \
             x86_64:amd64:x86_64:ld-linux-x86-64.so.2 x86:i386:i686:ld-linux.so.2; do
    abi=$(echo "$entry" | cut -d: -f1)
    arch=$(echo "$entry" | cut -d: -f2)
    tarch=$(echo "$entry" | cut -d: -f3)
    loader=$(echo "$entry" | cut -d: -f4)
    echo "== $abi ($arch / $tarch)"
    curl -fsSL "$MIRROR/dists/$SUITE/main/binary-$arch/Packages.xz" | xz -dc > "$WORK/Packages"
    root="$WORK/$abi/root"
    lib="$WORK/$abi/lib"
    mkdir -p "$root" "$lib"
    for pkg in $PACKAGES; do
        # 該当パッケージの段落から Filename / Version / SHA256 を取り出す
        info=$(awk -v p="$pkg" '
            $1=="Package:" { hit = ($2==p) }
            hit && $1=="Filename:" { f=$2 }
            hit && $1=="Version:" { v=$2 }
            hit && $1=="SHA256:" { s=$2 }
            END { print f, v, s }' "$WORK/Packages")
        set -- $info
        [ -n "${1:-}" ] || { echo "$pkg が見つかりません ($arch)" >&2; exit 1; }
        curl -fsSL "$MIRROR/$1" -o "$WORK/pkg.deb"
        echo "$3  $WORK/pkg.deb" | sha256sum -c --quiet -
        dpkg-deb -x "$WORK/pkg.deb" "$root"
        echo "$abi $pkg $2" >> "$OUT/VERSIONS.txt"
    done
    # ライブラリ本体だけを 1 つのディレクトリに集める (gconv などの下位ディレクトリは含めない)
    for dir in "$root"/usr/lib/*-linux-gnu*; do
        for f in "$dir"/*.so*; do
            [ -f "$f" ] || continue
            cp -L "$f" "$lib/$(basename "$f")"
        done
        # シンボリックリンクの実体 (libstdc++.so.6.0.33 など) は、リンク名で写した分と重複するので消す
        for f in "$dir"/*.so*; do
            [ -L "$f" ] && rm -f "$lib/$(basename "$(readlink "$f")")"
        done
    done
    # glibc 本体 (Termux 版)。パッケージ一覧からファイル名・版・ハッシュを取り、検証してから展開する
    curl -fsSL "$TERMUX/$tarch/gpkg.json" -o "$WORK/gpkg.json"
    set -- $(python3 -c "import json,sys; d=json.load(open(sys.argv[1]))['glibc']; print(d['FILENAME'], d['VERSION'], d['SHA256SUM'])" "$WORK/gpkg.json")
    curl -fsSL "$TERMUX/$tarch/$1" -o "$WORK/glibc.pkg.tar.xz"
    echo "$3  $WORK/glibc.pkg.tar.xz" | sha256sum -c --quiet -
    mkdir -p "$WORK/$abi/termux"
    tar xJf "$WORK/glibc.pkg.tar.xz" -C "$WORK/$abi/termux" "$TERMUX_LIB" 2>/dev/null
    # 実行時に読み込まれる共有ライブラリ (名前に版番号が付くもの) だけを写す。
    # 開発用のファイル (.a や、リンカ用の libc.so) は要らない。
    for f in "$WORK/$abi/termux/$TERMUX_LIB"/*.so.[0-9]*; do
        [ -f "$f" ] || continue
        cp -L "$f" "$lib/$(basename "$f")"
    done
    [ -f "$lib/$loader" ] || { echo "$abi 用のローダ $loader がありません" >&2; exit 1; }
    echo "$abi glibc(termux-pacman) $2" >> "$OUT/VERSIONS.txt"
    mkdir -p "$JNI/$abi"
    cp "$lib/$loader" "$JNI/$abi/libbrld.so"
    chmod 755 "$JNI/$abi/libbrld.so"

    [ -f "$SDL/$abi/lib/libSDL2-2.0.so.0" ] || { echo "$abi 用の SDL2 がありません" >&2; exit 1; }
    [ -f "$SDL/$abi/lib/libbrshim.so" ] || { echo "先に native/build-shim.sh を実行してください" >&2; exit 1; }
    cp "$SDL/$abi"/lib/*.so* "$lib/"
    echo "$abi $(cat "$SDL/VERSION.txt")" >> "$OUT/VERSIONS.txt"
    rm -f "$OUT/$abi.zip"
    (cd "$WORK/$abi" && zip -q -9 -r "$OLDPWD/$OUT/$abi.zip" lib)
    ls -la "$OUT/$abi.zip"
done
cat "$OUT/VERSIONS.txt"
