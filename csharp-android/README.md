# Block Destroy Lite — C# (.NET for Android) 版 APK

`ブロック壊しゲーム`(アップロードされた単体 HTML、5x5 のブロック消しゲーム)を
**C# / .NET for Android** で APK 化したものです。`android/`(Java/Kotlin 版・別ゲーム本体)
とは別の、独立したアプリです。

## 元 HTML から取り除いたもの

アップロードされた HTML に含まれていた外部リソースを削除し、完全にオフラインで
動く状態にしてから同梱しています。

- `<link rel="manifest" href="https://adgj.neocities.org/manifest.json">`
- Cloudflare Insights のビーコンスクリプト
  (`<script type="module" src="https://static.cloudflareinsights.com/...">`)

ゲーム本体のロジック・見た目は変更していません。

## なぜ C# (.NET for Android) 方式か

依頼どおり C# で APK 化するため、Java/Kotlin ではなく **.NET for Android**
(`dotnet new android` テンプレート)を採用しました。ゲーム HTML はネイティブの
`Android.Webkit.WebView` に読み込んで表示するだけのシンプルなラッパーです。

## 成果物 (ビルド済み APK)

`dist/` に配置しています。

| ファイル | 用途 |
|---|---|
| `dist/BlockDestroyLite-1.0-release.apk` | 署名済みリリース版 |
| `dist/BlockDestroyLite-1.0-debug.apk`   | デバッグ版(検証用)|

- パッケージ名: `site.ragdollp.blockdestorylite`
- versionName `1.0` / versionCode `1`
- minSdk 24 (Android 7.0) / targetSdk 34 (Android 14) / compileSdk 34
- 追加の権限なし(リリースビルドでは `INTERNET` 権限も付与していません。デバッグビルドは
  .NET のデプロイ/デバッガ接続のため自動的に `INTERNET` が付与されます)

## インストール方法(実機)

```bash
adb install -r dist/BlockDestroyLite-1.0-release.apk
```

または APK ファイルを端末に転送し、「提供元不明のアプリ」を許可してタップでインストール。

## ソースからビルドする

前提: .NET 8 SDK + `android` ワークロード、Android SDK (platform-tools / platforms;android-34 /
build-tools;34.0.0)、JDK 17(.NET for Android は JDK 17 のみサポート。JDK 21 等では
`XA0030` エラーになります)。

```bash
# .NET 8 SDK
curl -sSL https://dot.net/v1/dotnet-install.sh | bash -s -- --channel 8.0
export PATH="$HOME/.dotnet:$PATH"

# Android ビルド用ワークロード (.NET 側の Android SDK ラッパー)
dotnet workload install android

# Android SDK 本体 (sdkmanager で取得)
sdkmanager --sdk_root=/path/to/android-sdk "platform-tools" "platforms;android-34" "build-tools;34.0.0"

cd csharp-android/BlockDestroyLite

# デバッグ APK
dotnet build -c Debug -f net8.0-android \
  -p:AndroidSdkDirectory=/path/to/android-sdk \
  -p:JavaSdkDirectory=/path/to/jdk-17
#   → bin/Debug/net8.0-android/site.ragdollp.blockdestorylite-Signed.apk

# 署名済みリリース APK (keystore/ 内の鍵で署名される)
dotnet build -c Release -f net8.0-android \
  -p:AndroidSdkDirectory=/path/to/android-sdk \
  -p:JavaSdkDirectory=/path/to/jdk-17
#   → bin/Release/net8.0-android/site.ragdollp.blockdestorylite-Signed.apk
```

### 署名鍵について

`BlockDestroyLite/keystore/blockdestroylite-release.keystore` は個人配布・検証用の
**使い捨て自己署名鍵**です。

| 項目 | 値 |
|---|---|
| keystore | `blockdestroylite-release.keystore` |
| storePassword | `blockdestroylite` |
| keyAlias | `blockdestroylite` |
| keyPassword | `blockdestroylite` |

Google Play へ公開する場合は、各自で新しい鍵を作成し `BlockDestroyLite.csproj` の
`AndroidSigning*` プロパティを差し替えてください。この鍵を本番の秘密鍵として使わないこと。

## ゲーム HTML を更新したら

`BlockDestroyLite/Assets/game.html` を新しい HTML で置き換えて再ビルドするだけです
(アプリ側のコード変更は不要)。外部スクリプト/リンクを含む HTML を使う場合は、
オフライン動作が必要ならあらかじめ取り除いてください。

## プロジェクト構成

```
csharp-android/
├── README.md
├── dist/                                  # ビルド済み APK
│   ├── BlockDestroyLite-1.0-release.apk
│   └── BlockDestroyLite-1.0-debug.apk
└── BlockDestroyLite/
    ├── BlockDestroyLite.csproj
    ├── MainActivity.cs                    # WebView ラッパー本体
    ├── AndroidManifest.xml
    ├── Assets/game.html                   # ゲーム本体(外部リソース除去済み・オフライン同梱)
    ├── keystore/blockdestroylite-release.keystore
    └── Resources/                         # アイコン / 文言
```
