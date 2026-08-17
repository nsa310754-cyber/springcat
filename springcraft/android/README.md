# SpringCat for Minecraft — Android アプリ (APK)

Google Play にある「Minecraft アドオンインストーラー」系アプリと同じ方式で、
自作の Minecraft 統合版アドオン（`../addon/`）をアプリからワンタップで
Minecraft に受け渡す Android アプリです。

## 仕組み（アプリ→Minecraft への受け渡し）

1. アプリの `assets/SpringCat.mcaddon` をキャッシュ領域にコピー
2. `FileProvider` で `content://` URI を発行（`file://` を直接渡さない）
3. `Intent(ACTION_VIEW)` に MIME タイプ `application/mcaddon` を付け、
   `com.mojang.minecraftpe` 宛てに送る（`FLAG_GRANT_READ_URI_PERMISSION` 付き）
4. Minecraft 側のインポート確認 UI が表示され、取り込みが完了する
5. 「Minecraft を起動」ボタンは `PackageManager#getLaunchIntentForPackage` で
   そのまま Minecraft を開く

Minecraft が端末に無い場合は Play ストアの Minecraft ページへ誘導します
（`market://details?id=com.mojang.minecraftpe` → 失敗時は `https://play.google.com/...`）。
Android 11+ のパッケージ可視性制限に対応するため `AndroidManifest.xml` に
`<queries>` で `com.mojang.minecraftpe` を宣言しています。

## 成果物 (ビルド済み APK)

リポジトリ直下の `dist/` に配置しています:

| ファイル | 用途 |
|---|---|
| `dist/SpringCraft-1.2.0-release.apk` | 署名済みリリース版(提出・配布用)|
| `dist/SpringCraft-1.2.0-debug.apk`   | デバッグ版(検証用)|
| `dist/SpringCat-Addon-v1.2.0.zip`    | アドオン単体のストア/配布サイト提出用 zip(APKではなく `.mcaddon` + アイコン + 説明文)|

- パッケージ名: `site.ragdollp.springcraft`
- versionName `1.2.0` / versionCode `2`
- minSdk 24 (Android 7.0) / targetSdk 34 (Android 14) / compileSdk 35

## インストール方法(実機)

1. `dist/SpringCraft-1.2.0-release.apk` を端末へ転送
2. 「提供元不明のアプリ」/「この提供元を許可」を有効化
3. APK をタップしてインストール
4. Minecraft（統合版）がインストール済みの端末で開き、「Minecraft にアドオンを追加」をタップ

```bash
adb install -r dist/SpringCraft-1.2.0-release.apk
```

## ソースからビルドする

前提: JDK 17+、Android SDK(build-tools 35.0.0 / platform android-34, android-35)。

```bash
cd android
echo "sdk.dir=/path/to/Android/sdk" > local.properties   # または環境変数 ANDROID_HOME を設定

# アドオンを更新した場合は先に再生成して assets へコピー
python3 ../addon/build_addon.py
cp ../addon/out/SpringCat.mcaddon app/src/main/assets/SpringCat.mcaddon

# デバッグ APK
./gradlew :app:assembleDebug
#   → app/build/outputs/apk/debug/app-debug.apk

# 署名済みリリース APK
./gradlew :app:assembleRelease
#   → app/build/outputs/apk/release/app-release.apk
```

### 署名鍵について

`android/springcraft-release.keystore` は個人配布・提出用の**使い捨て自己署名鍵**です
（`android/` の Block Destroy / OshiLog と同じ運用方針）。

| 項目 | 値 |
|---|---|
| keystore | `springcraft-release.keystore` |
| storePassword | `springcraft` |
| keyAlias | `springcraft` |
| keyPassword | `springcraft` |

> Google Play へ公開する場合は、各自で新しいアップロード鍵を作成し `app/build.gradle` の
> `signingConfigs.release` を差し替えてください。この鍵を本番の秘密鍵として使わないこと。

## 動作確認について

このアプリ自体（ボタンの表示・FileProvider・Intent の発行、APK の署名/インストール可否）は
このリポジトリのビルド環境で確認済みです。ただし **Minecraft 実機での受け渡し〜インポート〜
アドオン動作の一連の流れは、この環境に Minecraft が無いため未検証**です。実機でお試しの上、
もし Minecraft 側がこの MIME タイプ / Intent 形式を想定通り受け取らない場合は、
`MainActivity.java` の `installAddon()` 内の MIME タイプ（`application/mcaddon`）や
パッケージ指定を調整してください。

## プロジェクト構成

```
android/
├── settings.gradle / build.gradle / gradle.properties
├── gradlew / gradle/wrapper/            # Gradle 8.9 wrapper
├── springcraft-release.keystore         # リリース署名鍵(上記)
└── app/
    ├── build.gradle
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml          # FileProvider, <queries> 宣言
        ├── assets/SpringCat.mcaddon     # 同梱アドオン本体
        ├── java/site/ragdollp/springcraft/MainActivity.java
        └── res/                        # レイアウト / アイコン / 文言 (ライト/ダーク対応)
```
