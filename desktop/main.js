// Block Destroy — Windows/PC 版 (Electron ラッパー)
//   ゲーム本体 game.html を Chromium で表示するデスクトップ版。
//   ・UserAgent に "BlockdestoryApp/1" を付与 (game.html の簡易ブラウザ判定を通す)
//   ・html2canvas の CDN 要求を同梱ファイルに差し替え (オフラインでもスクショ可)
//   ・実績解除時に「パソコン標準の実績達成音」を鳴らす (preload.js 経由)
//   ・オフラインでも遊べる。オンライン機能(ランキング/購入等)は
//     Firebase のオリジン検証の都合で無効になる場合があります。
const { app, BrowserWindow, session, ipcMain, shell } = require('electron');
const path = require('path');
const { spawn, spawnSync } = require('child_process');

// 🍷 Wine (Android の Winlator Bionic版 / glibc版 など) で動かす時の設定
//   Wine の上では Chromium の GPU・サンドボックス・ウィンドウの隠れ判定がうまく動かず、
//   真っ白/真っ黒な画面のまま止まることがあるため、Wine を見つけたら全部オフにしてソフト描画にする。
//   (Wine は C:\windows\system32 に winecfg.exe / wineboot.exe を置くので、それで見分ける)
const fs = require('fs');
function isWine() {
  if (process.platform !== 'win32') return false;
  try {
    const sys = (process.env.SystemRoot || 'C:\\windows') + '\\system32\\';
    return fs.existsSync(sys + 'winecfg.exe') || fs.existsSync(sys + 'wineboot.exe');
  } catch (e) { return false; }
}
const ON_WINE = isWine() || process.argv.includes('--wine');
if (ON_WINE) {
  app.disableHardwareAcceleration();
  app.commandLine.appendSwitch('no-sandbox');
  app.commandLine.appendSwitch('disable-gpu');
  app.commandLine.appendSwitch('disable-gpu-compositing');
  app.commandLine.appendSwitch('in-process-gpu');
  app.commandLine.appendSwitch('disable-features', 'CalculateNativeWinOcclusion,RendererCodeIntegrity');
}

// Windows で通知/AppUserModelID を安定させる
try { app.setAppUserModelId('site.ragdollp.blockdestory'); } catch (e) {}

// 🔔 パソコン標準の「実績達成音」を鳴らす。ウィンドウ等は出さず音だけ。
function playAchievementSound() {
  try {
    if (process.platform === 'win32' && !ON_WINE) {
      // Windows 標準の通知音 (Asterisk) を再生
      spawn('powershell', [
        '-NoProfile', '-WindowStyle', 'Hidden',
        '-Command', '[System.Media.SystemSounds]::Asterisk.Play()'
      ], { windowsHide: true, stdio: 'ignore' });
      return;
    }
    if (process.platform === 'darwin') {
      // macOS 標準のシステムサウンド
      spawn('afplay', ['/System/Library/Sounds/Glass.aiff'], { stdio: 'ignore' });
      return;
    }
    // Linux・Wine 等: 標準ビープにフォールバック (Wine には powershell が無い)
    if (shell && shell.beep) shell.beep();
  } catch (e) {
    try { if (shell && shell.beep) shell.beep(); } catch (_) {}
  }
}

// preload から実績解除の通知を受け取ったら音を鳴らす
ipcMain.on('bd-achievement', () => { playAchievementSound(); });

