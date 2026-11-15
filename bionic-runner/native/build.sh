#!/bin/sh
# 補助プログラム ptyrun を Android NDK で 4 種類の CPU 向けにビルドし、app/src/main/jniLibs へ置く。
# 名前が lib〜.so なのは、APK の lib/ に入れたファイルだけがインストール後も実行できるため
# (中身は共有ライブラリではなく実行ファイル)。ビルド結果はリポジトリに入れてあるので、
# ptyrun.c を変えたときだけ実行すればよい。
#   使い方: ANDROID_NDK_HOME=/path/to/ndk native/build.sh
set -eu
cd "$(dirname "$0")"
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME に NDK の場所を指定してください}"
BIN=$(echo "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin)
OUT=../app/src/main/jniLibs

# Android の ABI 名 : コンパイラの接頭辞 (末尾の数字は最低 API レベル = アプリの minSdk)
for pair in arm64-v8a:aarch64-linux-android24 armeabi-v7a:armv7a-linux-androideabi24 \
            x86_64:x86_64-linux-android24 x86:i686-linux-android24; do
    abi=${pair%%:*}
    mkdir -p "$OUT/$abi"
    # max-page-size=16384: メモリの単位が 16KB の端末 (Android 15 以降の新しい機種) でも
    # 起動できるようにする。指定しないと 4KB 境界で作られ、そうした端末では実行できない。
    "$BIN/${pair##*:}-clang" -O2 -Wall -Wextra -fPIE -pie -Wl,--build-id=none \
        -Wl,-z,max-page-size=16384 \
        -o "$OUT/$abi/libptyrun.so" ptyrun.c
    "$BIN/llvm-strip" "$OUT/$abi/libptyrun.so"
    ls -la "$OUT/$abi/libptyrun.so"
done
