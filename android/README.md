# APKリフレッシュ(Android アプリ)

古い APK を端末上で選ぶと、**最新の Android にインストールできる形へ自動変換する** Android アプリです。
AI・サーバー通信は一切なし。すべて端末内で完結します。

## 何をするのか

1. 選んだ APK の `AndroidManifest.xml`(バイナリ AXML)の `targetSdkVersion` を **24 以上に引き上げ**
   （`targetSdkVersion` が無いアプリは代わりに `minSdkVersion` を引き上げ）
   → Android 14 (`<23` 拒否) / Android 15 (`<24` 拒否) のインストール拒否を回避
2. 古い `META-INF` 署名を除去し、全エントリを再パッケージ
3. Google 製の **apksig ライブラリで v1+v2+v3 署名**し直し(端末内で実行、root 不要)
4. 標準のインストーラ画面を起動 → ユーザーがタップしてインストール

## 使い方

1. アプリを起動し「古いAPKを選ぶ」をタップ、変換したい APK を選択
2. 自動で変換され、ログに結果(SDK バージョンの変化など)が表示される
3. 「インストール」をタップ
   - 初回は「提供元不明のアプリ」のインストール許可を求められるので許可してください

## ビルド方法

Android SDK(API 34 / build-tools 34)と JDK 17 以上が必要です。

```bash
cd android
# local.properties に sdk.dir=/path/to/android-sdk を書く(または ANDROID_HOME を設定)
gradle :app:assembleDebug
# 出力: app/build/outputs/apk/debug/app-debug.apk
```

テスト(変換〜apksig 署名検証までを JVM 上で実行):

```bash
gradle :app:testDebugUnitTest
```

## コード構成

| ファイル | 役割 |
| --- | --- |
| `AxmlPatcher.kt` | バイナリ Manifest の SDK バージョンをサイズ不変でその場書き換え |
| `ApkFixer.kt` | ZIP 再パッケージ・旧署名除去・apksig で v1/v2/v3 署名 |
| `MainActivity.kt` | ファイル選択・変換・インストール起動の UI |
| `assets/fixapk.p12` | 署名に使う自己署名鍵(アプリ同梱) |

## 制限事項

- **署名鍵が変わります。** 元開発者の署名とは別物になるため、既存アプリの上書き
  アップデートはできません(いったんアンインストールが必要)。Play ストア配布も不可。
- 全エントリを再圧縮するため、`android:extractNativeLibs="false"` のアプリ(圧縮済み
  ネイティブライブラリを前提とするもの)は非対応です。古いアプリの大半は該当しません。
- `<uses-sdk>` に SDK バージョンが一切書かれていない特殊な APK は、その場書き換えが
  できないためエラーになります。
- 自分が権利を持つ、または改変・再インストールが許可された APK にのみ使用してください。
