'use strict';
/**
 * SpringCat Files — デスクトップ版の Bridge 実装。
 *
 * Android 版の FileBridge.java と同じ名前・同じ JSON 形式で応答するので、
 * UI (app.html) は Android と共通のまま動く。パスは常に "/" 区切りで UI に渡す
 * (Windows でも "C:/Users/..." 形式。Node の fs はこの形式をそのまま受け付ける)。
 *
 * Electron 固有の処理 (シェル連携・オンライン判定・ごみ箱) は env で注入するので、
 * このファイル単体を Node でテストできる (scripts/selftest.js)。
 */
const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');
const { execFile } = require('child_process');

const IS_WIN = process.platform === 'win32';

// ---------------------------------------------------------------- JSON / パス helpers
const J = (o) => JSON.stringify(o);
const ok = (o = {}) => J({ ok: true, ...o });
const err = (e) => J({ ok: false, error: typeof e === 'string' ? e : `${e && e.code ? e.code + ': ' : ''}${e && e.message || e}` });

/** UI から来たパスを正規化 ("\" → "/"、"C:" → "C:/")。 */
function np(p) {
  p = String(p == null ? '' : p).replace(/\\/g, '/');
  if (!p) return p;
  if (/^[A-Za-z]:$/.test(p)) p += '/';
  p = path.normalize(p).replace(/\\/g, '/');          // "C://a/../b" → "C:/b"
  if (p.length > 1 && p.endsWith('/') && !/^[A-Za-z]:\/$/.test(p)) p = p.slice(0, -1);
  return p;
}
/** OS のパスを UI 用 ("/" 区切り) に。 */
const out = (p) => String(p).replace(/\\/g, '/');
const ext = (name) => { const i = name.lastIndexOf('.'); return i <= 0 || i === name.length - 1 ? '' : name.slice(i + 1).toLowerCase(); };
const parentOf = (p) => { const d = path.dirname(p); return out(d) === out(p) ? '' : out(d); };
const join = (...a) => out(path.join(...a.map(np)));
/** 外部プログラム (7-Zip) に渡すパス。Windows では "\\" 区切りに戻す。 */
const nat = (p) => (IS_WIN ? String(p).replace(/\//g, '\\') : String(p));

// Windows のシステム項目 (隠し属性の簡易判定)。「👁 隠し」で表示切替できる。
const WIN_HIDDEN = new Set(['system volume information', 'pagefile.sys', 'swapfile.sys', 'hiberfil.sys',
  'dumpstack.log.tmp', 'dumpstack.log', 'recovery', 'config.msi', 'desktop.ini', 'thumbs.db', 'msocache',
  'documents and settings', 'appdata', 'application data', 'local settings', 'ntuser.ini']);
const isHiddenName = (name) => {
  const l = name.toLowerCase();
  return l.startsWith('.') || (IS_WIN && (l.startsWith('$') || l.startsWith('ntuser.') || WIN_HIDDEN.has(l)));
};

function stripArchiveExt(name) {
  const l = name.toLowerCase();
  if (l.endsWith('.tar.gz')) return name.slice(0, -7);
  const i = name.lastIndexOf('.');
  return i <= 0 ? name : name.slice(0, i);
}

function uniquePath(dir, name) {
  let p = join(dir, name), n = 1;
  while (fs.existsSync(p)) p = join(dir, `${name}_${n++}`);
  return p;
}

function entryOf(full, st) {
  const name = path.basename(full);
  return {
    name, path: out(full), isDir: st.isDirectory(),
    size: st.isDirectory() ? 0 : st.size, mtime: Math.round(st.mtimeMs),
    ext: ext(name), readable: true, hidden: isHiddenName(name),
  };
}

// ---------------------------------------------------------------- 7-Zip
function sevenZipPath() {
  // asar 内のパスは実行できないので app.asar.unpacked 側を使う。
  return require('7zip-bin').path7za.replace('app.asar' + path.sep, 'app.asar.unpacked' + path.sep)
    .replace('app.asar/', 'app.asar.unpacked/');
}

function run7z(args) {
  return new Promise((resolve) => {
    execFile(sevenZipPath(), args, { windowsHide: true, maxBuffer: 64 * 1024 * 1024 }, (e, stdout, stderr) => {
      resolve({ code: e ? (typeof e.code === 'number' ? e.code : 1) : 0, out: String(stdout || ''), err: String(stderr || '') });
    });
  });
}

const PW_ERR = /wrong password|can not open encrypted|cannot open encrypted|data error in encrypted|encrypted.*headers|password/i;

// ---------------------------------------------------------------- 再帰ユーティリティ
function walk(dir, cb, limit = { n: Infinity }) {
  let names;
  try { names = fs.readdirSync(dir); } catch { return; }
  for (const name of names) {
    if (limit.n <= 0) return;
    const full = join(dir, name);
    let st;
    try { st = fs.lstatSync(full); } catch { continue; }
    if (cb(full, st) === false) return;
    if (st.isDirectory() && !st.isSymbolicLink()) walk(full, cb, limit);
  }
}

function hash(file, algo) {
  const a = String(algo || 'SHA-256').toLowerCase().replace('-', '');
  return new Promise((resolve, reject) => {
    const h = crypto.createHash(a === 'sha256' ? 'sha256' : a);
    fs.createReadStream(file).on('error', reject).on('data', (d) => h.update(d)).on('end', () => resolve(h.digest('hex')));
  });
}

const TEXT_EXT = new Set(['txt', 'md', 'json', 'xml', 'csv', 'log', 'js', 'ts', 'java', 'py', 'c', 'cpp', 'h',
  'html', 'css', 'kt', 'go', 'rs', 'php', 'sh', 'yml', 'yaml', 'properties', 'gradle', 'sql', 'ini', 'cfg', 'bat', 'ps1']);

// ================================================================ Bridge 本体
function createBridge(env = {}) {
  const shell = env.shell || null;      // electron.shell
  const isOnlineFn = env.isOnline || (() => true);
  const toast = env.toast || (() => {});

  const home = os.homedir();

  const B = {
    platform: () => (IS_WIN ? 'win' : process.platform),

    // ---- 権限/環境 (デスクトップでは常に許可済み) ----
    hasAllFilesAccess: () => true,
    requestAllFilesAccess: () => {},
    isOnline: () => !!isOnlineFn(),

    // ---- ルート/バグレポート/APK (Android 専用) ----
    isRootAvailable: () => false,
    isRootMode: () => false,
    requestRoot: () => false,
    setRootMode: () => err('ルートモードは Android 版のみの機能です'),
    rootStatus: () => ok({ binary: false, suPath: '', testKeys: false, verified: false, granted: false, uid: '', rootMode: false }),
    findBugReports: () => ok({ dirs: [], downloads: [] }),
    openDeveloperOptions: () => {},
    inspectApk: () => err('APK の検査/インストールは Android 版のみの機能です (zip として展開はできます)'),
    installApk: () => toast('APK のインストールは Android 端末でのみ可能です'),

    // ---- 一覧/ストレージ ----
    roots() {
      const roots = [];
      if (IS_WIN) {
        for (let c = 67; c <= 90; c++) {               // C: 〜 Z:
          const d = String.fromCharCode(c) + ':/';
          try { fs.accessSync(d); roots.push({ name: `ドライブ ${d.slice(0, 2)}`, path: d, exists: true }); } catch {}
        }
      } else {
        roots.push({ name: 'ルート /', path: '/', exists: true });
      }
      const user = [['ホーム', ''], ['デスクトップ', 'Desktop'], ['ドキュメント', 'Documents'], ['ダウンロード', 'Downloads'],
        ['ピクチャ', 'Pictures'], ['ビデオ', 'Videos'], ['ミュージック', 'Music']];
      for (const [name, sub] of user) {
        const p = join(home, sub);
        if (fs.existsSync(p)) roots.push({ name, path: p, exists: true });
      }
      return ok({ roots });
    },

    list(p) {
      p = np(p);
      try {
        const st = fs.statSync(p);
        if (!st.isDirectory()) return err('ディレクトリではありません: ' + p);
        const entries = [];
        for (const name of fs.readdirSync(p)) {
          const full = join(p, name);
          try { entries.push(entryOf(full, fs.statSync(full))); }
          catch { entries.push({ name, path: full, isDir: false, size: 0, mtime: 0, ext: ext(name), readable: false, hidden: true }); }
        }
        entries.sort((a, b) => (a.isDir !== b.isDir ? (a.isDir ? -1 : 1) : a.name.localeCompare(b.name, 'ja')));
        return ok({ path: out(p), parent: parentOf(p), entries });
      } catch (e) {
        if (e.code === 'EPERM' || e.code === 'EACCES') return err('アクセスが拒否されました (管理者権限が必要なフォルダです): ' + p);
        if (e.code === 'ENOENT') return err('パスが存在しません: ' + p);
        return err(e);
      }
    },

    storageInfo() {
      const vols = [];
      const add = (name, p) => {
        try {
          const s = fs.statfsSync(p);
          const total = s.blocks * s.bsize, free = s.bavail * s.bsize;
          vols.push({ name, path: out(p), total, free, used: total - free });
        } catch {}
      };
      if (IS_WIN) {
        for (let c = 67; c <= 90; c++) {
          const d = String.fromCharCode(c) + ':/';
          try { fs.accessSync(d); add(`ドライブ ${d.slice(0, 2)}`, d); } catch {}
        }
      } else {
        add('ルート /', '/');
        add('ホーム', home);
      }
      return ok({ volumes: vols });
    },

    // ---- 読み書き ----
    readText(p) {
      p = np(p);
      try {
        const max = 4 * 1024 * 1024;
        const st = fs.statSync(p);
        if (!st.isFile()) return err('ファイルがありません');
        const fd = fs.openSync(p, 'r');
        const len = Math.min(st.size, max);
        const buf = Buffer.alloc(len);
        fs.readSync(fd, buf, 0, len, 0);
        fs.closeSync(fd);
        return ok({ text: buf.toString('utf8'), truncated: st.size > max });
      } catch (e) { return err(e); }
    },
    readBase64(p, maxBytes) {
      p = np(p);
      try {
        const st = fs.statSync(p);
        if (st.size > maxBytes) return err(`大きすぎます (${st.size} bytes)`);
        return ok({ base64: fs.readFileSync(p).toString('base64') });
      } catch (e) { return err(e); }
    },
    writeText(p, content) {
      p = np(p);
      try { fs.writeFileSync(p, String(content), 'utf8'); return ok({ path: out(p), name: path.basename(p) }); }
      catch (e) { return err(e); }
    },
    createFile(dir, name, content) {
      const p = join(dir, name);
      try {
        if (fs.existsSync(p)) return err('既に存在します: ' + name);
        fs.mkdirSync(path.dirname(p), { recursive: true });
        fs.writeFileSync(p, content == null ? '' : String(content), 'utf8');
        return ok({ path: p, name: path.basename(p) });
      } catch (e) { return err(e); }
    },
    createDir(dir, name) {
      const p = join(dir, name);
      try {
        if (fs.existsSync(p)) return err('既に存在します: ' + name);
        fs.mkdirSync(p, { recursive: true });
        return ok({ path: p, name: path.basename(p) });
      } catch (e) { return err(e); }
    },
    rename(p, newName) {
      p = np(p);
      const dest = join(path.dirname(p), newName);
      try {
        if (!fs.existsSync(p)) return err('ファイルがありません');
        if (fs.existsSync(dest) && out(dest).toLowerCase() !== out(p).toLowerCase()) return err('同名が既に存在します: ' + newName);
        fs.renameSync(p, dest);
        return ok({ path: dest, name: path.basename(dest) });
      } catch (e) { return err(e); }
    },
    copyTo(p, destPath) {
      p = np(p); destPath = np(destPath);
      try {
        fs.mkdirSync(path.dirname(destPath), { recursive: true });
        fs.cpSync(p, destPath, { recursive: true, errorOnExist: true, force: false });
        return ok({ path: out(destPath), name: path.basename(destPath) });
      } catch (e) { return err(e); }
    },
    moveTo(p, destDir) {
      p = np(p);
      const dst = join(destDir, path.basename(p));
      try {
        if (fs.existsSync(dst)) return err('移動先に同名が存在します: ' + path.basename(p));
        try { fs.renameSync(p, dst); }
        catch (e) {
          if (e.code !== 'EXDEV') throw e;                       // 別ドライブ間はコピー+削除
          fs.cpSync(p, dst, { recursive: true });
          fs.rmSync(p, { recursive: true, force: true });
        }
        return ok({ path: dst, name: path.basename(dst) });
      } catch (e) { return err(e); }
    },
    /** Windows ではごみ箱へ。ごみ箱が使えないときだけ完全削除。 */
    async delete(p) {
      p = np(p);
      try {
        if (shell && shell.trashItem) {
          try { await shell.trashItem(IS_WIN ? p.replace(/\//g, '\\') : p); return ok({ message: 'ごみ箱に移動しました' }); }
          catch {}
        }
        fs.rmSync(p, { recursive: true, force: false });
        return ok({ message: '削除しました' });
      } catch (e) { return err(e); }
    },

    // ---- 圧縮 ----
    async compress(pathsJson, destDir, baseName, format, password) {
      let sources;
      try { sources = JSON.parse(pathsJson).map(np); } catch { sources = []; }
      if (!sources.length) return err('対象がありません');
      format = String(format).toLowerCase();
      const pw = password ? String(password) : '';
      if (format === 'rar') return err('RAR は独自形式のため作成はできません (展開のみ対応)。zip/7z/tar/gz をご利用ください。');
      if (pw && !['zip', 'jar', '7z'].includes(format)) return err('パスワード付き圧縮は zip / 7z のみ対応です。');

      const pwArgs = (type) => (!pw ? [] : type === '7z' ? [`-p${pw}`, '-mhe=on'] : [`-p${pw}`, '-mem=AES256']);
      const make = async (outFile, type, inputs, extra = []) => {
        try { fs.rmSync(outFile, { force: true }); } catch {}
        const r = await run7z(['a', `-t${type}`, '-y', '-bd', ...extra, nat(outFile), ...inputs.map(nat)]);
        if (r.code !== 0) throw new Error((r.err || r.out).trim().split('\n').slice(-3).join(' '));
      };
      try {
        let outFile;
        if (format === 'zip' || format === 'jar') {
          outFile = join(destDir, `${baseName}.${format}`);
          await make(outFile, 'zip', sources, pwArgs('zip'));
        } else if (format === '7z') {
          outFile = join(destDir, `${baseName}.7z`);
          await make(outFile, '7z', sources, pwArgs('7z'));
        } else if (format === 'tar') {
          outFile = join(destDir, `${baseName}.tar`);
          await make(outFile, 'tar', sources);
        } else if (format === 'gz' && sources.length === 1 && fs.statSync(sources[0]).isFile()) {
          outFile = join(destDir, path.basename(sources[0]) + '.gz');
          await make(outFile, 'gzip', sources);
        } else if (['gz', 'tgz', 'targz', 'tar.gz'].includes(format)) {
          outFile = join(destDir, `${baseName}.tar.gz`);
          const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'scf-'));
          const tarFile = join(tmp, `${baseName}.tar`);
          try {
            await make(tarFile, 'tar', sources);
            await make(outFile, 'gzip', [tarFile]);
          } finally { fs.rmSync(tmp, { recursive: true, force: true }); }
        } else {
          return err('未対応の形式: ' + format);
        }
        return ok({ path: outFile, name: path.basename(outFile) });
      } catch (e) { return err(e); }
    },

    // ---- 解凍 ----
    async extract(p, destDir, password, merge) {
      p = np(p); destDir = np(destDir);
      const name = path.basename(p), low = name.toLowerCase();
      const pw = password ? String(password) : '';
      if (!fs.existsSync(p)) return err('ファイルがありません');

      let outDir;
      if (merge) outDir = out(destDir);
      else outDir = uniquePath(destDir, stripArchiveExt(name));
      fs.mkdirSync(outDir, { recursive: true });

      // 新規フォルダに展開して失敗したときは、そのフォルダ (今作ったばかり) を片付ける。
      const cleanup = () => { if (!merge) { try { fs.rmSync(outDir, { recursive: true, force: true }); } catch {} } };
      const needPw = (msg) => { cleanup(); return J({ ok: false, needsPassword: true, error: 'パスワードが必要か、間違っています: ' + msg }); };

      try {
        // ---- rar (unrar の WASM 版) ----
        if (low.endsWith('.rar')) {
          const { createExtractorFromFile } = require('node-unrar-js');
          try {
            const ex = await createExtractorFromFile({ filepath: p, targetPath: outDir, password: pw || undefined });
            let count = 0;
            for (const f of ex.extract().files) if (!f.fileHeader.flags.directory) count++;
            return ok({ dir: outDir, count });
          } catch (e) {
            const reason = String(e && (e.reason || e.message) || e);
            if (/PASSWORD/i.test(reason)) return needPw(reason);
            throw e;
          }
        }

        // ---- 7-Zip で扱う形式 ----
        const x = async (archive, target) => {
          // -p は常に付ける (未指定だと 7za が対話入力で止まるため)。暗号化されていなければ無視される。
          const r = await run7z(['x', nat(archive), `-o${nat(target)}`, '-aoa', '-y', '-bd', `-p${pw || '\u2063'}`]);
          const msg = (r.err + '\n' + r.out).trim();
          if (r.code !== 0) {
            if (PW_ERR.test(msg)) { const e2 = new Error(msg.split('\n').slice(-3).join(' ')); e2.pw = true; throw e2; }
            throw new Error(msg.split('\n').slice(-3).join(' '));
          }
          const m = r.out.match(/Files:\s*(\d+)/);
          return m ? Number(m[1]) : 1;
        };

        let count;
        if (low.endsWith('.tar.gz') || low.endsWith('.tgz')) {
          const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'scf-'));
          try {
            await x(p, tmp);
            const tarName = fs.readdirSync(tmp).find((f) => true);
            count = await x(join(tmp, tarName), outDir);
          } finally { fs.rmSync(tmp, { recursive: true, force: true }); }
        } else {
          count = await x(p, outDir);
        }
        return ok({ dir: outDir, count });
      } catch (e) {
        if (e && e.pw) return needPw(e.message);
        cleanup();
        return err(e);
      }
    },

    // ---- 外部アプリ連携 ----
    openAs(p) {
      p = np(p);
      if (shell) shell.openPath(IS_WIN ? p.replace(/\//g, '\\') : p).then((e) => e && toast('開けませんでした: ' + e));
    },
    shareFile(p) {
      p = np(p);
      if (shell) shell.showItemInFolder(IS_WIN ? p.replace(/\//g, '\\') : p);
    },

    // ---- HTTP (多言語オンライン実行用) ----
    async httpRequest(method, url, body) {
      try {
        const res = await fetch(url, {
          method: (method || 'GET').toUpperCase(),
          headers: body ? { 'Content-Type': 'application/json', Accept: 'application/json' } : { Accept: 'application/json' },
          body: body || undefined,
        });
        return J({ ok: res.ok, status: res.status, body: await res.text() });
      } catch (e) { return err(e); }
    },

    // ---- 検索 ----
    search(dir, query, contentToo, maxResults) {
      const q = String(query || '').toLowerCase();
      if (!q) return err('検索語を入力してください');
      const max = Number(maxResults) || 500;
      const results = [];
      const limit = { n: max };
      walk(np(dir), (full, st) => {
        const name = path.basename(full);
        let hit = name.toLowerCase().includes(q);
        if (!hit && contentToo && st.isFile() && st.size <= 2 * 1024 * 1024 && TEXT_EXT.has(ext(name))) {
          try { hit = fs.readFileSync(full, 'utf8').toLowerCase().includes(q); } catch {}
        }
        if (hit) { results.push(entryOf(full, st)); limit.n--; }
        return limit.n > 0;
      }, limit);
      return ok({ count: results.length, results });
    },

    // ---- 整理 ----
    deleteEmptyFiles(dir, recursive) {
      let n = 0;
      const doDir = (d) => {
        let names; try { names = fs.readdirSync(d); } catch { return; }
        for (const name of names) {
          const full = join(d, name);
          let st; try { st = fs.lstatSync(full); } catch { continue; }
          if (st.isFile() && st.size === 0) { try { fs.unlinkSync(full); n++; } catch {} }
          else if (st.isDirectory() && recursive) doDir(full);
        }
      };
      doDir(np(dir));
      return ok({ deleted: n, message: `${n} 件の 0 バイトファイルを削除しました` });
    },
    deleteEmptyDirs(dir) {
      let n = 0;
      const rm = (d, isRoot) => {
        let names; try { names = fs.readdirSync(d); } catch { return false; }
        let empty = true;
        for (const name of names) {
          const full = join(d, name);
          let st; try { st = fs.lstatSync(full); } catch { empty = false; continue; }
          if (st.isDirectory()) { if (!rm(full, false)) empty = false; } else empty = false;
        }
        if (empty && !isRoot) { try { fs.rmdirSync(d); n++; return true; } catch { return false; } }
        return false;
      };
      rm(np(dir), true);
      return ok({ deleted: n, message: `${n} 個の空フォルダを削除しました` });
    },
    async findDuplicates(dir, maxFiles) {
      const files = [];
      walk(np(dir), (full, st) => { if (st.isFile() && st.size > 0) files.push([full, st.size]); return files.length < (Number(maxFiles) || 3000); });
      const bySize = new Map();
      for (const [f, s] of files) { if (!bySize.has(s)) bySize.set(s, []); bySize.get(s).push(f); }
      const byHash = new Map();
      for (const [size, list] of bySize) {
        if (list.length < 2) continue;
        for (const f of list) {
          let h; try { h = await hash(f, 'sha256'); } catch { continue; }
          const k = size + ':' + h;
          if (!byHash.has(k)) byHash.set(k, { size, files: [] });
          byHash.get(k).files.push(f);
        }
      }
      const groups = [...byHash.values()].filter((g) => g.files.length > 1);
      return ok({ groups, groupCount: groups.length });
    },
    async hashFile(p, algo) {
      try { return ok({ algo, hash: await hash(np(p), algo) }); } catch (e) { return err(e); }
    },
    stat(p) {
      p = np(p);
      try {
        const st = fs.statSync(p);
        let canRead = true, canWrite = true;
        try { fs.accessSync(p, fs.constants.R_OK); } catch { canRead = false; }
        try { fs.accessSync(p, fs.constants.W_OK); } catch { canWrite = false; }
        let children = 0;
        if (st.isDirectory()) { try { children = fs.readdirSync(p).length; } catch {} }
        const name = path.basename(p) || out(p);
        return ok({ name, path: out(p), isDir: st.isDirectory(), size: st.isDirectory() ? 0 : st.size,
          mtime: Math.round(st.mtimeMs), children, canRead, canWrite, hidden: isHiddenName(name), ext: ext(name) });
      } catch (e) { return err(e); }
    },
  };
  return B;
}

module.exports = { createBridge, np, out };
