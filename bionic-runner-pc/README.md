# Bionic Runner PC

Windows の PC で **Linux (glibc)** と **Android (bionic)** の実行ファイルを動かすアプリ。
Android 版 [Bionic Runner](https://github.com/nsa310754-cyber/springcat/tree/6189b5ca92325ec797d0d8cc05693b6b38211cf5/bionic-runner)
と同じ作り (状態表示・ボタン列・出力・入力欄、ELF の判定、登録してコマンド名で実行) の PC 版です。

ビルド済み exe: [`dist/BionicRunnerPC-1.1.0.exe`](dist/BionicRunnerPC-1.1.0.exe)
(約 240 KB・インストール不要。Windows 10 / 11 に最初から入っている .NET Framework 4.x で動きます)

## 使い方

1. `BionicRunnerPC-1.1.0.exe` をダブルクリックする。
   - **デスクトップ (ホーム画面) とスタートメニューに「Bionic Runner PC」のアイコンが追加されます。**
     exe 本体は `%LOCALAPPDATA%\Programs\BionicRunnerPC` にコピーされるので、ダウンロードした exe は消して構いません。
2. 「Linux を使えるようにします。始めますか?」で「はい」。
   - WSL が無効な PC では、管理者の確認画面のあと WSL を有効にします。**PC の再起動が必要**です
     (再起動後、自動で続きから始まります)。
   - Ubuntu 24.04 の rootfs・Termux・qemu などを合計約 200 MB ダウンロードします。
3. 完了すると、デスクトップに **「Linux (glibc)」「Termux (bionic)」** のアイコンも追加されます。

## 管理者で実行すると root (`#`)

Windows の権限と Linux のユーザーを対応させています。

| 起動のしかた | Linux のユーザー | プロンプト |
| --- | --- | --- |
| ふつうにダブルクリック | 一般ユーザー `user` (uid 1000) | `$` |
| 右クリック →「管理者として実行」 | `root` | `#` (入力欄の記号も赤くなる) |

- 本体の画面・「Linux 端末」「Termux 端末」ボタン・デスクトップの「Linux (glibc)」「Termux (bionic)」アイコン、すべて同じ規則です
  (アイコンは右クリック →「管理者として実行」で root の端末になります)。
- 画面の「セットアップ ▾」→「管理者として開き直す」でも root に切り替えられます。
- Termux を root で開くと `~ #` になりますが、Termux 自身の決まりで **root では `pkg` / `apt` が使えません**
  (「Cannot run 'pkg' command as root」)。パッケージを入れるときは普通に起動するか、root の画面から
  `termux pkg install …` と打ってください (`termux` コマンドは自動で一般ユーザーに切り替えて実行します)。
- 管理者でも一般ユーザーで開きたいときは `BionicRunnerPC.exe --user`。
- これは使い分けのための対応づけで、セキュリティの境界ではありません。WSL の仕様上、
  Windows の一般ユーザーでも `wsl -u root` で Linux の root になれます。

## できること

| | 内容 |
| --- | --- |
| **glibc** | 土台は Ubuntu 24.04 (glibc 2.39)。普通の Linux 用プログラムがそのまま動き、`apt install` でソフトを足せる。musl 用 (Alpine など) も動く |
| **bionic** | `/system` に Android 9 の bionic (`linker64` / `libc.so` など) を置いてあるので、**Android 用にビルドされた ELF がそのまま起動する** |
| **Termux** | Termux の環境一式 (bionic で動く bash・apt・pkg)。`termux` コマンドか「Termux 端末」で入る。`termux pkg install python` のように使える |
| **他の CPU** | x86_64 の PC でも **ARM64 / ARM の Android・Linux 用 ELF** を qemu で動かす (ARM 版 Windows では逆に x86_64 を動かす) |
| **実行ファイル** | 「実行ファイル」で選ぶかウィンドウ/アイコンにドラッグすると、ヘッダを読んで bionic / glibc / musl / 静的 / スクリプト / Windows 用 / CPU を判定して表示。登録すると以後はコマンド名だけで呼び出せる (CPU が違うものは自動で qemu 経由) |
| **APK** | APK の `lib/<ABI>/` にあるネイティブの実行ファイルを取り出して登録できる (APK 自体は Android 端末用なのでそのままは動かない) |
| **シェル** | 下の入力欄からコマンドを実行 (一般ユーザー `$`、管理者なら root `#`)。実行中のプログラムへの入力にもなる。「中断 ^C」で実行中のコマンドを止める。↑↓ で履歴 |
| **端末** | 「Linux 端末」「Termux 端末」で本物の端末を開く (`vi` や `top` などの全画面プログラムはこちらで) |
| **ファイル** | 「ファイル」で登録済みの実行ファイルの場所 (`\\wsl.localhost\BionicRunner\opt\bionic-runner\bin`) をエクスプローラーで開く |

Linux 側で使えるコマンド:

- `termux [--root] [コマンド…]` — Termux (bionic) の環境に入る / その中でコマンドを実行する (`--root` は root のまま・`pkg` 不可)
- `brun ファイル [引数…]` — CPU を見て、違えば qemu で実行する

## ISO イメージは使いません

入手するのは次のものだけです (どれも公式の配布元から、ISO ではないアーカイブ):

| 何を | どこから | 形式 |
| --- | --- | --- |
| WSL 本体 | Windows (`wsl --install --no-distribution`) | Windows の機能 |
| Ubuntu 24.04 base (glibc) | cdimage.ubuntu.com/ubuntu-base | `.tar.gz` (約 30 MB・SHA256 を確認) |
| Ubuntu のパッケージ (curl, qemu-user-static, musl など) | Ubuntu の apt | `.deb` |
| Termux bootstrap | github.com/termux/termux-packages の Releases | `.zip` |
| bionic (`aosp-libs` / `aosp-utils`) | packages-cf.termux.dev | `.deb` |

bionic と Termux の組み立て方は Termux 公式の [termux-docker](https://github.com/termux/termux-docker) と同じです。

## 仕組み

```
Bionic Runner PC (Windows / .NET Framework 4.x の WinForms)
  └─ wsl.exe -d BionicRunner …                       … WSL 2 の Linux カーネル
        ├─ Ubuntu 24.04 (glibc)                        /bin, /usr …
        ├─ Android 9 bionic                            /system → /data/data/com.termux/files/usr/opt/aosp
        ├─ Termux                                      /data/data/com.termux/files/usr  (termux コマンド)
        ├─ qemu-user-static + 他の CPU の sysroot     /opt/bionic-runner/sysroot/<cpu>  (brun コマンド)
        └─ 登録した実行ファイル                        /opt/bionic-runner/bin  (PATH に入っている)
```

- WSL のディストリ名は `BionicRunner`、場所は `%LOCALAPPDATA%\BionicRunnerPC\distro`。
- Linux 側の準備は [`src/linux/setup.sh`](src/linux/setup.sh) (exe に埋め込み)。何度実行してもよい。
- 管理者権限を使うのは WSL を有効にするときだけ。それ以外は自分のユーザーの範囲だけを変更する。

## 必要なもの

- Windows 10 (バージョン 2004 以降) / Windows 11、x64 または ARM64
- CPU の仮想化 (VT-x / AMD-V) が有効なこと (WSL 2 に必要。無効なら WSL 1 で作るか選べる)
- インターネット接続 (初回セットアップ時)

## 実行できないもの

- 新しい Android (API 29 以降) にしか無い関数を使う bionic 用プログラム (bionic が Android 9 相当のため)
- Android アプリ (APK) そのもの (ART / Android フレームワークは無い)
- 他の CPU 用の musl・32bit glibc プログラム (bionic 版・静的リンク版なら動く)
- 画面 (GUI) を使う Android 用プログラム

## アンインストール

「セットアップ ▾」→「アンインストール…」。Linux 環境 (`BionicRunner`) とアイコン、
`%LOCALAPPDATA%` のファイルを削除します (WSL 本体は Windows に残ります)。

## ビルド (Linux 上で)

```sh
sudo apt install mono-devel binutils-mingw-w64-x86-64 cpp
./build.sh            # → dist/BionicRunnerPC-1.1.0.exe
```

| ファイル | 役割 |
| --- | --- |
| `src/Program.cs` | 起動・二重起動防止・アイコン追加 |
| `src/MainForm.cs` | 画面 (Android 版 MainActivity に相当) |
| `src/WslShell.cs` | 常駐シェル (Android 版 RootShell に相当) |
| `src/ElfInfo.cs` | ELF の判定 (Android 版 ElfInfo の移植) |
| `src/Setup.cs` | WSL の有効化・rootfs の入手と検証・インポート |
| `src/Installer.cs` | exe の配置・デスクトップとスタートメニューのアイコン・アンインストール |
| `src/Wsl.cs` | wsl.exe の呼び出し |
| `src/linux/setup.sh` | Linux 側の準備 (glibc の道具・bionic・Termux・qemu) |
| `src/res/` | アイコン・マニフェスト・バージョン情報 |

## 確認済みのこと / 未確認のこと

このリポジトリでの開発環境 (Linux) で確認したこと:

- `setup.sh` を Ubuntu 24.04 base の chroot で実行し、glibc・bionic (x86_64)・Termux (bash / apt でのパッケージ追加)・
  qemu 経由の ARM64 bionic・ARM64 glibc がすべて動くこと
- exe を Mono + 仮想 X サーバーで起動し、`wsl.exe` を chroot で代用して、初回セットアップ
  (rootfs のダウンロードと SHA256 確認 → インポート → setup.sh) → シェル → ARM64 bionic の登録と実行 →
  APK からの取り出し → 登録済み一覧・削除 → 中断、が動くこと
- 管理者モード (root・`#`) と一般モード (`user`・`$`) の切り替え、Linux / Termux の端末のプロンプト、
  セットアップ v1 → v2 の更新 (ユーザー名の移行と Termux のファイルの持ち主の修復)

**実際の Windows ではまだ動かしていません。** WSL の有効化 (管理者確認・再起動)、`wsl.exe` との受け渡し、
デスクトップのアイコン作成 (Windows のシェル API)、「管理者として実行」したときの WSL の動きは Windows 実機での確認が必要です。
