'use strict';
/**
 * SpringCat Files — Windows 版 (Electron)。
 * UI は Android 版と同じ app.html。ファイル操作は bridge.js が担当する。
 */
const { app, BrowserWindow, ipcMain, shell, net, Menu } = require('electron');
const fs = require('fs');
const path = require('path');
const { createBridge, out } = require('./bridge');

let win = null;

const bridge = createBridge({
  shell,
  isOnline: () => net.isOnline(),
  toast: (msg) => {
    if (win) win.webContents.executeJavaScript(`window.toast && window.toast(${JSON.stringify(String(msg))})`).catch(() => {});
  },
});

ipcMain.on('bridge:methods', (e) => { e.returnValue = Object.keys(bridge); });
ipcMain.on('bridge', async (e, name, args) => {
  // UI 本体 (メインフレーム) 以外からの呼び出しは拒否 (プレビュー用 iframe 等)。
  if (!win || e.senderFrame !== win.webContents.mainFrame) {
    e.returnValue = JSON.stringify({ ok: false, error: 'denied' });
    return;
  }
  const fn = bridge[name];
  if (typeof fn !== 'function') { e.returnValue = JSON.stringify({ ok: false, error: 'unknown: ' + name }); return; }
  try {
    const r = await fn(...(Array.isArray(args) ? args : []));
    e.returnValue = r === undefined ? null : r;
  } catch (err) {
    e.returnValue = JSON.stringify({ ok: false, error: String(err && err.message || err) });
  }
});

/** 起動引数 (フォルダを exe にドロップ / 「プログラムから開く」) で開く場所。 */
function startPathFrom(argv) {
  for (const a of argv.slice(app.isPackaged ? 1 : 2)) {
    if (!a || a.startsWith('-')) continue;
    try {
      const st = fs.statSync(a);
      return out(st.isDirectory() ? a : path.dirname(a));
    } catch {}
  }
  return '';
}

function openStartPath(p) {
  if (win && p) win.webContents.executeJavaScript(`typeof navTo==='function' && navTo(${JSON.stringify(p)})`).catch(() => {});
}

function createWindow() {
  Menu.setApplicationMenu(null);
  win = new BrowserWindow({
    width: 1200,
    height: 820,
    minWidth: 360,
    minHeight: 480,
    backgroundColor: '#0f1420',
    title: 'SpringCat Files',
    icon: path.join(__dirname, 'icon.png'),
    autoHideMenuBar: true,
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      spellcheck: false,
    },
  });

  win.once('ready-to-show', () => win.show());
  win.loadFile(path.join(__dirname, 'app.html'));

  // 外部リンクは既定のブラウザで。アプリ画面自体は遷移させない。
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:\/\//.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  win.webContents.on('will-navigate', (e, url) => {
    e.preventDefault();
    if (/^https?:\/\//.test(url)) shell.openExternal(url);
  });

  // キーボード: F11 全画面 / Ctrl +, -, 0 で拡大縮小 / Ctrl+Shift+I 開発者ツール
  win.webContents.on('before-input-event', (e, input) => {
    if (input.type !== 'keyDown') return;
    const wc = win.webContents;
    if (input.key === 'F11') { win.setFullScreen(!win.isFullScreen()); e.preventDefault(); }
    else if (input.control && (input.key === '+' || input.key === '=' || input.key === ';')) { wc.setZoomLevel(Math.min(wc.getZoomLevel() + 0.5, 4)); e.preventDefault(); }
    else if (input.control && input.key === '-') { wc.setZoomLevel(Math.max(wc.getZoomLevel() - 0.5, -3)); e.preventDefault(); }
    else if (input.control && input.key === '0') { wc.setZoomLevel(0); e.preventDefault(); }
    else if (input.control && input.shift && (input.key === 'I' || input.key === 'i')) { wc.toggleDevTools(); e.preventDefault(); }
  });

  const start = startPathFrom(process.argv);
  if (start) win.webContents.once('did-finish-load', () => setTimeout(() => openStartPath(start), 300));

  // マウスのサイドボタン「戻る」(Windows は app-command で届く)
  win.on('app-command', (_e, cmd) => {
    if (cmd === 'browser-backward') win.webContents.executeJavaScript('window.onAndroidBack && window.onAndroidBack()').catch(() => {});
  });

  win.on('closed', () => { win = null; });
}

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', (_e, argv) => {
    if (!win) return;
    if (win.isMinimized()) win.restore();
    win.focus();
    openStartPath(startPathFrom(argv));
  });
  app.setAppUserModelId('site.ragdollp.filemanager');
  app.whenReady().then(createWindow);
  app.on('window-all-closed', () => app.quit());
}
