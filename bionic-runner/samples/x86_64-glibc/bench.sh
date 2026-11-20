#!/usr/bin/env bash
# Box64 と QEMU で、同じ計算 (素数を数える) にかかった時間を比べる。
# 使い方: bash bench.sh [登録名] [上限]
#   先に primes-x86_64 を「プログラムを追加」で登録しておく (登録名は primes-x86_64.Gelf になる)
#   例: bash bench.sh primes-x86_64.Gelf 3000000
# 起動スクリプトの環境変数 BR_X86 で、その 1 回だけ実行方法 (box64 / qemu) を選んで動かす。
P=${1:-primes-x86_64.Gelf}
N=${2:-3000000}
if ! command -v "$P" >/dev/null 2>&1; then
    echo "$P が見つかりません。primes-x86_64 を「プログラムを追加」で登録してください" >&2
    exit 1
fi
echo "計算: $N までの素数を数える"
TIMEFORMAT=%R
for m in box64 qemu; do
    err=$(mktemp "${TMPDIR:-.}/bench.XXXXXX")
    # 1 回目は準備 (Box64 が動くかの確認など) が入るので、空振りしてから測る
    BR_X86=$m "$P" 1000 >/dev/null 2>&1
    t=$( { time BR_X86=$m "$P" "$N" >"$err.out" 2>"$err"; } 2>&1 )
    note=""
    grep -q "Box64 が使えない" "$err" && note=" (Box64 が使えず QEMU で動いた: $(grep -o '(.*)' "$err" | head -n 1))"
    printf '%-6s %8s 秒  %s%s\n' "$m" "$t" "$(cat "$err.out")" "$note"
    rm -f "$err" "$err.out"
done
