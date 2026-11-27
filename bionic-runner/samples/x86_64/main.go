// PC 用 (x86_64、静的リンク) の動作確認用プログラム。
// 64bit 専用の端末では、Bionic Runner 同梱の CPU エミュレータ経由で動く。
//   ビルド: CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -o hello-x86_64 main.go
package main

import (
	"fmt"
	"os"
	"runtime"
)

func main() {
	fmt.Println("hello from", runtime.GOARCH, "args:", os.Args[1:])
}
