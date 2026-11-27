#!/bin/sh
# Neon Drift を Android NDK で 4 種類の CPU 向けにビルドする (bionic libc 用の ELF)。
#   使い方: ANDROID_NDK_HOME=/path/to/ndk ./build.sh
# できるもの: neondrift.exe (arm64。ほとんどのスマホはこれ) と out/<ABI>/neondrift.exe
# 名前は .exe だが中身は Android 用の ELF。Windows では動かない。
set -eu
cd "$(dirname "$0")"
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME に NDK の場所を指定してください}"
BIN=$(echo "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin)
for pair in arm64-v8a:aarch64-linux-android24 armeabi-v7a:armv7a-linux-androideabi24 \
            x86_64:x86_64-linux-android24 x86:i686-linux-android24; do
    abi=${pair%%:*}
    mkdir -p "out/$abi"
    # max-page-size=16384: メモリの単位が 16KB の端末 (Android 15 以降の新しい機種) でも動かすため
    "$BIN/${pair##*:}-clang" -O2 -Wall -Wextra -fPIE -pie -Wl,--build-id=none \
        -Wl,-z,max-page-size=16384 -o "out/$abi/neondrift.exe" main.c -lm
    "$BIN/llvm-strip" "out/$abi/neondrift.exe"
done
cp out/arm64-v8a/neondrift.exe neondrift.exe
ls -la neondrift.exe out/*/neondrift.exe
