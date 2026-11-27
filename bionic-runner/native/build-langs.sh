#!/bin/sh
# ソースをそのまま実行するための言語処理系を Android NDK で 4 種類の CPU 向けにビルドし、
# app/src/main/jniLibs へ置く。中身は実行ファイル (名前が lib〜.so なのは ptyrun と同じ理由)。
#   liblua.so    Lua 5.4.8 (公式の実装そのもの)
#   libmpy.so    MicroPython 1.26.1 (小さな Python 3。標準ライブラリは一部だけ)
# ビルド結果はリポジトリに入れてあるので、版を上げるときだけ実行すればよい。
# Go 用の libgorun.so は build-gorun.sh が作る。
#   使い方: ANDROID_NDK_HOME=/path/to/ndk native/build-langs.sh
# 必要なコマンド: curl, tar, xz, sha256sum, make, gcc (MicroPython の補助ツールを PC 用に作るため), python3
set -eu
LUA=5.4.8
LUA_SHA=4f18ddae154e793e46eeab727c59ef1c0c0c2b744e7b94219710d76f530629ae
MPY=1.26.1
MPY_SHA=12be6514df6272c0fcb328122b534af6b12abdd52435c19f40ee1707cc43ac98

cd "$(dirname "$0")"
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME に NDK の場所を指定してください}"
BIN=$(echo "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin)
OUT=$(pwd)/../app/src/main/jniLibs
WORK=$(pwd)/langs-build
mkdir -p "$WORK"
# max-page-size=16384: メモリの単位が 16KB の端末でも起動できるようにする (ptyrun と同じ)
LINK="-pie -Wl,--build-id=none -Wl,-z,max-page-size=16384"

fetch() { # 名前 URL SHA256
    [ -f "$WORK/$1" ] || curl -fsSL -o "$WORK/$1" "$2"
    echo "$3  $WORK/$1" | sha256sum -c -
}
fetch lua-$LUA.tar.gz https://www.lua.org/ftp/lua-$LUA.tar.gz $LUA_SHA
fetch micropython-$MPY.tar.xz \
    https://github.com/micropython/micropython/releases/download/v$MPY/micropython-$MPY.tar.xz $MPY_SHA
[ -d "$WORK/lua-$LUA" ] || tar -xzf "$WORK/lua-$LUA.tar.gz" -C "$WORK"
if [ ! -d "$WORK/micropython-$MPY" ]; then
    # 配布物には全マイコン用のコードが入っていて大きいので、Unix 版に要る部分だけ取り出す
    tar -xJf "$WORK/micropython-$MPY.tar.xz" -C "$WORK" --exclude='*/ports/[^u]*' --exclude='*/tests' \
        --exclude='*/lib/pico-sdk' --exclude='*/lib/tinyusb' --exclude='*/lib/stm32lib' \
        --exclude='*/lib/nrfx' --exclude='*/lib/fsp' --exclude='*/lib/nxp_driver' \
        --exclude='*/lib/esp*' --exclude='*/lib/cy*' --exclude='*/lib/btstack' \
        --exclude='*/lib/mynewt-nimble' --exclude='*/lib/alif*' --exclude='*/lib/wiz*' \
        --exclude='*/lib/libhydrogen' --exclude='*/lib/open-amp' --exclude='*/lib/arduino-lib'
fi
MP=$WORK/micropython-$MPY
# MicroPython は、組み込みの Python コードをあらかじめ変換する道具 (mpy-cross) を PC 用に作ってから使う
[ -x "$MP/mpy-cross/build/mpy-cross" ] || make -C "$MP/mpy-cross" -j"$(nproc)" >/dev/null
# Android の pthread は libc に入っていて libpthread が無い。-lpthread を通すための空のライブラリ
mkdir -p "$WORK/empty"
[ -f "$WORK/empty/libpthread.a" ] || "$BIN/llvm-ar" rc "$WORK/empty/libpthread.a"

for pair in arm64-v8a:aarch64-linux-android24 armeabi-v7a:armv7a-linux-androideabi24 \
            x86_64:x86_64-linux-android24 x86:i686-linux-android24; do
    abi=${pair%%:*}
    cc="$BIN/${pair##*:}-clang"
    mkdir -p "$OUT/$abi"

    # Lua: 対話用の行編集 (readline) なしの素の構成。luac (コンパイラだけの道具) は入れない
    (cd "$WORK/lua-$LUA/src" && "$cc" -O2 -DLUA_USE_POSIX -fPIE $LINK \
        -o "$OUT/$abi/liblua.so" $(ls *.c | grep -v '^luac\.c$') -lm)
    "$BIN/llvm-strip" "$OUT/$abi/liblua.so"

    # MicroPython (Unix 版の standard 構成)。外部ライブラリが要る機能 (ffi・btree・SSL・Bluetooth) は外す
    (cd "$MP/ports/unix" && make -j"$(nproc)" VARIANT=standard BUILD="build-$abi" \
        MICROPY_PY_FFI=0 MICROPY_PY_BTREE=0 MICROPY_SSL_MBEDTLS=0 MICROPY_PY_SSL=0 \
        MICROPY_PY_BLUETOOTH=0 CC="$cc" STRIP="$BIN/llvm-strip" SIZE="$BIN/llvm-size" \
        CFLAGS_EXTRA="-fPIE -Wno-error" LDFLAGS_EXTRA="$LINK -L$WORK/empty" \
        >"$WORK/mpy-$abi.log" 2>&1) || { tail -c 2000 "$WORK/mpy-$abi.log"; exit 1; }
    "$BIN/llvm-strip" -o "$OUT/$abi/libmpy.so" "$MP/ports/unix/build-$abi/micropython"
    ls -la "$OUT/$abi/liblua.so" "$OUT/$abi/libmpy.so"
done
echo "Lua $LUA, MicroPython $MPY"
