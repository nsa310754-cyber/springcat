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

## アイコン

`BlockDestroy` 本編(`dist/playstore/icon-512.png`)のブロック絵柄アイコンをベースに、
赤い「DEMO」リボンを斜めに重ねたものを使っています。アダプティブアイコン(Android 8+)の
セーフゾーンに収まるよう配置してあるので、丸型/角丸型どちらのランチャーマスクでも
リボン文字が欠けません。生成には Pillow を使用(スクリプトは同梱していません。
`Resources/mipmap-*/appicon*.png` を直接差し替える形で反映済みです)。

## ゲーム本体の難読化(APK 解凍対策)

`GameSrc/game.html`(平文のゲーム本体)はそのままでは APK に同梱していません。
ビルド時に `tools/GameEncryptor`(小さな .NET コンソールツール)で AES-256-CBC 暗号化し、
`Assets/game.enc` として同梱しています。`unzip`/`apktool` で APK を解凍しても、
ゲームの HTML/JS はバイナリの暗号文としてしか見えません。

復号は `MainActivity.cs` が起動時にメモリ上で行い、`WebView.LoadDataWithBaseURL` で
表示します(ディスク上に平文を書き出しません)。パスフレーズは
`BlockDestroyLite.csproj` の `GameEncPassphrase` と `MainActivity.cs` の
`GameEncPassphraseParts` の両方に埋め込まれており、常に一致させる必要があります
(クライアント側の難読化であり「解読不能」ではありません — 鍵はアプリ内にあります —
が、平文ファイルの直読みは防げます)。

`GameSrc/game.html` を更新して `dotnet build` すると、ビルドの一部として
自動的に再暗号化されます(`Assets/game.enc` は `.gitignore` 済みの生成物)。

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

Google Play へ公開する場合は、この使い捨て鍵ではなく **本番用の別鍵** で署名してください
(下記「公開用パッケージ」参照)。この使い捨て鍵を本番の秘密鍵として使わないこと。

## ゲーム HTML を更新したら

`BlockDestroyLite/GameSrc/game.html` を新しい HTML で置き換えて再ビルドするだけです
(アプリ側のコード変更は不要。`Assets/game.enc` はビルド時に自動再生成されます)。
外部スクリプト/リンクを含む HTML を使う場合は、オフライン動作が必要なら
あらかじめ取り除いてください。

## 公開用パッケージ (ストア掲載素材一式)

Google Play 提出に必要な素材をまとめた zip を別途、直接お渡ししています
(リポジトリには含めていません — 理由は下記)。内容:

- `icon/icon-512-demo.png` — ストア掲載用アイコン (512×512)
- `feature-graphic-1024x500.png` — フィーチャーグラフィック (1024×500)
- `screenshots/` — スクリーンショット3枚 (1080×1350、実際のゲーム画面をヘッドレス
  ブラウザで撮影)
- `short-description.txt` / `long-description.txt` — 短い説明・長い説明(日本語)
- `keystore/blockdestroylite-PRODUCTION-release.keystore` +
  `keystore/PASSWORD.txt` — **本番公開用の署名鍵**(上記の使い捨て検証鍵とは別物、
  今回新規に生成)
- 本番鍵で署名済みのリリース APK

**この zip は git にコミットしていません。** 本番の署名鍵とそのパスワードは
Google Play 上でこのアプリを将来アップデートし続けるための唯一の鍵であり、
リポジトリ(将来 public になる可能性やコラボレーターがいる可能性)に平文で
置くべきものではないためです。受け取ったら、パスワードマネージャー等の
安全な場所に鍵一式を移し、それ以外の場所からは削除することを推奨します。

## プロジェクト構成

```
csharp-android/
├── README.md
├── dist/                                  # ビルド済み APK (使い捨て検証鍵で署名)
│   ├── BlockDestroyLite-1.0-release.apk
│   └── BlockDestroyLite-1.0-debug.apk
├── tools/
│   └── GameEncryptor/                     # ビルド時に game.html を AES 暗号化するツール
└── BlockDestroyLite/
    ├── BlockDestroyLite.csproj
    ├── MainActivity.cs                    # WebView ラッパー + game.enc の復号処理
    ├── AndroidManifest.xml
    ├── GameSrc/game.html                  # ゲーム本体ソース(外部リソース除去済み、平文)
    ├── Assets/game.enc                    # ビルド時生成物(暗号化済み・.gitignore対象)
    ├── keystore/blockdestroylite-release.keystore  # 検証用の使い捨て鍵
    └── Resources/                         # DEMOリボン付きアイコン / 文言
```
