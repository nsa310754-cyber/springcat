#!/bin/sh
# 検知用の YARA (ルールでファイルを調べる道具) を Android NDK で 4 種類の CPU 向けにビルドし、
# app/src/main/jniLibs へ libyara.so という名前で置く。中身はコマンド yara の実行ファイル
# (名前が lib〜.so なのは ptyrun と同じ理由)。ビルド結果はリポジトリに入れてあるので、
# YARA の版を上げるときだけ実行すればよい。
#   使い方: ANDROID_NDK_HOME=/path/to/ndk native/build-yara.sh
# 必要なコマンド: curl, tar, sha256sum, autoreconf (autoconf / automake / libtool), make
#
# ソースは Debian が配っている公式の元ファイル (GitHub の v4.5.2 と同じ中身)。
# ハッシュ関数 (OpenSSL)、magic、cuckoo のモジュールは外部ライブラリが要るので入れない。
# pe・elf・math・time・string・console・dotnet・macho・dex のモジュールは使える。
set -eu
VERSION=4.5.2
SHA256=1f87056fcb10ee361936ee7b0548444f7974612ebb0e681734d8de7df055d1ec
URL=https://deb.debian.org/debian/pool/main/y/yara/yara_$VERSION.orig.tar.gz

cd "$(dirname "$0")"
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME に NDK の場所を指定してください}"
BIN=$(echo "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin)
OUT=$(pwd)/../app/src/main/jniLibs
WORK=$(pwd)/yara-build
mkdir -p "$WORK"

TARBALL=$WORK/yara-$VERSION.tar.gz
[ -f "$TARBALL" ] || curl -fsSL -o "$TARBALL" "$URL"
echo "$SHA256  $TARBALL" | sha256sum -c -
SRC=$WORK/yara-$VERSION
if [ ! -f "$SRC/configure" ]; then
    rm -rf "$SRC"
    tar -xzf "$TARBALL" -C "$WORK"
    (cd "$SRC" && autoreconf -fi >/dev/null)
fi

# Android の ABI 名 : コンパイラの接頭辞 (末尾の数字は最低 API レベル = アプリの minSdk) : configure の --host
for entry in arm64-v8a:aarch64-linux-android24:aarch64-linux-android \
             armeabi-v7a:armv7a-linux-androideabi24:arm-linux-androideabi \
             x86_64:x86_64-linux-android24:x86_64-linux-android \
             x86:i686-linux-android24:i686-linux-android; do
    abi=$(echo "$entry" | cut -d: -f1)
    cc=$(echo "$entry" | cut -d: -f2)
    host=$(echo "$entry" | cut -d: -f3)
    B=$WORK/$abi
    rm -rf "$B"
    mkdir -p "$B" "$OUT/$abi"
    # max-page-size=16384: メモリの単位が 16KB の端末でも起動できるようにする (ptyrun と同じ)
    (cd "$B" && "$SRC/configure" --host="$host" --disable-shared --enable-static \
        --without-crypto --disable-magic --disable-cuckoo \
        CC="$BIN/$cc-clang" AR="$BIN/llvm-ar" RANLIB="$BIN/llvm-ranlib" STRIP="$BIN/llvm-strip" \
        CFLAGS="-O2 -fPIE" LDFLAGS="-pie -Wl,--build-id=none -Wl,-z,max-page-size=16384" \
        >/dev/null && make -j"$(nproc)" yara >/dev/null)
    "$BIN/llvm-strip" -o "$OUT/$abi/libyara.so" "$B/yara"
    ls -la "$OUT/$abi/libyara.so"
done
echo "YARA $VERSION"
