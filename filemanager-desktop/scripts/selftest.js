'use strict';
/**
 * bridge.js の自己テスト (Electron 不要。node scripts/selftest.js)。
 * 一時フォルダに実ファイルを作り、同梱の 7-Zip で圧縮/解凍まで実際に行って検証する。
 */
const fs = require('fs');
const os = require('os');
const path = require('path');
const assert = require('assert');
const { createBridge, out } = require('../bridge');

const B = createBridge({ toast: () => {} });
const P = (s) => JSON.parse(s);
const call = async (fn, ...a) => P(await B[fn](...a));

let passed = 0;
async function t(name, fn) {
  try { await fn(); passed++; console.log('  ✓', name); }
  catch (e) { console.error('  ✗', name, '\n   ', e && e.stack || e); process.exitCode = 1; }
}

(async () => {
  try { fs.chmodSync(require('7zip-bin').path7za, 0o755); } catch {}
  const root = out(fs.mkdtempSync(path.join(os.tmpdir(), 'scf-test-')));
  const src = root + '/src';
  fs.mkdirSync(src + '/Download/sub', { recursive: true });
  fs.writeFileSync(src + '/hello.txt', 'こんにちは SpringCat');
  fs.writeFileSync(src + '/Download/a.txt', 'AAA');
  fs.writeFileSync(src + '/Download/sub/b.md', '# B needle');
  fs.writeFileSync(src + '/dup1.bin', 'same-content');
  fs.writeFileSync(src + '/dup2.bin', 'same-content');
  fs.writeFileSync(src + '/empty.txt', '');
  fs.mkdirSync(src + '/emptydir');

  console.log('bridge selftest in', root);

  await t('list sorts dirs first', async () => {
    const r = await call('list', src);
    assert.ok(r.ok); assert.strictEqual(r.entries[0].isDir, true); assert.strictEqual(r.parent, root);
  });
  await t('readText / writeText', async () => {
    assert.strictEqual((await call('readText', src + '/hello.txt')).text, 'こんにちは SpringCat');
    assert.ok((await call('writeText', src + '/w.txt', 'xyz')).ok);
    assert.strictEqual(fs.readFileSync(src + '/w.txt', 'utf8'), 'xyz');
  });
  await t('createFile / createDir / rename / copyTo / moveTo', async () => {
    assert.ok((await call('createFile', src, 'new.js', 'console.log(1)')).ok);
    assert.ok(!(await call('createFile', src, 'new.js', '')).ok, 'duplicate must fail');
    assert.ok((await call('createDir', src, 'nd')).ok);
    assert.ok((await call('rename', src + '/new.js', 'new.ts')).ok);
    assert.ok((await call('copyTo', src + '/new.ts', src + '/nd/copy.ts')).ok);
    assert.ok((await call('moveTo', src + '/new.ts', src + '/nd')).ok);
    assert.ok(fs.existsSync(src + '/nd/new.ts') && fs.existsSync(src + '/nd/copy.ts'));
  });
  await t('search name + content', async () => {
    assert.ok((await call('search', src, 'a.txt', false, 50)).results.some((e) => e.name === 'a.txt'));
    assert.ok((await call('search', src, 'needle', true, 50)).results.some((e) => e.name === 'b.md'));
  });
  await t('findDuplicates returns groups array', async () => {
    const r = await call('findDuplicates', src, 1000);
    assert.ok(Array.isArray(r.groups) && r.groupCount === r.groups.length);
    // dup1/dup2 と、前のテストでコピーした new.ts/copy.ts の 2 組
    assert.ok(r.groups.some((g) => g.files.some((f) => f.endsWith('dup1.bin')) && g.files.length === 2));
    assert.strictEqual(r.groups.length, 2);
  });
  await t('hashFile md5 / sha256', async () => {
    assert.strictEqual((await call('hashFile', src + '/dup1.bin', 'MD5')).hash.length, 32);
    assert.strictEqual((await call('hashFile', src + '/dup1.bin', 'SHA-256')).hash.length, 64);
  });
  await t('stat', async () => {
    const r = await call('stat', src + '/hello.txt');
    assert.ok(r.ok && r.size > 0 && r.ext === 'txt');
  });

  const arch = root + '/arch';
  fs.mkdirSync(arch);
  const sel = JSON.stringify([src + '/Download', src + '/hello.txt']);

  for (const fmt of ['zip', '7z', 'tar', 'tgz', 'jar']) {
    await t(`compress + extract ${fmt}`, async () => {
      const c = await call('compress', sel, arch, 'pack_' + fmt, fmt, '');
      assert.ok(c.ok, c.error);
      const x = await call('extract', c.path, arch, '', false);
      assert.ok(x.ok, x.error);
      assert.strictEqual(fs.readFileSync(x.dir + '/Download/a.txt', 'utf8'), 'AAA');
      assert.ok(fs.existsSync(x.dir + '/hello.txt'));
    });
  }
  await t('gz single file', async () => {
    const c = await call('compress', JSON.stringify([src + '/hello.txt']), arch, 'x', 'gz', '');
    assert.ok(c.ok && c.name === 'hello.txt.gz', c.error);
    const x = await call('extract', c.path, arch, '', false);
    assert.ok(x.ok, x.error);
    assert.strictEqual(fs.readFileSync(x.dir + '/hello.txt', 'utf8'), 'こんにちは SpringCat');
  });
  await t('rar creation refused', async () => {
    assert.ok(!(await call('compress', sel, arch, 'r', 'rar', '')).ok);
  });
  await t('password not allowed for tar', async () => {
    assert.ok(!(await call('compress', sel, arch, 'r', 'tar', 'pw')).ok);
  });

  for (const fmt of ['zip', '7z']) {
    await t(`password ${fmt}: wrong → needsPassword, right → ok`, async () => {
      const c = await call('compress', sel, arch, 'secret_' + fmt, fmt, 'p@ss');
      assert.ok(c.ok, c.error);
      const none = await call('extract', c.path, arch, '', false);
      assert.ok(!none.ok && none.needsPassword, JSON.stringify(none));
      const bad = await call('extract', c.path, arch, 'nope', false);
      assert.ok(!bad.ok && bad.needsPassword, JSON.stringify(bad));
      assert.ok(!fs.existsSync(`${arch}/secret_${fmt}`), 'failed attempts must not leave folders behind');
      const good = await call('extract', c.path, arch, 'p@ss', false);
      assert.ok(good.ok, good.error);
      assert.strictEqual(fs.readFileSync(good.dir + '/Download/a.txt', 'utf8'), 'AAA');
    });
  }

  await t('merge extract: Download/ joins existing Download/', async () => {
    const dest = root + '/device';
    fs.mkdirSync(dest + '/Download', { recursive: true });
    fs.writeFileSync(dest + '/Download/keep.txt', 'keep');
    const c = await call('compress', JSON.stringify([src + '/Download']), arch, 'mergepack', 'zip', '');
    const x = await call('extract', c.path, dest, '', true);
    assert.ok(x.ok, x.error);
    assert.strictEqual(out(x.dir), dest);
    assert.ok(fs.existsSync(dest + '/Download/keep.txt'), 'existing file kept');
    assert.strictEqual(fs.readFileSync(dest + '/Download/a.txt', 'utf8'), 'AAA');
    assert.ok(!fs.existsSync(dest + '/mergepack'), 'no extra subfolder');
  });

  await t('cleanup: delete 0-byte files & empty dirs', async () => {
    assert.ok((await call('deleteEmptyFiles', src, true)).deleted >= 1);
    assert.ok(!fs.existsSync(src + '/empty.txt'));
    assert.ok((await call('deleteEmptyDirs', src)).deleted >= 1);
    assert.ok(!fs.existsSync(src + '/emptydir'));
  });
  await t('delete (no shell → permanent)', async () => {
    assert.ok((await call('delete', src + '/nd')).ok);
    assert.ok(!fs.existsSync(src + '/nd'));
  });
  await t('roots / storageInfo', async () => {
    assert.ok((await call('roots')).roots.length >= 1);
    assert.ok((await call('storageInfo')).volumes[0].total > 0);
  });
  await t('android-only features report gracefully', async () => {
    assert.strictEqual(B.isRootAvailable(), false);
    assert.ok(!(await call('inspectApk', src + '/hello.txt')).ok);
  });

  fs.rmSync(root, { recursive: true, force: true });
  console.log(`\n${passed} passed${process.exitCode ? ', SOME FAILED' : ''}`);
})();
