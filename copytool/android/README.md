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

## spring貼り付け

アプリ内の「spring貼り付け」ボタンは `ClipHelper#readClipboardText` で
クリップボードを読む。springコピーで載せた URI クリップならファイルから
直接ストリーム読みするため25MBまで対応（`navigator.clipboard.writeText`
と違い OS のクリップボード転送上限を受けない）。それ以外の普通のテキスト
クリップは `ClipData.Item#coerceToText` にフォールバックする。

## すべてのアプリで有効にする（アクセシビリティサービス）

`ACTION_PROCESS_TEXT` は選択メニューにその項目を出すかどうかがアプリ側の
実装に依存するため、対応していないアプリでは出てこない。より広く動かすため
`SpringCopyAccessibilityService`（アクセシビリティサービス）も同梱している。

これは OS レベルでテキスト選択の変化 (`TYPE_VIEW_TEXT_SELECTION_CHANGED`) や
入力欄へのフォーカス (`TYPE_VIEW_FOCUSED`) を検知し、その場に「springコピー」
「springペースト」ボタンをオーバーレイ表示する仕組み
（`TYPE_ACCESSIBILITY_OVERLAY`。`SYSTEM_ALERT_WINDOW` 権限は不要）。
Google翻訳の「タップして翻訳」などと同じ仕組み。

springペースト側は `AccessibilityNodeInfo#performAction(ACTION_SET_TEXT, …)`
で他アプリの入力欄に文字を書き戻すが、これもBinderのトランザクション
バッファ上限（実質1MB前後）の影響を受けるため、非常に大きいテキストは
他アプリへの書き戻しに失敗することがある（上限を超えた場合はToastで
案内し、アプリ内の「spring貼り付け」を使うよう促す）。

Android の仕様上、アクセシビリティサービスの ON/OFF はアプリから直接
操作できず、ユーザーが設定画面で手動で切り替える必要がある。アプリ内の
「すべてのアプリで springコピー / springペースト を有効にする」チェック
ボックスは、タップすると `Settings.ACTION_ACCESSIBILITY_SETTINGS` を開き、
現在の ON/OFF 状態を（`MainActivity#onResume` で再取得して）表示する。

なお、ストア経由でなく直接インストール（サイドロード）したアプリが
ユーザー補助権限を要求すると、Android 13以降は「アプリはアクセスを
拒否されました」と表示して自動的にブロックする（OS標準のセキュリティ
機能で回避不可）。設定 → アプリ → 文字コピーツール → 「⋮」メニュー →
「制限付きの設定を許可」で解除してから、もう一度ONにする必要がある。

## 使い方

1. アプリを起動
2. ファイルを選択（またはテキストを貼り付け）
3. 「全文をコピー」/「spring貼り付け」で最大25MBのテキストをコピー・貼り付け
   — または他のアプリで文字を選択→メニューから「springコピー」
   — もしくはアプリ内でチェックを入れて全アプリ対応を有効化

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
- versionName `1.5` / versionCode `6`
- minSdk 26 (Android 8.0) / targetSdk 35 / compileSdk 35
- 依存: `androidx.core:core`（`FileProvider` のため）
