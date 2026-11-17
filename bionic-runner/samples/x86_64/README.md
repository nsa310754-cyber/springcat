# PC 用 (x86_64) のサンプル

`hello-x86_64` は PC 用の 64bit (x86_64、静的リンク) の小さなプログラム。
ARM のスマホで「実行ファイル」から選ぶと、「同梱の CPU エミュレータ (QEMU) で実行します」と
表示され、登録して実行すると次のように出る。

```
hello from amd64 args: []
```

ソースは `main.go`。ビルドは `CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o hello-x86_64 main.go`。
