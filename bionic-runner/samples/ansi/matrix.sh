#!/usr/bin/env bash
# 文字の雨を降らせる (カーソル位置の指定と色を使って画面全体に描くサンプル)。
#   使い方: コマンドプロンプトで  bash matrix.sh   (止めるときは「中断 ^C」)
# 半角カタカナと数字が、緑色で縦に流れる。

read -r rows cols < <(stty size 2>/dev/null)
rows=${rows:-24}
cols=${cols:-80}
chars=(ｱ ｲ ｳ ｴ ｵ ｶ ｷ ｸ ｹ ｺ ｻ ｼ ｽ ｾ ｿ ﾀ ﾁ ﾂ ﾃ ﾄ 0 1 2 3 4 5 6 7 8 9)

# 終わるときは、カーソルを戻して色を元に戻す
cleanup() { printf '\e[0m\e[?25h\e[%d;1H\n' "$rows"; exit 0; }
trap cleanup INT TERM

printf '\e[?25l\e[2J'   # カーソルを隠して、画面を消す
declare -a drop         # 列ごとの、いま落ちている文字の行 (0 なら止まっている)
for ((c = 1; c <= cols; c += 2)); do drop[c]=$(( -(RANDOM % rows) )); done

while true; do
    frame=''
    for ((c = 1; c <= cols; c += 2)); do
        y=${drop[c]}
        if ((y > 0 && y <= rows)); then
            ch=${chars[RANDOM % ${#chars[@]}]}
            frame+=$'\e['"$y;${c}H"$'\e[1;97m'"$ch"            # 先頭は白く明るく
            ((y > 1)) && frame+=$'\e['"$((y - 1));${c}H"$'\e[0;32m'"${chars[RANDOM % ${#chars[@]}]}"
        fi
        tail=$((y - rows / 2))                                  # 尾の端は消していく
        ((tail > 0 && tail <= rows)) && frame+=$'\e['"$tail;${c}H "
        ((y++))
        ((tail > rows)) && y=$(( -(RANDOM % rows) ))
        drop[c]=$y
    done
    printf '%s' "$frame"
    sleep 0.05
done
