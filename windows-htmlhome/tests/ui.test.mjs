// ランチャー画面の自動テスト（モックホストで動かす）
import { createRequire } from 'module';
const require = createRequire('/opt/node-tools/node_modules/');
let chromium;
try { ({ chromium } = require('playwright')); } catch { ({ chromium } = require(process.env.PW_PATH || 'playwright')); }
import path from 'path'; import { fileURLToPath } from 'url';
const dir = path.dirname(fileURLToPath(import.meta.url));
const fx = n => path.join(dir, 'fixtures', n);
const shots = process.env.SHOTS || dir;
let fail = 0;
const ok = (c, m) => { console.log((c ? 'PASS ' : 'FAIL ') + m); if (!c) fail++; };

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1100, height: 720 } });
const errors = []; page.on('pageerror', e => errors.push(e.message)); page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
await page.goto('file://' + path.join(dir, '..', 'launcher', 'index.html'));
const labels = () => page.$$eval('#grid > .icon .label', ns => ns.map(n => n.textContent));
const items = () => page.evaluate(() => JSON.parse(JSON.stringify(ui.items)));

ok(JSON.stringify(await labels()) === JSON.stringify(['New app', 'Delete', 'Setting', 'exit']), '最初は 4 つの標準アプリ');
ok(!(await page.locator('#banner').isVisible()), '通常時は削除モードの案内が出ない');

