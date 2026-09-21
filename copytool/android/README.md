# 文字コピーツール — Android アプリ (APK)

Web版 HTML（`../index.html`）を WebView に読み込む **本物のネイティブ APK** です
（PWA ではありません。`.apk` として配布・インストールできます）。

外部通信・広告なし、AdMobやAPIキーも不要で完全オフラインで動作します。

## 使い方

1. アプリを起動
2. ファイルを選択（またはテキストを貼り付け）
3. 「全文をコピー」で最大25MBのテキストを選択操作なしにコピー

## ソースからビルド

前提: JDK 17+、Android SDK（build-tools 35 / platform android-35）。

```bash
cd android
echo "sdk.dir=/path/to/Android/sdk" > local.properties   # または ANDROID_HOME を設定

# デバッグ APK
./gradlew :app:assembleDebug
#   → app/build/outputs/apk/debug/app-debug.apk

# 署名済みリリース APK
./gradlew :app:assembleRelease
#   → app/build/outputs/apk/release/app-release.apk
```

### 署名鍵

`copytool-release.keystore` は個人配布・検証用の**使い捨て自己署名鍵**です。

| 項目 | 値 |
|---|---|
| keystore | `copytool-release.keystore` |
| storePassword / keyPassword | `copytool2026` |
| keyAlias | `copytool` |

> Google Play 公開時は各自の鍵に差し替えてください。

## HTML を更新したら

`../index.html` を編集後、`app/src/main/assets/index.html` にコピーして再ビルドします。

```bash
cp ../index.html app/src/main/assets/index.html
./gradlew :app:assembleRelease
```

## 構成

- パッケージ名: `site.ragdollp.copytool`
- versionName `1.0` / versionCode `1`
- minSdk 26 (Android 8.0) / targetSdk 35 / compileSdk 35
- 追加依存なし（フレームワークの WebView のみ）
