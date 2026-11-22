#!/bin/sh
# 日本語の表示の確認用プログラムをビルドする。
# 必要なもの: x86_64-w64-mingw32-gcc と i686-w64-mingw32-gcc
#   (Debian / Ubuntu なら sudo apt install gcc-mingw-w64-x86-64 gcc-mingw-w64-i686)
# -32 の付いたものは 32bit 版 (追加機能の「32bit の exe も動かす (試験中)」を入れると動く)
set -eu
cd "$(dirname "$0")"
x86_64-w64-mingw32-gcc -O2 -o jp-console.exe jp-console.c
x86_64-w64-mingw32-gcc -O2 -municode -mwindows -o jp-window.exe jp-window.c
i686-w64-mingw32-gcc -O2 -o jp-console-32.exe jp-console.c
i686-w64-mingw32-gcc -O2 -municode -mwindows -o jp-window-32.exe jp-window.c
x86_64-w64-mingw32-strip jp-console.exe jp-window.exe
i686-w64-mingw32-strip jp-console-32.exe jp-window-32.exe
ls -l jp-console.exe jp-window.exe jp-console-32.exe jp-window-32.exe
