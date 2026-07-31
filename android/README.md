# Block Destroy — Android アプリ (APK)

`Blockdestory1.html`(46,397行 / 2.4MB の製品版ゲーム)を Android アプリ化したものです。
ゲーム本体は WebView に読み込まれて動作し、**完全オフラインでプレイ可能**です。

## なぜ WebView 方式か

元の HTML は以下を含む大規模な Web アプリで、C++ への忠実な 1:1 移植は現実的ではありません:

- 1,466 個の JS 関数 / 4,007 個の const / 865 箇所の DOM 操作
- Firebase Realtime Database(世界ランキング)、Google AdSense、reCAPTCHA Enterprise、Cloudflare
- 4 言語 i18n(日/英/中/韓)、BigNum(任意精度演算)、複数ゲームモード、ブロック図鑑 ほか

そのためゲームの全機能を 100% 保ったまま APK 化できる **WebView ラッパー方式**を採用しました
(PWA ではなく、`.apk` として配布・インストール・提出が可能な本物のネイティブアプリです)。

> **設計上の裏付け:** 元 HTML には「HTML ビューア / 簡易ブラウザ対策」として WebView をブロックする
> コードがあり、開発メモに *「ネイティブアプリ(APK)からの起動は必ず通す。Kotlin 側で UserAgent
> の末尾に `BlockdestoryApp/1` を付けること」* と明記されています。つまり作者は最初から APK 化を
> 想定していました。本アプリはその指示どおり UserAgent に `BlockdestoryApp/1` を付与しています
> (`MainActivity.java`)。

## オフライン動作について

- ゲーム HTML は `app/src/main/assets/game.html` に同梱し、`file:///android_asset/game.html`
  から読み込むためネットワーク不要でプレイできます。
- セーブデータは WebView の `localStorage`(DOM Storage)に保存されます。
- Firebase の世界ランキング・広告・reCAPTCHA などのオンライン機能は、通信できない場合は
  静かに失敗するだけで、コアのゲームプレイには影響しません(元コードが try/catch で握りつぶす設計)。

## 成果物 (ビルド済み APK)

リポジトリ直下の `dist/` に配置しています:

| ファイル | 用途 |
|---|---|
| `dist/BlockDestroy-1.0-release.apk` | 署名済みリリース版(提出・配布用)|
| `dist/BlockDestroy-1.0-debug.apk`   | デバッグ版(検証用)|

- パッケージ名: `site.ragdollp.blockdestory`
- versionName `1.0` / versionCode `1`
- minSdk 24 (Android 7.0) / targetSdk 34 (Android 14)

## インストール方法(実機)

1. `dist/BlockDestroy-1.0-release.apk` を端末へ転送
2. 「提供元不明のアプリ」/「この提供元を許可」を有効化
3. APK をタップしてインストール

`adb` を使う場合:

```bash
adb install -r dist/BlockDestroy-1.0-release.apk
```

## ソースからビルドする

前提: JDK 17+、Android SDK(build-tools 34.0.0 / platform android-34)。

```bash
cd android
echo "sdk.dir=/path/to/Android/sdk" > local.properties   # または環境変数 ANDROID_HOME を設定

# デバッグ APK
./gradlew :app:assembleDebug
#   → app/build/outputs/apk/debug/app-debug.apk

# 署名済みリリース APK
./gradlew :app:assembleRelease
#   → app/build/outputs/apk/release/app-release.apk
```

### 署名鍵について

`android/blockdestory-release.keystore` は個人配布・提出用の**使い捨て自己署名鍵**です。

| 項目 | 値 |
|---|---|
| keystore | `blockdestory-release.keystore` |
| storePassword | `blockdestory` |
| keyAlias | `blockdestory` |
| keyPassword | `blockdestory` |

> Google Play へ公開する場合は、各自で新しいアップロード鍵を作成し `app/build.gradle` の
> `signingConfigs.release` を差し替えてください。この鍵を本番の秘密鍵として使わないこと。

## ゲーム HTML を更新したら

`app/src/main/assets/game.html` を新しい HTML で置き換えて再ビルドするだけです
(アプリ側のコード変更は不要)。ただし新しい HTML でも UserAgent の `BlockdestoryApp`
判定フックが維持されていることを確認してください。

## プロジェクト構成

```
android/
├── settings.gradle / build.gradle / gradle.properties
├── gradlew / gradle/wrapper/            # Gradle 8.9 wrapper
├── blockdestory-release.keystore        # リリース署名鍵(上記)
└── app/
    ├── build.gradle
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/game.html             # ゲーム本体(オフライン同梱)
        ├── java/site/ragdollp/blockdestory/MainActivity.java
        └── res/                         # アイコン / テーマ / 文言
```
