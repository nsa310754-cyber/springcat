# tapupgrade — タップしてどんどんアップグレードするゲーム

Bionic Runner (Android, arm64) 用。SDL2 + SDL2_ttf を動的リンクした glibc 版 ELF (形式 4)。

## 遊び方

- 左の丸をタップ → コインが入る
- 右のボタンをタップ → アップグレードを買う (買えるものは青、買えないものは灰色)

| アップグレード | 効果 | 最初の値段 |
|---|---|---|
| 指の力 | 1 タップのコイン +1 | 15 |
| ロボット | 毎秒 +1 | 100 |
| 工場 | 毎秒 +8 | 1,100 |
| 研究所 | 毎秒 +47 | 12,000 |
| 宇宙基地 | 毎秒 +260 | 130,000 |
| 倍率アップ | 全部の収入 ×2 | 5,000 |

買うたびに値段が上がる (倍率アップ以外は 1.15 倍、倍率アップは 9 倍)。

画面ボタンでも遊べる: Space / Z = 丸をタップ、↑↓ = 選ぶ、Enter / X = 買う、Esc = 保存して終了。

- 進行は `$HOME/.tapupgrade.sav` に自動保存 (10 秒ごと + 終了時)
- 閉じている間も自動収入の半分がたまる (最大 8 時間ぶん)

## 引数

```
tapupgrade           ゲームを始める
tapupgrade --reset   セーブデータを消す
tapupgrade --help    説明
```

## ビルド

### GitHub Actions (PC が無い場合)

このフォルダーの `main.c` と `.github/workflows/build.yml` を新しいリポジトリの一番上に置いて push。
Actions タブ → 実行結果 → Artifacts の `tapupgrade-arm64` をダウンロードし、中の `tapupgrade` を端末に入れる。

### ARM64 の Linux で直接

```bash
sudo apt-get install -y gcc libsdl2-dev libsdl2-ttf-dev
gcc -O2 -o tapupgrade main.c $(pkg-config --cflags --libs sdl2 SDL2_ttf) -lm
```

glibc 2.41 より新しい環境ではビルドしない (Ubuntu 24.04 は 2.39 なので可)。

## 動作確認用の引数 (PC 上)

`--test N --shot out.bmp` で N フレーム自動プレイして画面を BMP に保存する。
フォントは `TAPUPGRADE_FONT` で差し替えられる。

```bash
SDL_VIDEODRIVER=dummy SDL_AUDIODRIVER=dummy \
TAPUPGRADE_FONT=/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc \
./tapupgrade --test 14 --shot shot.bmp
```
