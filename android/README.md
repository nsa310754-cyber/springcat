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
| `dist/BlockDestroy-3.7.0-release.apk` | Google Play 版・署名済みリリース(提出・配布用)|
| `dist/BlockDestroy-3.7.0-debug.apk`   | Google Play 版・デバッグ(検証用)|
| `dist/BlockDestroy-3.7.0-fire-release.apk` | **Fire タブレット版**・署名済みリリース |
| `dist/BlockDestroy-3.7.0-fire-debug.apk`   | **Fire タブレット版**・デバッグ(検証用)|

- パッケージ名: `site.ragdollp.blockdestory`(Google Play 版 / Fire 版とも共通)
- versionName `3.7.0` / versionCode `6`
- minSdk 24 (Android 7.0) / targetSdk 34 (Android 14) / compileSdk 35
- Firebase(Analytics/Messaging)、AdMob、Google Play 課金をネイティブ統合。`google-services.json` 同梱、AndroidX 有効

### Fire タブレット版について

Amazon Fire タブレット (Fire OS) には Google Play 開発者サービスが入っていないため、
Firebase / AdMob / Google Play 課金は接続できず**黙って無効化**されます
(いずれも `try/catch` で握りつぶす設計。README 冒頭のとおりオンライン機能はオフラインでも
コアのゲームプレイに影響しません)。日々のボーナス通知は GMS 非依存の
`AlarmManager`/`NotificationManager` で実装済みのため、Fire タブレットでも問題なく動作します。

`fire` フレーバーは Google Play 版と全く同じコード・同じ `applicationId` を使い、以下の一点だけを
上書きしています(`app/src/fire/AndroidManifest.xml`):

- 画面回転を `portrait` 固定 → `unspecified`(端末の向きに追従)に変更。
  Fire タブレットは横持ちで使われることが多いため、横向きでも起動できるようにしています。

Amazon Appstore への提出、または `adb install` / ファイルマネージャー経由でのサイドロードに
そのまま使えます(Fire タブレット側で「不明ソースからのアプリ」を許可してください)。

## インストール方法(実機)

Google Play 版(スマホ・通常タブレット):

```bash
adb install -r dist/BlockDestroy-3.7.0-release.apk
```

Fire タブレット版:

```bash
adb install -r dist/BlockDestroy-3.7.0-fire-release.apk
```

`adb` が使えない場合は APK を端末へ転送し、「提供元不明のアプリ」/
「不明ソースからのアプリ」を有効化してから APK をタップしてインストールしてください。

## ソースからビルドする

前提: JDK 17+、Android SDK(build-tools 35.0.0 / platform android-35。Firebase BoM 34.x が compileSdk 35 を要求するため)。

```bash
cd android
echo "sdk.dir=/path/to/Android/sdk" > local.properties   # または環境変数 ANDROID_HOME を設定

# Google Play 版
./gradlew :app:assembleGoogleDebug
#   → app/build/outputs/apk/google/debug/app-google-debug.apk
./gradlew :app:assembleGoogleRelease
#   → app/build/outputs/apk/google/release/app-google-release.apk

# Fire タブレット版
./gradlew :app:assembleFireDebug
#   → app/build/outputs/apk/fire/debug/app-fire-debug.apk
./gradlew :app:assembleFireRelease
#   → app/build/outputs/apk/fire/release/app-fire-release.apk

# フレーバーを指定しない場合は両方まとめてビルドされる
./gradlew :app:assembleRelease
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
        # (fire フレーバー上書き: app/src/fire/AndroidManifest.xml)
```
