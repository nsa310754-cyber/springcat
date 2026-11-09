#!/usr/bin/env bash
# Block Destroy — Windows x64 版 (Electron) を作る。Winlator (Bionic版 / glibc版) でもそのまま動く。
#   使い方: bash desktop/build-winlator.sh <出力フォルダ>
#   ・Electron の Windows 版を取ってきて electron.exe → BlockDestroy.exe にする
#   ・ゲーム本体・main.js・preload.js と、Wine 用のフォント (wfonts/) を resources/app に入れる
#   ・wine64 があれば、exe のアイコンとバージョン情報も入れる (rcedit)
set -euo pipefail
OUT=${1:?出力フォルダを指定してください}
REPO=$(cd "$(dirname "$0")/.." && pwd)
EV=31.7.7
VER=$(grep -o 'versionName "[^"]*"' "$REPO/android/app/build.gradle" | cut -d'"' -f2)
mkdir -p "$OUT" && cd "$OUT"
[ -f electron-win.zip ] || curl -sSL -o electron-win.zip "https://github.com/electron/electron/releases/download/v$EV/electron-v$EV-win32-x64.zip"
rm -rf BlockDestroy && mkdir BlockDestroy && (cd BlockDestroy && unzip -q ../electron-win.zip && mv electron.exe BlockDestroy.exe && rm -f resources/default_app.asar)
APP=BlockDestroy/resources/app && mkdir -p "$APP/wfonts"
cp "$REPO/desktop/main.js" "$REPO/desktop/preload.js" "$APP/"
cp "$REPO/android/app/game-src/game.html" "$APP/game.html"
cp "$REPO/android/app/src/main/assets/html2canvas.min.js" "$APP/"
cp "$REPO/dist/playstore/icon-512.png" "$APP/icon.png"
node -e "require('fs').writeFileSync('$APP/package.json', JSON.stringify({ name:'blockdestroy-desktop', productName:'BlockDestroy', version:'$VER', main:'main.js', author:'ragdollp', license:'UNLICENSED' }, null, 2))"
# Wine 用フォント (Google Fonts の Noto。OFL ライセンス)
mkdir -p fonts && (cd fonts && [ -f package.json ] || npm init -y >/dev/null; for p in noto-sans-jp noto-sans-sc noto-sans-kr noto-sans noto-sans-symbols noto-sans-symbols-2 noto-sans-math noto-emoji; do [ -d node_modules/@expo-google-fonts/$p ] || npm i --silent @expo-google-fonts/$p; done)
for f in NotoSans_400Regular NotoSans_700Bold NotoSansJP_400Regular NotoSansJP_700Bold NotoSansSC_400Regular NotoSansSC_700Bold NotoSansKR_400Regular NotoSansKR_700Bold NotoSansSymbols_400Regular NotoSansSymbols_700Bold NotoSansSymbols2_400Regular NotoSansMath_400Regular NotoEmoji_400Regular; do
  cp "$(find fonts/node_modules/@expo-google-fonts -name "$f.ttf" | head -1)" "$APP/wfonts/"
done
# アイコン・バージョン情報 (wine64 がある時だけ)
if command -v wine64 >/dev/null 2>&1 || [ -x /usr/lib/wine/wine64 ]; then
  export PATH=/usr/lib/wine:$PATH WINEDEBUG=-all
  (mkdir -p rc && cd rc && { [ -f package.json ] || npm init -y >/dev/null; } && [ -d node_modules/rcedit ] || npm i --silent rcedit@4.0.1) || true
  command -v convert >/dev/null && convert "$REPO/dist/playstore/icon-512.png" -define icon:auto-resize=256,128,64,48,32,16 icon.ico || true
  [ -f icon.ico ] && xvfb-run -a wine64 rc/node_modules/rcedit/bin/rcedit-x64.exe BlockDestroy/BlockDestroy.exe --set-icon icon.ico \
    --set-version-string ProductName "Block Destroy" --set-version-string FileDescription "Block Destroy" --set-version-string CompanyName ragdollp \
    --set-file-version "$VER.0" --set-product-version "$VER" >/dev/null 2>&1 || true
fi
rm -f "BlockDestroy-$VER-Windows-x64-Winlator.zip" && zip -qr -9 "BlockDestroy-$VER-Windows-x64-Winlator.zip" BlockDestroy
echo "done: $OUT/BlockDestroy-$VER-Windows-x64-Winlator.zip"
