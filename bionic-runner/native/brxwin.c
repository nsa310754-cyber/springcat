/*
 * brxwin: X サーバーに、新しいウィンドウが出るまで待つ小さな道具。
 *   使い方: libbrxwin.so <X の接続口 (UNIX ソケットの場所)> <見張るプロセス番号> [待つ秒数]
 *
 * 起動したときに画面に出ている (地図に載っている = mapped) 一番上のウィンドウを覚えておき、
 * それ以外の、普通のウィンドウ (メニューや説明の小窓のような override-redirect でないもの) が
 * 新しく出たら、0 を返して終わる。見張るプロセスが終わったら 1、時間切れなら 2、つながらなければ 3。
 *
 * wine.sh が、Windows のプログラムのウィンドウが本当に出てから X11 の画面に切り替えるために使う
 * (それまではコマンドプロンプトに経過やエラーを出したままにする)。
 * X11 の決まり (プロトコル) のうち、接続・QueryTree・GetWindowAttributes だけを自分で話す
 * (ライブラリを使わない)。画面の中身は読まない (ウィンドウがあるかどうかだけを見る)。
 */
#include <errno.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define MAX_WIN 1024

static int fd = -1;

static int read_all(void *buf, size_t n)
{
    size_t got = 0;
    while (got < n) {
        ssize_t r = read(fd, (char *)buf + got, n - got);
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) return -1;
        got += (size_t)r;
    }
    return 0;
}

static int write_all(const void *buf, size_t n)
{
    size_t done = 0;
    while (done < n) {
        ssize_t w = write(fd, (const char *)buf + done, n - done);
        if (w < 0 && errno == EINTR) continue;
        if (w <= 0) return -1;
        done += (size_t)w;
    }
    return 0;
}

static uint16_t u16(const unsigned char *p) { return (uint16_t)(p[0] | p[1] << 8); }
static uint32_t u32(const unsigned char *p) { return p[0] | p[1] << 8 | p[2] << 16 | (uint32_t)p[3] << 24; }

/* UNIX ソケットでつなぐ。path が使えなければ、抽象名前空間の同じ名前も試す (proot の中の Xvnc 用) */
static int connect_x(const char *path, const char *abstract_name)
{
    struct sockaddr_un a;
    int s = socket(AF_UNIX, SOCK_STREAM, 0);
    if (s < 0) return -1;
    memset(&a, 0, sizeof(a));
    a.sun_family = AF_UNIX;
    strncpy(a.sun_path, path, sizeof(a.sun_path) - 1);
    if (connect(s, (struct sockaddr *)&a, sizeof(a)) == 0) return s;
    memset(&a, 0, sizeof(a));
    a.sun_family = AF_UNIX;
    strncpy(a.sun_path + 1, abstract_name, sizeof(a.sun_path) - 2);
    if (connect(s, (struct sockaddr *)&a,
                (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + strlen(abstract_name))) == 0) {
        return s;
    }
    close(s);
    return -1;
}

/* 返事を 1 つ読む (エラーなら -1)。本体は buf (32 バイト + 追加分) に入る */
static unsigned char reply[32 + 4 * 65536];

static int read_reply(void)
{
    for (;;) {
        if (read_all(reply, 32) < 0) return -1;
        if (reply[0] == 1) {
            uint32_t extra = u32(reply + 4) * 4;
            if (extra > sizeof(reply) - 32) return -1;
            if (extra && read_all(reply + 32, extra) < 0) return -1;
            return 0;
        }
        if (reply[0] == 0) return -1; /* エラー (ウィンドウが消えたなど) */
        /* イベントは選んでいないが、来たら読み捨てる */
    }
}

/* 一番上のウィンドウのうち、出ていて普通のもの (override-redirect でない) を集める */
static int list_windows(uint32_t root, uint32_t *out, int max)
{
    unsigned char q[8];
    uint32_t kids[MAX_WIN];
    int n, i, count = 0;
    q[0] = 15; /* QueryTree */
    q[1] = 0;
    q[2] = 2;
    q[3] = 0;
    memcpy(q + 4, &root, 4);
    if (write_all(q, 8) < 0 || read_reply() < 0) return -1;
    n = u16(reply + 16);
    if (n > MAX_WIN) n = MAX_WIN;
    for (i = 0; i < n; i++) kids[i] = u32(reply + 32 + 4 * i);
    for (i = 0; i < n && count < max; i++) {
        unsigned char g[8];
        g[0] = 3; /* GetWindowAttributes */
        g[1] = 0;
        g[2] = 2;
        g[3] = 0;
        memcpy(g + 4, &kids[i], 4);
        if (write_all(g, 8) < 0) return -1;
        if (read_reply() < 0) continue; /* その間に消えたウィンドウ */
        /* reply[26] = map-state (2 = 出ている)、reply[27] = override-redirect */
        if (reply[26] == 2 && reply[27] == 0) out[count++] = kids[i];
    }
    return count;
}

int main(int argc, char **argv)
{
    unsigned char setup[12] = {'l', 0, 11, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    unsigned char head[8];
    uint32_t root, base[MAX_WIN], now[MAX_WIN];
    int nbase, i, j, vendor_len, formats;
    pid_t watch;
    long limit;
    time_t start = time(NULL);
    unsigned char *d;
    size_t extra;

    if (argc < 3) return 3;
    watch = (pid_t)atoi(argv[2]);
    limit = argc > 3 ? atol(argv[3]) : 0;
    signal(SIGPIPE, SIG_IGN);
    fd = connect_x(argv[1], "/tmp/.X11-unix/X1");
    if (fd < 0) return 3;
    if (write_all(setup, 12) < 0 || read_all(head, 8) < 0 || head[0] != 1) return 3;
    extra = (size_t)u16(head + 6) * 4;
    d = malloc(extra);
    if (!d || read_all(d, extra) < 0 || extra < 32) return 3;
    vendor_len = u16(d + 16);
    formats = d[21];
    /* 最初の画面の root ウィンドウ: 32 バイトの見出し + 製造元の名前 (4 の倍数に詰める) + 形式 (8 バイトずつ) の後ろ */
    {
        size_t at = 32 + (size_t)((vendor_len + 3) & ~3) + (size_t)formats * 8;
        if (at + 4 > extra) return 3;
        root = u32(d + at);
    }
    free(d);

    nbase = list_windows(root, base, MAX_WIN);
    if (nbase < 0) return 3;
    for (;;) {
        int n;
        struct timespec ts = {0, 400 * 1000 * 1000};
        nanosleep(&ts, NULL);
        if (watch > 0 && kill(watch, 0) != 0 && errno == ESRCH) return 1;
        if (limit > 0 && time(NULL) - start > limit) return 2;
        n = list_windows(root, now, MAX_WIN);
        if (n < 0) return 3;
        for (i = 0; i < n; i++) {
            int known = 0;
            for (j = 0; j < nbase; j++) {
                if (base[j] == now[i]) {
                    known = 1;
                    break;
                }
            }
            if (!known) return 0;
        }
    }
}
