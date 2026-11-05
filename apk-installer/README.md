# APK Installer (Windows 専用)

Windows の PC から、USB 接続した Android 端末へ APK を一括インストールする単体 exe ツールです。
内部では Google 公式の **adb (Android SDK Platform-Tools)** を使います。

## 使い方

1. Android 端末の「開発者向けオプション」→「USB デバッグ」を ON にする
2. USB ケーブルで PC に接続し、端末に表示される「USB デバッグを許可しますか？」で許可する
3. `APKInstaller.exe` を起動
4. 「ADB を準備」(初回のみ。adb が無い場合) → 「更新」で端末を表示
5. 「追加...」で APK を選ぶ (複数可)
6. オプションを選んで「インストール開始」

コマンドライン引数で APK パスを渡して起動すると、その APK が最初から一覧に入ります
(例: `APKInstaller.exe C:\Downloads\app.apk`)。エクスプローラーで APK を右クリック →「プログラムから開く」で
APKInstaller.exe を選べば、ダブルクリックで開くことも可能です。

| オプション | adb オプション | 内容 |
|---|---|---|
| 既存アプリを上書き | `-r` | 同じアプリがあれば更新インストール (通常はオン) |
| 古いバージョンへの変更を許可 | `-d` | 端末より古い版を入れる場合に必要 (データが消える場合あり) |
| 権限を自動付与 | `-g` | 実行時権限をすべて許可した状態で入れる |

## 対応・制限

- Windows 10 / 11 (64bit) 向け。Windows 専用です
- 対象は **.apk** のみ。XAPK / APKS / AAB は直接インストールできません
- 端末の adb 検出順: `platform-tools\adb.exe` (exe と同じ場所) → `%LOCALAPPDATA%\APKInstaller\platform-tools` → `ANDROID_HOME` / `ANDROID_SDK_ROOT` → `%LOCALAPPDATA%\Android\Sdk` → PATH
- 初回の adb 自動取得には インターネット接続 が必要です (`https://dl.google.com/` から取得)

## 開発・ビルド

Python 3.12 (Windows) で動きます。

```bat
cd apk-installer
pip install -r requirements.txt
python -m unittest discover -s tests -v
python apk_installer.py
pyinstaller --noconfirm --onefile --windowed --name APKInstaller apk_installer.py
```

ビルド済み exe は GitHub Actions (`.github/workflows/build-apk-installer.yml`) で作られ、
Actions の Artifacts `APKInstaller-windows` からダウンロードできます。

### 注意
- exe は署名していないため、初回起動時に Windows SmartScreen の警告が出ることがあります
  (「詳細情報」→「実行」)。
- ロジック部分 (adb 出力の解析・コマンド生成・APK 判定) は `tests/` で検証しています。
  GUI は Windows 実機での確認が必要です。
