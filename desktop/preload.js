// Block Destroy — Windows/PC 版 preload
//   ゲーム本体(game.html)が実績解除時に発火する DOM イベント
//   'bd-achievement-unlocked' を購読し、メインプロセスへ転送する。
//   → メイン側で「パソコン標準の実績達成音」を鳴らす。
//   contextIsolation:true でも DOM(window)イベントは共有されるため購読できる。
const { ipcRenderer } = require('electron');

window.addEventListener('bd-achievement-unlocked', (e) => {
  let detail = {};
  try { detail = { id: (e.detail && e.detail.id) || '', name: (e.detail && e.detail.name) || '' }; } catch (_) {}
  try { ipcRenderer.send('bd-achievement', detail); } catch (_) {}
});
