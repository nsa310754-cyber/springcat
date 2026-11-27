#!/bin/sh
# Windows の exe の構造の検査 (PeInfo・ExeScanner.scanPe) の確かめを PC で動かす。
#   tools/run-pe-tests.sh [Wine の x86_64-windows フォルダ]
# 必要なもの: JDK (javac と java)。外部のライブラリは使わない
set -eu
cd "$(dirname "$0")/.."
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
SRC=app/src/main/java/site/springcat/bionicrunner
javac -encoding UTF-8 -d "$OUT" "$SRC/PeInfo.java" "$SRC/ExeScanner.java" \
    app/src/test/java/site/springcat/bionicrunner/PeScannerTest.java
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$OUT" site.springcat.bionicrunner.PeScannerTest "${1:-}" samples/windows
