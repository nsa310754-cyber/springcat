# APK Builder

Android アプリ(Kotlin)から、HTML/JS で作った Web ゲームをそのまま **署名済み `.apk`** に変換するツールです。
ゲーム本体・アイコン画像・必要な権限を選ぶだけで、端末上で `apk` + `keystore` + 署名パスワードを生成します。

## できること

- HTML/JS/CSS のゲーム(1ファイル or フォルダ一式)を WebView ラッパーアプリとして APK 化
- **PWAモード**: 既存サイトの URL を貼るだけで、そのドメイン(サブドメイン含む)の `manifest.json` を
  自動検出・取得し、name/icons/start_url/display などを見て「インストール可能な構成か」を判定。
  問題なければアプリ名・アイコンを自動入力し、生成される APK はその URL をラップして起動します
- アイコン画像を選択 → 全解像度(mdpi〜xxxhdpi)に自動リサイズして反映(PWAモードでは manifest 側の
  アイコンを自動取得、手動選択があればそちらを優先)
- アプリ名・パッケージID(applicationId)・バージョン名/コードを指定
- 権限(インターネット、カメラ、マイク、通知、位置情報、ストレージ等)をチェックボックスで選択 → 実際にマニフェストへ反映
- 新しい自己署名の署名鍵(RSA-2048)をその場で生成し、APK Signature Scheme v1/v2/v3 で署名
- PWAモードでは、生成した署名鍵の指紋(SHA-256)から `assetlinks.json` も自動生成
- **その他のサービス**: `google-services.json` を追加すると Firebase Analytics を、
  AdMob の App ID / バナー広告ユニットID を入力すると AdMob バナー広告を組み込めます
  (どちらか一方でも指定すると、それらを同梱した `template-services.apk` でビルドされ、
  指定しなければ軽量な `template.apk` のまま — 使わない権限やSDKを無駄に持たせません)
- **ゲーム本体の難読化**: チェックを入れると `assets/game.html` を AES-256-CBC で暗号化して
  `assets/game.enc` として同梱し、`unzip` で中身を直接読めなくします(この repo の
  `../android` アプリが Block Destroy で使っている手法と同じ)
- `<アプリ名>.apk` / `<アプリ名>.keystore` / `keystore-info.txt`(alias・パスワード・証明書指紋)
  / (PWAモード時)`assetlinks.json` をまとめた zip として保存

`aapt2`/`d8`/Gradle のような Android ビルドツールを **端末上に一切必要とせず**、あらかじめコンパイル済みの
テンプレート APK を土台に、ZIP 操作とバイナリ `AndroidManifest.xml` の直接編集だけで再構成しています。

## 仕組み・構成

```
apkbuilder/
├── template/           ゲーム非依存の汎用 WebView ラッパー(Kotlin、Firebase/AdMob無し・軽量)。
│                       ビルド結果は :app に assets/template.apk として同梱される。
├── template-services/  同上だが Firebase Analytics + AdMob (Play Services Ads) を同梱した版。
│                       assets/template-services.apk として同梱。どちらも「土台」として
│                       ZIP操作だけで再構成される点は同じ(コードは意図的にほぼ同一)。
├── core/       ピュア Kotlin/JVM の変換エンジン。Android 端末にもビルド用PCにも同じコードで動く:
│                 - axml/AxmlDocument.kt   バイナリ AndroidManifest.xml の読み書き(uses-permission/meta-data追加など)
│                 - zip/RawZipReader.kt, zip/ZipWriter.kt   ZIP の再構成 + zipalign 相当の 4byte アライン
│                 - ApkAssembler.kt        テンプレートAPK + ユーザー入力 → 未署名APK
│                 - KeystoreGenerator.kt   自己署名鍵の生成 (Bouncy Castle)
│                 - ApkSigner.kt           v1/v2/v3 署名 (Google 製 apksig ライブラリ)
│                 - AssetLinksGenerator.kt assetlinks.json の生成
│                 - GameObfuscator.kt      game.html の AES-256-CBC 暗号化 (assets/game.enc)
│                 - FirebaseConfigParser.kt google-services.json → 実行時用の firebase-config.json への変換
│                 - json/MiniJson.kt       依存ライブラリ無しの最小 JSON パーサ(org.json は Android専用のため)
│                 - pwa/PwaManifestFetcher.kt   ページURL→manifest.json の自動検出・取得(gzip対応)
│                 - pwa/PwaManifestParser.kt    manifest.json のパース・相対URL解決
│                 - pwa/PwaManifestValidator.kt インストール可能かどうかの簡易判定
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

WebView ラッパー自体の挙動(JS ブリッジ、フルスクリーン処理など)を変えたいときは `template/`
(軽量版)と `template-services/`(Firebase/AdMob同梱版)の両方を編集し、再ビルドしたものを
`app/src/main/assets/` に上書きしてください(2つのテンプレートは意図的にほぼ同じコードなので、
片方を直したらもう片方にも同じ修正を反映してください)。

```bash
cd apkbuilder/template
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew :app:assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk ../app/src/main/assets/template.apk

