/*
 * brcp932: Windows のコンソールのプログラムの出力を、端末で読める UTF-8 にそろえるフィルター。
 *   使い方: プログラム | libbrcp932.so <Wine の c_932.nls>
 *
 * 日本語版 Windows 用のプログラムは、日本語を Shift_JIS (コードページ 932) で出すことが多い。
 * 端末は UTF-8 なので、そのままでは文字化けする。そこで 1 行ごとに:
 *   - UTF-8 として正しい行 (英数字だけの行を含む) は、そのまま通す
 *   - そうでない行は Shift_JIS とみなして UTF-8 に直す
 * 変換表は Wine に入っている c_932.nls を読む (アプリに表を持たない)。読めなければ何も変えずに通す。
 * 入力の中身はどこにも保存・送信しない (読んで変換して書くだけ)。
 */
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static uint16_t *nls;          /* ファイルの中身 (16bit の並び) */
static size_t nls_words;
static const uint16_t *mb_table;     /* 1 バイトの文字の表 (256 個) */
static const uint16_t *dbcs_offsets; /* 2 バイトの文字の表 (先頭のバイトごとの位置) */
static uint16_t default_char = '?';

/* c_932.nls を読む (Windows の .nls と同じ形)。読めなければ 0 */
static int load_nls(const char *path)
{
    int fd = open(path, O_RDONLY);
    struct stat st;
    size_t got = 0;
    if (fd < 0) return 0;
    if (fstat(fd, &st) != 0 || st.st_size < 64 || st.st_size > 4 * 1024 * 1024) {
        close(fd);
        return 0;
    }
    nls = malloc((size_t)st.st_size);
    if (!nls) {
        close(fd);
        return 0;
    }
    while (got < (size_t)st.st_size) {
        ssize_t r = read(fd, (char *)nls + got, (size_t)st.st_size - got);
        if (r <= 0) break;
        got += (size_t)r;
    }
    close(fd);
    nls_words = got / 2;
    {
        size_t hdr = nls[0], p;
        if (hdr < 13 || nls[1] != 932 || hdr + 1 + 256 + 2 >= nls_words) return 0;
        default_char = nls[4];
        p = hdr + 1;                  /* 見出しの次の 1 語は、Unicode → 932 の表の位置 */
        mb_table = nls + p;
        p += 256;
        if (nls[p++]) p += 256;       /* 字形の表があれば飛ばす */
        if (p >= nls_words || !nls[p]) return 0; /* 2 バイトの文字の表が無い */
        dbcs_offsets = nls + p + 1;
        if (p + 1 + 256 > nls_words) return 0;
    }
    return 1;
}

static uint16_t dbcs_char(unsigned lead, unsigned trail)
{
    size_t off = dbcs_offsets[lead];
    size_t at = (size_t)(dbcs_offsets - nls) + off + trail;
    if (!off || at >= nls_words) return default_char;
    return nls[at];
}

static int is_lead(unsigned b)
{
    return dbcs_offsets && dbcs_offsets[b] != 0;
}

/* 出力用のバッファ */
static unsigned char out[65536 * 3 + 16];
static size_t out_len;

static void flush_out(void)
{
    size_t done = 0;
    while (done < out_len) {
        ssize_t w = write(1, out + done, out_len - done);
        if (w < 0) {
            if (errno == EINTR) continue;
            exit(1);
        }
        done += (size_t)w;
    }
    out_len = 0;
}

static void put_utf8(unsigned c)
{
    if (out_len + 4 > sizeof(out)) flush_out();
    if (c < 0x80) {
        out[out_len++] = (unsigned char)c;
    } else if (c < 0x800) {
        out[out_len++] = (unsigned char)(0xc0 | (c >> 6));
        out[out_len++] = (unsigned char)(0x80 | (c & 0x3f));
    } else {
        out[out_len++] = (unsigned char)(0xe0 | (c >> 12));
        out[out_len++] = (unsigned char)(0x80 | ((c >> 6) & 0x3f));
        out[out_len++] = (unsigned char)(0x80 | (c & 0x3f));
    }
}

static void put_raw(const unsigned char *s, size_t n)
{
    if (out_len + n > sizeof(out)) flush_out();
    if (n > sizeof(out)) {
        size_t done = 0;
        while (done < n) {
            ssize_t w = write(1, s + done, n - done);
            if (w < 0) {
                if (errno == EINTR) continue;
                exit(1);
            }
            done += (size_t)w;
        }
        return;
    }
    memcpy(out + out_len, s, n);
    out_len += n;
}

/*
 * s[0..n) が UTF-8 として正しいか。partial が真なら、末尾の途中で切れた文字は正しいものとみなし、
 * その長さを *cut に入れる (次の読み込みとつなげるため)
 */
static int valid_utf8(const unsigned char *s, size_t n, int partial, size_t *cut)
{
    size_t i = 0;
    *cut = 0;
    while (i < n) {
        unsigned c = s[i];
        size_t len, k;
        if (c < 0x80) {
            i++;
            continue;
        }
        if (c >= 0xc2 && c <= 0xdf) len = 2;
        else if (c >= 0xe0 && c <= 0xef) len = 3;
        else if (c >= 0xf0 && c <= 0xf4) len = 4;
        else return 0;
        for (k = 1; k < len; k++) {
            if (i + k >= n) {
                if (partial) {
                    *cut = n - i;
                    return 1;
                }
                return 0;
            }
            if ((s[i + k] & 0xc0) != 0x80) return 0;
        }
        i += len;
    }
    return 1;
}

/* Shift_JIS として直す。partial が真なら、末尾の先頭バイトだけの文字は残して、その長さを返す */
static size_t convert_sjis(const unsigned char *s, size_t n, int partial)
{
    size_t i = 0;
    while (i < n) {
        unsigned c = s[i];
        if (is_lead(c)) {
            if (i + 1 >= n) {
                if (partial) return 1;
                put_utf8(default_char);
                return 0;
            }
            put_utf8(dbcs_char(c, s[i + 1]));
            i += 2;
        } else {
            put_utf8(c < 0x80 ? c : mb_table[c]);
            i++;
        }
    }
    return 0;
}

/* 1 行 (または行の途中までのかたまり) を処理する。後回しにした末尾のバイト数を返す */
static size_t process(const unsigned char *s, size_t n, int partial)
{
    size_t cut;
    if (valid_utf8(s, n, partial, &cut)) {
        put_raw(s, n - cut);
        return cut;
    }
    return convert_sjis(s, n, partial);
}

int main(int argc, char **argv)
{
    static unsigned char buf[65536];
    size_t have = 0;
    int ok = argc > 1 && load_nls(argv[1]);
    for (;;) {
        ssize_t r = read(0, buf + have, sizeof(buf) - have);
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) break;
        if (!ok) {
            put_raw(buf, (size_t)r);
            flush_out();
            continue;
        }
        have += (size_t)r;
        {
            size_t start = 0, i, rest;
            for (i = 0; i < have; i++) {
                if (buf[i] == '\n') {
                    process(buf + start, i + 1 - start, 0);
                    start = i + 1;
                }
            }
            /* 改行の無い残り (入力を待つ表示など) もすぐに出す。途中で切れた文字だけは次に回す */
            rest = start < have ? process(buf + start, have - start, 1) : 0;
            memmove(buf, buf + have - rest, rest);
            have = rest;
        }
        flush_out();
    }
    if (have) process(buf, have, 0);
    flush_out();
    return 0;
}
