'use strict';
/**
 * UI (app.html) に Android 版と同じ形の window.Bridge を公開する。
 * 各メソッドは同期 IPC でメインプロセスの bridge.js を呼び、JSON 文字列を返す
 * (Android の @JavascriptInterface と同じく同期呼び出し)。
 */
const { contextBridge, ipcRenderer, webUtils } = require('electron');

const api = {};
for (const name of ipcRenderer.sendSync('bridge:methods')) {
  api[name] = (...args) => ipcRenderer.sendSync('bridge', name, args);
}
// エクスプローラーからドロップされたファイルの実パス。
api.pathForFile = (file) => { try { return webUtils.getPathForFile(file); } catch { return ''; } };

contextBridge.exposeInMainWorld('Bridge', api);
