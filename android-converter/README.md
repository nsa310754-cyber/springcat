# codeconv — Android アプリ (APK)

プログラミング言語コンバーター **codeconv** を Android アプリ化したものです。
変換 UI とロジック（`assets/index.html` + `assets/convert.js`）を WebView に読み込んで動作し、
**完全にオフラインで動きます**。

## 特徴

- 🔒 **通信ゼロ / 外部 API なし** — `AndroidManifest.xml` に `INTERNET` 権限を宣言していません。
  貼り付けたコードは端末の外に一切送られません。変換はすべて WebView 内の JavaScript
  （`convert.js`）が、コードをトークナイズ（読み取り）して構文ルールで書き換えます。
- 📦 軽量（リリース APK 約 320KB）。ネイティブコードなし、WebView ラッパー方式。

## 変換できるもの

- **正確変換**: `JSON ⇔ YAML`
- **構造変換**: Python / CoffeeScript / JavaScript / TypeScript / JSX / Java / C / C++ / Go の間
  （インデント⇔波括弧、制御構文・キーワードの対応、コメント記号、真偽値/null の変換 など）
- **簡易変換**: 上記以外（Ruby / Haskell / HTML など）はキーワード・コメント記号の置換のみ
- **中継変換**: 例 `Haskell → HTML → JSX` のように多段変換

> これは「構文レベル」のコンバーターです。実行意味を100%保証するトランスパイラではありません。
> 整形済みの入力ほど綺麗に変換できます。

## 成果物 (ビルド済み APK)

リポジトリ直下の `dist/` に配置しています:

| ファイル | 用途 |
|---|---|
| `dist/codeconv-1.0-release.apk` | 署名済みリリース版 |
| `dist/codeconv-1.0-debug.apk`   | デバッグ版 |

- パッケージ名: `site.ragdollp.codeconv`
- versionName: `1.0` / versionCode: `1`

## ビルド方法

```bash
# Android SDK (platform android-34, build-tools 34.0.0) が必要
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :app:assembleRelease   # -> app/build/outputs/apk/release/app-release.apk
./gradlew :app:assembleDebug     # -> app/build/outputs/apk/debug/app-debug.apk
```

- Gradle 8.9 (wrapper) / Android Gradle Plugin 8.6.0 / compileSdk 34 / minSdk 24
- 署名鍵は同梱の使い捨て鍵 `codeconv-release.keystore`
  （store/key パスワードともに `codeconv`）。**公開配布時は各自の鍵に差し替えてください。**

## Web 版

同じ UI・ロジックはブラウザでもそのまま動きます: [`/converter/`](../converter/index.html)
