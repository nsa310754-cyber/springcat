# Windows (64bit) のサンプル

Bionic Runner の Wine で動かす確認用の、小さな Windows のプログラムです。

| ファイル | 中身 |
|---|---|
| `hello-console.c` / `.exe` | コマンドプロンプト用。Windows の版・CPU の数・今いる場所・引数を出し、`D:\hello-from-windows.txt` (共有フォルダ) にファイルを書く |
| `hello-window.c` / `.exe` | ウィンドウを 1 つ出す。ボタンを押すと数を数える。X11 の画面に出る |
| `hello-console-32.exe` / `hello-window-32.exe` | 上の 2 つの 32bit 版 (同じソース)。追加機能の「32bit の exe も動かす (試験中)」を入れると動く |
| `dotnet/` | .NET (C#) の確認用: `hello-dotnet.exe` (コンソール。.NET の版・64bit か・日本語を出す) と `hello-winforms.exe` (Windows フォームのウィンドウ。ボタンで数を数える)。追加機能の「.NET のプログラムも動かす (Wine Mono)」を入れると動く |
| `bubble32/` | Bubble Pop: 32bit の exe の確認用のミニゲーム (泡をタップして割る。`exe: 32bit / WoW64: yes` と出る) |
| `wsf/` | Windows Script File (.wsf) の確認用 (`hello.wsf`: JScript と VBScript の job、`redscreen-test.wsf`: 赤い警告画面の確認用。どちらも害は無い) |
| `japanese/` | 日本語の表示の確認用。`jp-console.exe` は UTF-8・Shift_JIS・Unicode の 3 とおりで日本語を出す。`jp-window.exe` は日本語のボタン・文字・入力欄と、MS ゴシックなどの字体の見本を出す (ソースと `build.sh` 付き) |
| `webview2/` | WebView2 の確認用 (`hello-webview2.exe`: WebView2 で日本語の HTML を出し、各段階の結果をコマンドプロンプトに出す)。追加機能の「WebView2 を取り込む (試験中)」が必要。WebView2 Runtime は入っていない |
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
# 32bit 版 (sudo apt install gcc-mingw-w64-i686)
i686-w64-mingw32-gcc -O2 -o hello-console-32.exe hello-console.c
i686-w64-mingw32-gcc -O2 -municode -mwindows -o hello-window-32.exe hello-window.c
i686-w64-mingw32-strip hello-console-32.exe hello-window-32.exe
```

- 64bit (x86-64) の exe はそのまま動きます。32bit の exe (`i686-w64-mingw32-gcc` で作ったもの) は、追加機能の「32bit の exe も動かす (試験中)」を入れると動きます (試験中で、ときどき落ちることがあります)。
- ウィンドウの文字が英語なのは、Wine に付いている字体に日本語が無いためです。
