// 落ちてくるブロックを並べて、横 1 列がそろうと消える落ち物ゲーム (文字で描く)。
// Bionic Runner のゲームモード用サンプル:  /run blocks.go game
//
// 操作: ◀ ▶ 移動 / ▲ 回転 / ▼ 1 段落とす / Space 一気に落とす / P 一時停止 / Q 終了
package main

import (
	"fmt"
	"math/rand"
	"os"
	"strings"
	"time"

	"golang.org/x/term"
)

const (
	width  = 10
	height = 20
)

// 7 種類のブロック。4x4 のマスのうち、どこが埋まっているかを 4 つの (x, y) で表す
var shapes = [7][4][2]int{
	{{0, 1}, {1, 1}, {2, 1}, {3, 1}}, // I
	{{1, 0}, {2, 0}, {1, 1}, {2, 1}}, // O
	{{1, 0}, {0, 1}, {1, 1}, {2, 1}}, // T
	{{1, 0}, {2, 0}, {0, 1}, {1, 1}}, // S
	{{0, 0}, {1, 0}, {1, 1}, {2, 1}}, // Z
	{{0, 0}, {0, 1}, {1, 1}, {2, 1}}, // J
	{{2, 0}, {0, 1}, {1, 1}, {2, 1}}, // L
}

// 色 (256 色表の番号)
var colors = [8]int{0, 51, 226, 201, 46, 196, 33, 208}

type piece struct {
	kind  int
	cells [4][2]int
	x, y  int
}

var (
	board [height][width]int
	cur   piece
	next  int
	score int
	lines int
	over  bool
	pause bool
)

func newPiece(kind int) piece {
	return piece{kind: kind, cells: shapes[kind], x: width/2 - 2, y: 0}
}

func (p piece) fits() bool {
	for _, c := range p.cells {
		x, y := p.x+c[0], p.y+c[1]
		if x < 0 || x >= width || y >= height {
			return false
		}
		if y >= 0 && board[y][x] != 0 {
			return false
		}
	}
	return true
}

// 4x4 のマスの中で右に 90 度回す
func (p piece) rotated() piece {
	if p.kind == 1 {
		return p // 四角は回しても同じ
	}
	size := 3
	if p.kind == 0 {
		size = 4
	}
	q := p
	for i, c := range p.cells {
		q.cells[i] = [2]int{size - 1 - c[1], c[0]}
	}
	return q
}

func lock() {
	for _, c := range cur.cells {
		x, y := cur.x+c[0], cur.y+c[1]
		if y >= 0 {
			board[y][x] = cur.kind + 1
		}
	}
	cleared := 0
	for y := height - 1; y >= 0; y-- {
		full := true
		for x := 0; x < width; x++ {
			if board[y][x] == 0 {
				full = false
				break
			}
		}
		if full {
			cleared++
			for yy := y; yy > 0; yy-- {
				board[yy] = board[yy-1]
			}
			board[0] = [width]int{}
			y++ // 詰めた行をもう一度調べる
		}
	}
	lines += cleared
	score += []int{0, 100, 300, 500, 800}[cleared]
	cur = newPiece(next)
	next = rand.Intn(7)
	if !cur.fits() {
		over = true
	}
}

func step(dx, dy int) bool {
	q := cur
	q.x += dx
	q.y += dy
	if q.fits() {
		cur = q
		return true
	}
	return false
}

func cell(kind int) string {
	if kind == 0 {
		return "\x1b[48;5;234m  \x1b[0m"
	}
	return fmt.Sprintf("\x1b[48;5;%dm  \x1b[0m", colors[kind])
}

