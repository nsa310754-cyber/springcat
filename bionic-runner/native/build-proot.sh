#!/bin/sh
# PRoot (Termux 版) を Android NDK で 4 種類の CPU 向けに静的リンクでビルドし、app/src/main/jniLibs へ
#   libproot.so          … proot 本体 (中身は実行ファイル。名前が lib〜.so なのは ptyrun と同じ理由)
#   libproot-loader.so   … proot が Linux のプログラムを読み込むときに使う小さなローダ
#   libproot-loader32.so … 64 ビットの端末で 32 ビットの Linux のプログラムを動かすときのローダ
# として置く。ローダを別のファイルにする (PROOT_UNBUNDLE_LOADER) のは、Android 10 以降は
# アプリのデータ領域に書き出したファイルを実行できないため。jniLibs に置けば実行できる。
# PRoot は root 無しで「別の Linux (Alpine など) の中にいるように見せる」ための道具で、
# X11 (Xサーバー) を動かす Linux 環境に使う。ビルド結果はリポジトリに入れてあるので、
# PRoot の版を上げるときだけ実行すればよい。
#   使い方: ANDROID_NDK_HOME=/path/to/ndk native/build-proot.sh
# 必要なコマンド: curl, git, tar, sha256sum, make, gawk (proot の Makefile が使う)
set -eu
PROOT_REPO=https://github.com/termux/proot
PROOT_COMMIT=52d65a4dc140ca554d241a1438ddd4f6b00c9813
TALLOC_VERSION=2.5.0
TALLOC_SHA256=912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007

cd "$(dirname "$0")"
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME に NDK の場所を指定してください}"
BIN=$(echo "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin)
HERE=$(pwd)
OUT=$HERE/../app/src/main/jniLibs
WORK=$HERE/proot-build
mkdir -p "$WORK"

T=$WORK/talloc-$TALLOC_VERSION.tar.gz
[ -f "$T" ] || curl -fsSL -o "$T" "https://www.samba.org/ftp/talloc/talloc-$TALLOC_VERSION.tar.gz"
echo "$TALLOC_SHA256  $T" | sha256sum -c -
if [ ! -d "$WORK/proot/.git" ]; then
    git clone -q "$PROOT_REPO" "$WORK/proot"
fi
(cd "$WORK/proot" && git checkout -q -f "$PROOT_COMMIT")
# ashmem_memfd.c が string.h を読み込み忘れている (NDK の新しい版ではエラーになる)
sed -i '0,/^#include/s//#include <string.h>\n#include/' "$WORK/proot/src/extension/ashmem_memfd/ashmem_memfd.c"

# Android の ABI 名 : コンパイラの接頭辞。API 30 の定義で作るが、静的リンクなので古い Android でも動く
for entry in arm64-v8a:aarch64-linux-android30 armeabi-v7a:armv7a-linux-androideabi30 \
             x86_64:x86_64-linux-android30 x86:i686-linux-android30; do
    abi=${entry%%:*}
    cc=$BIN/${entry#*:}-clang
    B=$WORK/$abi
    rm -rf "$B"
    mkdir -p "$B" "$OUT/$abi"
    # talloc (proot が使うメモリ管理の部品) は waf を使わず、必要な 1 ファイルだけを直接コンパイルする
    tar -xzf "$T" -C "$B"
    TD=$B/talloc-$TALLOC_VERSION
    mkdir -p "$TD/andr"
    cp "$HERE/proot/talloc-config.h" "$TD/andr/config.h"
    cp "$TD/talloc.h" "$TD/andr/"
    "$cc" -O2 -fPIC -D__STDC_WANT_LIB_EXT1__=1 -I"$TD/andr" -I"$TD/lib/replace" -I"$TD" \
        -include "$TD/andr/config.h" -c "$TD/talloc.c" -o "$TD/andr/talloc.o"
    "$cc" -O2 -c "$HERE/proot/talloc-shim.c" -o "$TD/andr/shim.o"
    "$BIN/llvm-ar" rcs "$TD/andr/libtalloc.a" "$TD/andr/talloc.o" "$TD/andr/shim.o"

    cp -r "$WORK/proot/src" "$B/src"
    # 64 ビットの ARM では、32 ビットのローダ (-m32 を付けてコンパイルされる) を 32 ビットの ARM 用
    # コンパイラで作る必要がある。-m32 が付いたときだけ切り替える小さな仲介を挟む
    if [ "$abi" = arm64-v8a ]; then
        cat > "$B/cc" <<EOF
#!/bin/sh
for a in "\$@"; do
    if [ "\$a" = -m32 ]; then
        n=\$#; i=0
        while [ \$i -lt \$n ]; do x=\$1; shift; [ "\$x" = -m32 ] || set -- "\$@" "\$x"; i=\$((i + 1)); done
        exec "$BIN/armv7a-linux-androideabi30-clang" "\$@"
    fi
done
exec "$cc" "\$@"
EOF
        chmod +x "$B/cc"
        cc=$B/cc
    fi
    # CFLAGS・LDFLAGS は環境変数で渡す (make の引数にすると、Makefile 側の += が効かなくなる)
    # max-page-size=16384: メモリの単位が 16KB の端末でも起動できるようにする
    (cd "$B/src" && CFLAGS="-O2 -I$TD/andr" \
        LDFLAGS="-static -L$TD/andr -Wl,--build-id=none -Wl,-z,max-page-size=16384" \
        make -j"$(nproc)" CC="$cc" LD="$cc" STRIP="$BIN/llvm-strip" OBJCOPY="$BIN/llvm-objcopy" \
        OBJDUMP="$BIN/llvm-objdump" PROOT_UNBUNDLE_LOADER=/x proot loader/loader > "$B/build.log" 2>&1) \
        || { tail -30 "$B/build.log"; exit 1; }
    "$BIN/llvm-strip" "$B/src/proot" -o "$OUT/$abi/libproot.so"
    cp "$B/src/loader/loader" "$OUT/$abi/libproot-loader.so"
    if [ -f "$B/src/loader/loader-m32" ]; then
        cp "$B/src/loader/loader-m32" "$OUT/$abi/libproot-loader32.so"
    fi
    ls -l "$OUT/$abi"/libproot*
done
