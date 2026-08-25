# 配達員 営業リスト作成ツール — Android アプリ (APK)

単体版 HTML（`../standalone/index.html`）を WebView に読み込む **本物のネイティブ APK** です
（PWA ではありません。`.apk` として配布・インストールできます）。

## なぜ APK 版が必要か（HTML 単体との違い）

ブラウザから X API を直接呼ぶと **CORS でブロック**されます。APK 版は
`MainActivity` に **ネイティブ HTTP ブリッジ**（`window.NativeHTTP.request`）を実装しており、
X API / Gemini API への通信をネイティブ側（`HttpURLConnection`）で行うため
**CORS を回避して X 検索が実行できます**。これが APK 版の主な利点です。

| | HTML 単体（ブラウザ） | APK 版 |
|---|---|---|
| AI 判定・DM 生成（Gemini） | ✅ 動作 | ✅ 動作 |
| X API 検索 | ⚠️ CORS 制約（失敗時サンプル/インポート） | ✅ ネイティブ通信で動作 |
| オフライン起動 | — | ✅ HTML 同梱 |

## 使い方

1. アプリを起動 → 「API設定」で **Gemini APIキー**（と任意で X の APIキー/シークレット）を入力し保存
2. 「検索・抽出を実行」→ 収集 → AI 判定 → 一覧化
3. 各候補で「DM生成」→ コピーして手動送信、「送信済/返信あり」を記録

キーは端末内（WebView の localStorage）にのみ保存され、外部サーバーには送信されません。

## ソースからビルド

前提: JDK 17+、Android SDK（build-tools 34 / platform android-34）。

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

`deliverysales-release.keystore` は個人配布・検証用の**使い捨て自己署名鍵**です。

| 項目 | 値 |
|---|---|
| keystore | `deliverysales-release.keystore` |
| storePassword / keyPassword | `deliverysales` |
| keyAlias | `deliverysales` |

> Google Play 公開時は各自の鍵に差し替えてください。

## HTML を更新したら

`../standalone/index.html` を編集後、`app/src/main/assets/index.html` にコピーして再ビルドします。

```bash
cp ../standalone/index.html app/src/main/assets/index.html
./gradlew :app:assembleRelease
```

## 構成

- パッケージ名: `site.ragdollp.deliverysales`
- versionName `1.0` / versionCode `1`
- minSdk 26 (Android 8.0) / targetSdk 34 / compileSdk 34
- 追加依存なし（フレームワークの WebView のみ）
