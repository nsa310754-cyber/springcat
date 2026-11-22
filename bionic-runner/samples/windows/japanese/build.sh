#!/bin/sh
# 日本語の表示の確認用プログラムをビルドする。
# 必要なもの: x86_64-w64-mingw32-gcc (Debian / Ubuntu なら sudo apt install gcc-mingw-w64-x86-64)
set -eu
cd "$(dirname "$0")"
x86_64-w64-mingw32-gcc -O2 -o jp-console.exe jp-console.c
x86_64-w64-mingw32-gcc -O2 -municode -mwindows -o jp-window.exe jp-window.c
x86_64-w64-mingw32-strip jp-console.exe jp-window.exe
ls -l jp-console.exe jp-window.exe
