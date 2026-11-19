# mkexe — 端末の上で bionic 用・glibc 用の実行ファイルを作る

Bionic Runner のコマンドプロンプトで使える、C のソースから実行ファイルを作るコマンドです
(Bionic Runner 1.25.0 から同梱。64bit の ARM 端末だけ)。PC が無くても、端末だけでプログラムを作れます。

| 形式 | できるもの | 動かし方 |
|---|---|---|
| `bionic` (既定) | Android の libc で動く実行ファイル (PIE、ローダは `/system/bin/linker64`) | `./名前` (root のシェルで) |
| `glibc` | 普通の Linux (glibc) 用の実行ファイル (ローダは `/lib/ld-linux-aarch64.so.1`) | `mkexe run 名前`、またはアプリの「プログラムを追加」で登録 |

## 使い方

```
mkexe new hello                       見本 hello.c を作る
mkexe hello.c                         → ./hello (bionic 用) ができる
./hello                               実行 (root のシェルで)
mkexe -t glibc -o hello-linux hello.c → glibc 用
mkexe run hello-linux 引数1 引数2       glibc 用をアプリ同梱の glibc で実行
mkexe -r -O2 -Wall prog.c util.c      複数のソース。-r でできたらすぐ実行
mkexe -o calc calc.c -DDEBUG=1        -D・-I・-l・-O2・-Wall などはそのままコンパイラへ
mkexe info hello                      形式を表示
mkexe status                          準備の状態
mkexe setup all                       ヘッダとライブラリを先に取ってくる (初回は自動)
mkexe clean                           取ってきたものを消す
mkexe help                            使い方
```

引数なしの `mkexe` は対話形式です。入力欄に 1 行ずつ打ちます:

```
mkexe
  → 作る形式を番号で選んでください: 1 = bionic … 2 = glibc …
1
  → ソースファイル (.c) の場所を入力してください (何も入れずに Enter で見本 hello.c を作って使う)
/sdcard/Download/game.c
  → 出力する名前を入力してください (何も入れずに Enter で game)
(Enter)
  → 追加のオプションがあれば入力してください (例: -O2 -Wall。無ければ Enter)
-O2
  → いま実行しますか? (y = はい / Enter = いいえ)
y
```

出力の名前に使えるのは半角英数字と `. _ -` だけです。

## 仕組み

- コンパイラは [TCC (Tiny C Compiler)](https://bellard.org/tcc/) (Termux の tcc と同じ 2023-04-15 の版)。
  `native/build-tcc.sh` で Android NDK を使ってビルドし、APK の `lib/arm64-v8a/libtccbionic.so`・
  `libtccglibc.so` として入れてあります (約 400KB ずつ)。どちらも端末の上で動き、作るものの形式だけが違います。
- libc のヘッダとライブラリは、初めて使うときに公式の配布元から取ってきて `~/.mkexe` に置きます
  (アプリには同梱しません)。配布元の一覧に載っている SHA-256 で確かめてから使います。
  - bionic: Termux の `ndk-sysroot` (Android NDK のヘッダと起動用の部品、約 2MB)。libc 本体は端末の
    `/system/lib64/libc.so` を使う。ヘッダの「どの版の Android 用か」は端末の版 (`getprop
    ro.build.version.sdk`) にする。古い端末でも動かすなら `-D__ANDROID_API__=24` を付ける
  - glibc: Debian 13 (trixie) の `libc6`・`libc6-dev`・`linux-libc-dev` (glibc 2.41。アプリ同梱の
    glibc 2.44 で動く)。一覧と合わせて約 20MB
- 取ってきたものは合わせて 50MB ほどになります。`mkexe clean` で消せます。

## できないこと

- C++ (TCC は C だけ)。`_Complex` (複素数、`complex.h`) も TCC が対応していない。
- 静的リンク (`-static`)。どちらの形式も libc を動的にリンクする。
- 最適化は控えめ (TCC は速くコンパイルする代わりに、gcc や clang ほどは速いコードを作らない)。
- SDL2 などのライブラリを使うプログラム (ヘッダが無い)。glibc 用で SDL2 を使うゲームは、従来どおり
  PC や GitHub Actions でビルドする。

## 確認したこと (PC 上で)

- `test-all.c` (並べ替え・スレッド・atomic・数学関数・書式・setjmp・シグナル・ファイル・errno・
  htons・malloc を一通り使う) を、端末用にビルドした TCC そのもの (QEMU で、Termux の aosp-libs =
  Android の本物の bionic とローダの上で動かした) で bionic 用・glibc 用にコンパイルし、bionic 用は
  Android の bionic の上で、glibc 用はアプリ同梱の glibc 2.44 の上で動かした。どちらも PC の gcc で
  作ったものと出力が完全に一致した。
- よく使う 41 個のヘッダ (stdio.h・pthread.h・sys/socket.h・regex.h・stdatomic.h など) が、両方の形式で
  読めることを確かめた。bionic のヘッダの clang 専用の書き方 (`_Null_unspecified`・C23 の型付き enum・
  `__builtin_bswap*`) は mkexe が補う。

## コンパイラを作り直すには

```sh
ANDROID_NDK_HOME=/path/to/android-ndk bionic-runner/native/build-tcc.sh
```

PC が無い場合は、リポジトリの Actions →「build-mkexe」→「Run workflow」で同じビルドができ、
出来上がりを Artifacts の `mkexe-tcc` からダウンロードできます (`.github/workflows/build-mkexe.yml`)。
