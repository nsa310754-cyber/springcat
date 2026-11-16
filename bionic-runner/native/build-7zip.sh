#!/bin/sh
# 圧縮・解凍用の 7-Zip (コマンド版 7zz) を Android NDK で 4 種類の CPU 向けにビルドし、
# app/src/main/jniLibs へ lib7zz.so という名前で置く。中身は実行ファイル
# (名前が lib〜.so なのは ptyrun と同じ理由)。ビルド結果はリポジトリに入れてあるので、
# 7-Zip の版を上げるときだけ実行すればよい。
#   使い方: ANDROID_NDK_HOME=/path/to/ndk native/build-7zip.sh
# 必要なコマンド: curl, tar, xz, sha256sum, make
#
# ソースは 7-Zip 公式の配布物。ライセンスは LGPL (一部 BSD)、RAR の展開部分は unRAR の制限付き
# (RAR を「作る」ことには使えない。このアプリは RAR の展開にだけ使う)。
# 全形式入りの 7zz (Bundles/Alone2) を作る。アセンブリ版の高速化コードは使わない。
set -eu
VERSION=26.06
FILE=7z2606-src.tar.xz
SHA256=a4e374c8f864f51df48c2cfd0e1413ec3b2ea030deae852d6a655402ebb61ac4
URL=https://github.com/ip7z/7zip/releases/download/$VERSION/$FILE

cd "$(dirname "$0")"
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME に NDK の場所を指定してください}"
BIN=$(echo "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin)
OUT=$(pwd)/../app/src/main/jniLibs
WORK=$(pwd)/7zip-build
mkdir -p "$WORK"

[ -f "$WORK/$FILE" ] || curl -fsSL -o "$WORK/$FILE" "$URL"
echo "$SHA256  $WORK/$FILE" | sha256sum -c -
SRC=$WORK/src
if [ ! -d "$SRC/CPP" ]; then
    mkdir -p "$SRC"
    tar -xJf "$WORK/$FILE" -C "$SRC"
fi

# Android の ABI 名 : コンパイラの接頭辞 (末尾の数字は最低 API レベル = アプリの minSdk)
for pair in arm64-v8a:aarch64-linux-android24 armeabi-v7a:armv7a-linux-androideabi24 \
            x86_64:x86_64-linux-android24 x86:i686-linux-android24; do
    abi=${pair%%:*}
    cc=${pair##*:}
    mkdir -p "$OUT/$abi"
    # O は中間ファイルの置き場。ABI ごとに分ける。
    # -Wno-error: NDK の clang は公式が想定する版と警告の出方が違うので、警告でビルドを止めない。
    # LIB2: Android の pthread は libc に入っていて、libpthread という名前のライブラリは無い。
    # max-page-size=16384: メモリの単位が 16KB の端末でも起動できるようにする (ptyrun と同じ)。
    (cd "$SRC/CPP/7zip/Bundles/Alone2" && make -j"$(nproc)" -f makefile.gcc \
        O="b/$abi" CC="$BIN/$cc-clang" CXX="$BIN/$cc-clang++" USE_CLANG=1 \
        CFLAGS_WARN="-Wno-error -w" LIB2="-ldl" \
        LDFLAGS_STATIC_3="-static-libstdc++ -pie -Wl,--build-id=none -Wl,-z,max-page-size=16384" \
        >"$WORK/$abi.log" 2>&1) || { tail -c 2000 "$WORK/$abi.log"; exit 1; }
    "$BIN/llvm-strip" -o "$OUT/$abi/lib7zz.so" "$SRC/CPP/7zip/Bundles/Alone2/b/$abi/7zz"
    ls -la "$OUT/$abi/lib7zz.so"
done
echo "7-Zip $VERSION"
