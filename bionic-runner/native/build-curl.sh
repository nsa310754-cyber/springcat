#!/bin/sh
# curl を Android NDK で 4 種類の CPU 向けにビルドし、app/src/main/jniLibs へ libcurl.so という
# 名前で置く。中身は実行ファイル (名前が lib〜.so なのは ptyrun と同じ理由。ライブラリではない)。
# HTTPS には mbedTLS を使い、どちらも静的リンクするので、ほかのライブラリは要らない。
# 証明書は Android が持っている信頼できる認証局の一覧 (/system/etc/security/cacerts) を使う。
# ビルド結果はリポジトリに入れてあるので、版を上げるときだけ実行すればよい。
#   使い方: ANDROID_NDK_HOME=/path/to/ndk native/build-curl.sh
# 必要なコマンド: curl, tar, xz, bzip2, sha256sum, cmake, ninja
set -eu
CURL=8.23.0
CURL_SHA=8dadd7d2536a314f982ad05a651b713f426bf7d02cc2c76cffb9796f2218e6c5
MBED=3.6.6
MBED_SHA=8fb65fae8dcae5840f793c0a334860a411f884cc537ea290ce1c52bb64ca007a

cd "$(dirname "$0")"
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME に NDK の場所を指定してください}"
BIN=$(echo "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin)
OUT=$(pwd)/../app/src/main/jniLibs
WORK=$(pwd)/curl-build
mkdir -p "$WORK"

fetch() { # 名前 URL SHA256
    [ -f "$WORK/$1" ] || curl -fsSL -o "$WORK/$1" "$2"
    echo "$3  $WORK/$1" | sha256sum -c -
}
fetch curl-$CURL.tar.xz https://curl.se/download/curl-$CURL.tar.xz $CURL_SHA
fetch mbedtls-$MBED.tar.bz2 \
    https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-$MBED/mbedtls-$MBED.tar.bz2 $MBED_SHA
[ -d "$WORK/curl-$CURL" ] || tar -xJf "$WORK/curl-$CURL.tar.xz" -C "$WORK"
[ -d "$WORK/mbedtls-$MBED" ] || tar -xjf "$WORK/mbedtls-$MBED.tar.bz2" -C "$WORK"

TOOLCHAIN=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake
SYSROOT=$(echo "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/sysroot)
# Android の ABI 名 : NDK の中のライブラリの置き場所の名前
for pair in arm64-v8a:aarch64-linux-android armeabi-v7a:arm-linux-androideabi \
            x86_64:x86_64-linux-android x86:i686-linux-android; do
    abi=${pair%%:*}
    P=$WORK/prefix-$abi
    # zlib は NDK の静的ライブラリを使う (指定しないと共有ライブラリを選んで、静的リンクに失敗する)
    ZLIB=$SYSROOT/usr/lib/${pair##*:}/libz.a
    # API 30 で作る (bash と同じ)。静的リンクなので、古い Android でも動く
    COMMON="-DCMAKE_TOOLCHAIN_FILE=$TOOLCHAIN -DANDROID_ABI=$abi -DANDROID_PLATFORM=30
        -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=$P -G Ninja"
    # mbedTLS (暗号と TLS)。プログラムやテストは作らない
    rm -rf "$WORK/mbed-$abi"
    cmake -S "$WORK/mbedtls-$MBED" -B "$WORK/mbed-$abi" $COMMON -DENABLE_PROGRAMS=OFF \
        -DENABLE_TESTING=OFF -DUSE_SHARED_MBEDTLS_LIBRARY=OFF >"$WORK/$abi-mbed.log" 2>&1
    cmake --build "$WORK/mbed-$abi" --target install >>"$WORK/$abi-mbed.log" 2>&1 \
        || { tail -c 2000 "$WORK/$abi-mbed.log"; exit 1; }
    # curl。外部のライブラリが要る機能 (HTTP/2・圧縮の一部・国際化ドメイン名など) は外す。
    # HAVE_MEMSET_EXPLICIT: 静的ライブラリには入っているが、宣言は API 34 からなので、無い側にする。
    # max-page-size=16384: メモリの単位が 16KB の端末でも起動できるようにする (ptyrun と同じ)
    rm -rf "$WORK/curl-$abi"
    cmake -S "$WORK/curl-$CURL" -B "$WORK/curl-$abi" $COMMON \
        -DBUILD_SHARED_LIBS=OFF -DBUILD_STATIC_LIBS=ON -DBUILD_STATIC_CURL=ON -DBUILD_LIBCURL_DOCS=OFF \
        -DBUILD_MISC_DOCS=OFF -DENABLE_CURL_MANUAL=OFF -DBUILD_TESTING=OFF -DBUILD_EXAMPLES=OFF \
        -DCURL_USE_MBEDTLS=ON -DCURL_USE_OPENSSL=OFF -DMBEDTLS_INCLUDE_DIR="$P/include" \
        -DMBEDTLS_LIBRARY="$P/lib/libmbedtls.a" -DMBEDX509_LIBRARY="$P/lib/libmbedx509.a" \
        -DMBEDCRYPTO_LIBRARY="$P/lib/libmbedcrypto.a" \
        -DCURL_CA_PATH=/system/etc/security/cacerts -DCURL_CA_BUNDLE=none -DCURL_CA_FALLBACK=OFF \
        -DCURL_USE_LIBPSL=OFF -DUSE_LIBIDN2=OFF -DCURL_USE_LIBSSH2=OFF -DUSE_NGHTTP2=OFF \
        -DCURL_BROTLI=OFF -DCURL_ZSTD=OFF -DENABLE_ARES=OFF -DCURL_DISABLE_LDAP=ON \
        -DCURL_ZLIB=ON -DZLIB_LIBRARY="$ZLIB" -DHAVE_MEMSET_EXPLICIT=OFF -DCMAKE_EXE_LINKER_FLAGS="-static -Wl,--build-id=none -Wl,-z,max-page-size=16384" \
        >"$WORK/$abi-curl.log" 2>&1
    cmake --build "$WORK/curl-$abi" --target curl >>"$WORK/$abi-curl.log" 2>&1 \
        || { tail -c 3000 "$WORK/$abi-curl.log"; exit 1; }
    mkdir -p "$OUT/$abi"
    "$BIN/llvm-strip" -o "$OUT/$abi/libcurl.so" "$WORK/curl-$abi/src/curl"
    ls -la "$OUT/$abi/libcurl.so"
done
echo "curl $CURL (mbedTLS $MBED)"
