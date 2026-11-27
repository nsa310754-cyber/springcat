#!/bin/sh
# Go のソースをそのまま実行する gorun (Go のインタープリタ yaegi に golang.org/x/term を足したもの) を
# 4 種類の CPU 向けにビルドし、app/src/main/jniLibs へ libgorun.so という名前で置く。
# 中身は実行ファイル (名前が lib〜.so なのは ptyrun と同じ理由)。ビルド結果はリポジトリに
# 入れてあるので、gorun/ を変えたときや yaegi の版を上げるときだけ実行すればよい。
#   使い方: ANDROID_NDK_HOME=/path/to/ndk native/build-gorun.sh
# 必要なもの: Go (go.mod の版より古ければ、go コマンドが必要な版を自動で取得する。
# 依存パッケージは go.sum の版を proxy.golang.org から取得する)
#
# xterm.go は `yaegi extract -name main golang.org/x/term` で生成したもの。
set -eu
cd "$(dirname "$0")"
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME に NDK の場所を指定してください}"
BIN=$(echo "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin)
OUT=$(pwd)/../app/src/main/jniLibs

# Android の ABI 名 : Go の GOARCH : コンパイラの接頭辞 (末尾の数字は最低 API レベル = アプリの minSdk)
# 1 個 20MB ほどあるので、ほぼ使われていない 32bit の x86 用は作らない (その端末では Go を実行できない)
for entry in arm64-v8a:arm64:aarch64-linux-android24 armeabi-v7a:arm:armv7a-linux-androideabi24 \
             x86_64:amd64:x86_64-linux-android24; do
    abi=$(echo "$entry" | cut -d: -f1)
    arch=$(echo "$entry" | cut -d: -f2)
    cc=$(echo "$entry" | cut -d: -f3)
    mkdir -p "$OUT/$abi"
    # GOOS=android: Android のアプリに禁止されているシステムコールを Go が避けるようになる。
    # -s -w: デバッグ情報を省いて小さくする。max-page-size: 16KB ページの端末でも起動できるように。
    (cd gorun && GOOS=android GOARCH="$arch" GOARM=7 CGO_ENABLED=1 CC="$BIN/$cc-clang" \
        go build -trimpath -buildmode=pie \
        -ldflags "-s -w -extldflags '-Wl,-z,max-page-size=16384 -Wl,--build-id=none'" \
        -o "$OUT/$abi/libgorun.so" .)
    ls -la "$OUT/$abi/libgorun.so"
done
