#!/usr/bin/env bash
# Box64・QEMU・ネイティブ (arm64) の速さを比べる。bench4 (bench4.c) を使い、次の 4 項目を測って表で出す。
#   1. 整数の計算とループ  2. メモリの読み書き  3. libc の関数  4. 起動の速さ (何もしないで 50 回起動)
#
# 使い方:
#   アプリの設定 → 個人設定 →「速度を比べる」から (何も準備しなくてよい)
#   または、コマンドプロンプトで:
#     1. bench4-x86_64 を「プログラムを追加」で登録する (登録名は bench4-x86_64.Gelf になる)
#     2. bash bench.sh [登録名] [計算の量]      例: bash bench.sh bench4-x86_64.Gelf 1
# x86_64 版は起動スクリプトの環境変数 BR_X86 (box64 / qemu) で、その 1 回だけ実行方法を選んで動かす。
# ネイティブ版は同じソースを mkexe (端末の tcc) で arm64 の bionic 向けにビルドして動かす
# (初回は mkexe がヘッダなどを約 2MB 取ってくる。ネイティブ版は tcc でビルドするので、gcc -O2 の
#  x86_64 版より最適化が弱いぶん、本来のネイティブの速さより遅めに出る)。

SCALE=${2:-${BENCH_SCALE:-1}}
HERE=$(cd "$(dirname "$0")" && pwd)
SRC=${BENCH_DIR:-$HERE}/bench4.c
WORK=${TMPDIR:-$HOME}/bench4.$$
mkdir -p "$WORK"
trap 'rm -rf "$WORK"' EXIT

# x86_64 版の動かし方: アプリから呼ばれたときは用意された起動スクリプト、手で呼ぶときは登録名
if [ -n "${BENCH_X86_WRAPPER:-}" ]; then
    x86() { sh "$BENCH_X86_WRAPPER" "$@"; }
else
    NAME=${1:-bench4-x86_64.Gelf}
    if ! command -v "$NAME" >/dev/null 2>&1; then
        echo "$NAME が見つかりません。bench4-x86_64 を「プログラムを追加」で登録してください" >&2
        echo "(アプリの設定 → 個人設定 →「速度を比べる」からなら、登録しなくても測れます)" >&2
        exit 1
    fi
    x86() { "$NAME" "$@"; }
fi

# ネイティブ版: mkexe で作る。動かすときは /system/bin/linker64 に読み込ませる
# (root が無いと、アプリの作業領域のファイルは直接は実行できないため)
NATIVE=
if [ -n "${BR_MKEXE:-}" ] && [ -f "$BR_MKEXE" ] && [ -f "$SRC" ]; then
    echo "ネイティブ版 (arm64) を mkexe でビルドしています…"
    if (cd "$WORK" && "$BR_NATIVE/libbash.so" "$BR_MKEXE" -o bench4-native "$SRC" >"$WORK/mkexe.log" 2>&1); then
        NATIVE=$WORK/bench4-native.Belf
    else
        echo "ネイティブ版はビルドできませんでした (ネットワークにつながっているか確かめてください):"
        tail -n 3 "$WORK/mkexe.log"
    fi
else
    echo "(mkexe が使えないので、ネイティブ版は測りません)"
fi
native() {
    if [ -x /system/bin/linker64 ]; then /system/bin/linker64 "$NATIVE" "$@"; else "$NATIVE" "$@"; fi
}

# 1 つの方法で、3 項目と起動 50 回を測る。結果は変数 R_<方法>_<項目> に入れる
measure() { # 方法の名前 動かすコマンド
    local name=$1 cmd=$2 out err t0 t1 i
    echo "== $name で測っています…"
    err=$WORK/$name.err
    $cmd noop >/dev/null 2>"$err"  # 1 回目は準備 (Box64 が動くかの確認など) が入るので空振り
    out=$($cmd "$SCALE" 2>>"$err")
    local line
    line=$(printf '%s\n' "$out" | grep '^RESULT')
    if [ -z "$line" ]; then
        echo "   動きませんでした: $(head -n 2 "$err" | tr '\n' ' ')"
        return
    fi
    eval "$(printf '%s\n' "$line" | sed 's/^RESULT //; s/ /\n/g' | sed "s/^/R_${name}_/")"
    t0=$EPOCHREALTIME
    for i in $(seq 50); do $cmd noop >/dev/null 2>&1; done
    t1=$EPOCHREALTIME
    eval "R_${name}_start=$(awk -v a="$t0" -v b="$t1" 'BEGIN { printf "%.3f", b - a }')"
    if grep -q "Box64 が使えない" "$err"; then
        eval "R_${name}_note='$(grep -o '(.*)' "$err" | head -n 1 | tr -d "'")'"
    fi
}

echo "計算の量: $SCALE (bench.sh の 2 つ目の引数で変えられる)"
# x86_64 版を、その 1 回だけ Box64 / QEMU で動かす
run_box64() { (export BR_X86=box64; x86 "$@"); }
run_qemu() { (export BR_X86=qemu; x86 "$@"); }
[ -n "$NATIVE" ] && measure native native
measure box64 run_box64
measure qemu run_qemu
show() { local v; eval "v=\${R_$1_$2:-}"; if [ -n "$v" ]; then printf '%8ss' "$v"; else printf '%9s' '-'; fi; }
ratio() { # Box64 は QEMU の何倍速いか
    local b q
    eval "b=\${R_box64_$1:-}; q=\${R_qemu_$1:-}"
    if [ -n "$b" ] && [ -n "$q" ]; then
        awk -v b="$b" -v q="$q" 'BEGIN { if (b > 0) printf "%7.1f倍", q / b; else printf "%8s", "-" }'
    else
        printf '%8s' '-'
    fi
}
echo
echo "結果 (秒。小さいほど速い)"
printf '%9s %9s %9s %9s  %s\n' "ネイティブ" "Box64" "QEMU" "Box64速さ" "項目"
for k in int mem libc start; do
    case $k in
        int) label="1. 整数の計算とループ (割り算なし)" ;;
        mem) label="2. メモリの読み書き (ふるい・配列のコピー)" ;;
        libc) label="3. libc の関数 (memcpy・strcmp・qsort・sin/cos)" ;;
        start) label="4. 起動の速さ (何もしないで 50 回起動)" ;;
    esac
    printf '%s %s %s %s  %s\n' "$(show native $k)" "$(show box64 $k)" "$(show qemu $k)" "$(ratio $k)" "$label"
done
echo "「Box64速さ」は、Box64 が QEMU の何倍速いか。"
[ -n "${R_box64_note:-}" ] && echo "※ Box64 は使えず QEMU で動きました $R_box64_note"
[ -n "${R_box64_sum:-}" ] && [ -n "${R_qemu_sum:-}" ] && [ "$R_box64_sum" != "$R_qemu_sum" ] \
    && echo "※ 結果の値が Box64 と QEMU で違います (計算がおかしい可能性があります)"
exit 0
