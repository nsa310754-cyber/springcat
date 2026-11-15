# Bionic Runner

Android (bionic libc) 向けにビルドされた ELF 実行ファイルを root 権限で実行し、
APK もインストールできるアプリ。

ビルド済み APK: [`dist/BionicRunner-1.4.0.apk`](dist/BionicRunner-1.4.0.apk)

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
- **絵や音を出すプログラム (ゲーム)** — SDL2 を使う glibc 用プログラムは、ウィンドウの中身が
  アプリのゲーム画面に表示され、音が端末から出る。タッチはマウス操作、画面上のボタン
  (矢印・Z・X・Space・Enter・Esc) と画面キーボードはキー入力として届く。
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
- 画面を描き直すプログラム (`vi`、`top`、テトリスのような curses を使うゲームなど)。
  シェルは疑似端末の中で動くが、画面は文字が下へ流れるだけで、カーソル移動や
  1 文字単位のキー入力には対応していない。
- OpenGL / Vulkan で描画するゲーム (3D ゲームの大半)、X11 や Wayland を直接使う GUI アプリ。
  絵が出せるのは SDL2 (または SDL 1.2) のソフトウェア描画を使うプログラムだけ。

## 絵と音の仕組み

同梱の glibc 一式に、出力先をこのアプリに差し替えた SDL2 を入れてある
(`native/sdl/` が差し替えたドライバ、`native/build-sdl.sh` がビルド手順)。

- プログラムが SDL2 でウィンドウを作ると、SDL2 がアプリ内のサーバ (127.0.0.1) に接続し、
  フレームと PCM 音声を送る。接続先と合言葉はシェルの環境変数 `BR_DISPLAY` で渡している。
- 同梱ライブラリ: SDL2 2.32.10、SDL2_image (PNG・JPG・BMP・GIF など)、
  SDL2_mixer (WAV・OGG・MP3・FLAC)、SDL2_ttf、SDL 1.2 互換 (`libSDL-1.2.so.0`)。
  SDL2 を**動的リンク**しているプログラムが対象で、SDL2 を静的に取り込んだものや、
  ここに無いライブラリ (SDL_net、SDL 1.2 用の SDL_image / SDL_mixer など) が要るものは動かない。
- 描画はソフトウェア描画のみ。OpenGL のコンテキストは作れない。
- ゲームのデータファイルが決まった場所 (`/usr/share/...`) に必要なプログラムは、
  そのままでは動かない (実行ファイル 1 個で完結するものが向いている)。
- root が必要 (glibc 用プログラムの実行に root が要るため)。

## 動作確認したゲーム

Debian の `bsdgames` (arm64・glibc 動的リンク) を、アプリと同じ経路 (同梱 glibc + ptyrun +
1 行ずつの入力) で PC 上のエミュレーションにより確認した。Android 実機での確認ではない。

| ゲーム | 結果 |
| --- | --- |
| Colossal Cave Adventure、Hunt the Wumpus、go-fish、arithmetic | 遊べる |
| tetris-bsd など curses を使うもの | 起動しない (libncurses が無い。入れても画面描画に非対応) |
| Sopwith (Debian の arm64 版、SDL2) | タイトル画面とプレイ画面の表示、キー入力、音声の送出を確認 |
| SDL2_image / SDL2_ttf / SDL2_mixer を使う自作テスト | PNG の表示、文字描画、WAV の再生データを確認 |

絵と音の確認は、アプリ内のサーバ部分 (`DisplayServer`) を PC 上で動かし、届いたフレームを
画像に保存して目視したもの。Android 上のゲーム画面 (`GameActivity`) の表示・音の再生・
タッチ操作は実機で未確認。

## glibc について

- 同梱しているのは libc, libm, libpthread, libdl, librt, libresolv, libgcc_s, libstdc++ など。
  対応 CPU は arm64-v8a / armeabi-v7a / x86_64 / x86。
- **root が必要**。root なしの制限モードでは Android の制限でローダを起動できない。
- Android には `/etc/resolv.conf` が無いので、glibc 用プログラムはホスト名を解決できない
  (IP アドレス直指定の通信はできる)。`/etc/passwd` やロケールデータも無い。
- SDL2・SDL2_image・SDL2_mixer・SDL2_ttf は zlib ライセンス (SDL2_ttf に含まれる FreeType は
  FreeType License)。SDL 1.2 互換は Debian の `libsdl1.2debian` (sdl12-compat、zlib ライセンス)。
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
native/build-sdl.sh         # SDL2 一式をビルド (初回のみ。各 CPU 向けの GNU クロスコンパイラが必要)
./fetch-glibc.sh            # glibc 一式を Debian から取得して SDL2 と合わせる (初回のみ)
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

Java のライブラリには依存していない。
補助プログラム `ptyrun` (シェルを疑似端末の中で動かす) はビルド済みのものを
`app/src/main/jniLibs` に入れてある。`native/ptyrun.c` を変えたら
`ANDROID_NDK_HOME=... native/build.sh` で作り直す。署名は同梱の `bionicrunner.keystore`
(自前配布用の固定鍵・パスワードは `app/build.gradle` に記載) で行うので、
再ビルドしても上書き更新できる。Play ストア等で公開する場合は非公開の鍵に差し替えること。
