#!/system/bin/sh
# 画面の上に、通信の速度 (下り / 上り) を出し続ける。ほかのアプリを使っていても見える。
#   使い方: コマンドプロンプトで  sh netspeed.sh   (止めるときは「中断 ^C」)
# 先に、設定 → 個人設定 →「自動操作と画面上の表示」をオンにし、
# 「他のアプリの上に重ねて表示」を許可しておくこと。

# 1 秒あたりのバイト数を、読みやすい単位にする (例: 1536000 → 1.4 MB/s)
pretty() {
    if [ "$1" -ge 1048576 ]; then
        echo "$(($1 / 1048576)).$(($1 % 1048576 * 10 / 1048576)) MB/s"
    elif [ "$1" -ge 1024 ]; then
        echo "$(($1 / 1024)) KB/s"
    else
        echo "$1 B/s"
    fi
}

trap 'br overlay hide netspeed; exit 0' INT TERM
while true; do
    down=0
    up=0
    link=
    # br net は「名前=値」を 1 行ずつ返す
    while IFS='=' read -r key value; do
        case "$key" in
            rx_bytes_per_sec) down=$value ;;
            tx_bytes_per_sec) up=$value ;;
            wifi_link_mbps) link="  Wi-Fi ${value}Mbps" ;;
        esac
    done <<END
$(br net)
END
    br overlay set netspeed "↓ $(pretty "$down")  ↑ $(pretty "$up")$link" || exit 1
    sleep 1
done
