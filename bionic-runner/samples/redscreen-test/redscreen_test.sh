#!/system/bin/sh
# redscreen_test.sh : Bionic Runner の検知機能 (赤い警告画面) の動作確認用プログラム。
#
# このプログラムは無害です。ファイルの作成・変更・削除、通信、設定の変更などは一切しません。
# 説明と端末の情報を表示するだけです。
#
# 警告画面が出るのは、下の TEST_STRING に「EICAR テスト文字列」が入っているためです。
# EICAR テスト文字列は、ウイルス対策ソフトが正しく反応するかを確かめるために業界で決められた、
# ただの文字の並びです。プログラムとしての働きは持たず、実行しても何も起きません。
# この変数は表示に使うだけで、実行はしません。
TEST_STRING='X5O!P%@AP[4\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*'

explain() {
    echo ""
    echo "【このプログラムについて】"
    echo "Bionic Runner の検知機能が正しく働くかを確かめるためのものです。"
    echo "ファイルの中に EICAR テスト文字列 (${#TEST_STRING} 文字) が入っているので、"
    echo "開いたときに赤い警告画面が出ます。"
    echo "EICAR テスト文字列は、ウイルス対策ソフトの確認用に決められた、ただの文字の並びで、"
    echo "このプログラムはそれを表示に使うだけです。スマホには何もしません。"
    echo ""
    echo "警告画面で「詳細」を押すと、検知の理由が"
    echo "「ウイルス検査用のテスト文字列 (EICAR)」と表示されていたはずです。"
    echo ""
}

device_info() {
    echo ""
    echo "【この端末の情報】 (読み取るだけで、何も変更しません)"
    echo "機種    : $(getprop ro.product.model 2>/dev/null)"
    echo "Android : $(getprop ro.build.version.release 2>/dev/null)"
    echo "CPU     : $(getprop ro.product.cpu.abi 2>/dev/null)"
    echo "利用者  : $(id -un 2>/dev/null) (uid $(id -u 2>/dev/null))"
    echo "作業場所: $HOME"
    echo ""
}

echo "========================================"
echo "  検知テスト用プログラム (無害)"
echo "========================================"
echo "赤い警告画面のあとに「それでも実行」を押したので、ここが表示されています。"
echo "このプログラムはスマホに何もしません。"
echo ""

while :; do
    echo "番号を入力してください  1: 説明 / 2: 端末の情報 / q: 終了"
    if ! read -r ans; then
        break
    fi
    case "$ans" in
        1) explain ;;
        2) device_info ;;
        q|Q) break ;;
        "") ;;
        *) echo "1・2・q のどれかを入力してください" ;;
    esac
done
echo "終了しました。"
