/*
 * Windows (64bit) のコンソール用の小さなプログラム。Bionic Runner の Wine で動かす確認用。
 * 引数・環境・ファイルの読み書き (D: ドライブ = 共有フォルダ) を一通り使う。
 */
#include <stdio.h>
#include <windows.h>

int main(int argc, char **argv)
{
    OSVERSIONINFOA v = {sizeof v};
    SYSTEM_INFO si;
    char dir[MAX_PATH];
    GetVersionExA(&v);
    GetNativeSystemInfo(&si);
    GetCurrentDirectoryA(sizeof dir, dir);
    printf("Hello from Windows! (Wine の上で動いています)\n");
    printf("Windows %lu.%lu  CPU の数 %lu  64bit=%s\n", v.dwMajorVersion, v.dwMinorVersion,
           si.dwNumberOfProcessors, si.wProcessorArchitecture == PROCESSOR_ARCHITECTURE_AMD64 ? "yes" : "no");
    printf("今いる場所: %s\n", dir);
    for (int i = 1; i < argc; i++) printf("引数 %d: %s\n", i, argv[i]);

    FILE *f = fopen("D:\\hello-from-windows.txt", "w");
    if (f) {
        fprintf(f, "Windows のプログラムが書いたファイルです\n");
        fclose(f);
        printf("D:\\hello-from-windows.txt (共有フォルダ) に書きました\n");
    } else {
        printf("D: に書けませんでした\n");
    }
    return 0;
}