async function addApp(files, name) {
  await page.click('.icon[data-key="app:builtin:new"]');
  const [fc] = await Promise.all([page.waitForEvent('filechooser'), page.click('.drop')]);
  await fc.setFiles(files.map(fx));
  await page.waitForFunction(() => document.querySelector('.newgrid input').value !== '');
  if (name) await page.fill('.newgrid input', name);
  return page;
}
// 1) HTML + JS + CSS + 画像 を 1 つにまとめる
await addApp(['index.html', 'app.js', 'style.css', 'extra.js', 'logo.png']);
ok(await page.inputValue('.newgrid input') === 'メモ帳', 'アプリ名の初期値は <title>');
await page.screenshot({ path: path.join(shots, 'shot-newapp.png') });
await page.click('.actions .btn.primary');
await page.waitForSelector('.window', { state: 'detached' });
const html = await page.evaluate(() => Object.values(__mock.html)[0]);
ok(!/src="js\/app\.js/.test(html) && html.includes("JS OK <\\/script> safe"), 'JS が HTML に入り </script> がエスケープされる');
ok(html.includes('<style>h1{color:red}') && !html.includes('css/style.css'), 'CSS が <style> になる');
ok(html.includes('window.extra = 1;'), '参照されていない JS も入る');
ok(html.includes('<img src="logo.png">'), '画像のパスがファイル名だけになる');
ok(html.startsWith('<!DOCTYPE html>') && html.includes('<meta charset="utf-8">'), 'DOCTYPE と UTF-8 指定');
const meta = await page.evaluate(() => __mock.apps[0]);
ok(/^[0-9a-f]{64}$/.test(meta.sha256) && meta.icon.startsWith('data:image/png'), 'SHA-256 と favicon 由来のアイコン');
ok((await labels()).includes('メモ帳'), 'ホームにアイコンが出る');

// 2) 同じ名前は不可（標準アプリ名も不可）
await addApp(['second.html'], 'メモ帳');
ok(await page.isDisabled('.actions .btn.primary') && (await page.textContent('.err')).includes('同じ名前'), '同じアプリ名は追加できない');
await page.fill('.newgrid input', 'setting');
ok(await page.isDisabled('.actions .btn.primary'), '標準アプリと同じ名前も不可（大文字小文字を無視）');
await page.fill('.newgrid input', 'アプリ2');
await page.click('.actions .btn.primary'); await page.waitForSelector('.window', { state: 'detached' });
// 3) Shift_JIS
await addApp(['sjis.html']);
ok(await page.inputValue('.newgrid input') === '日本語アプリ', 'Shift_JIS の HTML を読める');
await page.click('.actions .btn.primary'); await page.waitForSelector('.window', { state: 'detached' });
ok((await page.evaluate(() => Object.values(__mock.html)[2])).includes('こんにちは'), 'Shift_JIS の本文が化けない');

// 4) 長押しドラッグ
const center = async key => { const b = await page.locator(`.icon[data-key="${key}"]`).first().boundingBox(); return [b.x + b.width / 2, b.y + b.height / 2, b]; };
async function longDrag(fromKey, tx, ty) {
  const [x, y] = await center(fromKey);
  await page.mouse.move(x, y); await page.mouse.down(); await page.waitForTimeout(480);
  await page.mouse.move((x + tx) / 2, (y + ty) / 2, { steps: 5 }); await page.mouse.move(tx, ty, { steps: 5 });
}
const ids = await page.evaluate(() => apps.map(a => a.id));
{ // 短いクリック移動ではドラッグにならない
  const before = JSON.stringify(await items());
  const [x, y] = await center('app:' + ids[0]); const [tx, ty] = await center('app:' + ids[1]);
  await page.mouse.move(x, y); await page.mouse.down(); await page.mouse.move(tx, ty, { steps: 4 }); await page.mouse.up();
  ok(JSON.stringify(await items()) === before, '長押ししないと動かない');
}
{ // アプリを別のアプリの上へ → フォルダー
  const [tx, ty] = await center('app:' + ids[1]);
  await longDrag('app:' + ids[0], tx, ty);
  ok(await page.locator('.merge-target').count() === 1, '重ねると相手が強調される');
  await page.screenshot({ path: path.join(shots, 'shot-drag.png') });
  await page.mouse.up();
  const it = await items(); const f = it.find(i => i.t === 'folder');
  ok(f && f.apps.join() === [ids[1], ids[0]].join() && f.name === 'フォルダー', 'アプリを重ねるとフォルダーができる');
  ok(it.filter(i => i.t === 'app').every(i => !f.apps.includes(i.id)), 'フォルダーに入れたアプリはホームから消える');
}
const fid = (await items()).find(i => i.t === 'folder').fid;
{ // 3 つ目をフォルダーへ
  const [tx, ty] = await center('folder:' + fid);
  await longDrag('app:' + ids[2], tx, ty); await page.mouse.up();
  ok((await items()).find(i => i.t === 'folder').apps.length === 3, 'フォルダーにアプリを追加できる');
}
{ // 並べ替え: exit を先頭へ
  const [, , b] = await center('app:builtin:new');
  await longDrag('app:builtin:exit', b.x + 6, b.y + b.height / 2);
  ok(await page.locator('#insbar').count() === 1, '並べ替えの目印が出る');
  await page.mouse.up();
  ok((await items())[0].id === 'builtin:exit', '長押しで並べ替えできる');
}
{ // フォルダーを開いて名前を変え、1 つ外へ出す
  await page.waitForTimeout(400);
  await page.click(`.icon[data-key="folder:${fid}"]`);
  await page.waitForSelector('#folderPanel');
  await page.fill('.fname', 'ゲーム'); await page.press('.fname', 'Enter'); await page.locator('.fname').blur();
  await page.screenshot({ path: path.join(shots, 'shot-folder.png') });
  const [x, y] = await center('app:' + ids[2]);
  await page.mouse.move(x, y); await page.mouse.down(); await page.waitForTimeout(480);
  await page.mouse.move(900, 600, { steps: 8 }); await page.mouse.up();
  const it = await items();
  ok(it.find(i => i.t === 'folder').name === 'ゲーム', 'フォルダー名を変えられる');
  ok(it.find(i => i.t === 'folder').apps.length === 2 && it[it.length - 1].id === ids[2], 'フォルダーの外へ出せる');
  ok(await page.locator('#folderBack').count() === 0, '外へ出すとフォルダーが閉じる');
}
// 5) 設定
await page.waitForTimeout(400);
await page.click('.icon[data-key="app:builtin:setting"]');
ok(JSON.stringify(await page.$$eval('.menu button', ns => ns.map(n => n.textContent))) === JSON.stringify(['1アプリ情報', '2ホーム画面', '3ストレージと通信使用量', '4通信速度', '5バックアップ']), '設定は ①〜⑤');
await page.screenshot({ path: path.join(shots, 'shot-settings.png') });
await page.click('.menu button >> nth=0');
await page.click('.applist button >> nth=0');
await page.waitForSelector('table.kv');
const infoText = await page.textContent('.window');
ok(['アプリ本体', 'セーブデータ', '合計', 'アンインストール', 'セーブデータをなくす', 'SHA-256', 'にダウンロード'].every(s => infoText.includes(s)), 'アプリ情報にスケッチの項目がそろう');
await page.click('text=SHA-256');
ok((await page.textContent('.hash')).length === 64, 'SHA-256 を表示');
await page.screenshot({ path: path.join(shots, 'shot-appinfo.png') });
await page.click('.backbtn'); // → アプリ一覧
await page.click('.applist button:has-text("Delete")');
const biText = await page.textContent('.window');
ok(biText.includes('できません') && !(await page.locator('.window button:has-text("アンインストール")').count()), '標準アプリの情報にアンインストールが無い');
await page.click('.backbtn'); await page.click('.backbtn');
await page.click('.menu button >> nth=1'); await page.click('.seg button:has-text("大")');
ok(await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--tile')) === '100px', 'アイコンの大きさを変えられる');
await page.click('.seg button:has-text("中")'); await page.click('.backbtn');
await page.click('.menu button >> nth=2'); ok((await page.textContent('.window')).includes('通信使用量'), 'ストレージと通信使用量の画面');
await page.screenshot({ path: path.join(shots, 'shot-usage.png') });
await page.click('.backbtn'); await page.click('.menu button >> nth=3');
await page.click('text=測定する'); await page.waitForSelector('text=93.4 Mbps');
ok(true, '通信速度の測定結果が出る');
await page.click('.backbtn'); await page.click('.menu button >> nth=4');
ok(await page.locator('text=バックアップを書き出す').count() === 1, 'バックアップ画面');
await page.keyboard.press('Escape');
ok(await page.locator('.window').count() === 0, 'Esc でウィンドウが閉じる');

// 6) Delete: 標準アプリは消せない
await page.click('.icon[data-key="app:builtin:delete"]');
ok(await page.locator('#banner').isVisible(), '削除モードになる');
ok(await page.locator('#grid > .icon .xbadge').count() === 1 && await page.locator('#grid > .icon .lockbadge').count() === 4, '× はユーザーアプリだけ、標準アプリは鍵');
await page.screenshot({ path: path.join(shots, 'shot-delete.png') });
for (const b of ['new', 'delete', 'setting', 'exit']) await page.click(`.icon[data-key="app:builtin:${b}"]`);
ok(await page.locator('.window').count() === 0 && (await labels()).filter(l => ['New app', 'Delete', 'Setting', 'exit'].includes(l)).length === 4, '標準アプリは押しても消えない・開かない');
await page.click(`.icon[data-key="app:${ids[2]}"]`);
await page.click('.window .btn.danger');
ok(!(await page.evaluate(() => apps.map(a => a.id))).includes(ids[2]) && await page.evaluate(() => __mock.apps.length) === 2, 'ユーザーアプリはアンインストールできる');
// フォルダー内のアプリも消せて、1 個になったらフォルダーが解ける
await page.click(`.icon[data-key="folder:${fid}"]`);
await page.click(`#fgrid .icon[data-key="app:${ids[0]}"]`); await page.click('.window .btn.danger');
ok(!(await items()).some(i => i.t === 'folder') && (await items()).some(i => i.id === ids[1]), '中身が 1 個になるとフォルダーが解ける');
await page.click('#delDone');
ok(!(await page.locator('#banner').isVisible()), '完了で削除モード終了');
// uninstallApp を直接呼ばれても画面側は標準アプリを対象にしない
ok(await page.evaluate(() => uninstallFlow('builtin:exit')) === false, 'uninstallFlow は標準アプリを拒否');
// 右クリックメニュー
await page.click('.icon[data-key="app:builtin:setting"]', { button: 'right' });
ok(await page.locator('#ctx button').count() === 2, '標準アプリの右クリックにアンインストールが無い');
await page.keyboard.press('Escape');
await page.click(`.icon[data-key="app:${ids[1]}"]`, { button: 'right' });
ok(await page.locator('#ctx button.danger').count() === 1, 'ユーザーアプリの右クリックにはある');
await page.keyboard.press('Escape');
// 7) 起動して戻る
await page.click(`.icon[data-key="app:${ids[1]}"]`);
await page.waitForSelector('.mockapp iframe'); await page.click('.mockapp .btn');
ok(await page.evaluate(() => apps[0].traffic) === 4096, 'アプリから戻ると使用量が更新される');
// 8) 並びの保存と復元
const saved = await page.evaluate(() => new Promise(r => setTimeout(() => r(__mock.ui), 300)));
ok(JSON.parse(saved).items.length === (await items()).length, '並びがホストに保存される');
await page.screenshot({ path: path.join(shots, 'shot-home.png') });
ok(errors.length === 0, 'JS エラーなし' + (errors.length ? ': ' + errors.join(' | ') : ''));
await browser.close();
console.log(fail ? `\n${fail} 件失敗` : '\nすべて成功');
process.exit(fail ? 1 : 0);
