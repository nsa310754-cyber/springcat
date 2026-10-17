// Block Destroy — Windows/PC 版 (Electron ラッパー)
//   ゲーム本体 game.html を Chromium で表示するデスクトップ版。
//   ・UserAgent に "BlockdestoryApp/1" を付与 (game.html の簡易ブラウザ判定を通す)
//   ・html2canvas の CDN 要求を同梱ファイルに差し替え (オフラインでもスクショ可)
//   ・実績解除時に「パソコン標準の実績達成音」を鳴らす (preload.js 経由)
//   ・オフラインでも遊べる。オンライン機能(ランキング/購入等)は
//     Firebase のオリジン検証の都合で無効になる場合があります。
const { app, BrowserWindow, session, ipcMain, shell } = require('electron');
const path = require('path');
const { spawn } = require('child_process');

// Windows で通知/AppUserModelID を安定させる
try { app.setAppUserModelId('site.ragdollp.blockdestory'); } catch (e) {}

// 🔔 パソコン標準の「実績達成音」を鳴らす。ウィンドウ等は出さず音だけ。
function playAchievementSound() {
  try {
    if (process.platform === 'win32') {
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
    // Linux 等: 標準ビープにフォールバック
    if (shell && shell.beep) shell.beep();
  } catch (e) {
    try { if (shell && shell.beep) shell.beep(); } catch (_) {}
  }
}

// preload から実績解除の通知を受け取ったら音を鳴らす
ipcMain.on('bd-achievement', () => { playAchievementSound(); });

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

  win.loadFile('game.html', { userAgent: ua });
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
