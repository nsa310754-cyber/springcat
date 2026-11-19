#!/bin/bash
# ScreenRec.exe (画面録画) をビルドする (Linux + Mono の mcs)。外部 DLL なしの 1 ファイル。
set -euo pipefail
cd "$(dirname "$0")"
# Mono の標準ライブラリには .NET Framework に無い関数があり、Windows で「メソッドが見つかりません」になるため、
# .NET Framework 4.6.2 の参照アセンブリ (NuGet) に対してビルドする。
REF=.netfx/build/.NETFramework/v4.6.2
if [ ! -f "$REF/mscorlib.dll" ]; then
  rm -rf .netfx && mkdir -p .netfx
  curl -fsSL -o .netfx/ref.nupkg https://api.nuget.org/v3-flatcontainer/microsoft.netframework.referenceassemblies.net462/1.0.3/microsoft.netframework.referenceassemblies.net462.1.0.3.nupkg
  unzip -q -o .netfx/ref.nupkg -d .netfx
fi
mkdir -p dist
mcs -nologo -nostdlib -r:$REF/mscorlib.dll -target:winexe -platform:anycpu -optimize+ -codepage:utf8 -langversion:7 \
  -out:dist/ScreenRec.exe -win32icon:src/app.ico \
  -r:$REF/System.dll -r:$REF/System.Core.dll -r:$REF/System.Drawing.dll -r:$REF/System.Windows.Forms.dll \
  src/Avi.cs src/Recorder.cs src/SelfTest.cs src/Win.cs
ls -la dist/ScreenRec.exe
# 自己テスト: 実際に AVI を作り、構造を読み直して確かめる
OUT=$(mktemp -d)
mono dist/ScreenRec.exe --selftest "$OUT"
# ffmpeg があれば、できた AVI が本当に再生 (デコード) できるか確かめる
if command -v ffmpeg >/dev/null; then
  for f in "$OUT"/*.avi; do
    ffmpeg -v error -xerror -i "$f" -f null - && echo "PLAY OK  $(basename "$f")  $(ffprobe -v error -select_streams v -show_entries stream=codec_name,width,height,nb_frames,r_frame_rate -show_entries format=duration -of csv=p=0 "$f" | tr '\n' ' ')"
  done
fi
rm -rf "$OUT"
