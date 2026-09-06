# SysFile Viewer 🐈

システムファイルをテキストとして閲覧するデスクトップアプリ (Electron製)。

`/etc/hosts` や `/var/log/syslog` のような **保護されたシステムファイル** を、
必要に応じて **スーパーユーザー(root/管理者)権限** に昇格して覗くための、
**読み取り専用** のファイルビューアです。

## 特徴

- 📁 任意のディレクトリを辿ってファイルを閲覧
- 📄 ファイルの中身をテキスト（行番号付き）で表示
- 🔓 ボタン一つでスーパーユーザー権限に昇格して再起動
  - Linux: `pkexec`（無ければ端末で `sudo`）
  - macOS: `osascript`（管理者として実行）
  - Windows: PowerShell `Start-Process -Verb RunAs`（UAC）
- 🔒 **読み取り専用** — 書き込み・削除・実行は一切行いません
- ⚡ 大きなファイル/ログは先頭 8 MiB のみ読み込み、フリーズを防止
- 🧷 バイナリファイルも「テキストとして」表示（NUL は置換文字で表示）

## 使い方（開発）

```bash
cd sysfile-viewer
npm install
npm start
```

起動後、上部のショートカット（`/etc`, `/var/log` など）やパス入力欄から辿れます。
権限が足りずに読めないファイルは「⚠ アクセスが拒否されました」と表示されるので、
右上の **「🔓 スーパーユーザー権限で再起動」** を押すと認証ダイアログが出て、
root として起動し直した後にそのまま読めるようになります。

## ビルド

```bash
npm run dist:linux   # AppImage (Linux)
npm run dist:win     # zip (Windows)
npm run dist:mac     # zip (macOS)
```

## セキュリティ設計

- `contextIsolation: true` / `nodeIntegration: false`。レンダラは `preload.js` が
  公開する最小 API (`list` / `read` / `info` / `elevate`) 経由でのみ main と通信します。
- ファイル操作は main プロセスの `fs` による **読み取り専用**。書き込み系 API は公開していません。
- 権限昇格は OS 標準の認証機構（polkit / UAC / 管理者認証）を通し、
  アプリ自身を昇格プロセスとして起動し直すだけです。パスワードを自前で扱いません。
