#!/bin/bash
# ExecSaver.exe (実行コード保存) をビルドする (Linux + Mono の mcs)。必要な DLL は exe の中に入れるので 1 ファイル。
set -euo pipefail
cd "$(dirname "$0")"

# Mono の標準ライブラリには .NET Framework に無い関数があり、それに対してビルドすると Windows で
# 「メソッドが見つかりません」になる。そのため .NET Framework 4.8 の参照アセンブリ (NuGet) に対してビルドする。
# (4.8 なのは、ILSpy のエンジンが .NET Standard 2.0 用で、その橋渡しが 4.7.1 以降にしか無いため)
REF=.netfx48/build/.NETFramework/v4.8
if [ ! -f "$REF/mscorlib.dll" ]; then
  rm -rf .netfx48 && mkdir -p .netfx48
  curl -fsSL -o .netfx48/ref.nupkg https://api.nuget.org/v3-flatcontainer/microsoft.netframework.referenceassemblies.net48/1.0.3/microsoft.netframework.referenceassemblies.net48.1.0.3.nupkg
  unzip -q -o .netfx48/ref.nupkg -d .netfx48
fi

# exe に入れるライブラリ (すべて MIT ライセンス)。バージョン固定。
#   ICSharpCode.Decompiler = ILSpy のエンジン (.NET → C#)、Iced = x86/x64 逆アセンブラー
DEPS=.deps
fetch() {  # パッケージ名 バージョン 中のDLLパス
  local id=$1 ver=$2 dll=$3 out=$DEPS/$(basename "$3")
  [ -f "$out" ] && return
  mkdir -p "$DEPS/pkg"
  curl -fsSL -o "$DEPS/pkg/$id.nupkg" "https://api.nuget.org/v3-flatcontainer/$id/$ver/$id.$ver.nupkg"
  unzip -q -o -j "$DEPS/pkg/$id.nupkg" "$dll" -d "$DEPS"
}
fetch icsharpcode.decompiler 8.2.0.7535 lib/netstandard2.0/ICSharpCode.Decompiler.dll
fetch iced 1.21.0 lib/net45/Iced.dll
fetch system.reflection.metadata 6.0.0 lib/net461/System.Reflection.Metadata.dll
fetch system.collections.immutable 6.0.0 lib/net461/System.Collections.Immutable.dll
fetch system.memory 4.5.4 lib/net461/System.Memory.dll
fetch system.runtime.compilerservices.unsafe 6.0.0 lib/net461/System.Runtime.CompilerServices.Unsafe.dll
fetch system.buffers 4.5.1 lib/net461/System.Buffers.dll
fetch system.numerics.vectors 4.5.0 lib/net46/System.Numerics.Vectors.dll
RES=()
for f in $DEPS/*.dll; do gzip -9 -n -c "$f" > "$f.gz"; RES+=("-resource:$f.gz,lib/$(basename "$f").gz"); done

mkdir -p dist
mcs -nologo -nostdlib -r:$REF/mscorlib.dll -target:winexe -platform:anycpu -optimize+ -codepage:utf8 -langversion:7 \
  -out:dist/ExecSaver.exe -win32icon:src/app.ico "${RES[@]}" \
  -r:$REF/System.dll -r:$REF/System.Core.dll -r:$REF/System.Drawing.dll -r:$REF/System.Windows.Forms.dll -r:$REF/System.Management.dll \
  -r:$REF/System.IO.Compression.dll -r:$REF/Facades/netstandard.dll -r:$REF/Facades/System.Runtime.dll \
  -r:$DEPS/ICSharpCode.Decompiler.dll -r:$DEPS/Iced.dll -r:$DEPS/System.Reflection.Metadata.dll -r:$DEPS/System.Collections.Immutable.dll \
  src/Core.cs src/Readable.cs src/SelfTest.cs src/Win.cs
ls -la dist/ExecSaver.exe
# 自己テスト: コマンドラインの解釈・形式判定・保存・コードへの変換 (DLL を置かずに exe 単体で動くことも確かめる)
T=$(mktemp -d); cp dist/ExecSaver.exe "$T/"
mono "$T/ExecSaver.exe" --selftest
rm -rf "$T"
