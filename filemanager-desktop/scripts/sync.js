'use strict';
/**
 * UI とアイコンを Android 版から取り込む (二重管理しないため app.html は Git に置かない)。
 *   filemanager/app/src/main/assets/app.html → filemanager-desktop/app.html
 *   filemanager/ic_launcher-512.png          → filemanager-desktop/icon.png
 */
const fs = require('fs');
const path = require('path');
const root = path.join(__dirname, '..');
const android = path.join(root, '..', 'filemanager');
for (const [from, to] of [
  [path.join(android, 'app', 'src', 'main', 'assets', 'app.html'), path.join(root, 'app.html')],
  [path.join(android, 'ic_launcher-512.png'), path.join(root, 'icon.png')],
]) {
  fs.copyFileSync(from, to);
  console.log('synced', path.relative(root, to));
}
