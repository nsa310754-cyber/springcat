#!/bin/sh
# 確認用のプログラムをビルドし、末尾に目印付きのデータ (オーバーレイ) を付ける。
# 必要なもの: x86_64-w64-mingw32-gcc と i686-w64-mingw32-gcc (Debian / Ubuntu なら
#   sudo apt install gcc-mingw-w64-x86-64 gcc-mingw-w64-i686)、python3
set -eu
cd "$(dirname "$0")"
x86_64-w64-mingw32-gcc -O1 -o scanner-test.exe scanner-test.c
i686-w64-mingw32-gcc -O1 -o scanner-test-32.exe scanner-test.c
x86_64-w64-mingw32-strip scanner-test.exe
i686-w64-mingw32-strip scanner-test-32.exe
# 末尾のデータ: 目印 + 「暗号化した設定」に見立てた乱数のようなバイト列 (毎回同じ中身になるよう、
# 決まった種から作る)。本物のウイルスの設定のように、中身がランダムに近い
python3 - scanner-test.exe scanner-test-32.exe <<'PY'
import sys
x = 0x2545F4914F6CDD1D
data = bytearray()
for _ in range(6000):
    x ^= (x << 13) & 0xFFFFFFFFFFFFFFFF
    x ^= x >> 7
    x ^= (x << 17) & 0xFFFFFFFFFFFFFFFF
    data.append(x & 0xff)
overlay = b"BIONIC-RUNNER-SCANNER-TEST-OVERLAY" + bytes(data)
for f in sys.argv[1:]:
    with open(f, "ab") as o:
        o.write(overlay)
PY
ls -l scanner-test.exe scanner-test-32.exe
