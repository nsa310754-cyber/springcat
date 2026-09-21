# 文字コピーツール — Android アプリ (APK)

Web版 HTML（`../index.html`）を WebView に読み込む **本物のネイティブ APK** です
（PWA ではありません。`.apk` として配布・インストールできます）。

外部通信・広告なし、AdMobやAPIキーも不要で完全オフラインで動作します。

## springコピー（このアプリの主機能）

Android には `ACTION_PROCESS_TEXT` という仕組みがあり、対応するとどのアプリの
テキスト選択メニュー（ブラウザやメモ帳などで文字を長押し選択したときに出る
「コピー / 切り取り / 共有」のメニュー）にも項目を追加できる。このアプリを
インストールすると、そこに **「springコピー」** が並ぶようになる。

タップすると、選択したテキストをファイル経由（`FileProvider` の `content://`
URI）でクリップボードに載せる。Android の標準コピー（`ClipboardManager` に
文字列を直接渡す方式）は Binder のトランザクションバッファ上限（実質1MB前後、
プロセス内で共有）を超えると `TransactionTooLargeException` で失敗するが、
URI 経由なら貼り付け時にファイルをストリーム読み込みするだけなので、この
上限を回避できる（`ClipHelper.java` 参照）。

アプリ内の「全文をコピー」ボタンも同じ仕組みを使うため、25MBまでのテキストを
確実にコピーできる（ブラウザ単体版は `navigator.clipboard.writeText()` に
フォールバックするため、Android実機では上記の1MB前後の上限に縛られる）。

## 使い方

1. アプリを起動
2. ファイルを選択（またはテキストを貼り付け）
3. 「全文をコピー」で最大25MBのテキストをコピー
   — または他のアプリで文字を選択→メニューから「springコピー」

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
- versionName `1.1` / versionCode `2`
- minSdk 26 (Android 8.0) / targetSdk 35 / compileSdk 35
- 依存: `androidx.core:core`（`FileProvider` のため）
