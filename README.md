# SpringCat ISO

`.iso`(ISO 9660 ディスクイメージ)の中身をブラウズできる Android アプリです。
**インストール可能な APK としてビルドでき、ホーム画面から起動して開けます。**

- 端末内の `.iso` ファイルを選んで、中に含まれるファイル/フォルダを一覧
- フォルダをタップして中へ移動、戻るボタンで階層を上がる
- Joliet 拡張があれば正しいファイル名(日本語・長い名前)を表示
- ファイルマネージャーから `.iso` を「開く」対象としても起動可能

依存ライブラリなしの自前 ISO 9660 パーサ(`IsoReader.kt`)を使用しています。

## 必要環境

| ツール | バージョン |
|--------|-----------|
| JDK | 17 以上(開発は 21 で確認) |
| Android SDK | Platform 34 / Build-Tools 34.0.0 |
| Gradle | 同梱の Wrapper(8.9)。または 8.9+ |
| minSdk / targetSdk | 24 / 34 |

## ビルド方法

```bash
# Android SDK の場所を指定(local.properties は git 管理外)
echo "sdk.dir=/path/to/Android/sdk" > local.properties

# デバッグ APK をビルド(インストール可能な署名付き)
./gradlew assembleDebug
# 出力: app/build/outputs/apk/debug/app-debug.apk
```

Android Studio で開く場合は、このディレクトリをそのまま開けば Wrapper が
自動でセットアップされます。

## インストールと起動

```bash
# 実機 / エミュレータへインストール
adb install app/build/outputs/apk/debug/app-debug.apk
```

インストール後、アプリ一覧に **「SpringCat ISO」** が表示されます。
起動して「ISOイメージを開く」から `.iso` を選ぶか、
ファイルマネージャーで `.iso` を開くとこのアプリで中身を閲覧できます。

## テスト

ISO パーサは Android 実機なしで JVM 上のユニットテストで検証できます
(Joliet 名・ディレクトリ優先ソート・サイズ・サブディレクトリ移動)。

```bash
./gradlew testDebugUnitTest
```

## 構成

```
app/src/main/java/com/springcat/iso/
  MainActivity.kt       画面・ファイル選択(SAF)・ナビゲーション
  IsoReader.kt          ISO 9660 / Joliet パーサ(依存なし)
  IsoEntry.kt           エントリのモデル(名前・種別・サイズ)
  IsoEntryAdapter.kt    一覧表示の RecyclerView アダプタ
app/src/test/           IsoReader のユニットテスト + テスト用 .iso
```
