#!/bin/bash
# C2Guard.exe をビルドする (Linux + Mono の mcs)。外部 DLL なしの 1 ファイル。
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p dist
mcs -nologo -target:winexe -platform:anycpu -optimize+ -codepage:utf8 -langversion:7 \
  -out:dist/C2Guard.exe -win32icon:src/app.ico \
  -r:System.dll -r:System.Core.dll -r:System.Drawing.dll -r:System.Windows.Forms.dll -r:System.Web.Extensions.dll \
  src/Core.cs src/Win.cs
ls -la dist/C2Guard.exe
mono dist/C2Guard.exe --selftest
