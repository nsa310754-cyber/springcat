# OshiLog — Android アプリ (APK)

推し活まとめアプリ **OshiLog** の Android 版です。アプリ UI (`assets/app.html`) を
WebView に読み込んで動作し、**完全オフラインで利用可能**です。

> これは「とりあえず動く」プレビュー版 APK です。ホーム / カレンダー / 記録 /
> マイページのタブ切り替えと「＋追加」シートが実際に動きます。表示データは
> サンプル (デモ) で、まだサーバー連携・永続保存の本実装は入っていません。

## 成果物 (ビルド済み APK)

リポジトリ直下の `dist/` に配置しています:

| ファイル | 用途 |
|---|---|
| `dist/OshiLog-1.0-release.apk` | 署名済みリリース版 (配布・実機インストール用) |
| `dist/OshiLog-1.0-debug.apk`   | デバッグ版 (検証用) |

- パッケージ名: `site.ragdollp.oshilog`
- versionName `1.0` / versionCode `1`
- minSdk 24 (Android 7.0) / targetSdk 34 / compileSdk 35

## なぜ WebView 方式か

アプリ UI は HTML/CSS/JS (`oshilog/app.html`) で作られています。これを WebView に
そのまま載せることで、Web 版と 100% 同じ見た目・動作を Android アプリとして
配布できます。UI は `assets/app.html` に同梱され、
`https://appassets.androidplatform.net/assets/app.html` として読み込むためネット
ワーク不要です (`WebViewAssetLoader` を使用。`file://` ではなく正規オリジンで配信
するため、将来の `localStorage` 保存も安定して動きます)。

## インストール方法 (実機)

1. `dist/OshiLog-1.0-release.apk` を端末へ転送
2. 「提供元不明のアプリ」/「この提供元を許可」を有効化
3. APK をタップしてインストール

`adb` を使う場合:

```bash
adb install -r dist/OshiLog-1.0-release.apk
```

## ソースからビルドする

前提: JDK 17+、Android SDK (build-tools 35.0.0 / platform android-35)。

```bash
cd oshilog/android
echo "sdk.dir=/path/to/Android/sdk" > local.properties   # または環境変数 ANDROID_HOME を設定

# デバッグ APK
./gradlew :app:assembleDebug
#   → app/build/outputs/apk/debug/app-debug.apk

# 署名済みリリース APK
./gradlew :app:assembleRelease
#   → app/build/outputs/apk/release/app-release.apk
```

### 署名鍵について

`oshilog-release.keystore` は個人配布 / 動作確認用の**使い捨て自己署名鍵**です。

| 項目 | 値 |
|---|---|
| keystore | `oshilog-release.keystore` |
| storePassword | `oshilog` |
| keyAlias | `oshilog` |
| keyPassword | `oshilog` |

> Google Play へ公開する場合は、各自で新しいアップロード鍵を作成し
> `app/build.gradle` の `signingConfigs.release` を差し替えてください。この鍵を
> 本番の秘密鍵として使わないこと。

## アプリ UI を更新したら

`oshilog/app.html` を編集したら、`app/src/main/assets/app.html` に反映して再ビルド
してください (アプリ側 Java コードの変更は不要):

```bash
cp oshilog/app.html oshilog/android/app/src/main/assets/app.html
cd oshilog/android && ./gradlew :app:assembleRelease
```

## プロジェクト構成

```
oshilog/
├── index.html                 # ランディングページ (LP)
├── app.html                   # アプリ本体 UI (WebView に載る画面)
└── android/
    ├── settings.gradle / build.gradle / gradle.properties
    ├── gradlew / gradle/wrapper/            # Gradle 8.9 wrapper
    ├── oshilog-release.keystore             # リリース署名鍵 (上記)
    └── app/
        ├── build.gradle
        ├── proguard-rules.pro
        └── src/main/
            ├── AndroidManifest.xml
            ├── assets/app.html              # アプリ UI (オフライン同梱)
            ├── java/site/ragdollp/oshilog/MainActivity.java
            └── res/                         # アイコン / テーマ / 文言
```

## 今後の実装候補

- 推し・予定・チケット・支出データの端末内保存 (localStorage → 後に同期)
- チケット情報の取り込み: 国内主要サービス (ぴあ / ローソン / e+ 等) は公開 API が
  無いため、メール / カレンダー連携や手入力＋テンプレ補完が現実的
- 次の予定までのカウントダウン通知 (ローカル通知)
