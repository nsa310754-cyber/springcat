#!/bin/sh
# Bubble Pop (32bit 版) をビルドする。
# 必要なもの: i686-w64-mingw32-gcc (Debian / Ubuntu なら sudo apt install gcc-mingw-w64-i686)
set -eu
cd "$(dirname "$0")"
i686-w64-mingw32-gcc -O2 -Wall -municode -mwindows -o bubble-pop-32.exe bubble-pop.c
i686-w64-mingw32-strip bubble-pop-32.exe
ls -l bubble-pop-32.exe
