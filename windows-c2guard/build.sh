#!/bin/bash
# C2Guard.exe をビルドする (Linux + Mono の mcs)。外部 DLL なしの 1 ファイル。
set -euo pipefail
cd "$(dirname "$0")"
# Mono の標準ライブラリには .NET Framework に無い関数 (String.Split(char, options) など) があり、
# それに対してビルドすると Windows で「メソッドが見つかりません」になる。
# そのため .NET Framework 4.6.2 の参照アセンブリ (NuGet) に対してビルドする。
REF=.netfx/build/.NETFramework/v4.6.2
if [ ! -f "$REF/mscorlib.dll" ]; then
  rm -rf .netfx && mkdir -p .netfx
  curl -fsSL -o .netfx/ref.nupkg https://api.nuget.org/v3-flatcontainer/microsoft.netframework.referenceassemblies.net462/1.0.3/microsoft.netframework.referenceassemblies.net462.1.0.3.nupkg
  unzip -q -o .netfx/ref.nupkg -d .netfx
fi
mkdir -p dist
mcs -nologo -nostdlib -r:$REF/mscorlib.dll -target:winexe -platform:anycpu -optimize+ -codepage:utf8 -langversion:7 \
  -out:dist/C2Guard.exe -win32icon:src/app.ico \
  -r:$REF/System.dll -r:$REF/System.Core.dll -r:$REF/System.Drawing.dll -r:$REF/System.Windows.Forms.dll -r:$REF/System.Web.Extensions.dll \
  src/Core.cs src/Win.cs
ls -la dist/C2Guard.exe
mono dist/C2Guard.exe --selftest
