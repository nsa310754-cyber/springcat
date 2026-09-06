// preload — レンダラに安全な API のみを公開する橋渡し。
// contextIsolation 有効 + nodeIntegration 無効なので、fs 等には直接触れさせない。
'use strict';

const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('sysfile', {
  info: () => ipcRenderer.invoke('sys:info'),
  list: (dirPath) => ipcRenderer.invoke('fs:list', dirPath),
  read: (filePath) => ipcRenderer.invoke('fs:read', filePath),
  elevate: () => ipcRenderer.invoke('sys:elevate'),
});
