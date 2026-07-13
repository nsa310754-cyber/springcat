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
2. **`F12`** ボタンで開発者ツール（Eruda）を開閉（Eruda のフローティングボタンからも可）。
3. **🏠 ホームボタン**で Google のホームに戻ります。
4. ◀ / ▶ / ⟳ で戻る・進む・再読み込み。端末の戻るキーで履歴を戻ります。

### ✨ 追加機能
- **PCサイト表示（User-Agent Switcher）**: 既定でデスクトップ UA。UA 文字列に加えて、
  ページのスクリプトが動く前（document-start）に `navigator.userAgentData`（Client Hints）・
  `navigator.platform`・タッチ判定を **デスクトップに偽装**するため、Client Hints で端末を
  判定する最近のサイト（例: developer.android.com のダウンロード可否）でも PC 版として
  扱われます。右上 **⋮メニュー → 「PCサイト表示（デスクトップUA）」** でモバイル表示と
  切り替え（切替後は自動リロード）。
- **ダウンロード表示 & 一覧**: サイト内のダウンロードボタンを押すと、画面下部に
  **「〇〇 をダウンロードしています…」** とファイル名付きで表示され、実ファイルは
  端末の「ダウンロード」フォルダへ保存されます。⋮メニュー → **「⬇ ダウンロード一覧」**
  で履歴（ファイル名・日時）を確認でき、項目タップで元URLを新しいタブで開く／
  「端末のDL画面を開く」/「履歴を消去」も可能です。
- **JavaScript ON/OFF（script / noscript）**: ⋮メニュー → 「JavaScript を有効」で
  スクリプトの有効・無効を切り替え（切替後は自動リロード）。
- **タブ**: 上部のタブ列で複数タブを管理。**＋** で新規タブ、各タブの **×** で閉じる。
  リンクの新規ウィンドウ（`target=_blank` 等）も自動で新しいタブとして開きます。
- **ブックマーク**: ⋮メニュー → 「★ ブックマークに追加」で現在ページを保存。
  「ブックマーク一覧」から開く／「削除…」でまとめて削除（端末内に永続保存）。

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
