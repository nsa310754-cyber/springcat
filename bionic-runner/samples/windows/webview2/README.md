# WebView2 の確認用サンプル

`hello-webview2.exe` は、WebView2 で日本語の小さな HTML を出す Win32 (C++) のプログラムです。
各段階の結果 (HRESULT) をコマンドプロンプトに出すので、どこまで進んだかが分かります。

```
runtime version: 135.0.3179.98 (0x00000000)   ← ランタイムが見つかった
environment: 0x00000000                       ← Edge が起動した
controller: 0x00000000                        ← 表示部品ができた
navigate: 0x00000000                          ← HTML を読み込んだ
```

## ファイル

| ファイル | 中身 |
|---|---|
| `hello-webview2.cpp` | ソース (WIL などは使わず、COM の受け取り口を手で書いている) |
| `hello-webview2.exe` | ビルドしたもの (x64) |
| `WebView2Loader.dll` | WebView2 SDK 1.0.3179.45 の x64 のローダー (Microsoft の署名付き。BSD 3 条項の許諾で再配布できる。`LICENSE-WebView2-SDK.txt`) |
| `build.sh` | ビルドの手順 (SDK を SHA-256 で照合してから使う) |

**WebView2 Runtime そのものは入っていません** (Microsoft の再配布の条件のため)。
追加機能の「WebView2 を取り込む (試験中)」で、自分で用意した Fixed Version の x64 の zip を取り込んでください。

## 動かし方

1. `hello-webview2.exe` と `WebView2Loader.dll` を共有フォルダ (`D:`) に置く
2. `wine hello-webview2.exe`、または `wine --diag` の「6. WebView2」の C

## 今の結果

Wine 10 では、上の 4 つはすべて成功しますが、ウィンドウのページの部分は白いままで HTML は表示されません。

## ビルドのしかた (PC の Debian / Ubuntu で)

```
sudo apt install g++-mingw-w64-x86-64 unzip curl
sh build.sh
```
