# APK Builder Web — ブラウザだけで署名付きAPKを作る

Android アプリ版と同じ「テンプレート差し替え」方式を **ブラウザの JavaScript に移植**したものです。
サーバー無し・インストール無しで、**iPhone / iPad の Safari でも** HTML から署名付き `.apk` を生成できます。
生成物は実機にインストール可能で、Google の `apksigner` で **APK Signature Scheme v2** の検証を通っています。

## なぜiPhoneでも動くのか

APK 生成は「ZIPの組み替え + バイナリマニフェスト(AXML)の書き換え + 鍵生成 + 署名」で、
すべてブラウザ標準APIだけで完結します:

- ZIP 読み書き・4byteアライン … 自前実装(`engine.mjs`)
- deflate/inflate … `CompressionStream` / `DecompressionStream`
- バイナリ `AndroidManifest.xml` 編集 … 自前実装(`axml.mjs`、Kotlin版の移植)
- 鍵生成・自己署名X.509証明書・APK v2署名 … `crypto.subtle`(WebCrypto)+ 自前のDER/署名ブロック(`sign.mjs`)

`aapt2` も `gradle` も `bundletool` も**サーバーも**使いません。100% クライアントサイドです。

## 動作要件
- Safari 16.4+ (iOS/iPadOS 16.4+)、Chrome/Edge 現行、Firefox 現行
  (`CompressionStream` と WebCrypto RSA が必要)

## ファイル構成

```
web/
├── index.html      ← ビルド済み・単一ファイル(これをホストするだけ)。template.apk を base64 で内蔵。
├── shell.html      UI の HTML/CSS 雛形(__BUNDLE__ にJSを差し込む)
├── ui.js           画面ロジック(フォーム・アイコンリサイズ・ダウンロード)
├── engine.mjs      ZIP 読み書き / deflate / crc32
├── axml.mjs        バイナリ AndroidManifest.xml エディタ
├── sign.mjs        鍵生成 + X.509 + APK Signature Scheme v2
├── build.mjs       テンプレート+入力 → 署名付きAPK
├── build-html.mjs  上記モジュールを index.html に束ねるビルドスクリプト(Node)
└── (template は ../app/src/main/assets/template.apk を参照)
```

## ホスト方法(iPhoneの人に使わせる)

`index.html` を静的ホスティングに置くだけです。例:

- **GitHub Pages**: リポジトリの `apkbuilder/web/` を Pages で公開 → `https://<user>.github.io/<repo>/apkbuilder/web/`
- Netlify / Cloudflare Pages / 任意のWebサーバーに `index.html` を置く

iPhone の人はその URL を Safari で開くだけ。**アプリのインストールは不要**です。
生成した `.apk` は「共有 → ファイルに保存」や AirDrop で Android 端末へ渡してインストールします。

> ⚠️ `file://` で直接開くと一部ブラウザで制限があります。動作確認は http(s) でのホストを推奨します。

## ソースを編集したら

```bash
cd apkbuilder/web
node build-html.mjs      # engine/axml/sign/build/ui + template.apk → index.html を再生成
```

## Web簡易版の範囲

- 対応: HTML(コード入力/ファイル/複数ファイル)→ アイコン差し替え・権限選択 → **署名付きAPK**
- Web版に無い(= Android アプリ版のフル機能): AAB / Google Play 提出用zip / PWAモード /
  既存keystoreでのアップデート署名 / Firebase・AdMob / APK Analyzer / 難読化
- 署名鍵は毎回新規生成されます(WebCrypto は鍵をエクスポートできる形で作成しますが、
  現状 UI では鍵ファイルの書き出しは未対応 — アップデートを重ねる用途は Android版へ)。
