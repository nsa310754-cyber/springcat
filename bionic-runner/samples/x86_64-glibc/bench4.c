/*
 * bench4: Box64・QEMU・ネイティブの速さを比べるためのベンチマーク (1 つのプログラムで 3 種類を測る)。
 *
 *   1. 整数の計算とループ   … xorshift 乱数の足し合わせ・掛け算・ビット演算 (割り算なし)
 *   2. メモリの読み書き     … エラトステネスのふるい、大きな配列のコピーと足し合わせ
 *   3. libc の関数を呼ぶもの … memcpy・strcmp・qsort・sin/cos・snprintf
 *   (4. 起動の速さは bench.sh が、このプログラムを noop で 50 回起動して測る)
 *
 * 使い方:
 *   bench4            3 種類を測って表で出す (最後に「RESULT int=秒 mem=秒 libc=秒」の行も出す)
 *   bench4 0.5        計算の量を半分にする (遅い端末向け。2 なら倍)
 *   bench4 noop       何もせずに終わる (起動の速さを測るため)
 * 各項目の結果の値 (チェックサム) も出すので、どの方法で動かしても同じ計算をしたか確かめられる。
 * x86_64 用は gcc -O2 で、ネイティブ (arm64) 用は端末の tcc (mkexe) でビルドする。
 */
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

static double now(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec / 1e9;
}

/* 1. 整数の計算とループ (割り算なし) */
static uint64_t bench_int(double scale)
{
    uint64_t x = 88172645463325252ULL, sum = 0, mix = 0;
    long n = (long)(60000000 * scale);
    for (long i = 0; i < n; i++) {
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        sum += x & 0xffff;
        mix = (mix * 6364136223846793005ULL + x) ^ (mix >> 29);
    }
    return sum ^ mix;
}

/* 2. メモリの読み書き (ふるいと配列のコピー) */
static uint64_t bench_mem(double scale)
{
    long limit = (long)(12000000 * scale);
    unsigned char *sieve = calloc(limit + 1, 1);
    uint64_t primes = 0;
    if (!sieve) return 0;
    for (long i = 2; (long long)i * i <= limit; i++)
        if (!sieve[i])
            for (long j = (long)i * i; j <= limit; j += i) sieve[j] = 1;
    for (long i = 2; i <= limit; i++) primes += !sieve[i];
    free(sieve);

    long words = (long)(2000000 * scale);
    uint32_t *a = malloc(words * sizeof *a), *b = malloc(words * sizeof *b);
    uint64_t total = 0;
    if (!a || !b) return primes;
    for (long i = 0; i < words; i++) a[i] = (uint32_t)(i * 2654435761u);
    for (int pass = 0; pass < 8; pass++) {
        for (long i = 0; i < words; i++) b[i] = a[i] + pass;          /* 1 語ずつのコピー */
        for (long i = 0; i < words; i += 16) total += b[i];            /* とびとびの読み出し */
        uint32_t *t = a; a = b; b = t;
    }
    free(a);
    free(b);
    return primes * 1000003ULL + total;
}

static int cmp_u32(const void *p, const void *q)
{
    uint32_t a = *(const uint32_t *)p, b = *(const uint32_t *)q;
    return a < b ? -1 : a > b;
}

/* 3. libc の関数を多く呼ぶもの */
static uint64_t bench_libc(double scale)
{
    uint64_t acc = 0;
    char src[4096], dst[4096];
    for (int i = 0; i < 4096; i++) src[i] = (char)('a' + i % 26);
    long copies = (long)(200000 * scale);
    for (long i = 0; i < copies; i++) {
        src[i & 4095] = (char)i;
        memcpy(dst, src, sizeof dst);
        acc += (unsigned char)dst[(i * 7) & 4095];
    }

    char s1[64], s2[64];
    long cmps = (long)(1500000 * scale);
    for (long i = 0; i < cmps; i++) {
        snprintf(s1, sizeof s1, "item-%08ld", i);
        snprintf(s2, sizeof s2, "item-%08ld", i ^ 1);
        acc += strcmp(s1, s2) < 0;
    }

    long count = (long)(400000 * scale);
    uint32_t *arr = malloc(count * sizeof *arr), x = 2463534242u;
    if (arr) {
        for (long i = 0; i < count; i++) {
            x ^= x << 13; x ^= x >> 17; x ^= x << 5;
            arr[i] = x;
        }
        qsort(arr, count, sizeof *arr, cmp_u32);
        acc += arr[0] + arr[count / 2] + arr[count - 1];
        free(arr);
    }

    double f = 0;
    long trig = (long)(2000000 * scale);
    for (long i = 0; i < trig; i++) f += sin(i * 0.001) * cos(i * 0.0007);
    acc += (uint64_t)(int64_t)(f * 1000);
    return acc;
}

int main(int argc, char **argv)
{
    if (argc > 1 && strcmp(argv[1], "noop") == 0) return 0;
    double scale = argc > 1 ? atof(argv[1]) : 1.0;
    if (scale <= 0) scale = 1.0;

    double t0 = now();
    uint64_t r1 = bench_int(scale);
    double t1 = now();
    uint64_t r2 = bench_mem(scale);
    double t2 = now();
    uint64_t r3 = bench_libc(scale);
    double t3 = now();

    printf("%-24s %10s  %s\n", "項目", "秒", "結果の値 (同じなら同じ計算)");
    printf("%-24s %10.3f  %016llx\n", "1. 整数の計算とループ", t1 - t0, (unsigned long long)r1);
    printf("%-24s %10.3f  %016llx\n", "2. メモリの読み書き", t2 - t1, (unsigned long long)r2);
    printf("%-24s %10.3f  %016llx\n", "3. libc の関数", t3 - t2, (unsigned long long)r3);
    printf("RESULT int=%.3f mem=%.3f libc=%.3f sum=%016llx\n", t1 - t0, t2 - t1, t3 - t2,
           (unsigned long long)(r1 ^ r2 ^ r3));
    return 0;
}
