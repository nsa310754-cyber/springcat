/*
 * 日本語の表示の確認用 (コマンドプロンプト用)。Bionic Runner の Wine で動かす。
 *
 * 次の 3 とおりの書き方で同じ日本語を出す。どれも「こんにちは、世界」と読めれば正しい。
 *   1. UTF-8 のバイト列をそのまま書く (Linux 風のプログラム・新しいプログラム)
 *   2. Shift_JIS のバイト列をそのまま書く (日本語版 Windows 用の古いプログラムによくある)
 *   3. Unicode (ワイド文字) の文字列を、Windows の文字コードの設定 (ANSI コードページ) で変換して書く
 * 最後に Windows の文字コードの設定 (ACP・コンソールのコードページ) も出す。
 * ファイルの中身はすべて ASCII で書いてある (コンパイラーの文字コードの設定に左右されないように)。
 */
#include <windows.h>
#include <string.h>

static HANDLE out;

static void put(const char *s, DWORD n)
{
    DWORD done;
    WriteFile(out, s, n, &done, NULL);
}

static void line(const char *s)
{
    put(s, (DWORD)strlen(s));
}

int main(void)
{
    /* "こんにちは、世界" */
    static const char utf8[] =
        "\xe3\x81\x93\xe3\x82\x93\xe3\x81\xab\xe3\x81\xa1\xe3\x81\xaf\xe3\x80\x81\xe4\xb8\x96\xe7\x95\x8c";
    static const char sjis[] =
        "\x82\xb1\x82\xf1\x82\xc9\x82\xbf\x82\xcd\x81\x41\x90\xa2\x8a\x45";
    static const WCHAR wide[] = {0x3053, 0x3093, 0x306b, 0x3061, 0x306f, 0x3001, 0x4e16, 0x754c, 0};
    char buf[256];
    int n;

    out = GetStdHandle(STD_OUTPUT_HANDLE);

    line("1. UTF-8     : ");
    line(utf8);
    line("\r\n");

    line("2. Shift_JIS : ");
    line(sjis);
    line("\r\n");

    line("3. Unicode   : ");
    n = WideCharToMultiByte(CP_ACP, 0, wide, -1, buf, sizeof(buf), NULL, NULL);
    if (n > 1) put(buf, (DWORD)(n - 1));
    line("\r\n");

    wsprintfA(buf, "ACP=%u  ConsoleOutputCP=%u\r\n", GetACP(), GetConsoleOutputCP());
    line(buf);
    return 0;
}
