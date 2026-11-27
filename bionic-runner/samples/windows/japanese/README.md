# 日本語の表示の確認用 (Windows 64bit)

Bionic Runner の Wine で、日本語が □ や文字化けにならずに出るかを確かめるためのプログラムです。
ソースはすべて ASCII で書いてあり (日本語は番号で書く)、コンパイラーの文字コードの設定に左右されません。

| ファイル | 中身 |
|---|---|
| `jp-console.c` / `.exe` | コマンドプロンプト用。同じ「こんにちは、世界」を、1. UTF-8 のバイト列、2. Shift_JIS のバイト列、3. Unicode の文字列を Windows の文字コード (ANSI) に変換したもの、の 3 とおりで出し、最後に Windows の文字コードの設定 (ACP) を出す |
| `jp-console-32.exe` / `jp-window-32.exe` | 上の 2 つの 32bit 版 (同じソース)。追加機能の「32bit の exe も動かす (試験中)」を入れると動く |
| `jp-window.c` / `.exe` | ウィンドウ版。日本語のタイトル・ボタン・入力欄と、字体の見本 (指定なし・MS UI Gothic・ＭＳ ゴシック・メイリオ・ＭＳ 明朝) を出す。ボタンを押すと日本語のメッセージボックスを出す |

## 動かし方

先に、X11 の画面の「追加機能」で「Windows のプログラム (Wine)」と「日本語フォント (Noto CJK)」を入れておきます。

```
wine jp-console.exe
wine jp-window.exe
```

## 正しい結果

- `jp-console.exe`
  - ふだんの環境: 1 と 2 は「こんにちは、世界」。3 は「????????」(Windows の文字コードが英語 (1252) のため)。`ACP=1252`
  - 「日本語の環境で動かす」を選んだとき: 1・2・3 とも「こんにちは、世界」。`ACP=932`
- `jp-window.exe`: どの行も □ にならず、日本語が読める。ＭＳ 明朝の行は明朝体 (Noto Serif CJK JP) になる。
  日本語フォントを入れていないと □ になり、wine コマンドが入れ方を案内する (落ちない)

## ビルドのしかた (PC の Debian / Ubuntu で)

```
sudo apt install gcc-mingw-w64-x86-64 gcc-mingw-w64-i686
./build.sh
```
