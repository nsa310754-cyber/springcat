#!/bin/sh
# Linux 上で Windows 用の BionicRunnerPC.exe をビルドする。
# 必要なもの (Ubuntu / Debian): apt install mono-devel binutils-mingw-w64-x86-64 cpp
# 出力は .NET Framework 4.x 用の exe (Windows 10 / 11 には最初から入っているので追加インストール不要)。
set -eu
cd "$(dirname "$0")"
VERSION=1.2.0
OUT=dist/BionicRunnerPC-$VERSION.exe
mkdir -p dist build

# アイコン・マニフェスト・バージョン情報 (Win32 リソース)
x86_64-w64-mingw32-windres --preprocessor=cpp -c 65001 -O res \
	-I src/res src/res/app.rc -o build/app.res

mcs -nologo -target:winexe -platform:anycpu -sdk:4.5 -optimize+ -codepage:utf8 \
	-out:"$OUT" \
	-win32res:build/app.res \
	-r:System.dll -r:System.Core.dll -r:System.Drawing.dll -r:System.Windows.Forms.dll \
	-r:System.IO.Compression.dll \
	-resource:src/linux/setup.sh,setup.sh \
	-resource:src/res/app.ico,app.ico \
	-resource:src/res/linux.ico,linux.ico \
	-resource:src/res/termux.ico,termux.ico \
	src/*.cs

ls -l dist/*.exe
sha256sum dist/*.exe
