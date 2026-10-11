# BigClip 1MB — 大容量コピー＆ペースト (root 対応)

テキストを **最大 1MB (UTF-8)** までコピーして、他のアプリに貼り付けられる Android アプリです。

## 成果物

| ファイル | 用途 |
|---|---|
| `dist/BigClip-1.0-release.apk` | 署名済みリリース版 |
| `dist/BigClip-1.0-debug.apk`   | デバッグ版 |

- パッケージ名 `site.ragdollp.bigclip` / minSdk 24 (Android 7.0) / targetSdk 34
- 署名鍵 `bigclip-release.keystore` (store/key パスワード・alias すべて `bigclip`)

## 仕組み

Android のクリップボードは本文を Binder(プロセス間通信)でそのまま送ります。Binder のバッファは
1 プロセスあたり約 1MB で、文字列は UTF-16(1 文字 2 バイト)で送られるため、数十万文字で
`TransactionTooLargeException` になります。この上限は libbinder / カーネル側に埋め込まれており、
**root でも設定値の変更では上げられません**。

そこで BigClip は:

1. 10 万文字以下 → 通常どおり本文をクリップボードへ(互換性が最も高い)
2. 10 万文字を超える場合 → 本文をアプリ内ファイルに保存し、クリップボードには
   `content://site.ragdollp.bigclip.clip/...` の URI だけを載せる(大容量モード)
3. 貼り付け先アプリは標準の貼り付け処理(`ClipData.Item#coerceToText`)でこの URI を
   ストリームとして読み、全文を貼り付ける。読み取り権限は ClipboardService が自動で付与する。

## root 機能

- **root 確認**: `su` が使えるか確認します(Magisk / KernelSU で許可)。
- **root 貼り付け通知**: 常駐通知の「貼り付け」を押すと、通知シェードを閉じて前面アプリの入力欄へ
  `input keyevent 279`(KEYCODE_PASTE)を root で送ります。長押しメニューが出ない入力欄にも貼り付けられます。

## 使い方

1. テキストを入力するか、「ファイルを開く」で読み込む(または他アプリの「共有」から BigClip を選ぶ。受け取ると自動でコピーします)
2. 「1MB コピー」
3. 貼り付けたいアプリで普通に貼り付け(またはroot 貼り付け通知を使う)

「1MB テスト文生成」で約 1MB のテキストを作って動作確認できます。

## 制限

- 独自の貼り付け処理を持つアプリ(一部のキーボードのクリップボード履歴、ゲーム、WebView の一部など)は
  URI を読まないため、大容量モードの内容を貼り付けられないことがあります。
- 入力欄に文字数制限(maxLength)があるアプリでは、その上限までしか入りません。

## ビルド

```bash
cd bigclip/android
echo "sdk.dir=/path/to/android-sdk" > local.properties   # platform android-35 / build-tools 35
./gradlew assembleRelease assembleDebug
```
