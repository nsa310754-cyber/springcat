#!/bin/bash
# HtmlHome.exe をビルドする (Linux + Mono の mcs)。出来上がりは dist/HtmlHome.exe の 1 ファイル。
#   必要: mono-mcs, libmono-system-windows-forms4.0-cil, libmono-system-web-extensions4.0-cil,
#         libmono-system-io-compression4.0-cil, libmono-system-io-compression-filesystem4.0-cil
#   lib/ には NuGet の Microsoft.Web.WebView2 から取り出した DLL を置く (README 参照)。
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p dist
mcs -nologo -target:winexe -platform:anycpu -optimize+ -codepage:utf8 -warn:3 \
  -out:dist/HtmlHome.exe -win32icon:src/app.ico \
  -r:System.dll -r:System.Core.dll -r:System.Drawing.dll -r:System.Windows.Forms.dll \
  -r:System.Web.Extensions.dll -r:System.IO.Compression.dll -r:System.IO.Compression.FileSystem.dll \
  -r:lib/Microsoft.Web.WebView2.Core.dll -r:lib/Microsoft.Web.WebView2.WinForms.dll \
  -resource:lib/Microsoft.Web.WebView2.Core.dll,lib.Microsoft.Web.WebView2.Core.dll \
  -resource:lib/Microsoft.Web.WebView2.WinForms.dll,lib.Microsoft.Web.WebView2.WinForms.dll \
  -resource:lib/WebView2Loader.x64.dll,lib.WebView2Loader.x64.dll \
  -resource:lib/WebView2Loader.x86.dll,lib.WebView2Loader.x86.dll \
  -resource:lib/WebView2Loader.arm64.dll,lib.WebView2Loader.arm64.dll \
  -resource:launcher/index.html,launcher.index.html \
  src/Program.cs src/Store.cs src/MainForm.cs src/SpeedTest.cs
ls -la dist/HtmlHome.exe
echo "--- selftest (データ処理) ---"
mono dist/HtmlHome.exe --selftest
