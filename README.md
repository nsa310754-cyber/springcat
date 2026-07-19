# ブロック壊しゲーム (Block Breaker) — Android アプリ

HTML/JS で書かれた「ブロック壊しゲーム」を、**ネイティブの Android アプリ (APK)** に
パッケージ化したものです。**PWA は使用していません** — WebView を内蔵した通常の
Android アプリとしてビルドしています。

同じ 5×5 グリッドの色消しゲームがそのまま端末アプリとして動作します。

## できあがった APK

すぐインストールできる APK は `dist/` にあります。

| ファイル | 用途 |
| --- | --- |
| `dist/blockbreaker-release.apk` | 配布用。署名済み。ふつうはこちらを使用 |
| `dist/blockbreaker-debug.apk` | 開発用（デバッグ署名） |

### インストール方法（実機）

1. APK を Android 端末へ転送する
2. 「提供元不明のアプリ（不明なアプリのインストール）」を許可する
3. APK をタップしてインストール

`adb` が使える場合:

```bash
adb install -r dist/blockbreaker-release.apk
```

## 構成

- **WebView 方式のネイティブアプリ**。ゲーム本体 (`app/src/main/assets/index.html`)
  を `file:///android_asset/` から読み込みます。オフラインで完全に動作し、
  ネットワーク権限もサーバーも不要です。
- ゲーム終了時の `alert()` は、`WebChromeClient` でネイティブダイアログとして表示します。
- 端末の戻るボタンに対応。

| 項目 | 値 |
| --- | --- |
| applicationId | `com.adgj.blockbreaker` |
| minSdk | 26 (Android 8.0) |
| targetSdk / compileSdk | 34 (Android 14) |
| versionName | 1.0 |

## ソースからビルドする

必要環境: JDK 17+、Android SDK (platform 34 / build-tools 34.0.0)。

```bash
# デバッグ APK
./gradlew assembleDebug
#   -> app/build/outputs/apk/debug/app-debug.apk

# リリース APK（署名するには release.keystore が必要 / 下記参照）
./gradlew assembleRelease
#   -> app/build/outputs/apk/release/app-release.apk
```

### 署名鍵について

リリースビルドの署名鍵 `release.keystore` は、秘密情報のため
**リポジトリには含めていません**（`.gitignore` で除外）。
自分でビルドする場合は鍵を作成してください:

```bash
keytool -genkeypair -v -keystore release.keystore -alias blockbreaker \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass <pass> -keypass <pass> \
  -dname "CN=BlockBreaker, O=adgj, C=JP"
```

パスワード・エイリアスは環境変数 `KS_PASS` / `KS_ALIAS` / `KS_KEY_PASS` で
上書きできます（未設定時は既定値を使用）。`release.keystore` が無い場合、
リリースビルドは未署名になります。
