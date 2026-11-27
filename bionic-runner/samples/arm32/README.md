# 32bit ARM 用のサンプル

`hello-arm32` は 32bit の ARM 用 (armeabi-v7a、静的リンク) の小さなプログラム。
64bit 専用の端末で「実行ファイル」から選ぶと、「同梱の CPU エミュレータ (QEMU) で実行します」と
表示され、登録して実行すると次のように出る。

```
hello from 32bit arm args: []
```

ソースは `main.go`。ビルドは `CGO_ENABLED=0 GOOS=linux GOARCH=arm GOARM=7 go build -o hello-arm32 main.go`。
