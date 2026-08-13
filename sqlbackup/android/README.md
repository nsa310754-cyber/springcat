# SQL Backup — Android アプリ (Kotlin / Jetpack Compose)

SQL Server の **BACKUP DATABASE / RESTORE DATABASE** をスマホから実行できるアプリです。**Kotlin +
Jetpack Compose によるネイティブアプリ**で、WebView は使っていません。SQL Server へは
[jTDS](http://jtds.sourceforge.net/)(純Java実装の JDBC ドライバ)で直接 TCP 接続します。

## 成果物 (ビルド済み APK)

リポジトリ直下の `dist/` に配置しています:

| ファイル | 用途 |
|---|---|
| `dist/SqlBackup-1.0-release.apk` | 署名済みリリース版 (配布・実機インストール用) |
| `dist/SqlBackup-1.0-debug.apk`   | デバッグ版 (検証用) |

- パッケージ名: `site.ragdollp.sqlbackup`
- versionName `1.0` / versionCode `1`
- minSdk 24 (Android 7.0) / targetSdk 34 / compileSdk 35
- Kotlin 1.9.24 / Jetpack Compose (Compose BOM 2024.09.00, Material3)
- JDBC ドライバ: `net.sourceforge.jtds:jtds:1.3.1`(SQL Server 認証・Windows/NTLM 認証の両方に対応)

## 重要: バックアップ/リストアのパスについて

`BACKUP DATABASE` / `RESTORE DATABASE` はいずれも **SQL Server が動作しているマシン上のファイル
パス** を指定する T-SQL コマンドです。アプリ(スマホ)側のストレージにはアクセスしません。
例えば `C:\Backup\mydb.bak` はサーバーの `C:\Backup\` を指し、スマホの内部ストレージではありません。

## 機能

- **接続管理**: ホスト・ポート・初期データベース・ユーザー名/パスワード(SQL Server 認証)または
  Windows(NTLM)認証を保存し、タップ一つで再接続
- **データベース一覧**: `sys.databases` から名前・状態・復旧モデル・サイズを取得して一覧表示
- **バックアップ**: `BACKUP DATABASE ... TO DISK` を実行。圧縮 (COMPRESSION)・差分
  (DIFFERENTIAL)・コピーのみ (COPY_ONLY) をオプションで指定可能
- **リストア**: `RESTORE DATABASE ... FROM DISK` を実行。`RESTORE HEADERONLY` /
  `RESTORE FILELISTONLY` でバックアップの中身を事前確認し、`MOVE` によるファイル配置先の変更、
  `REPLACE` による上書きにも対応
- **進捗表示**: 実行中は別コネクションで `sys.dm_exec_requests.percent_complete` をポーリングし、
  進捗バーに反映(権限不足時は不明表示のまま続行)
- **履歴**: 実行した接続確認・バックアップ・リストアの結果を端末内に保存し、あとから確認可能
- **保存**: 接続情報・履歴はすべて端末内 (SharedPreferences に JSON) に保存。外部送信はしない

## 構成 (主要ファイル)

```
sqlbackup/android/
├── settings.gradle / build.gradle / gradle.properties
├── gradlew / gradle/wrapper/                 # Gradle 8.9 wrapper
├── sqlbackup-release.keystore                # リリース署名鍵 (使い捨て・下記)
└── app/
    ├── build.gradle                          # Kotlin + Compose + jTDS 設定
    └── src/main/
        ├── AndroidManifest.xml               # INTERNET権限
        ├── java/site/ragdollp/sqlbackup/
        │   ├── Models.kt                      # データモデル
        │   ├── Store.kt                       # 端末内保存 (接続情報・履歴)
        │   ├── SqlClient.kt                   # jTDS 経由の BACKUP/RESTORE 実行ロジック
        │   ├── AppViewModel.kt                # 画面状態管理
        │   └── MainActivity.kt                # Compose UI 一式
        └── res/                               # アイコン / テーマ / 文言
```

## ソースからビルドする

前提: JDK 17+、Android SDK (build-tools 35.0.0 / platform android-35)。

```bash
cd sqlbackup/android
echo "sdk.dir=/path/to/Android/sdk" > local.properties   # または ANDROID_HOME を設定

# デバッグ APK
./gradlew :app:assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
# 署名済みリリース APK
./gradlew :app:assembleRelease      # → app/build/outputs/apk/release/app-release.apk
```

### 署名鍵

`sqlbackup-release.keystore` は個人配布 / 動作確認用の使い捨て自己署名鍵です
(storePassword / keyAlias / keyPassword いずれも `sqlbackup`)。Google Play 公開時は
各自のアップロード鍵に差し替えてください。

## 動作確認について

このアプリは任意の SQL Server インスタンスに接続する汎用ツールです。自分が管理権限を持つ
SQL Server(ローカル環境や検証用インスタンスなど)に対してのみ使用してください。
