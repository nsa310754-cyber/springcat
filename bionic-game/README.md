# snake — Bionic Runner 向け SDL2 ゲーム

Android の **Bionic Runner** で動く、SDL2 のスネークゲーム。画面はアプリのゲーム画面に表示され、
音は端末スピーカーから出ます。実行ファイルは **`snake`** 1 個だけ(画像・音・フォントは内蔵/生成)。

- 形式: **glibc 動的リンク ELF(arm64-v8a)+ SDL2 を動的リンク**(仕様の「形式4」)
- 描画: **ソフトウェア描画のみ**(`SDL_CreateRenderer(win, -1, 0)`)、ウィンドウ **640×360**
- 依存: **SDL2 コアのみ**(SDL2_image / mixer / ttf は不要)。文字は内蔵ビットマップフォント
- `SDL_QUIT` を受けたら必ず終了

## 遊び方(操作)

| 操作 | キー / タップ |
| --- | --- |
| 方向を変える | **矢印キー** / 画面を**タップ**(蛇の頭から見た方向へ曲がる) |
| 開始・リスタート | **Space / Enter / Z**、または画面タップ |
| 終了 | **Esc**(またはアプリでウィンドウを閉じる) |

餌(赤)を食べると伸びてスコアが増えます。壁と自分にぶつかるとゲームオーバー。

### Bionic Runner での使い方
1. 下記いずれかで作った実行ファイル `snake` を用意する
2. Bionic Runner の「実行ファイル」から `snake` を選び、「登録して実行」
3. 以後は入力欄に `snake` と打つだけでも起動できる(引数は不要)

## ビルド

### A. GitHub Actions でビルド(PC 不要・おすすめ)
このリポジトリに同梱の [`.github/workflows/bionic-game.yml`](../.github/workflows/bionic-game.yml) が
**ARM64 ランナー**でビルドします。`bionic-game/` を push するか、Actions 画面で
「bionic-game (snake)」を **Run workflow** → 完了後に **Artifacts の `snake-arm64`** を
ダウンロード → 中の `snake` を端末へ。

### B. 手元の Linux(arm64)でビルド
```sh
sudo apt-get update
sudo apt-get install -y libsdl2-dev
gcc -O2 -o snake snake.c $(pkg-config --cflags --libs sdl2) -lm
file snake   # ELF 64-bit ... ARM aarch64 ... dynamically linked
```

### C. x86_64 PC からクロスビルド
```sh
sudo dpkg --add-architecture arm64
sudo apt-get update
sudo apt-get install -y gcc-aarch64-linux-gnu libsdl2-dev:arm64
aarch64-linux-gnu-gcc -O2 -o snake snake.c \
  $(PKG_CONFIG_PATH=/usr/lib/aarch64-linux-gnu/pkgconfig pkg-config --cflags --libs sdl2) -lm
```

> 注意: **glibc 2.44 より新しい環境でビルドしない**こと(端末同梱の glibc は 2.44)。
> Ubuntu 24.04(glibc 2.39)/ ubuntu-24.04-arm ランナーは条件を満たします。

## 動作確認(ヘッドレス自己テスト)
`SNAKE_SELFTEST=1` を付けると、画面を出さずに内部ロジックと描画を一巡させて終了します
(CI のスモークテスト用)。
```sh
SDL_VIDEODRIVER=dummy SDL_AUDIODRIVER=dummy SNAKE_SELFTEST=1 ./snake
```

## 仕様メモ
- 入力は 1 行ずつの端末ではなく SDL のイベント(キー/タップ)で受けます。TERM=dumb の制約は
  グラフィックゲームには影響しません(起動・終了時のみ stdout に 1 行表示)。
- 音は SDL2 の AudioDevice で短いビープを生成。オーディオ初期化に失敗しても無音で続行します。
- OpenGL / Vulkan / `SDL_RENDERER_ACCELERATED` は使用していません。
