# Neon Drift (サンプル)

Android 用 (bionic libc) にビルドした、Bionic Runner で遊べる小さなゲーム。
洞窟の中を進む光の機体を、**押している間は上昇・離すと落下**の 1 ボタンで操る。

- 実行ファイル: [`neondrift.exe`](neondrift.exe) (arm64 / bionic 動的リンク、約 22KB)
- 名前は `.exe` だが中身は Android 用の ELF。Windows では動かない。
- **root は要らない** (bionic 用なので、制限モードでもそのまま動く)。
- SDL などのライブラリを使わず、Bionic Runner の画面・音声サーバ (環境変数 `BR_DISPLAY`) へ
  直接つないで、絵・音を送り、タッチとキー入力を受け取る。画像・音・文字はすべてプログラムの中で
  作るので、ファイル 1 個で動く。

## 遊び方

1. `neondrift.exe` を端末に保存する。
2. Bionic Runner の「実行ファイル」(またはデスクトップの「プログラムを追加」) で選び、
   「登録して実行」を押す。ゲーム画面が開く。
3. 次からはデスクトップのアイコンか、コマンドプロンプトで `neondrift.exe` と打てば起動する。

| 操作 | 内容 |
| --- | --- |
| 画面を押し続ける / ▲・Z・Space・Enter | 上昇 (離すと落下) |
| タップ | 開始・再挑戦 |
| Esc | 一時停止 (タップで再開) |
| ✕ / 戻る | 終了 |

- 壁か柱に触れたら終わり。光の玉は 1 つ 25 点、進んだ距離でも点が入る。
- 進むほど速く、洞窟は狭くなり、柱が増える。洞窟の色も移り変わる。
- 最高記録は `$HOME/.neondrift_best` に保存される。

## ビルド

```sh
ANDROID_NDK_HOME=/path/to/ndk ./build.sh
```

4 種類の CPU 用 (`out/<ABI>/neondrift.exe`) を作り、arm64 用を `neondrift.exe` にコピーする。
メモリのページが 16KB の新しい端末でも動くよう、`-Wl,-z,max-page-size=16384` を付けている。

同じ `main.c` は PC の Linux でも `gcc -O2 -o neondrift main.c -lm` でビルドでき、
`BR_DISPLAY` に画面サーバの代わりを用意すれば動作を確かめられる。
