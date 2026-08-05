// Block Destroy — Windows/PC 版 (Electron ラッパー)
//   ゲーム本体 game.html を Chromium で表示するデスクトップ版。
//   ・UserAgent に "BlockdestoryApp/1" を付与 (game.html の簡易ブラウザ判定を通す)
//   ・html2canvas の CDN 要求を同梱ファイルに差し替え (オフラインでもスクショ可)
//   ・オフラインでも遊べる。オンライン機能(ランキング/購入等)は
//     Firebase のオリジン検証の都合で無効になる場合があります。
const { app, BrowserWindow, session } = require('electron');
const path = require('path');

function createWindow() {
  const win = new BrowserWindow({
    width: 1180,
    height: 820,
    backgroundColor: '#f2a0f1',
    title: 'Block Destroy',
    autoHideMenuBar: true,
    icon: path.join(__dirname, 'icon.png'),
    webPreferences: {
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