func draw() {
	var b strings.Builder
	b.WriteString("\x1b[H")
	view := board
	if !over {
		for _, c := range cur.cells {
			if y := cur.y + c[1]; y >= 0 {
				view[y][cur.x+c[0]] = cur.kind + 1
			}
		}
	}
	b.WriteString("\x1b[1;36m  B L O C K S\x1b[0m\x1b[K\r\n")
	for y := 0; y < height; y++ {
		b.WriteString("\x1b[90m|\x1b[0m")
		for x := 0; x < width; x++ {
			b.WriteString(cell(view[y][x]))
		}
		b.WriteString("\x1b[90m|\x1b[0m")
		switch y {
		case 1:
			fmt.Fprintf(&b, "  SCORE %d", score)
		case 3:
			fmt.Fprintf(&b, "  LINES %d", lines)
		case 5:
			b.WriteString("  NEXT")
		case 6, 7:
			b.WriteString("  ")
			for x := 0; x < 4; x++ {
				on := false
				for _, c := range shapes[next] {
					if c[0] == x && c[1] == y-6 {
						on = true
					}
				}
				if on {
					b.WriteString(cell(next + 1))
				} else {
					b.WriteString("  ")
				}
			}
		case 10:
			if over {
				b.WriteString("  \x1b[1;31mGAME OVER\x1b[0m")
			} else if pause {
				b.WriteString("  \x1b[1;33mPAUSED\x1b[0m")
			}
		case 11:
			if over {
				b.WriteString("  R: retry  Q: quit")
			}
		}
		b.WriteString("\x1b[K\r\n")
	}
	b.WriteString("\x1b[90m+" + strings.Repeat("--", width) + "+\x1b[0m\x1b[K")
	os.Stdout.WriteString(b.String())
}

func reset() {
	board = [height][width]int{}
	score, lines, over, pause = 0, 0, false, false
	cur = newPiece(rand.Intn(7))
	next = rand.Intn(7)
}

func main() {
	fd := int(os.Stdin.Fd())
	old, err := term.MakeRaw(fd) // 1 文字ずつ、Enter なしでキーを受け取る
	if err != nil {
		fmt.Println("このゲームは端末の画面が必要です。ゲームモードで実行してください:  /run blocks.go game")
		os.Exit(1)
	}
	defer term.Restore(fd, old)
	fmt.Print("\x1b[2J\x1b[?25l") // 画面を消し、カーソルを隠す
	defer fmt.Print("\x1b[?25h\x1b[0m\r\n")

	keys := make(chan string, 16)
	go func() {
		buf := make([]byte, 16)
		for {
			n, err := os.Stdin.Read(buf)
			if err != nil {
				close(keys)
				return
			}
			keys <- string(buf[:n])
		}
	}()

	reset()
	draw()
	tick := time.NewTicker(50 * time.Millisecond)
	defer tick.Stop()
	fall := 0
	for {
		select {
		case k, ok := <-keys:
			if !ok {
				return
			}
			switch k {
			case "q", "Q", "\x03":
				return
			case "r", "R":
				reset()
			case "p", "P", "\x1b":
				pause = !pause
			}
			if !over && !pause {
				switch k {
				case "\x1b[D", "a":
					step(-1, 0)
				case "\x1b[C", "d":
					step(1, 0)
				case "\x1b[B", "s":
					if !step(0, 1) {
						lock()
					}
				case "\x1b[A", "w", "z", "x":
					// 壁ぎわでは、左右に 1 マスずらして回せるか試す
					for _, dx := range []int{0, -1, 1, -2, 2} {
						q := cur.rotated()
						q.x += dx
						if q.fits() {
							cur = q
							break
						}
					}
				case " ":
					for step(0, 1) {
						score++
					}
					lock()
				}
			}
			draw()
		case <-tick.C:
			if over || pause {
				continue
			}
			// 10 行消すごとに速くなる (最初は 0.6 秒に 1 段)
			speed := 12 - lines/10
			if speed < 2 {
				speed = 2
			}
			fall++
			if fall >= speed {
				fall = 0
				if !step(0, 1) {
					lock()
				}
				draw()
			}
		}
	}
}
