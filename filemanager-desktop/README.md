# SpringCat Files — Windows 版

Surface などのタッチ対応 Windows PC 向けのファイルマネージャ。Electron 製で、UI は
Android 版と**同じ `app.html`** を使い、ファイル操作だけを `bridge.js`（Node.js）で
実装し直しています。Android 版の `FileBridge.java` と同じ名前・同じ JSON 形式で応答する
ので、画面や操作は Android 版と共通です。

## できること

- フォルダ閲覧（ドライブ一覧・デスクトップ/ドキュメント/ダウンロードなど）、ストレージ容量
- テキストをタップで表示・編集（`Ctrl+S` で保存）、画像・HTML/JSX プレビュー
- JS/TS のローカル実行、多言語（Python 等）のオンライン実行
- 圧縮: zip / 7z / tar / gz / tar.gz / jar（**zip と 7z はパスワード可**・AES-256）
- 解凍: zip / 7z / tar / gz / tar.gz / jar / apk / **rar**（パスワード対応）、既存フォルダへの統合展開
- 検索（名前・中身）、並び替え、隠しファイル切替、重複検出、0B ファイル/空フォルダ整理、ハッシュ
- 削除は**ごみ箱へ移動**（元に戻せる）
- エクスプローラーから**ドラッグ＆ドロップでコピー**

root・バグレポート・APK インストールは Android 専用のため Windows 版では表示されません
（APK は zip として展開できます）。

## タッチ / キーボード操作

| 操作 | 内容 |
|------|------|
| タップ | 開く |
| 長押し / 右クリック | 項目メニュー |
| `Esc` | ダイアログを閉じる / 選択解除 |
| `Alt+←` / `Backspace` / マウスの戻るボタン | 戻る |
| `Alt+↑` | 上のフォルダへ |
| `F5` | 再読み込み |
| `Ctrl+F` / `Ctrl+L` / `Ctrl+N` | 検索 / パス移動 / ファイル追加 |
| `Ctrl` + `+` `-` `0` | 拡大 / 縮小 / 元に戻す |
| `F11` | 全画面 |

## ビルド

```bash
cd filemanager-desktop
npm ci
npm test          # bridge.js の自己テスト (実ファイルで圧縮/解凍まで検証)
npm start         # 開発起動 (app.html とアイコンを Android 版から取り込んで起動)
npm run dist      # Windows 用 exe (インストーラー版 + ポータブル版) を release/ に出力
```

`app.html` と `icon.png` は `scripts/sync.js` が Android 版
（`filemanager/app/src/main/assets/app.html`, `filemanager/ic_launcher-512.png`）から
コピーするので、UI の修正は Android 側の `app.html` だけを編集します。

GitHub Actions（`.github/workflows/build-filemanager-windows.yml`）が Windows ランナーで
自己テスト → exe ビルドを行い、成果物 `SpringCatFiles-Windows` として公開します。

## 初回起動の警告について

コード署名をしていないため、初回起動時に Windows SmartScreen が
「Windows によって PC が保護されました」と表示することがあります。
「詳細情報」→「実行」で起動できます。
