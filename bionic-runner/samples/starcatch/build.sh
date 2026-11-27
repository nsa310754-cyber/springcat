#!/bin/sh
# Star Catch を arm64 の Android 端末 (Bionic Runner) 向けにビルドする。
# 必要なもの: aarch64-linux-gnu-gcc と、SDL2 の開発用ファイル (ヘッダと libSDL2.so)。
#   SDL2_PREFIX に SDL2 を入れた場所を指定する (例: native/build-sdl.sh の作業ディレクトリの
#   sdl-build/build-arm64-v8a/install)。
# 実行時は Bionic Runner 同梱の SDL2 を使うので、SDL2 は動的リンクする。
set -eu
cd "$(dirname "$0")"
: "${SDL2_PREFIX:?SDL2_PREFIX に SDL2 の場所を指定してください}"
aarch64-linux-gnu-gcc -O2 -Wall -Wextra -o starcatch.exe main.c \
    -I"$SDL2_PREFIX/include/SDL2" -D_REENTRANT -L"$SDL2_PREFIX/lib" -lSDL2 -lm
aarch64-linux-gnu-strip starcatch.exe
ls -la starcatch.exe
