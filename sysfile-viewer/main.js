// SysFile Viewer — システムファイルをテキストとして閲覧するデスクトップアプリ (Electron)
//
//   ・任意のディレクトリを辿り、ファイルの中身をテキストとして表示する「読み取り専用」ビューア。
//   ・/etc, /var/log などの保護されたシステムファイルを見るために
//     スーパーユーザー(root/管理者)権限での再起動をサポートする。
//   ・書き込み・削除・実行は一切行わない。あくまで閲覧のみ。
//
//   権限昇格の方針:
//     Linux : pkexec (無ければ sudo) でアプリ自身を root として再起動
//     macOS : osascript の "with administrator privileges" で再起動
//     Windows: PowerShell Start-Process -Verb RunAs (UAC) で再起動
//
'use strict';

const { app, BrowserWindow, ipcMain, dialog } = require('electron');
const path = require('path');
const fs = require('fs');
const os = require('os');
const { spawn, spawnSync } = require('child_process');

// テキスト表示の上限。巨大ファイル / ログを開いても固まらないように先頭のみ読む。
const MAX_BYTES = 8 * 1024 * 1024; // 8 MiB

let mainWindow = null;

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1180,
    height: 800,
    backgroundColor: '#1a1613',
    title: 'SysFile Viewer',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  });

  mainWindow.loadFile('index.html');
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

// ---------------------------------------------------------------------------
// 権限まわり
// ---------------------------------------------------------------------------

// 現在 root / 管理者として動いているか。
function isElevated() {
  if (process.platform === 'win32') {
    // Windows: net session は管理者でしか成功しない簡易判定。
    try {
      const r = spawnSync('net', ['session'], { windowsHide: true });
      return r.status === 0;
    } catch (_) {
      return false;
    }
  }
  // POSIX: 実効 UID が 0 なら root。
  return typeof process.getuid === 'function' && process.getuid() === 0;
}

// アプリ自身を昇格して起動するためのコマンドを組み立てる。
// 開発時(electron .)とパッケージ後(単体バイナリ)の両方に対応する。
function buildRelaunchTarget() {
  if (app.isPackaged) {
    // パッケージ済み: 実行ファイルそのものを起動。
    return { cmd: process.execPath, args: [] };
  }
  // 開発時: electron 本体 + アプリのディレクトリ。
  return { cmd: process.execPath, args: [path.resolve(__dirname)] };
}

function relaunchElevated() {
  const { cmd, args } = buildRelaunchTarget();

  if (process.platform === 'linux') {
    // GUI アプリなので DISPLAY / XAUTHORITY / WAYLAND を root 環境に引き継ぐ。
    const envArgs = [];
    for (const key of ['DISPLAY', 'XAUTHORITY', 'WAYLAND_DISPLAY', 'XDG_RUNTIME_DIR']) {
      if (process.env[key]) envArgs.push(`${key}=${process.env[key]}`);
    }

    const hasPkexec = spawnSync('which', ['pkexec']).status === 0;
    let child;
    if (hasPkexec) {
      // pkexec env KEY=VAL ... <cmd> <args...>
      child = spawn('pkexec', ['env', ...envArgs, cmd, ...args], {
        detached: true,
        stdio: 'ignore',
      });
    } else {
      // フォールバック: x-terminal-emulator 経由で sudo (パスワードは端末で入力)。
      const inner = ['env', ...envArgs, cmd, ...args]
        .map((a) => `'${String(a).replace(/'/g, `'\\''`)}'`)
        .join(' ');
      child = spawn('x-terminal-emulator', ['-e', `sudo ${inner}`], {
        detached: true,
        stdio: 'ignore',
      });
    }
    child.on('error', (e) => {
      dialog.showErrorBox('権限昇格に失敗しました', String(e && e.message ? e.message : e));
    });
    child.unref();
    // 昇格版が起動したら現行(非特権)プロセスは終了。
    setTimeout(() => app.quit(), 600);
    return;
  }

  if (process.platform === 'darwin') {
    const quoted = [cmd, ...args]
      .map((a) => `quoted form of "${String(a).replace(/"/g, '\\"')}"`)
      .join(' & " " & ');
    const script = `do shell script "open -a " & ${quoted} with administrator privileges`;
    // macOS はバンドルを open で開き直す形が確実。ここでは execPath を直接昇格実行。
    const alt = `do shell script quoted form of "${cmd}" with administrator privileges`;
    const child = spawn('osascript', ['-e', args.length ? script : alt], {
      detached: true,
      stdio: 'ignore',
    });
    child.on('error', (e) => {
      dialog.showErrorBox('権限昇格に失敗しました', String(e && e.message ? e.message : e));
    });
    child.unref();
    setTimeout(() => app.quit(), 600);
    return;
  }

  if (process.platform === 'win32') {
    // PowerShell の Start-Process -Verb RunAs で UAC を出す。
    const argList = args.length
      ? ` -ArgumentList @(${args.map((a) => `'${a}'`).join(',')})`
      : '';
    const ps = `Start-Process -FilePath '${cmd}'${argList} -Verb RunAs`;
    const child = spawn('powershell', ['-NoProfile', '-Command', ps], {
      detached: true,
      stdio: 'ignore',
      windowsHide: true,
    });
    child.on('error', (e) => {
      dialog.showErrorBox('権限昇格に失敗しました', String(e && e.message ? e.message : e));
    });
    child.unref();
    setTimeout(() => app.quit(), 600);
    return;
  }

  dialog.showErrorBox('未対応のプラットフォーム', process.platform);
}

