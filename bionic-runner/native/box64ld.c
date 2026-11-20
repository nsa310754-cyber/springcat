/*
 * box64ld: Box64 (libbox64.so) の「読み込み役 (ローダ)」の代わり。arm64 用。
 *
 * libbox64.so は glibc 用のプログラムなので、ふつうはアプリが
 *   libbrld.so --preload libbrshim.so --library-path <arm64 用 glibc> libbox64.so ...
 * のように起動する。ところが Box64 が自分自身をもう一度起動するとき (Wine が wineserver や
 * 別の Windows のプログラムを起動するときなど) は、libbox64.so を直接 execve する。すると
 * カーネルは、libbox64.so に書かれたローダの場所 (fetch-box64.sh が
 * /data/data/site.springcat.bionicrunner/files/box64/ld に書き換えてある) を読み込む。
 * その場所に置く (アプリがリンクを作る) のがこのプログラムで、上と同じ形で起動し直すだけをする。
 *
 * ローダとして読み込まれるので、C のライブラリ (bionic) には頼らずに書いてある
 * (自分で直す必要のある場所 (再配置) が無いように、文字列の表なども持たない)。
 * root のときは、アプリの起動スクリプトと同じく、補助ライブラリは付けず glibc のローダで起動する。
 *
 * ビルド: native/build.sh (Android NDK)
 */

#define FILES "/data/data/site.springcat.bionicrunner/files"
#define GLIBC FILES "/glibc/arm64-v8a/lib"

#define AT_NULL 0
#define AT_EXECFN 31

static long sys3(long n, long a, long b, long c)
{
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    __asm__ volatile("svc 0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2) : "memory");
    return x0;
}

static void say(const char *s)
{
    long n = 0;
    while (s[n]) n++;
    sys3(64, 2, (long)s, n); /* write(2, ...) */
}

static __attribute__((noreturn)) void quit(int code)
{
    for (;;) sys3(94, code, 0, 0); /* exit_group */
}

/* 文字列をつなげて buf に入れ、次の書き込み位置を返す */
static char *put(char *p, char *end, const char *s)
{
    while (*s && p < end - 1) *p++ = *s++;
    *p = 0;
    return p;
}

__attribute__((used)) void box64ld_main(long *sp)
{
    long argc = sp[0];
    char **argv = (char **)(sp + 1);
    char **envp = argv + argc + 1;
    char **e = envp;
    while (*e) e++;
    unsigned long *aux = (unsigned long *)(e + 1);
    const char *exe = 0;
    for (; aux[0] != AT_NULL; aux += 2) {
        if (aux[0] == AT_EXECFN) exe = (const char *)aux[1];
    }
    if (!exe || exe[0] != '/') {
        say("box64ld: 起動したプログラムの場所が分かりません\n");
        quit(127);
    }
    int root = sys3(174, 0, 0, 0) == 0; /* getuid */

    /* ローダの場所: root なら glibc のローダ、そうでなければ libbox64.so と同じフォルダの libbrld.so */
    char ld[512], shim[512];
    char *p = ld, *end = ld + sizeof ld;
    if (root) {
        put(p, end, GLIBC "/ld-linux-aarch64.so.1");
    } else {
        long slash = -1;
        for (long i = 0; exe[i]; i++) if (exe[i] == '/') slash = i;
        if (slash < 0 || slash >= (long)sizeof ld - 16) {
            say("box64ld: 場所が長すぎます\n");
            quit(127);
        }
        for (long i = 0; i <= slash; i++) *p++ = exe[i];
        *p = 0;
        put(p, end, "libbrld.so");
    }
    put(shim, shim + sizeof shim, GLIBC "/libbrshim.so");

    /* 新しい引数: ローダ [--preload 補助] --library-path glibc libbox64.so 元の引数 (1 番目から) */
    long extra = root ? 4 : 6;
    char *nargv[argc + extra + 1];
    long k = 0;
    nargv[k++] = ld;
    if (!root) {
        nargv[k++] = (char *)"--preload";
        nargv[k++] = shim;
    }
    nargv[k++] = (char *)"--library-path";
    nargv[k++] = (char *)GLIBC;
    nargv[k++] = (char *)exe;
    for (long i = 1; i < argc; i++) nargv[k++] = argv[i];
    nargv[k] = 0;
    sys3(221, (long)ld, (long)nargv, (long)envp); /* execve */
    say("box64ld: Box64 を起動し直せませんでした (");
    say(ld);
    say(")\n");
    quit(127);
}

/* 入口: スタックの先頭 (引数の数・引数・環境変数・補助情報) をそのまま渡す */
__asm__(
    ".text\n"
    ".global _start\n"
    "_start:\n"
    "  mov x0, sp\n"
    "  and sp, x0, #-16\n"
    "  bl box64ld_main\n"
    "  brk #0\n");
