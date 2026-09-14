# Block Destroy — PC(Windows)版 / Neutralino ラッパー

Chromiumを同梱せず、Windows標準の Edge WebView2 を使う軽量デスクトップ版
(Windows配布は exe 約2.5MB + resources.neu 約3.5MB ≒ 合計6MB)。

## ビルド手順
1. ゲーム本体をリソースへコピー＆パッチ（下記は scripts で自動化推奨）:
   - `cp ../android/app/game-src/game.html resources/index.html`
   - `resources/index.html` を次のように書き換える:
     - `var isNativeApp = /BlockdestoryApp/.test(ua);` → `var isNativeApp = true;`
       (WebView2 には BlockdestoryApp UA が無いためブロックされないように)
     - html2canvas の CDN URL
       `https://cdnjs.cloudflare.com/ajax/libs/html2canvas/1.4.1/html2canvas.min.js`
       → `html2canvas.min.js` (オフラインでもスクショ可)
     - `</body>` 直前に Neutralino クライアント + 実績解除音のスクリプトを注入:
       ```html
       <script src="js/neutralino.js"></script>
       <script>
       (function(){
         try{ Neutralino.init(); }catch(e){}
         window.addEventListener('bd-achievement-unlocked', function(){
           try{
             var os=(typeof NL_OS!=='undefined')?NL_OS:'';
             var cmd = os==='Windows'
               ? 'powershell -NoProfile -WindowStyle Hidden -Command "[System.Media.SystemSounds]::Asterisk.Play()"'
               : (os==='Darwin' ? 'afplay /System/Library/Sounds/Glass.aiff'
                                : 'paplay /usr/share/sounds/freedesktop/stereo/complete.oga');
             Neutralino.os.execCommand(cmd, { background:true });
           }catch(e){}
         });
       }());
       </script>
       ```
   - `cp ../android/app/src/main/assets/html2canvas.min.js resources/html2canvas.min.js`
   - `cp ../dist/playstore/icon-512.png resources/icons/appIcon.png`
2. `neutralino.config.json` の要点（このリポジトリでは設定済み）:
   - `enableNativeAPI: true`（実績音の os.execCommand に必要）
   - `nativeAllowList: ["app.*","os.execCommand","events.*","window.*"]`
3. バイナリ取得(初回のみ): `npx @neutralinojs/neu update`
4. ビルド: `npx @neutralinojs/neu build --release`
5. 出力 `dist/BlockDestroy/` から Windows配布物を作る:
   - `BlockDestroy-win_x64.exe`（`BlockDestroy.exe` にリネーム可）
   - `resources.neu`
   この2ファイルを同一フォルダに置いて配布。

## 実行条件
- Windows 10/11 標準の **Edge WebView2 ランタイム** が必要（多くの環境に既に導入済み）。
  未導入なら Microsoft 公式の「Edge WebView2 Runtime（Evergreen）」を入れる。
- WebView2Loader は近年の Neutralino では静的リンクされ、別DLLは不要。

## 実績の効果音
- ゲーム本体が実績解除時に `bd-achievement-unlocked` という DOM イベントを発火する。
  PC版ではそれを購読し、OS標準の通知音を鳴らす（Windows: SystemSounds.Asterisk）。
