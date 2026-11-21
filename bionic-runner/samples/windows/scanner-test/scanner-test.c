/*
 * 構造の検査 (ExeScanner.scanPe) を確かめるための、害の無いプログラム。
 *
 * 暗号化して中身を隠す「ローダー型」のウイルスと同じ特徴だけを持たせてある:
 *   1. 自分自身のファイルを読み (GetModuleFileName + CreateFile + ReadFile)、
 *      末尾に付け足したデータ (オーバーレイ) から目印を探す
 *   2. VirtualAlloc で「書き込みも実行もできる」メモリを取る
 *   3. XOR で隠したバイト列を元に戻してそこへ書き、呼び出す
 * ただし、隠してある命令は「ret」(何もせずに戻る) の 1 バイトだけで、ほかには何もしない。
 * ファイルを消したり、通信したり、ほかのプログラムに触ったりはしない。
 *
 * ビルドのしかたは build.sh (末尾のデータもそこで付ける)。
 */
#include <stdio.h>
#include <string.h>
#include <windows.h>

#define KEY 0x5a
/* ret (0xc3) を XOR で隠したもの */
static const unsigned char hidden[] = {0xc3 ^ KEY};
static const char marker[] = "BIONIC-RUNNER-SCANNER-TEST-OVERLAY";

int main(void)
{
    char path[MAX_PATH];
    unsigned char tail[8192];
    DWORD got = 0;
    int found = 0;

    /* 1. 自分のファイルの末尾を読み、目印を探す */
    GetModuleFileNameA(NULL, path, sizeof path);
    HANDLE f = CreateFileA(path, GENERIC_READ, FILE_SHARE_READ, NULL, OPEN_EXISTING, 0, NULL);
    if (f != INVALID_HANDLE_VALUE) {
        LARGE_INTEGER size;
        if (GetFileSizeEx(f, &size) && size.QuadPart > (LONGLONG)sizeof tail) {
            LARGE_INTEGER pos;
            pos.QuadPart = size.QuadPart - sizeof tail;
            SetFilePointerEx(f, pos, NULL, FILE_BEGIN);
            ReadFile(f, tail, sizeof tail, &got, NULL);
            for (DWORD i = 0; i + sizeof marker - 1 <= got; i++)
                if (memcmp(tail + i, marker, sizeof marker - 1) == 0) found = 1;
        }
        CloseHandle(f);
    }

    /* 2. 書き込みも実行もできるメモリを取る */
    unsigned char *mem = VirtualAlloc(NULL, 4096, MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE);
    if (!mem) {
        printf("メモリを取れませんでした\n");
        return 1;
    }
    /* 3. 隠したバイト列を元に戻して呼び出す (中身は ret だけ) */
    for (size_t i = 0; i < sizeof hidden; i++) mem[i] = hidden[i] ^ KEY;
    ((void (*)(void))mem)();
    VirtualFree(mem, 0, MEM_RELEASE);

    printf("確認用のプログラム: 末尾の目印 %s、隠した命令 (ret だけ) を動かして戻りました\n",
           found ? "あり" : "なし");
    return 0;
}
