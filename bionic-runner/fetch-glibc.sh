#!/bin/sh
# glibc ランタイム (ローダ + 基本ライブラリ) を Debian の公式パッケージから取り出し、
# native/build-sdl.sh が作った SDL2 一式 (アプリの画面と音へ出力する版) と合わせて、
# ABI ごとの zip にして app/src/main/assets/glibc/ へ置く。ビルド前に 1 回実行する。
# 先に native/build-sdl.sh を実行しておくこと。
# 必要なコマンド: curl, xz, dpkg-deb, zip
set -eu

SUITE=trixie
MIRROR=https://deb.debian.org/debian
# libsdl1.2debian は SDL 1.2 用の古いゲームを SDL2 の上で動かす互換ライブラリ
PACKAGES="libc6 libgcc-s1 libstdc++6 libsdl1.2debian"

cd "$(dirname "$0")"
OUT=app/src/main/assets/glibc
SDL=native/sdl-out
[ -f "$SDL/VERSION.txt" ] || { echo "先に native/build-sdl.sh を実行してください" >&2; exit 1; }
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT"
: > "$OUT/VERSIONS.txt"

# Android の ABI 名 : Debian のアーキテクチャ名
for pair in arm64-v8a:arm64 armeabi-v7a:armhf x86_64:amd64 x86:i386; do
    abi=${pair%%:*}
    arch=${pair##*:}
    echo "== $abi ($arch)"
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
    [ -f "$SDL/$abi/lib/libSDL2-2.0.so.0" ] || { echo "$abi 用の SDL2 がありません" >&2; exit 1; }
    cp "$SDL/$abi"/lib/*.so* "$lib/"
    echo "$abi $(cat "$SDL/VERSION.txt")" >> "$OUT/VERSIONS.txt"
    rm -f "$OUT/$abi.zip"
    (cd "$WORK/$abi" && zip -q -9 -r "$OLDPWD/$OUT/$abi.zip" lib)
    ls -la "$OUT/$abi.zip"
done
cat "$OUT/VERSIONS.txt"
