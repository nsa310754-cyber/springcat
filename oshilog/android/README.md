# OshiLog — Android アプリ (Kotlin / Jetpack Compose)

推し活まとめアプリ **OshiLog** の Android 版です。**Kotlin + Jetpack Compose によるネイティブアプリ**で、WebView は使っていません。データは端末内に保存され、オフラインで動作します。

## 成果物 (ビルド済み APK)

リポジトリ直下の `dist/` に配置しています:

| ファイル | 用途 |
|---|---|
| `dist/OshiLog-1.0-release.apk` | 署名済みリリース版 (配布・実機インストール用) |
| `dist/OshiLog-1.0-debug.apk`   | デバッグ版 (検証用) |

- パッケージ名: `site.ragdollp.oshilog`
- versionName `1.0` / versionCode `1`
- minSdk 24 (Android 7.0) / targetSdk 34 / compileSdk 35
- Kotlin 1.9.24 / Jetpack Compose (Compose BOM 2024.09.00, Material3)

## 機能

- **推し登録**: 名前・アイコン・推しカラー・誕生日・所属グループ・メモ
- **ホーム**: 次の予定までのカウントダウン、今月／今年の推し活費、今月のイベント数、
  チケット状況、推しの誕生日カウントダウン
- **カレンダー**: 月表示・予定/チケット/誕生日マーカー・月送り
- **チケット管理**: 公演・開催日・当落発表日・会場・座席・金額・状態（応募済み/当選/落選/入金済み）
- **推し活費**: 8カテゴリで記録、月別・年別・推し別に自動集計、前月比、日別グラフ
- **保存**: すべて端末内 (SharedPreferences に JSON) に保存。項目タップで編集・削除
- **テーマカラー**: 推しカラーに合わせて変更可能
- **広告**: AdMob インタースティシャル（全画面）。画面の区切りで、前回から5分経過時のみ表示

## 構成 (主要ファイル)

```
oshilog/android/
├── settings.gradle / build.gradle / gradle.properties
├── gradlew / gradle/wrapper/                 # Gradle 8.9 wrapper
├── oshilog-release.keystore                  # リリース署名鍵 (使い捨て・下記)
└── app/
    ├── build.gradle                          # Kotlin + Compose + AdMob 設定
    └── src/main/
        ├── AndroidManifest.xml               # INTERNET権限 / AdMob App ID
        ├── java/site/ragdollp/oshilog/
        │   ├── Models.kt                     # データモデル・定数・日付/整形ヘルパ
        │   ├── Store.kt                       # 端末内保存 + 集計 + サンプルデータ
        │   └── MainActivity.kt               # Compose UI 一式 + インタースティシャル
        └── res/                              # アイコン / テーマ / 文言
```

## ソースからビルドする

前提: JDK 17+、Android SDK (build-tools 35.0.0 / platform android-35)。

```bash
cd oshilog/android
echo "sdk.dir=/path/to/Android/sdk" > local.properties   # または ANDROID_HOME を設定

# デバッグ APK
./gradlew :app:assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
# 署名済みリリース APK
./gradlew :app:assembleRelease      # → app/build/outputs/apk/release/app-release.apk
```

## 広告 (AdMob)

- アプリID: `ca-app-pub-8357981710510236~6711242957`（Manifest の `APPLICATION_ID`）
- インタースティシャル: `ca-app-pub-8357981710510236/9323149768`（`MainActivity.kt`）
- 表示制御: 画面の区切り（保存・タブ切替）で `InterstitialManager.maybeShow()` を呼び、
  前回表示から 5 分未満は出さない。起動直後も出さない。

> 開発中はご自身の実広告をタップしないでください（AdMob アカウント停止の恐れ）。
> テスト時は AdMob の「テストデバイス」登録を推奨します。

### 署名鍵

`oshilog-release.keystore` は個人配布 / 動作確認用の使い捨て自己署名鍵です
（storePassword / keyAlias / keyPassword いずれも `oshilog`）。Google Play 公開時は
各自のアップロード鍵に差し替えてください。
