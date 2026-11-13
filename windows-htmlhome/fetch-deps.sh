#!/bin/bash
# WebView2 SDK (NuGet: Microsoft.Web.WebView2) から、ビルドに必要な DLL を lib/ に取り出す。
set -euo pipefail
cd "$(dirname "$0")"
VER=1.0.2592.51
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
curl -fsSL -o "$TMP/wv2.zip" "https://api.nuget.org/v3-flatcontainer/microsoft.web.webview2/$VER/microsoft.web.webview2.$VER.nupkg"
unzip -q "$TMP/wv2.zip" -d "$TMP/x"
mkdir -p lib
cp "$TMP/x/lib/net462/Microsoft.Web.WebView2.Core.dll" "$TMP/x/lib/net462/Microsoft.Web.WebView2.WinForms.dll" lib/
for a in x64 x86 arm64; do cp "$TMP/x/build/native/$a/WebView2Loader.dll" "lib/WebView2Loader.$a.dll"; done
cp "$TMP/x/LICENSE.txt" lib/WebView2-LICENSE.txt
ls -la lib
