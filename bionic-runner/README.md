# Bionic Runner

Android (bionic libc) 向けにビルドされた ELF 実行ファイルを root 権限で実行し、
APK もインストールできるアプリ。

ビルド済み APK: [`dist/BionicRunner-1.0.0.apk`](dist/BionicRunner-1.0.0.apk)

## できること

- **実行ファイルの実行** — ファイルを選ぶとヘッダを読み、bionic 動的リンク / 静的リンク /
  glibc 等の他 Linux 用 / Windows の .exe / スクリプトを判定して表示。
  登録するとアプリ内の `bin` に置かれ、以後はコマンド名だけで呼び出せる。
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
- glibc / musl 向けの普通の Linux バイナリ (動的リンク)。ローダが Android に無いため。
  Android NDK でビルドし直すか、静的リンク版を使う。
- 端末と CPU が違うバイナリ (例: arm64 端末で x86_64 用)。
- `vi` や `top` のような全画面プログラム (疑似端末を使っていないため)。

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
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

外部ライブラリには依存していない。署名は同梱の `bionicrunner.keystore`
(自前配布用の固定鍵・パスワードは `app/build.gradle` に記載) で行うので、
再ビルドしても上書き更新できる。Play ストア等で公開する場合は非公開の鍵に差し替えること。
