#!/bin/sh
# GNU bash を Android NDK で 4 種類の CPU 向けにビルドし、app/src/main/jniLibs へ libbash.so という
# 名前で置く。中身は実行ファイル (名前が lib〜.so なのは ptyrun と同じ理由)。
# 静的リンクなので、ほかのライブラリは要らない。ビルド結果はリポジトリに入れてあるので、
# bash の版を上げるときだけ実行すればよい。
#   使い方: ANDROID_NDK_HOME=/path/to/ndk native/build-bash.sh
# 必要なコマンド: curl, tar, patch, sha256sum, make
#
# ソースは GNU の公式の配布物と、公式の修正パッチ (bash53-001 〜 020)。
set -eu
VERSION=5.3
SHA256=0d5cd86965f869a26cf64f4b71be7b96f90a3ba8b3d74e27e8e9d9d5550f31ba
PATCHES=20
URL=https://ftp.gnu.org/gnu/bash

cd "$(dirname "$0")"
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME に NDK の場所を指定してください}"
BIN=$(echo "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin)
OUT=$(pwd)/../app/src/main/jniLibs
WORK=$(pwd)/bash-build
mkdir -p "$WORK/patches"

[ -f "$WORK/bash-$VERSION.tar.gz" ] || curl -fsSL -o "$WORK/bash-$VERSION.tar.gz" "$URL/bash-$VERSION.tar.gz"
echo "$SHA256  $WORK/bash-$VERSION.tar.gz" | sha256sum -c -
i=1
while [ $i -le $PATCHES ]; do
    p=$(printf 'bash53-%03d' $i)
    [ -f "$WORK/patches/$p" ] || curl -fsSL -o "$WORK/patches/$p" "$URL/bash-$VERSION-patches/$p"
    i=$((i + 1))
done
# パッチの中身が変わっていないことを確かめる (取得したときの値を patches.sha256 に残してある)
(cd "$WORK/patches" && sha256sum -c --quiet ../../bash-patches.sha256)

SRC=$WORK/bash-$VERSION
if [ ! -f "$SRC/.patched" ]; then
    rm -rf "$SRC"
    tar -xzf "$WORK/bash-$VERSION.tar.gz" -C "$WORK"
    for p in "$WORK"/patches/bash53-*; do (cd "$SRC" && patch -p0 -s < "$p"); done
    touch "$SRC/.patched"
fi

# Android の ABI 名 : コンパイラの接頭辞 : configure の --host
# API 30 で作る: bash が使う関数 (getgrent・getrandom・memfd_create) が揃うのが 30 から。
# 静的リンクなので、出来上がったものは古い Android (アプリの minSdk の 24) でも動く。
for entry in arm64-v8a:aarch64-linux-android30:aarch64-linux-android \
             armeabi-v7a:armv7a-linux-androideabi30:arm-linux-androideabi \
             x86_64:x86_64-linux-android30:x86_64-linux-android \
             x86:i686-linux-android30:i686-linux-android; do
    abi=$(echo "$entry" | cut -d: -f1)
    cc=$(echo "$entry" | cut -d: -f2)
    host=$(echo "$entry" | cut -d: -f3)
    B=$WORK/$abi
    rm -rf "$B"
    mkdir -p "$B" "$OUT/$abi"
    # bash_cv_*: 端末で動かして調べる項目 (クロスビルドでは調べられない) の答えを先に渡す。
    # strchrnul は bionic にあるのに「動かない」と判定され、bash 自前の版と衝突するので、ある側にする
    # max-page-size=16384: メモリの単位が 16KB の端末でも起動できるようにする (ptyrun と同じ)
    (cd "$B" && "$SRC/configure" --host="$host" --without-bash-malloc --disable-nls --enable-static-link \
        CC="$BIN/$cc-clang" AR="$BIN/llvm-ar" RANLIB="$BIN/llvm-ranlib" CFLAGS="-O2 -fPIE" \
        LDFLAGS="-Wl,--build-id=none -Wl,-z,max-page-size=16384" \
        bash_cv_func_strchrnul_works=yes bash_cv_getcwd_malloc=yes \
        bash_cv_job_control_missing=present bash_cv_sys_siglist=yes bash_cv_func_sigsetjmp=present \
        bash_cv_unusable_rtsigs=no bash_cv_func_ctype_nonascii=yes bash_cv_wcwidth_broken=no \
        bash_cv_dev_fd=standard bash_cv_dev_stdin=present >"$WORK/$abi-configure.log" 2>&1 \
        && make -j"$(nproc)" >"$WORK/$abi-make.log" 2>&1) \
        || { tail -c 2000 "$WORK/$abi-make.log" 2>/dev/null || tail -c 2000 "$WORK/$abi-configure.log"; exit 1; }
    "$BIN/llvm-strip" -o "$OUT/$abi/libbash.so" "$B/bash"
    ls -la "$OUT/$abi/libbash.so"
done
echo "bash $VERSION (patch $PATCHES)"