// ---------------------------------------------------------------------------
// ファイルシステム (読み取り専用)
// ---------------------------------------------------------------------------

function safeStat(p) {
  try {
    return fs.lstatSync(p);
  } catch (_) {
    return null;
  }
}

// ディレクトリの中身を一覧化。エラー(EACCES 等)はメッセージとして返す。
function listDir(dirPath) {
  const resolved = path.resolve(dirPath || '/');
  let entries;
  try {
    entries = fs.readdirSync(resolved, { withFileTypes: true });
  } catch (e) {
    return { path: resolved, error: describeErr(e), entries: [] };
  }

  const out = [];
  for (const ent of entries) {
    const full = path.join(resolved, ent.name);
    const st = safeStat(full);
    let type = 'other';
    if (ent.isSymbolicLink()) {
      // シンボリックリンクは追跡先の種別も見る。
      const target = (() => {
        try {
          return fs.statSync(full);
        } catch (_) {
          return null;
        }
      })();
      type = target && target.isDirectory() ? 'dir' : 'file';
    } else if (ent.isDirectory()) {
      type = 'dir';
    } else if (ent.isFile()) {
      type = 'file';
    }
    out.push({
      name: ent.name,
      type,
      symlink: ent.isSymbolicLink(),
      size: st && st.isFile() ? st.size : null,
      mode: st ? '0' + (st.mode & 0o777).toString(8) : null,
      mtime: st ? st.mtimeMs : null,
    });
  }

  out.sort((a, b) => {
    if (a.type !== b.type) return a.type === 'dir' ? -1 : 1;
    return a.name.localeCompare(b.name);
  });

  return { path: resolved, error: null, entries: out };
}

// バイナリ判定: 先頭ブロックに NUL があれば非テキストとみなす。
function looksBinary(buf) {
  const n = Math.min(buf.length, 8192);
  for (let i = 0; i < n; i++) {
    if (buf[i] === 0) return true;
  }
  return false;
}

// ファイルをテキストとして読む。
function readFileText(filePath) {
  const resolved = path.resolve(filePath);
  const st = safeStat(resolved);
  if (!st) {
    return { path: resolved, error: 'ファイル情報を取得できませんでした (存在しない/権限不足)。' };
  }
  if (st.isDirectory()) {
    return { path: resolved, error: 'これはディレクトリです。' };
  }

  let fd;
  try {
    fd = fs.openSync(resolved, 'r');
  } catch (e) {
    return { path: resolved, error: describeErr(e) };
  }

  try {
    const size = st.isFile() ? st.size : MAX_BYTES;
    const toRead = Math.min(size || MAX_BYTES, MAX_BYTES);
    const buf = Buffer.alloc(toRead);
    const bytes = fs.readSync(fd, buf, 0, toRead, 0);
    const slice = buf.subarray(0, bytes);
    const binary = looksBinary(slice);
    return {
      path: resolved,
      error: null,
      binary,
      truncated: (st.size || 0) > MAX_BYTES,
      size: st.size,
      mode: '0' + (st.mode & 0o777).toString(8),
      mtime: st.mtimeMs,
      // バイナリでも「テキストとして」見たいという要求なので、置換文字付きで返す。
      text: slice.toString('utf8'),
    };
  } catch (e) {
    return { path: resolved, error: describeErr(e) };
  } finally {
    try {
      fs.closeSync(fd);
    } catch (_) {}
  }
}

function describeErr(e) {
  const code = e && e.code ? e.code : '';
  const map = {
    EACCES: 'アクセスが拒否されました。スーパーユーザー権限で再起動すると読める場合があります。',
    EPERM: '操作が許可されていません。スーパーユーザー権限が必要です。',
    ENOENT: 'ファイル/ディレクトリが存在しません。',
     EISDIR: 'これはディレクトリです。',
    ENOTDIR: 'ディレクトリではありません。',
    ELOOP: 'シンボリックリンクのループが検出されました。',
  };
  return (map[code] || (e && e.message) || '不明なエラー') + (code ? ` (${code})` : '');
}

// ---------------------------------------------------------------------------
// IPC
// ---------------------------------------------------------------------------

ipcMain.handle('sys:info', () => ({
  platform: process.platform,
  elevated: isElevated(),
  user: os.userInfo().username,
  home: os.homedir(),
  root: process.platform === 'win32' ? 'C:\\' : '/',
}));

ipcMain.handle('fs:list', (_e, dirPath) => listDir(dirPath));
ipcMain.handle('fs:read', (_e, filePath) => readFileText(filePath));
ipcMain.handle('sys:elevate', () => {
  relaunchElevated();
  return true;
});
