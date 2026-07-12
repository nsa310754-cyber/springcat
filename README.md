# SpringCat DevBrowser 🐱🔧

**PCブラウザの「F12（開発者ツール）」をスマホでも使えるようにする Android ブラウザアプリです。**

通常、Chrome などの DevTools（F12）は PC でしか開けません。このアプリは、
WebView ベースの軽量ブラウザに、モバイル向け開発者ツール
[Eruda](https://github.com/liriliri/eruda) を全ページ自動注入することで、
**スマホ単体でコンソール・要素検証・ネットワーク・ストレージ確認**などができます。

## 📦 APK（そのままインストールできます）

| ファイル | 用途 |
|---|---|
| [`SpringCat-DevBrowser.apk`](./SpringCat-DevBrowser.apk) | リリース版（推奨・軽量） |
| [`SpringCat-DevBrowser-debug.apk`](./SpringCat-DevBrowser-debug.apk) | デバッグ版（`chrome://inspect` でPCからも接続可） |

- どちらも Android のデバッグ署名で署名済みなので、そのままサイドロードできます。
- `minSdk 21`（Android 5.0）〜 `targetSdk 34`（Android 14）対応。

### インストール手順（スマホ）
1. `SpringCat-DevBrowser.apk` を端末にダウンロード。
2. 「提供元不明のアプリ / このソースを許可」を有効にしてタップ。
3. インストール後、ホームの **F12 アイコン**から起動。

## 🚀 使い方
1. 上部のアドレスバーに URL か検索ワードを入力（`.` を含めば URL、なければ Google 検索）。
2. 右上の **`F12`** ボタンを押すと開発者ツール（Eruda）が開閉します。
   - Eruda 自体のフローティングボタンからも開けます。
3. ◀ / ▶ / ⟳ で戻る・進む・再読み込み。端末の戻るキーで履歴を戻ります。

### 開発者ツールでできること（Eruda）
- **Console** … `console.log` / エラー表示、任意 JS の実行
- **Elements** … DOM ツリー / スタイルの確認
- **Network** … リクエスト / レスポンスの確認
- **Resources** … localStorage / sessionStorage / Cookie / スクリプト
- **Info / Snippets** … 端末情報や便利スニペット

## 🛠 ソースからのビルド

Android SDK（Platform 34 / Build-Tools 34.0.0）と **Gradle 8.7+**（または Android Studio）が必要です。

```bash
# SDK の場所を指定
echo "sdk.dir=/path/to/android-sdk" > local.properties

# デバッグ APK
gradle :app:assembleDebug
#  -> app/build/outputs/apk/debug/app-debug.apk

# リリース APK（デバッグ署名で署名済み）
gradle :app:assembleRelease
#  -> app/build/outputs/apk/release/app-release.apk
```

> 本番配布向けに独自署名する場合は `app/build.gradle` の `signingConfigs` を
> 実際のキーストアに差し替えてください。

## 🧩 構成

```
app/src/main/
├── AndroidManifest.xml            # INTERNET 権限 / ランチャー
├── java/com/springcat/browser/
│   └── MainActivity.java          # WebView + アドレスバー + Eruda 注入 / F12 トグル
├── assets/eruda.js                # モバイル版 DevTools 本体（オフライン同梱）
└── res/                           # レイアウト・F12 アイコン・テーマ
```

Eruda はアプリ内 `assets/` に同梱しているため、**オフラインでも DevTools が動作**します。

## ⚠️ 注意
- 署名はデバッグ用のため Google Play へはそのまま公開できません（サイドロード用途）。
- `usesCleartextTraffic` を有効にしているため http サイトも開けます。
