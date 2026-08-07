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

## エディション (full / lite) — 最小ストレージ版について

インストールサイズを抑えたい場合向けに、Firebase を丸ごと除いた **lite** エディションを用意しています。
`flavorDimensions "edition"` で `full` / `lite` の 2 種類の product flavor に分かれています。

| エディション | Firebase (Analytics/Cloud Messaging) | イベント告知プッシュ通知 | 相対サイズ |
|---|---|---|---|
| `full` | あり | あり | 基準 |
| `lite` | **なし**(依存関係ごと除外) | なし(ローカルのデイリーボーナス通知は両方で動作) | 約 24% 小さい (release, 圧縮後) |

ゲーム本体(WebView + 同梱アセット)・オフラインプレイ・セーブ・世界ランキング等の Web 側機能は
**両エディションで完全に同じ**です。差はネイティブ側の Firebase 統合の有無のみです。

さらに release ビルドは R8 (`minifyEnabled` + `shrinkResources`) を有効化し、未使用コード/リソースを
除去しています。この変更だけで `full` 版も旧ビルドよりインストールサイズがかなり縮小しています
(Firebase 経由で持ち込まれる Kotlin ランタイム/protobuf 等の未使用コードが大量に削られるため)。

## 成果物 (ビルド済み APK)

リポジトリ直下の `dist/` に配置しています:

| ファイル | 用途 | 実測サイズ (release) |
|---|---|---|
| `dist/BlockDestroy-3.5.0-release.apk` | full 版・署名済みリリース(提出・配布用) | 約 3.9 MB |
| `dist/BlockDestroy-3.5.0-debug.apk` | full 版・デバッグ版(検証用) | — |
| `dist/BlockDestroy-3.5.0-lite-release.apk` | **lite 版**・最小ストレージ・署名済みリリース | 約 2.9 MB |

- パッケージ名: `site.ragdollp.blockdestory`(full/lite とも同一。同時インストール不可)
- versionName `3.5.0`(lite は `3.5.0-lite`) / versionCode `3`
- minSdk 24 (Android 7.0) / targetSdk 34 (Android 14) / compileSdk 35
- full のみ Firebase(Analytics + Cloud Messaging)をネイティブ統合。`google-services.json` 同梱、AndroidX 有効

## インストール方法(実機)

1. 用途に応じて `dist/BlockDestroy-3.5.0-release.apk`(full)または
   `dist/BlockDestroy-3.5.0-lite-release.apk`(lite・最小ストレージ)を端末へ転送
2. 「提供元不明のアプリ」/「この提供元を許可」を有効化
3. APK をタップしてインストール

`adb` を使う場合:

```bash
adb install -r dist/BlockDestroy-3.5.0-release.apk        # full
adb install -r dist/BlockDestroy-3.5.0-lite-release.apk   # lite (最小ストレージ)
```

## ソースからビルドする

前提: JDK 17+、Android SDK(build-tools 35.0.0 / platform android-35。Firebase BoM 34.x が compileSdk 35 を要求するため)。

```bash
cd android
echo "sdk.dir=/path/to/Android/sdk" > local.properties   # または環境変数 ANDROID_HOME を設定

# デバッグ APK (full/lite 両方)
./gradlew :app:assembleDebug
#   → app/build/outputs/apk/{full,lite}/debug/app-{full,lite}-debug.apk

# 署名済みリリース APK
./gradlew :app:assembleFullRelease
#   → app/build/outputs/apk/full/release/app-full-release.apk
./gradlew :app:assembleLiteRelease   # 最小ストレージ版
#   → app/build/outputs/apk/lite/release/app-lite-release.apk
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
    ├── build.gradle                     # flavorDimensions "edition": full / lite
    ├── proguard-rules.pro
    ├── src/main/                        # full/lite 共通コード
    │   ├── AndroidManifest.xml
    │   ├── assets/game.html             # ゲーム本体(オフライン同梱)
    │   ├── java/site/ragdollp/blockdestory/MainActivity.java
    │   └── res/                         # アイコン / テーマ / 文言
    ├── src/full/                        # full のみ追加 (Firebase Cloud Messaging)
    │   ├── AndroidManifest.xml
    │   └── java/site/ragdollp/blockdestory/{EventMessagingService,EventTopics}.java
    └── src/lite/                        # lite のみ追加 (FCM 購読の no-op 実装)
        └── java/site/ragdollp/blockdestory/EventTopics.java
```
