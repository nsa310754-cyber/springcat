// gorun: Go のソースファイルを、コンパイルせずにそのまま実行する (Bionic Runner 用)。
//
//	gorun file.go [引数...]     実行する
//	gorun -check file.go       文法の誤りだけを調べる (実行しない)
//
// 中身は Go のインタープリタ yaegi。標準ライブラリに加えて、端末を使うゲームでよく使われる
// golang.org/x/term (1 文字ずつのキー入力や画面の大きさの取得) を使えるようにしてある。
// それ以外の外部パッケージ (import "github.com/..." など) は使えない。
package main

import (
	"errors"
	"fmt"
	"go/scanner"
	"os"
	"reflect"

	"github.com/traefik/yaegi/interp"
	"github.com/traefik/yaegi/stdlib"
	"github.com/traefik/yaegi/stdlib/syscall"
	"github.com/traefik/yaegi/stdlib/unrestricted"
	"github.com/traefik/yaegi/stdlib/unsafe"
)

// Symbols は xterm.go (yaegi extract で生成) が埋める
var Symbols = map[string]map[string]reflect.Value{}

func main() {
	args := os.Args[1:]
	check := false
	if len(args) > 0 && args[0] == "-check" {
		check = true
		args = args[1:]
	}
	if len(args) == 0 {
		fmt.Fprintln(os.Stderr, "使い方: gorun [-check] file.go [引数...]")
		os.Exit(2)
	}
	path := args[0]
	src, err := os.ReadFile(path)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}

	i := interp.New(interp.Options{Args: args, Unrestricted: true})
	// unrestricted: os.Exit や os/exec などを、制限なしの本物の動作にする (無いと os.Exit が panic になる)
	for _, syms := range []interp.Exports{stdlib.Symbols, syscall.Symbols, unsafe.Symbols,
		unrestricted.Symbols, Symbols} {
		if err := i.Use(syms); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
	}
	if check {
		// 文法と型の誤りを調べる。main は呼ばない
		if _, err := i.Compile(string(src)); err != nil {
			report(err)
			os.Exit(1)
		}
		return
	}
	if _, err := i.EvalPath(path); err != nil {
		report(err)
		os.Exit(1)
	}
}

// report は誤りを「行:桁: 内容」の形で 1 行ずつ出す
func report(err error) {
	var list scanner.ErrorList
	if errors.As(err, &list) {
		for _, e := range list {
			fmt.Fprintf(os.Stderr, "%d:%d: %s\n", e.Pos.Line, e.Pos.Column, e.Msg)
		}
		return
	}
	var p interp.Panic
	if errors.As(err, &p) {
		fmt.Fprintf(os.Stderr, "panic: %v\n", p.Value)
		return
	}
	fmt.Fprintln(os.Stderr, err)
}
