# SwitchRec — USB キャプチャー画面録画アプリ (APK)

Nintendo Switch などの **HDMI 映像を USB キャプチャーカード経由で Android に取り込み、
プレビュー表示しながら MP4 録画する**アプリです。ネイティブライブラリを使わず、
Android 標準の **Camera2 API(外部カメラ / UVC)** だけで実装しています。

## 仕組み(なぜ Camera2 で録れるのか)

Switch 本体の USB-C は**映像出力に対応していません**。そのため「Switch を USB で録画」は
次の構成で実現します:

```
Nintendo Switch → ドック → HDMI → USB キャプチャーカード(UVC) → Android(USB-OTG)
```

一般的な HDMI→USB キャプチャーカードは **UVC(USB Video Class)デバイス**として動作し、
多くの Android 端末はこれを **外部カメラ(`CameraCharacteristics.LENS_FACING_EXTERNAL`)**
として認識します。本アプリはその外部カメラを Camera2 で開き、`TextureView` にプレビューし、
`MediaRecorder`(H.264/AAC, MP4)で録画します。

> 特別なドライバや root、Switch の改造(CFW)は不要です。
> 必要なのは「USB-OTG 対応の Android 端末」と「UVC 対応の HDMI キャプチャーカード」だけ。

## 使い方

1. Switch(ドック)の HDMI をキャプチャーカードの HDMI IN に挿す
2. キャプチャーカードの USB を Android 端末に挿す(USB-OTG)
3. 本アプリを起動 → カメラ/マイクの権限を許可
   - 「USB デバイスへのアクセスを許可しますか?」が出たら **許可**
4. 映像が表示されたら **録画開始** をタップ、止めるときは **停止**
5. 録画ファイルは端末の **`Movies/SwitchRec/`** に `SwitchRec_日時.mp4` として保存

- 「音声: ON/OFF」ボタンで録音の有無を切り替えられます(録画中は変更不可)。
- 画面は横向き固定・スリープ抑止です。

## 成果物(ビルド済み APK)

リポジトリ直下の `dist/` に配置しています:

| ファイル | 用途 |
|---|---|
| `dist/SwitchRec-1.0-release.apk` | 署名済みリリース版(配布/インストール用)|
| `dist/SwitchRec-1.0-debug.apk`   | デバッグ版(検証用)|

- パッケージ名: `site.ragdollp.switchrec`
- versionName `1.0.0` / versionCode `1`
- minSdk 24 (Android 7.0) / targetSdk 34 / compileSdk 35

### インストール(実機)

1. `dist/SwitchRec-1.0-release.apk` を端末へ転送
2. 「提供元不明のアプリ」/「この提供元を許可」を有効化
3. APK をタップしてインストール

`adb` を使う場合:

```bash
adb install -r dist/SwitchRec-1.0-release.apk
```

## ソースからビルドする

前提: JDK 17+、Android SDK(build-tools 35.0.0 / platform android-35)。

```bash
cd switch-recorder
echo "sdk.dir=/path/to/Android/sdk" > local.properties   # または環境変数 ANDROID_HOME を設定

# デバッグ APK
./gradlew :app:assembleDebug
#   → app/build/outputs/apk/debug/app-debug.apk

# 署名済みリリース APK
./gradlew :app:assembleRelease
#   → app/build/outputs/apk/release/app-release.apk
```

### 署名鍵について

リリース署名は、同リポジトリの `android/blockdestory-release.keystore`(個人配布/検証用の
使い捨て自己署名鍵)を流用しています。Google Play へ公開する場合は各自の鍵に差し替えてください
(`app/build.gradle` の `signingConfigs.release`)。

## 対応端末についての注意

- 端末が **外部(USB)カメラに対応している必要**があります。Camera2 の
  `FEATURE_CAMERA_EXTERNAL` に対応する端末で動作します。近年の多くの端末は対応していますが、
  一部の端末・メーカー ROM は UVC を認識しません(その場合は映像が出ません)。
- キャプチャーカードは **UVC 準拠**のものを使ってください(市販の HDMI→USB 変換ドングルの多くが該当)。
- キャプチャーカードの**音声**は UVC(映像)とは別の UAC(USB オーディオ)として来るため、
  端末によっては録音に含まれないことがあります。その場合は本体マイクの音が入るか、無音になります
  (「音声: OFF」でトラブルを避けられます)。

## 別方式(参考)

改造済み Switch(CFW)で **sysDVR** を使う場合は、USB バルク転送でストリームを受け取る別実装が
必要になります。本アプリはより一般的な「キャプチャーカード方式」を採用しています。

## プロジェクト構成

```
switch-recorder/
├── settings.gradle / build.gradle / gradle.properties
├── gradlew / gradle/wrapper/            # Gradle 8.9 wrapper
└── app/
    ├── build.gradle
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/site/ragdollp/switchrec/MainActivity.java
        └── res/                         # レイアウト / テーマ / アイコン / USB フィルタ
```
