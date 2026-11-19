#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <pthread.h>
#include <setjmp.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <inttypes.h>
#include <errno.h>
#include <unistd.h>
#include <sys/stat.h>
#include <stdatomic.h>
#include <arpa/inet.h>
#include <time.h>

struct item { char name[16]; double score; long long id; };
static int cmp(const void *a, const void *b) {
    const struct item *x = a, *y = b;
    return x->score < y->score ? -1 : x->score > y->score;
}
static atomic_int counter;
static void *worker(void *arg) {
    int n = *(int *)arg, i;
    for (i = 0; i < n; i++) atomic_fetch_add(&counter, 1);
    return (void *)(intptr_t)(n * 2);
}
static char *fmt(const char *f, ...) {
    static char buf[256]; va_list ap; va_start(ap, f); vsnprintf(buf, sizeof buf, f, ap); va_end(ap); return buf;
}
static jmp_buf jb;
static volatile sig_atomic_t got;
static void onsig(int s) { got = s; }
int main(int argc, char **argv) {
    struct item it[5] = {{"e", 2.5, 5}, {"b", -1.25, 2}, {"a", 3.75, 1}, {"d", 0.5, 4}, {"c", 1e10, 3}};
    pthread_t th[4]; int n[4] = {1000, 2000, 3000, 4000}, i; void *ret; long sum = 0;
    FILE *f; char line[64]; struct stat st;
    qsort(it, 5, sizeof it[0], cmp);
    for (i = 0; i < 5; i++) printf("%s %.3f %lld\n", it[i].name, it[i].score, it[i].id);
    for (i = 0; i < 4; i++) pthread_create(&th[i], NULL, worker, &n[i]);
    for (i = 0; i < 4; i++) { pthread_join(th[i], &ret); sum += (long)(intptr_t)ret; }
    printf("threads: counter=%d sum=%ld\n", atomic_load(&counter), sum);
    printf("math: %.6f %.6f %.6f %g\n", sin(1.0), pow(2, 0.5), log10(12345.0), floor(-2.5));
    printf("fmt: %s\n", fmt("%05d|%-4s|%x|%" PRIu64, 42, "ab", 255, (uint64_t)1 << 40));
    printf("strtod: %.4f strtol: %ld\n", strtod("3.14159abc", NULL), strtol("-0x1f", NULL, 16));
    if (setjmp(jb) == 0) { printf("setjmp ok\n"); longjmp(jb, 7); } else printf("longjmp back\n");
    signal(SIGUSR1, onsig); raise(SIGUSR1); printf("signal: %d\n", got == SIGUSR1);
    f = fopen("mkexe-test.txt", "w"); fprintf(f, "hello file\n"); fclose(f);
    f = fopen("mkexe-test.txt", "r"); fgets(line, sizeof line, f); fclose(f);
    stat("mkexe-test.txt", &st); unlink("mkexe-test.txt");
    printf("file: %s size=%ld\n", strtok(line, "\n"), (long)st.st_size);
    errno = 0; fopen("/nonexistent/x", "r"); printf("errno: %s\n", strerror(errno));
    printf("htons: %04x htonl: %08x\n", htons(0x1234), htonl(0x11223344));
    { char *p = malloc(1 << 20); memset(p, 'z', 1 << 20); printf("malloc: %c\n", p[(1 << 20) - 1]); free(p); }
    printf("long double: %d\n", (int)sizeof(long double));
    printf("argc=%d time>0:%d\n", argc, time(NULL) > 0);
    return 3;
}
