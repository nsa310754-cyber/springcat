// exe がアプリのデータ領域で実行するスクリプト (計測・削除・書き出し・復元) を Chromium で確かめる。
// 仮想ホスト https://<id>.htmlhome.example/ を route で再現し、chrome.webview.postMessage を偽物に差し替える。
import { createRequire } from 'module';
import { execFileSync } from 'child_process';
import path from 'path'; import { fileURLToPath } from 'url';
const require = createRequire('/opt/node-tools/node_modules/');
const { chromium } = require('playwright');
const dir = path.dirname(fileURLToPath(import.meta.url));
const js = JSON.parse(execFileSync('mono', [path.join(dir, '..', 'dist', 'HtmlHome.exe'), '--dump-js'], { encoding: 'utf8' }));
let fail = 0; const ok = (c, m) => { console.log((c ? 'PASS ' : 'FAIL ') + m); if (!c) fail++; };

const browser = await chromium.launch();
const ctx = await browser.newContext({ ignoreHTTPSErrors: true });
await ctx.route('https://*.htmlhome.example/**', r => r.fulfill({ contentType: 'text/html', body: '<!doctype html><meta charset="utf-8"><title></title>' }));
await ctx.addInitScript(() => { window.chrome = window.chrome || {}; window.chrome.webview = { postMessage: m => (window.__posted = window.__posted || []).push(m) }; });
const page = await ctx.newPage();
const run = async (script, token) => {
  await page.evaluate(() => { window.__posted = []; });
  await page.evaluate(script);
  await page.waitForFunction(() => window.__posted.length > 0);
  const m = JSON.parse(await page.evaluate(() => window.__posted[0]));
  if (m.__hh !== token) throw new Error('token mismatch');
  return m.v;
};
const A = 'https://aaaa000001.htmlhome.example/__blank.html', B = 'https://bbbb000002.htmlhome.example/__blank.html';

await page.goto(A);
ok((await run(js.measure, 'T1')).bytes === 0, '空のときセーブデータは 0');
await page.evaluate(async () => {
  localStorage.setItem('save', 'x'.repeat(1000)); sessionStorage.setItem('s', '1'); document.cookie = 'c=1';
  await new Promise((res, rej) => { const q = indexedDB.open('db1', 1); q.onupgradeneeded = () => q.result.createObjectStore('s'); q.onsuccess = () => { const t = q.result.transaction('s', 'readwrite'); t.objectStore('s').put('y'.repeat(5000), 'k'); t.oncomplete = () => { q.result.close(); res(); }; }; q.onerror = rej; });
});
const m1 = await run(js.measure, 'T1');
ok(m1.bytes >= (4 + 1000) * 2, `localStorage と IndexedDB を数える (${m1.bytes} bytes)`);
const d1 = await run(js.dump, 'T3');
ok(d1.save && d1.save.length === 1000 && Object.keys(d1).length === 1, '書き出しは localStorage の中身');
// 別アプリのホストからは見えない (アプリごとに分かれている)
await page.goto(B);
ok(Object.keys(await run(js.dump, 'T3')).length === 0, '別のアプリのデータは見えない');
await page.evaluate(() => localStorage.setItem('other', 'keep'));
await page.goto(A);
ok((await run(js.clear, 'T2')).ok === true, '削除スクリプトが完了する');
const after = await page.evaluate(async () => ({ ls: localStorage.length, ss: sessionStorage.length, dbs: (await indexedDB.databases()).length }));
ok(after.ls === 0 && after.ss === 0 && after.dbs === 0, 'localStorage・sessionStorage・IndexedDB が消える');
await page.goto(B);
ok((await run(js.dump, 'T3')).other === 'keep', '他のアプリのセーブデータは消えない');
await page.goto(A);
ok((await run(js.restore, 'T4')).ok === true, '復元スクリプトが完了する');
const d2 = await run(js.dump, 'T3');
ok(d2.score === '42' && d2["k</script> '\""] === "v'\"\n<x>", '記号や改行を含むデータも正しく戻る');
await browser.close();
console.log(fail ? `\n${fail} 件失敗` : '\nすべて成功'); process.exit(fail ? 1 : 0);