// 🍷 Wine では、日本語・記号・絵文字を Windows の「代わりのフォント探し」に任せると
//   何十個目かで止まってしまう (ページが表示されないまま固まる)。また Wine では
//   ページが読み込むフォント (@font-face) も使えない。そこで:
//   ① 同梱フォント (wfonts/ の Noto Sans JP・SC・KR・記号・数式・絵文字) を Wine の
//      C:\windows\Fonts に入れる (初回だけ。入れたら1回だけ自動で再起動して読み込ませる)
//   ② ゲームの文字はすべてそのフォントで描くよう、<head> の最初に CSS を差し込んだ写しを開く
const WINE_FONTS = [
  ['NotoSansJP_400Regular.ttf', 'Noto Sans JP Regular'], ['NotoSansJP_700Bold.ttf', 'Noto Sans JP Bold'],
  ['NotoSans_400Regular.ttf', 'Noto Sans Regular'], ['NotoSans_700Bold.ttf', 'Noto Sans Bold'],
  ['NotoSansSC_400Regular.ttf', 'Noto Sans SC Regular'], ['NotoSansSC_700Bold.ttf', 'Noto Sans SC Bold'],
  ['NotoSansKR_400Regular.ttf', 'Noto Sans KR Regular'], ['NotoSansKR_700Bold.ttf', 'Noto Sans KR Bold'],
  ['NotoSansSymbols_400Regular.ttf', 'Noto Sans Symbols Regular'], ['NotoSansSymbols_700Bold.ttf', 'Noto Sans Symbols Bold'],
  ['NotoSansSymbols2_400Regular.ttf', 'Noto Sans Symbols 2 Regular'], ['NotoSansMath_400Regular.ttf', 'Noto Sans Math Regular'],
  ['NotoEmoji_400Regular.ttf', 'Noto Emoji Regular'],
];
const WINE_FONT_LIST = "'Noto Sans JP', 'Noto Sans', 'Noto Sans SC', 'Noto Sans KR', 'Noto Sans Symbols 2', 'Noto Sans Symbols', 'Noto Sans Math', 'Noto Emoji'";
function installWineFonts() {
  const src = path.join(__dirname, 'wfonts');
  const dst = path.join(process.env.WINDIR || process.env.SystemRoot || 'C:\\windows', 'Fonts');
  let added = false;
  for (const [file, name] of WINE_FONTS) {
    try {
      const from = path.join(src, file), to = path.join(dst, file);
      if (!fs.existsSync(from)) continue;
      if (fs.existsSync(to) && fs.statSync(to).size === fs.statSync(from).size) continue;
      fs.mkdirSync(dst, { recursive: true });
      fs.copyFileSync(from, to);
      spawnSync('reg', ['add', 'HKLM\\Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts', '/v', name + ' (TrueType)', '/t', 'REG_SZ', '/d', file, '/f'], { windowsHide: true, stdio: 'ignore' });
      added = true;
    } catch (e) {}
  }
  return added;
}
function gameFileForThisPC() {
  const orig = path.join(__dirname, 'game.html');
  if (!ON_WINE) return orig;
  try {
    const css = '<style id="bdWineFonts">*, *::before, *::after { font-family: ' + WINE_FONT_LIST + ', sans-serif !important; }</style>';
    const html = fs.readFileSync(orig, 'utf8').replace(/<head>/i, '<head>' + css);
    const out = path.join(app.getPath('userData'), 'game-wine.html');
    fs.mkdirSync(path.dirname(out), { recursive: true });
    fs.writeFileSync(out, html);
    return out;
  } catch (e) { return orig; }
}
if (ON_WINE && !process.argv.includes('--fonts-ready') && installWineFonts()) {
  // 入れたフォントは次の起動から使えるので、1回だけ自動で起動し直す
  app.relaunch({ args: process.argv.slice(1).concat(['--fonts-ready']) });
  app.exit(0);
}

function createWindow() {
  const win = new BrowserWindow({
    width: 1180,
    height: 820,
    backgroundColor: '#f2a0f1',
    title: 'Block Destroy',
    autoHideMenuBar: true,
    icon: path.join(__dirname, 'icon.png'),
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      webSecurity: false,   // file:// でのモジュール/リソース読み込みを許可
      spellcheck: !ON_WINE, // Wine には Windows のスペルチェック機能が無い
      // Wine: canvas などで「sans-serif」などと書かれた文字も同梱フォントで描く
      defaultFontFamily: ON_WINE ? { standard: 'Noto Sans JP', sansSerif: 'Noto Sans JP', serif: 'Noto Sans JP', monospace: 'Noto Sans JP', cursive: 'Noto Sans JP', fantasy: 'Noto Sans JP' } : undefined,
    },
  });

  // ゲームのブラウザ判定を通すため UA に BlockdestoryApp を付与
  const ua = win.webContents.getUserAgent() + ' BlockdestoryApp/1';
  win.webContents.setUserAgent(ua);

  // html2canvas の CDN 要求を同梱ファイルへリダイレクト (オフラインでスクショ可)
  try {
    session.defaultSession.webRequest.onBeforeRequest(
      { urls: ['*://*/*html2canvas*'] },
      (details, cb) => {
        cb({ redirectURL: 'file://' + path.join(__dirname, 'html2canvas.min.js') });
      }
    );
  } catch (e) {}

  win.loadFile(gameFileForThisPC(), { userAgent: ua });
  return win;
}

app.whenReady().then(() => {
  createWindow();
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
