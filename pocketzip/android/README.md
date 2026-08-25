# PocketZip — 端末内ファイル圧縮バックアップ (Android アプリ)

スマホ内のファイルをまとめて ZIP に圧縮して保存する Android アプリです。
保存前に「圧縮しないファイル・フォルダ」をチェックボックスで個別に除外できます。

## 主な機能

- **すべてのファイルへのアクセス** (`MANAGE_EXTERNAL_STORAGE`) を使い、内部ストレージ
  (および認識できる SD カード) 全体をフォルダツリーとして横断表示します。
- フォルダはタップで展開/折りたたみ (子要素は展開時に読み込むので起動が速い)。
- 各ファイル・フォルダにチェックボックスがあり、外すと「保存しないファイル」として
  除外されます。フォルダを除外するとその中身もまとめて除外されます。
- 除外設定はアプリ内に保存され、次回起動時も引き継がれます。
- 「圧縮して保存」を押すとフォアグラウンドサービスが起動し、通知で進行状況
  (処理済みファイル数・現在処理中のファイル名) を表示しながら 1 つの ZIP に
  ストリーミング圧縮します (メモリに全ファイルを載せないので、容量が大きくても
  OOM になりません)。進行中はキャンセルも可能です。
- 出力先: `内部ストレージ/Download/PocketZip/backup_YYYYMMDD_HHMMSS.zip`
- 完了後は共有シート経由で ZIP を他アプリ (クラウドストレージなど) に送れます。
- 読み取れないファイル(権限で弾かれるものなど)はスキップして続行し、
  最後にスキップ件数を表示します。

## 対応 OS

Android 11 (API 30) 以降。`MANAGE_EXTERNAL_STORAGE`(すべてのファイルへのアクセス)権限が
API 30 で追加されたものであるため、これより前の OS では対応していません。

## 権限について

初回起動時に「すべてのファイルへのアクセス」の設定画面へ案内します。ここで
PocketZip を許可しないと、ファイル一覧を表示できません(この権限が無いと
スコープドストレージの制限で自分のフォルダ以外を横断的に読めないため)。

広告・ネットワーク通信は一切行いません(`INTERNET` 権限なし)。

## ソースからビルドする

前提: JDK 17+、Android SDK (build-tools 35.0.0 / platform android-35)。

```bash
cd pocketzip/android
echo "sdk.dir=/path/to/Android/sdk" > local.properties   # または ANDROID_HOME を設定

# デバッグ APK
./gradlew :app:assembleDebug
#   → app/build/outputs/apk/debug/app-debug.apk

# 署名済みリリース APK
./gradlew :app:assembleRelease
#   → app/build/outputs/apk/release/app-release.apk
```

### 署名鍵について

`pocketzip-release.keystore` は個人配布用の**使い捨て自己署名鍵**です。

| 項目 | 値 |
|---|---|
| keystore | `pocketzip-release.keystore` |
| storePassword | `pocketzip` |
| keyAlias | `pocketzip` |
| keyPassword | `pocketzip` |

Google Play へ公開する場合は、各自で新しいアップロード鍵を作成し
`app/build.gradle` の `signingConfigs.release` を差し替えてください。

## インストール方法(実機)

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

または APK を端末へ転送し、「提供元不明のアプリ」を許可してタップでインストールします。

## プロジェクト構成

```
pocketzip/android/
├── settings.gradle / build.gradle / gradle.properties
├── gradlew / gradle/wrapper/            # Gradle 8.9 wrapper
├── pocketzip-release.keystore           # リリース署名鍵(上記)
└── app/
    ├── build.gradle
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/site/ragdollp/pocketzip/
        │   ├── MainActivity.kt          # Compose UI (権限画面/ツリー画面/進捗画面)
        │   ├── ZipService.kt            # フォアグラウンドサービス本体 (圧縮処理)
        │   ├── ZipProgressBus.kt        # サービス⇔UI 間の進行状況共有
        │   ├── StorageRoots.kt          # 内部ストレージ/SDカードの列挙
        │   ├── ExclusionStore.kt        # 除外パスの永続化
        │   └── Models.kt                # ZipState など
        └── res/                         # アイコン / テーマ / 文言
```

## 既知の制限

- 他アプリの `Android/data/<package>` 配下は OS の制限により読み取れない場合があります
  (読み取れなかったファイルとしてスキップされ、完了画面に件数が表示されます)。
- ルート化していない端末では、OS が保護している一部システム領域には元々アクセスできません。
