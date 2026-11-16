// 実行モード用のサンプル:  /run hello.go run
package main

import "fmt"

func main() {
	a := "a"
	fmt.Println(a)
	sum := 0
	for i := 1; i <= 5; i++ {
		sum += i * i
	}
	fmt.Println("sum of squares:", sum)
}
