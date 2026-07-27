# SpringCat Installer (Fire タブレット向け)

APKM / APKS / XAPK（中身が複数 APK の「分割 APK バンドル」）を渡すと、
中の APK をまとめて自動でインストールする Android アプリです。
Google Play が無い Fire タブレット（Fire OS 5〜8）でのサイドロードを想定しています。

## できること

- **APKM / APKS / XAPK / ZIP / APK** を展開して、中の `base.apk` + `split_*.apk` を
  `PackageInstaller` の **1 セッションでまとめてインストール**（分割 APK は 1 個ずつ入れると失敗するため）
- **複数ファイルをまとめて渡せる**（キューに積んで 1 つずつ順番にインストール）
- **バンドルに複数アプリが入っていても** パッケージ名ごとにグループ分けして別々にインストール
- **不要な split を自動で除外**（端末の ABI / 画面密度 / 言語に合うものだけ選択。オフにして全部入れることも可能）
- **受け取ったら即インストール**（ファイルマネージャの「開く」や「共有」から起動 → 追加操作なしで開始）
- **フォルダ監視**：指定フォルダに新しいバンドルが置かれたら自動でインストール
  （PC から転送 / ダウンロードした瞬間に走る。転送途中のファイルはサイズが安定するまで待つ）
- 進行状況はアプリ内ログと通知に出る

## 使い方

1. `app-release.apk` を Fire タブレットに転送してインストール
   （設定 → セキュリティとプライバシー → **不明ソースからのアプリ** を許可）
2. アプリを開き、**「提供元不明のアプリ」を許可する** ボタンからこのアプリにインストール権限を与える
3. あとはどちらでも:
   - APKM をファイルマネージャで開く / このアプリに「共有」する → 自動でインストール開始
   - アプリ内の **「APKM / APKS / XAPK を選ぶ」** で複数選択
   - **フォルダ監視** を ON にして監視フォルダ（例: `Download`）を選ぶ → そこに置くだけで自動インストール

### 確認ダイアログについて

Android のセキュリティ上、システム権限を持たないアプリはインストール時の
**確認ダイアログを完全には省略できません**（アプリ 1 つにつき 1 回タップが必要）。
このアプリは Android 12 以降で省略が許される場合（自分が入れたアプリの更新など）に
`setRequireUserAction(USER_ACTION_NOT_REQUIRED)` を使って自動化します。
バックグラウンド中に確認が必要になった場合は、通知をタップすれば承認できます。

完全に無確認で入れたい場合は、PC から ADB 経由で入れるしかありません:

```
adb install-multiple base.apk split_config.arm64_v8a.apk split_config.xxhdpi.apk
```

## 対応していないもの

- **暗号化された APKM**（APKMirror の新しい形式）。展開できない場合はその旨をログに出します。
  APKMirror からは非暗号化の APKM、または APKS / XAPK を使ってください。
- **XAPK 内の OBB ファイル**。Android 11 以降は他アプリの `Android/obb/` へ書き込めないためスキップします
  （OBB が必要なゲームは手動で配置してください）。

## ビルド

```bash
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

- `minSdk 22`（Fire OS 5 = Android 5.1 以降）/ `targetSdk 33`
- release も **debug 鍵で署名** しているので、そのままサイドロードできます
  （配布用に本番鍵を使う場合は `app/build.gradle.kts` の `signingConfig` を差し替えてください）
- GitHub Actions（`.github/workflows/android.yml`）に push すると APK が artifact として出ます

## 構成

| ファイル | 役割 |
| --- | --- |
| `MainActivity.kt` | UI、ファイル/フォルダ選択、Intent(VIEW/SEND) の受け取り |
| `InstallService.kt` | インストールキュー（フォアグラウンドサービス） |
| `WatchService.kt` | フォルダ監視（SAF ツリーをポーリング） |
| `BundleExtractor.kt` | APKM/APKS/XAPK/ZIP から .apk を取り出す |
| `ApkGrouper.kt` | 取り出した APK をパッケージ単位にまとめる |
| `SplitPicker.kt` | 端末に必要な split だけ選ぶ（ABI / DPI / 言語） |
| `ApkInstaller.kt` | `PackageInstaller` セッションで base+split を一括インストール |
| `InstallResultReceiver.kt` | インストール結果・確認要求の受け取り |
