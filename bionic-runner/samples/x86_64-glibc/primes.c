/*
 * 素数を数える (x86_64 の Linux 用、glibc を動的リンク)。
 * Box64 と QEMU の速さを比べるためのサンプル。引数は上限 (省略時は 3000000)。
 */
#include <stdio.h>
#include <stdlib.h>
#include <gnu/libc-version.h>

static int is_prime(unsigned n)
{
    if (n < 2) return 0;
    if (n % 2 == 0) return n == 2;
    for (unsigned d = 3; d * d <= n; d += 2)
        if (n % d == 0) return 0;
    return 1;
}

int main(int argc, char **argv)
{
    unsigned limit = argc > 1 ? (unsigned)strtoul(argv[1], NULL, 10) : 3000000u;
    unsigned count = 0;
    for (unsigned n = 0; n <= limit; n++)
        count += is_prime(n);
    printf("%u までの素数: %u 個 (x86_64, glibc %s)\n", limit, count, gnu_get_libc_version());
    return 0;
}
