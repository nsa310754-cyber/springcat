#!/usr/bin/env bash
# Block Destroy ネイティブ実行ファイル版 (Web サーバー) を作る
#   bash native/build.sh <出力フォルダ>
#   ・Android 用 (Bionic): Android NDK の clang で静的リンク (arm64 / armv7 / x86_64)
#   ・Linux 用 (glibc):    aarch64-linux-gnu-gcc / gcc で静的リンク (arm64 / x86_64)
set -euo pipefail
OUT=${1:?出力フォルダを指定してください}
HERE=$(cd "$(dirname "$0")" && pwd); REPO=$(cd "$HERE/.." && pwd)
VER=$(grep -o 'versionName "[^"]*"' "$REPO/android/app/build.gradle" | cut -d'"' -f2)
NDK=${ANDROID_NDK:-$(ls -d /opt/android-sdk/ndk/* 2>/dev/null | sort -V | tail -1)}
mkdir -p "$OUT"; W=$(mktemp -d)
# ゲーム本体: html2canvas の CDN を同梱ファイルに差し替えてから gzip で埋め込む
python3 - "$REPO" "$W" "$VER" <<'PY'
import gzip, sys
repo, w, ver = sys.argv[1:4]
g = open(repo + '/android/app/game-src/game.html', encoding='utf-8').read()
g = g.replace('https://cdnjs.cloudflare.com/ajax/libs/html2canvas/1.4.1/html2canvas.min.js', 'html2canvas.min.js')
h = open(repo + '/android/app/src/main/assets/html2canvas.min.js', 'rb').read()
def arr(name, data):
    z = gzip.compress(data, 9, mtime=0)
    body = ','.join(str(b) for b in z)
    return 'const unsigned char %s[] = {%s};\nconst unsigned long %s_len = %d;\n' % (name, body, name, len(z))
open(w + '/data.c', 'w').write(arr('bd_game_gz', g.encode('utf-8')) + arr('bd_h2c_gz', h) + 'const char bd_version[] = "v%s";\n' % ver)
PY
CF="-O2 -s -static -Wall"
AF="-fno-emulated-tls"   # Android: TLS を本物の TLS にして、上の 64 バイトそろえを効かせる
B="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
"$B/aarch64-linux-android28-clang"    $CF $AF "$HERE/bdserve.c" "$W/data.c" -o "$OUT/blockdestroy-$VER-android-arm64-bionic"
"$B/armv7a-linux-androideabi28-clang" $CF $AF "$HERE/bdserve.c" "$W/data.c" -o "$OUT/blockdestroy-$VER-android-armv7-bionic"
"$B/x86_64-linux-android28-clang"     $CF $AF "$HERE/bdserve.c" "$W/data.c" -o "$OUT/blockdestroy-$VER-android-x86_64-bionic"
aarch64-linux-gnu-gcc $CF "$HERE/bdserve.c" "$W/data.c" -o "$OUT/blockdestroy-$VER-linux-arm64-glibc"
gcc                   $CF "$HERE/bdserve.c" "$W/data.c" -o "$OUT/blockdestroy-$VER-linux-x86_64-glibc"
rm -rf "$W"
ls -la "$OUT"
