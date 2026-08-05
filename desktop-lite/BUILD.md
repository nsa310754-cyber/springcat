# Block Destroy — PC(Windows)版 / Neutralino ラッパー

Chromiumを同梱せず、Windows標準の Edge WebView2 を使う軽量デスクトップ版(~2MB)。

## ビルド手順
1. ゲーム本体をリソースへコピー(UAブロック判定を無効化):
   cp ../android/app/game-src/game.html resources/index.html
   # resources/index.html 内の
   #   var isNativeApp = /BlockdestoryApp/.test(ua);
   # を
   #   var isNativeApp = true;
   # に置換(WebView2でブロックされないように)
   cp ../android/app/src/main/assets/html2canvas.min.js resources/html2canvas.min.js
   cp ../dist/apkpure/icon-512.png resources/icons/appIcon.png
2. バイナリ取得(初回のみ): npx @neutralinojs/neu update
3. ビルド: npx @neutralinojs/neu build --release
4. 出力 dist/BlockDestroy/ の BlockDestroy-win_x64.exe + resources.neu を
   同一フォルダに置いて配布(exe はリネーム可)。

Windows実行時は Edge WebView2 ランタイム(Win10/11標準)が必要。
