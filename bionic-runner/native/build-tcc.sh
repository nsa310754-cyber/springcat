#!/bin/sh
# mkexe (端末の上で bionic 用・glibc 用の実行ファイルを作るコマンド) が使う C コンパイラ TCC
# (Tiny C Compiler) を、Android NDK でビルドする。64bit の ARM 端末用。
#   app/src/main/jniLibs/arm64-v8a/libtccbionic.so … bionic (Android の libc) 用の実行ファイルを作る TCC
#   app/src/main/jniLibs/arm64-v8a/libtccglibc.so  … glibc (普通の Linux の libc) 用の実行ファイルを作る TCC
#   app/src/main/assets/mkexe/arm64-v8a.zip        … TCC の補助 (libtcc1.a と TCC 自身のヘッダ)
# (mkexe 本体は app/src/main/assets/mkexe/mkexe.sh。こちらは直接編集する)
# どちらの TCC も Android の上で動く (bionic の実行ファイル)。違うのは作るものの形式だけ:
#   bionic: ローダ /system/bin/linker64、PIE (Android は位置独立の実行ファイルしか動かさない)、
#           起動用の部品は crtbegin_dynamic.o / crtend_android.o
#   glibc : ローダ /lib/ld-linux-aarch64.so.1、起動用の部品は crt1.o / crti.o / crtn.o
# ヘッダ・ライブラリ・起動用の部品の場所は、すべて -B で渡すフォルダからの相対 ({B}) にしてあり、
# mkexe が初回に公式の配布元から取ってきて並べる (アプリには同梱しない)。
#
# 加えて、PC の上で動く同じ設定の TCC (クロスコンパイラ) も作る。libtcc1.a のビルドと、PC での確認に使う
# (native/tcc-build/host-bionic-tcc・host-glibc-tcc)。
#   使い方: ANDROID_NDK_HOME=/path/to/ndk native/build-tcc.sh
# 必要なコマンド: git, gcc, make, zip
set -eu
REPO=https://repo.or.cz/tinycc.git
# Termux の tcc パッケージと同じ版 (2023-04-15 の mob)
COMMIT=6a24b762d3e1086dcffd002c68cb5ca3a33a5c6d

cd "$(dirname "$0")"
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME に NDK の場所を指定してください}"
BIN=$(echo "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin)
HERE=$(pwd)
JNI=$HERE/../app/src/main/jniLibs/arm64-v8a
ASSETS=$HERE/../app/src/main/assets/mkexe
WORK=$HERE/tcc-build
mkdir -p "$WORK" "$JNI" "$ASSETS"

if [ ! -d "$WORK/tinycc/.git" ]; then
    git clone -q "$REPO" "$WORK/tinycc"
fi
SRC=$WORK/tinycc
(cd "$SRC" && git checkout -q -f "$COMMIT" && git clean -qfdx)
# 予約語の表などの生成物 (tccdefs_.h) を作るために、PC 用に一度ビルドする
(cd "$SRC" && ./configure > /dev/null && make -j"$(nproc)" tccdefs_.h > /dev/null)

# 共通の設定。場所はすべて -B のフォルダ ({B}) から:
#   {B}/include        TCC 自身のヘッダ (stdarg.h など)      {B}/libtcc1.a  TCC の補助ライブラリ
#   {B}/sysroot/include libc のヘッダ                         {B}/crt        起動用の部品
#   {B}/lib            リンクするライブラリ
# 設定は C のヘッダに書いて -include で読ませる (コマンド行で文字列を渡すと引用符の扱いが面倒なため)
cat > "$WORK/cfg-common.h" <<'EOF'
#define ONE_SOURCE 1
#define TCC_TARGET_ARM64 1
#define CONFIG_TCC_BCHECK 0
#define CONFIG_TCC_BACKTRACE 0
#define CONFIG_TCCDIR "/nonexistent/mkexe"
#define CONFIG_TCC_CRTPREFIX "{B}/crt"
EOF
cat "$WORK/cfg-common.h" - > "$WORK/cfg-bionic.h" <<'EOF'
#define TARGETOS_ANDROID 1
#define CONFIG_TCC_PIE 1
#define CONFIG_NEW_DTAGS 1
#define CONFIG_TCC_ELFINTERP "/system/bin/linker64"
#define CONFIG_TCC_SYSINCLUDEPATHS "{B}/include:{B}/sysroot/include/aarch64-linux-android:{B}/sysroot/include"
#define CONFIG_TCC_LIBPATHS "{B}/lib:{B}:/system/lib64"
EOF
cat "$WORK/cfg-common.h" - > "$WORK/cfg-glibc.h" <<'EOF'
#define CONFIG_TCC_ELFINTERP "/lib/ld-linux-aarch64.so.1"
#define CONFIG_TCC_SYSINCLUDEPATHS "{B}/include:{B}/sysroot/include/aarch64-linux-gnu:{B}/sysroot/include"
#define CONFIG_TCC_LIBPATHS "{B}/lib:{B}"
EOF

build() { # 出力 形式 (bionic / glibc) コンパイラ [追加の引数...]
    out=$1; v=$2; cc=$3; shift 3
    # shellcheck disable=SC2086
    $cc -O2 -include "$WORK/cfg-$v.h" -I"$SRC" -o "$out" "$SRC/tcc.c" -lm -ldl "$@"
}

# PC 用 (クロスコンパイラ)
build "$WORK/host-bionic-tcc" bionic gcc -lpthread
build "$WORK/host-glibc-tcc" glibc gcc -lpthread
# 端末用 (Android 7 以降で動く。メモリの単位が 16KB の端末でも動くように)
NDKCC="$BIN/aarch64-linux-android24-clang -Wl,--build-id=none -Wl,-z,max-page-size=16384"
build "$WORK/libtccbionic.so" bionic "$NDKCC"
build "$WORK/libtccglibc.so" glibc "$NDKCC"
"$BIN/llvm-strip" "$WORK/libtccbionic.so" -o "$JNI/libtccbionic.so"
"$BIN/llvm-strip" "$WORK/libtccglibc.so" -o "$JNI/libtccglibc.so"

# libtcc1.a (TCC が作るプログラムに入る小さな補助関数)。それぞれの形式の TCC 自身でコンパイルする
for v in bionic glibc; do
    L=$WORK/libtcc1-$v
    rm -rf "$L"
    mkdir -p "$L"
    for f in lib-arm64.c stdatomic.c builtin.c dsohandle.c atomic.S; do
        "$WORK/host-$v-tcc" -B"$SRC" -I"$SRC/include" -nostdinc -c "$SRC/lib/$f" -o "$L/${f%.*}.o"
    done
    "$WORK/host-$v-tcc" -ar rcs "$L/libtcc1.a" "$L"/*.o
done

# アプリに入れる補助一式
Z=$WORK/zip
rm -rf "$Z"
mkdir -p "$Z/bionic" "$Z/glibc"
for v in bionic glibc; do
    cp -r "$SRC/include" "$Z/$v/include"
    cp "$WORK/libtcc1-$v/libtcc1.a" "$Z/$v/"
done
cp "$SRC/COPYING" "$Z/TCC-COPYING.txt"
rm -f "$ASSETS/arm64-v8a.zip"
(cd "$Z" && zip -q -r -X "$ASSETS/arm64-v8a.zip" .)
# 版の印 (アプリはこれが変わったときだけ展開し直す)
echo "tcc $COMMIT" > "$ASSETS/VERSIONS.txt"
ls -l "$JNI"/libtcc*.so "$ASSETS"/arm64-v8a.zip
