#!/bin/sh
# TypeScript の変換器 (公式の typescript.js) を取得して app/src/main/assets/run/ へ置く。
# .ts のファイルを実行するとき、アプリの中のブラウザ部品でこれを読み込んで JavaScript に変換する。
# 9MB ほどあるのでリポジトリには入れていない。ビルド前に 1 回実行する。
# 5 系の最後の版を使う (7 系からは JavaScript ではなく Go で書き直されていて、ブラウザでは動かない)。
# 必要なコマンド: curl, tar, sha256sum
set -eu
VERSION=5.9.3
SHA256=10e108c9cf7d5f2879053dff18515fb405abf2ccef63eaaf017d9c571687a1d3
cd "$(dirname "$0")"
OUT=app/src/main/assets/run
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
curl -fsSL -o "$WORK/ts.tgz" "https://registry.npmjs.org/typescript/-/typescript-$VERSION.tgz"
echo "$SHA256  $WORK/ts.tgz" | sha256sum -c -
tar -xzf "$WORK/ts.tgz" -C "$WORK" package/lib/typescript.js package/LICENSE.txt
mkdir -p "$OUT"
cp "$WORK/package/lib/typescript.js" "$OUT/typescript.js"
cp "$WORK/package/LICENSE.txt" "$OUT/typescript-LICENSE.txt"
ls -la "$OUT/typescript.js"
