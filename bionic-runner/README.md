# Bionic Runner

Android (bionic libc) 向けにビルドされた ELF 実行ファイルを root 権限で実行し、
APK もインストールできるアプリ。

ビルド済み APK: [`dist/BionicRunner-1.2.0.apk`](dist/BionicRunner-1.2.0.apk)

## できること

- **パソコン風のホーム画面** — 端末のホームアプリに設定できるデスクトップ。登録した実行ファイルが
  アイコンとして並び、タップするとターミナルで実行される (長押しで引数付き実行・削除)。
  元から入っている Android アプリは、タスクバーの「スタート」から検索して起動する
  (長押しでアプリ情報)。ホームアプリの切り替えはスタートメニュー下部の「ホームアプリの設定」から。

- **実行ファイルの実行** — ファイルを選ぶとヘッダを読み、bionic 動的リンク / 静的リンク /
  glibc 動的リンク / Windows の .exe / スクリプトを判定して表示。
  登録するとアプリ内の `bin` に置かれ、以後はコマンド名だけで呼び出せる。
- **glibc 用プログラムの実行** — 一般の Linux 向け (glibc 動的リンク) の ELF も、同梱の
  glibc 2.41 + libstdc++ (GCC 14) で起動する。初回に端末の CPU 用の一式を展開する。
- **AI に作らせる** — 作りたいものを書くと、この端末で動く形式で作るための条件
  (CPU、Android のバージョン、使えるファイル形式とビルドコマンド、入出力の制約) を付けた
  指示文をコピーできる。ChatGPT や Claude などに貼り付けて使う。
- **APK のインストール** — root の `pm install` で確認画面なしに導入 (上書き更新可)。
- **root シェル** — 下の入力欄からコマンドを直接実行。実行中のプログラムへの入力にもなる。
  「中断 ^C」で実行中のコマンドに SIGINT を送る。
- ファイルマネージャの「アプリで開く」から APK / 実行ファイルを受け取れる。

## 必要なもの

- Android 7.0 (API 24) 以上
- **root 化済みの端末** (Magisk / KernelSU など `su` が使えること)。
  初回起動時に出る su の許可ダイアログで許可する。
- root が無い場合は「制限モード」で起動する: APK は標準インストーラ経由、
  実行できるのは bionic 動的リンクの実行ファイル (システムのローダ経由) とスクリプトのみ。

## 実行できないもの

- Windows の `.exe` (PE 形式)。Linux カーネルでは動かない。
- musl を動的リンクしたバイナリ (静的リンク版なら動く)。
- 同梱以外の共有ライブラリ (libz, libssl など) が要る glibc バイナリ。必要な `.so` を
  `files/glibc/<ABI>/lib` に root で置けば読み込まれる。
- glibc 2.41 より新しい環境でビルドした glibc バイナリ。
- 端末と CPU が違うバイナリ (例: arm64 端末で x86_64 用)。
- `vi` や `top` のような全画面プログラム (疑似端末を使っていないため)。

## glibc について

- 同梱しているのは libc, libm, libpthread, libdl, librt, libresolv, libgcc_s, libstdc++ など。
  対応 CPU は arm64-v8a / armeabi-v7a / x86_64 / x86。
- **root が必要**。root なしの制限モードでは Android の制限でローダを起動できない。
- Android には `/etc/resolv.conf` が無いので、glibc 用プログラムはホスト名を解決できない
  (IP アドレス直指定の通信はできる)。`/etc/passwd` やロケールデータも無い。
- 取得元は Debian 13 (trixie) の公式パッケージ `libc6` `libgcc-s1` `libstdc++6`。
  版は APK 内の `assets/glibc/VERSIONS.txt` に記録している。glibc は LGPL-2.1 以降、
  libgcc / libstdc++ は GPL-3.0 以降 + GCC Runtime Library Exception で、対応するソースは
  Debian のソースパッケージ `glibc` と `gcc-14` (https://sources.debian.org/) から入手できる。

## 権限

| 権限 | 用途 |
| --- | --- |
| スーパーユーザー (su) | 実行ファイルの実行、`pm install` |
| INTERNET / ACCESS_NETWORK_STATE | 制限モードで実行したプログラムの通信 |
| REQUEST_INSTALL_PACKAGES | 制限モードで標準インストーラに APK を渡す |
| MANAGE_EXTERNAL_STORAGE (+ 旧 READ/WRITE) | 制限モードのシェルから `/sdcard` を使う (「権限」ボタンで付与) |

## ビルド

```sh
echo "sdk.dir=/path/to/android-sdk" > local.properties
./fetch-glibc.sh            # glibc 一式を Debian から取得 (初回のみ。curl, xz, dpkg-deb, zip が必要)
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

Java のライブラリには依存していない。署名は同梱の `bionicrunner.keystore`
(自前配布用の固定鍵・パスワードは `app/build.gradle` に記載) で行うので、
再ビルドしても上書き更新できる。Play ストア等で公開する場合は非公開の鍵に差し替えること。
