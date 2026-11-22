#!/bin/sh
# 補助プログラム ptyrun と br を Android NDK で 4 種類の CPU 向けにビルドし、app/src/main/jniLibs へ置く。
# 名前が lib〜.so なのは、APK の lib/ に入れたファイルだけがインストール後も実行できるため
# (中身は共有ライブラリではなく実行ファイル)。ビルド結果はリポジトリに入れてあるので、
# ptyrun.c や br.c を変えたときだけ実行すればよい。
#   使い方: ANDROID_NDK_HOME=/path/to/ndk native/build.sh
set -eu
cd "$(dirname "$0")"
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME に NDK の場所を指定してください}"
BIN=$(echo "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin)
OUT=../app/src/main/jniLibs

# Android の ABI 名 : コンパイラの接頭辞 (末尾の数字は最低 API レベル = アプリの minSdk)
for pair in arm64-v8a:aarch64-linux-android24 armeabi-v7a:armv7a-linux-androideabi24 \
            x86_64:x86_64-linux-android24 x86:i686-linux-android24; do
    abi=${pair%%:*}
    mkdir -p "$OUT/$abi"
    # max-page-size=16384: メモリの単位が 16KB の端末 (Android 15 以降の新しい機種) でも
    # 起動できるようにする。指定しないと 4KB 境界で作られ、そうした端末では実行できない。
    "$BIN/${pair##*:}-clang" -O2 -Wall -Wextra -fPIE -pie -Wl,--build-id=none \
        -Wl,-z,max-page-size=16384 \
        -o "$OUT/$abi/libptyrun.so" ptyrun.c
    "$BIN/llvm-strip" "$OUT/$abi/libptyrun.so"
    ls -la "$OUT/$abi/libptyrun.so"
    # br: プログラムからアプリの機能 (画面の上への表示・自動操作など) を呼ぶコマンド
    "$BIN/${pair##*:}-clang" -O2 -Wall -Wextra -fPIE -pie -Wl,--build-id=none \
        -Wl,-z,max-page-size=16384 \
        -o "$OUT/$abi/libbr.so" br.c
    "$BIN/llvm-strip" "$OUT/$abi/libbr.so"
    ls -la "$OUT/$abi/libbr.so"
done

# brcp932: Wine のコンソールのプログラムの出力 (Shift_JIS) を UTF-8 に直すフィルター (arm64 だけ。Wine は arm64 だけで動くため)
"$BIN/aarch64-linux-android24-clang" -O2 -Wall -Wextra -fPIE -pie -Wl,--build-id=none \
    -Wl,-z,max-page-size=16384 \
    -o "$OUT/arm64-v8a/libbrcp932.so" brcp932.c
"$BIN/llvm-strip" "$OUT/arm64-v8a/libbrcp932.so"
ls -la "$OUT/arm64-v8a/libbrcp932.so"

# brxwin: X サーバーに新しいウィンドウが出るまで待つ (wine.sh が、Windows のプログラムのウィンドウが出てから
# X11 の画面に切り替えるために使う。arm64 だけ)
"$BIN/aarch64-linux-android24-clang" -O2 -Wall -Wextra -fPIE -pie -Wl,--build-id=none \
    -Wl,-z,max-page-size=16384 \
    -o "$OUT/arm64-v8a/libbrxwin.so" brxwin.c
"$BIN/llvm-strip" "$OUT/arm64-v8a/libbrxwin.so"
ls -la "$OUT/arm64-v8a/libbrxwin.so"

# box64ld: Box64 が自分を起動し直すときのローダ (arm64 だけ。中身は box64ld.c の説明を参照)。
# ローダとして読み込まれるので、C のライブラリを使わず、自分で直す場所 (再配置) の無い形で作る
"$BIN/aarch64-linux-android24-clang" -O2 -Wall -Wextra -ffreestanding -fno-builtin -fno-stack-protector \
    -fPIE -nostdlib -static-pie -Wl,--no-dynamic-linker -Wl,-e,_start -Wl,--build-id=none \
    -Wl,-z,max-page-size=16384 -Wl,-z,noexecstack \
    -o "$OUT/arm64-v8a/libbox64ld.so" box64ld.c
ls -la "$OUT/arm64-v8a/libbox64ld.so"
