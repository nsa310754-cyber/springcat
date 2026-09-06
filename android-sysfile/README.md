# SysFile Viewer — Android アプリ (APK) 🐈

システムファイルをテキストとして閲覧する **読み取り専用** の Android アプリです。
デスクトップ版 (`../sysfile-viewer/`) の Android 版で、`/system`, `/proc`,
`/data` などの保護領域を、必要に応じて **スーパーユーザー(root)権限** に昇格して覗けます。

## 成果物 (ビルド済み APK)

リポジトリ直下の `dist/` に配置しています:

| ファイル | 用途 |
|---|---|
| `dist/SysFileViewer-1.0-release.apk` | 署名済みリリース版(配布/インストール用) |
| `dist/SysFileViewer-1.0-debug.apk`   | デバッグ版(検証用) |

- パッケージ名: `site.ragdollp.sysfileviewer`
- minSdk 24 (Android 7.0) / targetSdk 34 / compileSdk 35

## 仕組み

- UI は `app/src/main/assets/index.html`(WebView)。行番号付きでファイルを表示します。
- WebView の JavaScript から `@JavascriptInterface`("Native")経由で Java 側を呼びます。
  - `list(path)` … ディレクトリ一覧
  - `read(path)` … ファイルをテキストとして取得(先頭 8 MiB まで)
  - `requestRoot()` … `su` を起動して root 昇格(Magisk 等の許可ダイアログが出ます)
  - `requestAllFiles()` … Android 11+ の「すべてのファイルへのアクセス」設定を開く
- 読み取りは **まず通常の `java.io.File`**、権限が足りなければ **root 許可後に `su` 経由**
  (`head -c` でテキスト取得、`stat` でメタ情報)。
- **書き込み・削除・実行は一切行いません。** 純粋なビューアです。

## スーパーユーザー(root)権限について

- 端末が **root 化(Magisk / SuperSU など)されている場合のみ** 昇格が使えます。
  未 root 端末では「🔓」ボタンを押しても「su が見つかりません」と表示され、
  通常ユーザーとして読める範囲(アプリ領域・許可済みの外部ストレージ)のみ閲覧できます。
- 昇格はアプリが直接 `su` を起動して要求するだけで、パスワードは自前で扱いません
  (端末の Root 管理アプリが許可を判断します)。
- 非 root 端末で `/sdcard` などを広く見たい場合は「📂 全ファイル許可」から
  「すべてのファイルへのアクセス(MANAGE_EXTERNAL_STORAGE)」を付与してください。

## ビルド方法

Android SDK (platform 35 / build-tools 35.0.0) と JDK 17+ が必要です。

```bash
cd android-sysfile
echo "sdk.dir=/path/to/Android/sdk" > local.properties
gradle :app:assembleRelease :app:assembleDebug
# 出力: app/build/outputs/apk/{release,debug}/app-*.apk
```

- 署名鍵: `sysfileviewer-release.keystore`
  - storepass / keypass: `sysfileviewer` / alias: `sysfileviewer`
  - 個人配布・検証用の使い捨て鍵です。Play Store 公開時は各自の鍵に差し替えてください。

## インストール

```bash
adb install -r dist/SysFileViewer-1.0-release.apk
```

提供元不明のアプリのインストールを許可した上で APK を開いてもインストールできます。
