#!/system/bin/sh
# 自動操作のサンプル: 画面に「閉じる」ボタンが出たら、自動で押す。
# 広告の上に出る × や「スキップ」を、出るたびに押してくれる。
#   使い方: コマンドプロンプトで  sh autoclose.sh   (止めるときは「中断 ^C」)
# 先に、設定 → 個人設定 →「自動操作と画面上の表示」をオンにし、ユーザー補助を有効にしておくこと。
#
# 押すのは、部品に付いている文字や説明が下の言葉に当てはまるものだけ。絵だけで文字も説明も無い
# ボタンは見つけられない。うまく押せないときは、そのボタンが出ている状態で  br ui dump  を実行して、
# どんな文字・説明が付いているかを調べ、下の一覧に足す。

count=0
trap 'br overlay hide autoclose; exit 0' INT TERM
br overlay set autoclose "自動で閉じる: 待機中" --y 120 || exit 1
while true; do
    # 記号だけのボタンは、ほかの文章の中の × を押さないよう「完全に一致」で探す。
    # 言葉の方は、「広告を閉じる」「Skip Ad」のような言い回しにも当たるよう「含む」で探す。
    if br ui click --exact "×" "✕" "✖" "X" >/dev/null 2>&1 ||
       br ui click "広告を閉じる" "閉じる" "スキップ" "Close ad" "Skip ad" "Close" "Skip" >/dev/null 2>&1; then
        count=$((count + 1))
        br overlay set autoclose "自動で閉じる: $count 回押しました"
        sleep 2 # 押した直後は、画面が切り替わるのを待つ
    fi
    sleep 1
done
