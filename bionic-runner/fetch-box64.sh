#!/bin/sh
# Box64 (x86_64 の Linux のプログラムを ARM64 の上で速く動かす変換器) を集める。ビルド前に実行する
# (無くてもビルドはできる。そのときは x86_64 のプログラムを今までどおり QEMU で動かす)。
#
# Termux プロジェクトの配布物 (termux-pacman の glibc 系の box64-glibc、arm64 版) を使う。
# これは glibc を動的リンクした arm64 のプログラムなので、アプリ同梱の arm64 用 glibc
# (fetch-glibc.sh が用意する assets/glibc/arm64-v8a.zip と libbrld.so) の上で動かす。
#   - 本体は app/src/main/jniLibs/arm64-v8a/libbox64.so (実行ファイル。名前が lib〜.so なのは
#     ptyrun と同じ理由: APK の lib/ のファイルだけが、root なしでも実行を許されるため)。
#     デバッグ用の情報 (約 60MB) は動かすのに要らないので外す (llvm-strip がある場合)
#   - 付属のもの (x86_64 用の補助ライブラリ、設定ファイル box64.box64rc、ライセンス、
#     動くかどうかを確かめる小さな x86_64 のプログラム probe-x86_64) は
#     app/src/main/assets/box64/arm64-v8a.zip
# 配布元の一覧 (gpkg.json) に載っている SHA-256 で照合し、合わなければ使わない。
# 版は固定せず、その時点の一覧から取る。取った版は app/src/main/assets/box64/VERSIONS.txt に残す。
# Box64 は MIT ライセンス (Copyright (c) 2020 ptitSeb)。
# 必要なコマンド: curl, tar (xz に対応), sha256sum, zip, python3。あれば llvm-strip
set -eu

TERMUX=https://service.termux-pacman.dev/gpkg
PREFIX=data/data/com.termux/files/usr/glibc

cd "$(dirname "$0")"
OUT=app/src/main/assets/box64
JNI=app/src/main/jniLibs/arm64-v8a
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT" "$JNI"

echo "== box64-glibc (aarch64) の版を確認しています"
curl -fsSL "$TERMUX/aarch64/gpkg.json" -o "$WORK/gpkg.json"
set -- $(python3 -c "
import json, re, sys
d = json.load(open(sys.argv[1]))['box64-glibc']
f, v, s = d['FILENAME'], d['VERSION'], d['SHA256SUM']
# 一覧の中身がおかしければ使わない (ファイル名に / や .. を含む、ハッシュの形が違うなど)
assert re.fullmatch(r'[A-Za-z0-9._+-]+\.pkg\.tar\.xz', f) and re.fullmatch(r'[0-9a-f]{64}', s)
print(f, v, s)" "$WORK/gpkg.json")
FILE=$1 VERSION=$2 SHA=$3
echo "   $FILE"
curl -fsSL "$TERMUX/aarch64/$FILE" -o "$WORK/box64.pkg.tar.xz"
echo "$SHA  $WORK/box64.pkg.tar.xz" | sha256sum -c --quiet - || {
    echo "SHA-256 が一致しません。取得したものは使いません" >&2
    exit 1
}
mkdir -p "$WORK/x"
tar xJf "$WORK/box64.pkg.tar.xz" -C "$WORK/x"
SRC=$WORK/x/$PREFIX
[ -f "$SRC/bin/box64" ] || { echo "パッケージの中に box64 がありません" >&2; exit 1; }

# 本体。デバッグ用の情報だけを外す (動作に使う記号表は残す)
STRIP=$(command -v llvm-strip || true)
if [ -z "$STRIP" ] && [ -n "${ANDROID_NDK_HOME:-}" ]; then
    STRIP=$(ls "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/llvm-strip 2>/dev/null | head -n 1)
fi
if [ -n "$STRIP" ]; then
    "$STRIP" --strip-debug "$SRC/bin/box64" -o "$JNI/libbox64.so"
else
    echo "(llvm-strip が無いので、デバッグ用の情報を付けたまま置きます)"
    cp "$SRC/bin/box64" "$JNI/libbox64.so"
fi
chmod 755 "$JNI/libbox64.so"

# 付属のもの
Z=$WORK/zip
mkdir -p "$Z/x86_64"
cp "$SRC"/lib/box64-x86_64-linux-gnu/* "$Z/x86_64/"
cp "$SRC/etc/box64.box64rc" "$Z/box64.box64rc"
cp "$SRC/share/doc/box64-glibc/copyright" "$Z/LICENSE-box64.txt"
# 動くかどうかの確認用: 「box64-ok」と出して終わるだけの、x86_64 の静的リンクのプログラム (コンパイラ不要で作る)
python3 - "$Z/probe-x86_64" <<'PY'
import struct, sys
msg = b"box64-ok\n"
base = 0x400000
hdr = 64 + 56                               # ELF ヘッダ + プログラムヘッダ 1 個
code = bytes.fromhex(
    "b801000000"                            # mov eax, 1      (write)
    "bf01000000"                            # mov edi, 1      (標準出力)
    "488d3510000000"                        # lea rsi, [rip+16] (msg: 命令の後ろ 16 バイト目)
    "ba" + struct.pack("<I", len(msg)).hex() +  # mov edx, len
    "0f05"                                  # syscall
    "b83c000000"                            # mov eax, 60     (exit)
    "31ff"                                  # xor edi, edi
    "0f05")                                 # syscall
body = code + msg
size = hdr + len(body)
elf = (b"\x7fELF" + bytes([2, 1, 1, 0]) + bytes(8)
       + struct.pack("<HHIQQQIHHHHHH", 2, 62, 1, base + hdr, 64, 0, 0, 64, 56, 1, 0, 0, 0)
       + struct.pack("<IIQQQQQQ", 1, 5, 0, base, base, size, size, 0x1000))
open(sys.argv[1], "wb").write(elf + body)
PY
chmod 755 "$Z/probe-x86_64"
rm -f "$OUT/arm64-v8a.zip"
(cd "$Z" && zip -q -r -X "$OLDPWD/$OUT/arm64-v8a.zip" .)
echo "arm64-v8a box64-glibc $VERSION (termux-pacman)" > "$OUT/VERSIONS.txt"
ls -l "$JNI/libbox64.so" "$OUT/arm64-v8a.zip"
cat "$OUT/VERSIONS.txt"
