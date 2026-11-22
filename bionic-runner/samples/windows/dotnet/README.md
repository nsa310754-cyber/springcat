# .NET (C#) のプログラムの確認用

Bionic Runner の Wine で、.NET (C# など) のプログラムが動くかを確かめるためのものです。どちらも害はありません。

| ファイル | 中身 |
|---|---|
| `hello-dotnet.cs` / `.exe` | コマンドプロンプト用。.NET の版・64bit で動いているか・簡単な計算 (LINQ)・日本語を出す |
| `hello-winforms.cs` / `.exe` | Windows フォームのウィンドウ。日本語の文字とボタンがあり、押した回数を数える |
| `build.sh` | ビルドのしかた (`mcs`。どの CPU でも動く AnyCPU の exe になる) |

## 動かし方

1. X11 の画面の「追加機能」で「Windows のプログラム (Wine)」と「.NET のプログラムも動かす (Wine Mono)」を入れる
2. 「実行ファイル」で exe を選ぶか、コマンドプロンプトで `wine hello-dotnet.exe` / `wine hello-winforms.exe`

AnyCPU の .NET のプログラムは 64bit で動くので、「32bit の exe も動かす」は要りません。
Box64 の上なので、起動に時間がかかります。

## ビルドのしかた (PC の Debian / Ubuntu で)

```
sudo apt install mono-mcs libmono-system-windows-forms4.0-cil
./build.sh
```
