# Windows (64bit) のサンプル

Bionic Runner の Wine で動かす確認用の、小さな Windows のプログラムです。

| ファイル | 中身 |
|---|---|
| `hello-console.c` / `.exe` | コマンドプロンプト用。Windows の版・CPU の数・今いる場所・引数を出し、`D:\hello-from-windows.txt` (共有フォルダ) にファイルを書く |
| `hello-window.c` / `.exe` | ウィンドウを 1 つ出す。ボタンを押すと数を数える。X11 の画面に出る |
| `scanner-test/` | 構造の検査の確認用 (ローダー型のウイルスの特徴だけを持つ、害の無いプログラム)。検査で「危険」になる |

## 動かし方

1. Wine を入れる (X11 の画面の「追加機能」→「Windows のプログラム (Wine)」→「ダウンロード」)
2. `.exe` をファイル管理で長押し →「Wine で実行」。またはコマンドプロンプトで:

```
wine hello-console.exe 引数1 引数2
wine hello-window.exe
```

ウィンドウを出すプログラムは X11 の画面に出ます (X サーバーが止まっていれば自動で起動します)。

## ビルドのしかた (PC の Debian / Ubuntu で)

```
sudo apt install gcc-mingw-w64-x86-64
x86_64-w64-mingw32-gcc -O2 -o hello-console.exe hello-console.c
x86_64-w64-mingw32-gcc -O2 -municode -mwindows -o hello-window.exe hello-window.c
x86_64-w64-mingw32-strip hello-console.exe hello-window.exe
```

- 64bit (x86-64) の exe だけが動きます。32bit の exe (`i686-w64-mingw32-gcc` で作ったもの) はまだ動きません。
- ウィンドウの文字が英語なのは、Wine に付いている字体に日本語が無いためです。
