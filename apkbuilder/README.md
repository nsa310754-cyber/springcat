# APK Builder

Android アプリ(Kotlin)から、HTML/JS で作った Web ゲームをそのまま **署名済み `.apk`** に変換するツールです。
ゲーム本体・アイコン画像・必要な権限を選ぶだけで、端末上で `apk` + `keystore` + 署名パスワードを生成します。

## できること

- HTML/JS/CSS のゲーム(1ファイル or フォルダ一式)を WebView ラッパーアプリとして APK 化
- アイコン画像を選択 → 全解像度(mdpi〜xxxhdpi)に自動リサイズして反映
- アプリ名・パッケージID(applicationId)・バージョン名/コードを指定
- 権限(インターネット、カメラ、マイク、通知、位置情報、ストレージ等)をチェックボックスで選択 → 実際にマニフェストへ反映
- 新しい自己署名の署名鍵(RSA-2048)をその場で生成し、APK Signature Scheme v1/v2/v3 で署名
- `<アプリ名>.apk` / `<アプリ名>.keystore` / `keystore-info.txt`(alias・パスワード)をまとめた zip として保存

`aapt2`/`d8`/Gradle のような Android ビルドツールを **端末上に一切必要とせず**、あらかじめコンパイル済みの
テンプレート APK を土台に、ZIP 操作とバイナリ `AndroidManifest.xml` の直接編集だけで再構成しています。

## 仕組み・構成

```
apkbuilder/
├── template/   ゲーム非依存の汎用 WebView ラッパー(Kotlin)。ビルドすると app/build/outputs/apk/debug/app-debug.apk になり、
│               それが :app にコピーされて「土台」として同梱される (assets/template.apk)。
├── core/       ピュア Kotlin/JVM の変換エンジン。Android 端末にもビルド用PCにも同じコードで動く:
│                 - axml/AxmlDocument.kt   バイナリ AndroidManifest.xml の読み書き
│                 - zip/RawZipReader.kt, zip/ZipWriter.kt   ZIP の再構成 + zipalign 相当の 4byte アライン
│                 - ApkAssembler.kt        テンプレートAPK + ユーザー入力 → 未署名APK
│                 - KeystoreGenerator.kt   自己署名鍵の生成 (Bouncy Castle)
│                 - ApkSigner.kt           v1/v2/v3 署名 (Google 製 apksig ライブラリ)
└── app/        実際に配布する Android アプリ本体 (Jetpack Compose の UI)。:core を使って端末上で生成処理を行う。
```

### なぜテンプレート方式か

Android 端末上には `aapt2`/`d8` のような公式コンパイラは存在しないため、任意の Kotlin/Java ソースから
その場で `.dex` を生成することは現実的ではありません。そこで:

1. あらかじめ PC 側で `template/` を1回ビルドし、汎用 WebView ラッパー(コンパイル済み `.dex` 入り)を作る
2. 端末上ではその ZIP を分解し、`assets/game.html` などのゲーム本体・アイコン PNG・
   バイナリ `AndroidManifest.xml` 内の `package`/`versionName`/`versionCode`/`label`/`uses-permission` だけを
   書き換えて再 ZIP 化する
3. 新しい自己署名鍵で署名する

という方式を取っています。`resources.arsc` やコンパイル済みコード自体には一切手を加えないため、
`aapt2 dump badging` / `zipalign -c` / `apksigner verify` で正当性を検証できる、実際にインストール可能な
APK が生成されます(このリポジトリの実装もその3つのツールで検証済みです)。

## ビルド方法

`app/src/main/assets/template.apk` にはビルド済みのテンプレートが同梱されているので、通常は `template/` を
自分でビルドし直す必要はありません。

```bash
cd apkbuilder
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew :app:assembleDebug
#   → app/build/outputs/apk/debug/app-debug.apk
```

### テンプレートを変更したい場合

WebView ラッパー自体の挙動(JS ブリッジ、フルスクリーン処理など)を変えたいときは `template/` を編集し、
再ビルドしたものを `app/src/main/assets/template.apk` に上書きしてください。

```bash
cd apkbuilder/template
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew :app:assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk ../app/src/main/assets/template.apk
```

## 生成される APK について

- `minSdk 24`(Android 7.0)/ `targetSdk 34`
- 署名: その場で生成した自己署名 RSA-2048 鍵、APK Signature Scheme v1(minSdk<24相当の互換用)/v2/v3
- ストア公開(Google Play 等)には使えますが、その場合は各自の Play Console アップロード鍵で
  改めて管理することを推奨します。`keystore-info.txt` に書かれたパスワードは他人に渡さないでください。
- 現状 `.aab`(Android App Bundle)出力は未対応です(`.apk` のみ)。AAB はマニフェストが protobuf 形式になり
  変換方式が異なるため、将来の拡張候補としています。

## 制限事項

- ゲーム本体は HTML/JS/CSS のみ(WebView 上で動くもの)。任意の Kotlin/Java ソースからのネイティブコンパイルは
  端末上のツールでは行えないため非対応です。
- 権限はテンプレート側で用意した代表的なもの(INTERNET, CAMERA, RECORD_AUDIO, VIBRATE,
  POST_NOTIFICATIONS, ACCESS_FINE/COARSE_LOCATION, READ/WRITE_EXTERNAL_STORAGE)から選択する形式です。
