#!/bin/sh
# WebView2 の確認用の Win32 (C++) プログラムをビルドする。
# 必要なもの: x86_64-w64-mingw32-g++ (Debian / Ubuntu なら sudo apt install g++-mingw-w64-x86-64)、curl、unzip
# WebView2 の SDK (Microsoft.Web.WebView2 1.0.3179.45。Microsoft の BSD 形式のライセンス) を NuGet から取り、
# SHA-256 を照合してから、WebView2.h と x64 の WebView2Loader.dll を使う (WebView2.h は大きいので
# リポジトリには入れない。WebView2Loader.dll は、ライセンスの表示 (LICENSE-WebView2-SDK.txt) と一緒に入れてある)。
set -eu
cd "$(dirname "$0")"
VER=1.0.3179.45
SHA=70bd381c5d67f97a6a80294001cde125ab5b24b6d36098ad901d8e15efd752d0
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
curl -sSL -o "$T/sdk.nupkg" "https://api.nuget.org/v3-flatcontainer/microsoft.web.webview2/$VER/microsoft.web.webview2.$VER.nupkg"
echo "$SHA  $T/sdk.nupkg" | sha256sum -c -
(cd "$T" && unzip -q sdk.nupkg 'build/native/include/*' 'build/native/x64/WebView2Loader.dll' LICENSE.txt)
x86_64-w64-mingw32-g++ -O2 -municode -mwindows -static -Icompat -I"$T/build/native/include" \
    -o hello-webview2.exe hello-webview2.cpp -lole32 -luuid
x86_64-w64-mingw32-strip hello-webview2.exe
cp "$T/build/native/x64/WebView2Loader.dll" WebView2Loader.dll
cp "$T/LICENSE.txt" LICENSE-WebView2-SDK.txt
ls -l hello-webview2.exe WebView2Loader.dll
