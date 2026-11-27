#!/bin/sh
# brshim (root なしで glibc 用プログラムを動かすための補助ライブラリ) を 4 種類の CPU 向けにビルドし、
# native/sdl-out/<ABI>/lib/ へ置く。fetch-glibc.sh が glibc 一式と一緒に APK へ入れる。
# 必要なもの: 各 CPU 向けの GNU クロスコンパイラ (build-sdl.sh と同じ)。
set -eu
cd "$(dirname "$0")"
for pair in arm64-v8a:aarch64-linux-gnu armeabi-v7a:arm-linux-gnueabihf \
            x86_64:x86_64-linux-gnu x86:i686-linux-gnu; do
    abi=${pair%%:*}
    host=${pair##*:}
    mkdir -p "sdl-out/$abi/lib"
    "$host-gcc" -O2 -Wall -Wextra -shared -fPIC -o "sdl-out/$abi/lib/libbrshim.so" brshim.c
    "$host-strip" --strip-unneeded "sdl-out/$abi/lib/libbrshim.so"
    ls -la "sdl-out/$abi/lib/libbrshim.so"
done
