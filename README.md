# SpringCat 画面録画 (Screen Recorder)

Fire タブレットのように標準の画面録画機能が無い Android 端末向けの、シンプルな画面録画アプリです。
Android の `MediaProjection` API を使って画面を MP4 で録画します。

- **開始**: アプリの「録画を開始」ボタンから
- **停止**: 通知の「録画を停止」ボタン、またはアプリの画面から
- **保存先**: `Movies/SpringCat/`（ギャラリー／写真アプリから見られます）

対応: Android 5.1 (API 22) 〜 Android 14 (API 34)。FireOS 5 以降で動作します。

## APK のインストール（Fire タブレット）

ビルド済みの署名付き APK がリポジトリ直下にあります: **`SpringCat-ScreenRecorder-v1.1.apk`**

1. Fire タブレットの「設定 → セキュリティとプライバシー → 不明ソースからのアプリ」を ON にします
   （または、インストール時に表示される許可ダイアログを許可）。
2. この APK をタブレットに転送（メール・クラウド・USB など）してタップし、インストールします。
3. アプリを開き「録画を開始」→ 画面キャプチャの確認ダイアログで「今すぐ開始」を選びます。
4. 停止は通知シェードの「録画を停止」、またはアプリ画面のボタンから。

> 注: 初回起動時に通知の権限（Android 13+）を求められます。通知から停止するために許可を推奨します。

### 他のアプリに切り替えると録画が止まってしまう場合

Fire OS は省電力のため、バックグラウンドのアプリ（録画を続けているサービス）を止めてしまうことがあります。
本アプリは録画中に WakeLock を保持し、初回の録画開始時に「電池の最適化を無効化」の許可を求めることで、これを防ぎます。

もしそれでも止まる場合は、手動で以下を確認してください:

1. 「設定 → アプリと通知 → SpringCat 画面録画 → 電池」で、電池の最適化（省電力）を **オフ / 無制限** にする。
2. 録画中はアプリを「最近使ったアプリ」からスワイプで消さない（消すとサービスも終了します）。

## 使い方のポイント

- 録画中は上部の通知バーに録画中の通知が常駐します。
- 録画は端末の解像度・向きのまま、30fps・自動ビットレートで保存されます。
- マイク音声・内部音声は録音しません（映像のみ）。

## ソースからビルドする

必要環境: JDK 17+、Android SDK (Platform 34 / Build-Tools 34)、Gradle 8.9+。

```bash
# SDK の場所を指定
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# 署名付きリリース APK をビルド
gradle :app:assembleRelease
# 出力: app/build/outputs/apk/release/app-release.apk

# デバッグ APK
gradle :app:assembleDebug
```

### 署名について

同梱の `app/springcat-release.keystore` で署名しています（個人のサイドロード用の自己署名鍵）。

- keystore パスワード / 鍵パスワード: `springcat`
- エイリアス: `springcat`

公開配布する場合は、ご自身の鍵に差し替えてください（`app/build.gradle` の `signingConfigs`）。

## 主な構成

- `MainActivity.kt` — 開始/停止の UI、権限リクエスト、画面キャプチャの許可取得
- `ScreenRecordService.kt` — フォアグラウンドサービス。`MediaProjection` + `MediaRecorder` で録画し、通知から停止できます
