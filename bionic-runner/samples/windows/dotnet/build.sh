#!/bin/sh
# .NET の確認用のプログラムをビルドする (どの CPU でも動く AnyCPU の exe になる)。
# 必要なもの: mcs (Debian / Ubuntu なら sudo apt install mono-mcs libmono-system-windows-forms4.0-cil)
set -eu
cd "$(dirname "$0")"
mcs -optimize+ -out:hello-dotnet.exe hello-dotnet.cs
mcs -optimize+ -target:winexe -r:System.Windows.Forms.dll -r:System.Drawing.dll -out:hello-winforms.exe hello-winforms.cs
ls -l hello-dotnet.exe hello-winforms.exe