cd ../template-services
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew :app:assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk ../app/src/main/assets/template-services.apk
```

## PWAモードの仕組み

1. 入力された URL のオリジン(ドメイン・サブドメイン)に対して `https://<オリジン>/manifest.json`
   (見つからなければ `/manifest.webmanifest`)を直接検索します。ページHTMLを取得して
   `<link rel="manifest">` を探すような遠回りはしません。取得自体は直接接続ではなく
   [r.jina.ai](https://r.jina.ai/) のリーダープロキシ経由(`https://r.jina.ai/<対象URL>`)で行っています。
   サイト側がAndroidクライアントからの直接アクセスを遅延・ブロックすることがあり、
   直接接続だとタイムアウトが効かず延々と待ち続けることがあったための対策です
   (アイコン画像はテキスト用のプロキシでは扱えないため直接ダウンロードします)。
2. `name`/`short_name`・`icons`(192px以上推奨)・`start_url`・`display` を確認し、
   Chrome の「インストール可能」基準に近い簡易判定を行って結果を画面に表示
3. `assets/game.html` を「`start_url` へ即リダイレクトするだけの HTML」に差し替え、WebView が
   実際のサイトをオンラインで表示するアプリとして APK 化(オフライン同梱はしません)
4. 署名鍵の証明書指紋(SHA-256)を使って `assetlinks.json` を生成

`assetlinks.json` は **APK 自身がサイトへアップロードすることはできません**。生成された zip に
含まれる `assetlinks.json` を、サイト側の `https://<ドメイン>/.well-known/assetlinks.json` に
自分で設置してください(同梱の `assetlinks-README.txt` にも同じ説明があります)。

## その他のサービス(Firebase / AdMob)の仕組み

`com.google.gms.google-services` Gradle プラグインは使っていません(端末上でビルドするツールに
ビルド時プラグインは持ち込めないため)。代わりに:

- **Firebase**: `google-services.json` を渡すと、`FirebaseConfigParser` がそこから
  `mobilesdk_app_id`/`api_key`/`project_id` などを抜き出した小さな `firebase-config.json` を作り、
  APK の assets に同梱します。テンプレート側は起動時にこれを読み、
  `FirebaseOptions.Builder(...)` で **実行時に** `FirebaseApp.initializeApp()` します
  (現状は Analytics のみ有効化。Crashlytics/Messaging は将来の拡張候補)。
  `google-services.json` に複数アプリが登録されている場合、指定したパッケージIDと一致する
  クライアントがあればそれを、無ければ先頭のクライアントの値を使います(完全に対応させたい場合は
  Firebase コンソールで実際のパッケージIDのアプリを登録してください)。
- **AdMob**: App ID はマニフェストの `<meta-data android:name="com.google.android.gms.ads.APPLICATION_ID">`
  として(`AxmlDocument` の汎用の要素追加機能で)直接書き込みます。バナー広告ユニットIDも指定した
  場合は `assets/admob-config.json` に書き出し、テンプレートが起動時にこれを読んで画面下部に
  バナー広告(`AdSize.BANNER`)を表示します。
- どちらか一方でも指定すると、Firebase/AdMob (Play Services Ads) を同梱した `template-services.apk`
  を土台に使います。指定しなければ軽量な `template.apk` のままなので、使わないアプリが
  `AD_ID` 権限や余分な容量(数MB)を無駄に持つことはありません。

## ゲーム本体の難読化について

同梱の `../android` アプリ(Block Destroy)がすでに使っている手法をそのまま踏襲しています:
`assets/game.html` を AES-256-CBC(先頭16byteがIV)で暗号化して `assets/game.enc` として同梱し、
アプリ起動時に WebView の `shouldInterceptRequest` フックでメモリ上に復号してから配信します
(`WebViewAssetLoader` 経由、`file://` ではなく仮想の `https://appassets.androidplatform.net/`
オリジンで読み込みます)。鍵は APK 自身の中にあるため **`unzip` での直読みを防ぐだけの対策**であり、
本当の意味で解読不能というわけではありません。対象は入口の `game.html` のみで、そこから参照される
JS/CSS/画像などは平文のまま同梱されます(フォルダをまるごと選んだ場合、複数ファイルの個別暗号化には
未対応です)。PWAモードでは `game.html` の中身がその場で作る短いリダイレクトHTMLだけなので、
難読化オプションは対象外になります。

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
- Firebase は Analytics のみ対応(Crashlytics・Cloud Messaging・Remote Config 等は未対応)。
- ゲーム本体の難読化は入口の `game.html` のみが対象で、参照される個別の JS/CSS/画像ファイルの
  暗号化には対応していません。
