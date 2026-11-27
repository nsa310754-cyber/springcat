// Bionic Runner の YARA 対応を試すための、害の無いサンプルルール。
// 「実行ファイル」からこのファイルを選ぶと、検知への追加か、その場でのスキャンを選べる。

rule BR_Sample_Marker
{
    meta:
        description = "テスト用の目印の文字列を含むファイル"
    strings:
        $marker = "BIONIC-RUNNER-YARA-TEST"
    condition:
        $marker
}

rule BR_Sample_ELF_arm64
{
    meta:
        description = "arm64 用の ELF 実行ファイル (elf モジュールの動作確認)"
    condition:
        uint32(0) == 0x464c457f and uint16(18) == 0xb7
}
