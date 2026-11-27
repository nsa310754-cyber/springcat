/* talloc が使う memset_explicit (Android では API 34 から)。最適化で消されない書き方で 0 を書く */
#include <stddef.h>
void *rep_memset_explicit(void *p, int c, size_t n) {
    volatile unsigned char *q = p;
    while (n--) *q++ = (unsigned char) c;
    return p;
}
