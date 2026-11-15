/* Bionic Runner の画面・音声サーバとの接続。説明は SDL_brconn.h を参照 */
#include "../../SDL_internal.h"

#if defined(SDL_VIDEO_DRIVER_DUMMY) || defined(SDL_AUDIO_DRIVER_DUMMY)

#include <arpa/inet.h>
#include <errno.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <pthread.h>
#include <sys/socket.h>
#include <unistd.h>

#include "SDL_endian.h"
#include "SDL_stdinc.h"
#include "SDL_brconn.h"

/* 映像 (メインスレッド) と音声 (音声スレッド) が同じ接続へ書くので、送信は排他する */
static pthread_mutex_t br_lock = PTHREAD_MUTEX_INITIALIZER;
static int br_fd = -1;
static SDL_bool br_broken = SDL_FALSE;

static Uint8 br_inbuf[16];
static size_t br_inlen = 0;

SDL_bool BR_Available(void)
{
    const char *env = SDL_getenv("BR_DISPLAY");
    return (env && *env) ? SDL_TRUE : SDL_FALSE;
}

static int send_all(const void *data, size_t len)
{
    const Uint8 *p = (const Uint8 *)data;
    while (len > 0) {
        ssize_t n = send(br_fd, p, len, MSG_NOSIGNAL);
        if (n < 0) {
            if (errno == EINTR) {
                continue;
            }
            return -1;
        }
        p += n;
        len -= (size_t)n;
    }
    return 0;
}

/* br_lock を持った状態で呼ぶ */
static int send_locked(Uint32 type, const void *a, size_t alen, const void *b, size_t blen)
{
    Uint32 header[2];
    if (br_fd < 0 || br_broken) {
        return -1;
    }
    header[0] = SDL_SwapLE32(type);
    header[1] = SDL_SwapLE32((Uint32)(alen + blen));
    if (send_all(header, sizeof(header)) < 0 ||
        (alen && send_all(a, alen) < 0) ||
        (blen && send_all(b, blen) < 0)) {
        br_broken = SDL_TRUE;
        return -1;
    }
    return 0;
}

int BR_Connect(void)
{
    char spec[256];
    char *port, *token;
    const char *env;
    struct sockaddr_in addr;
    int fd, one = 1, result = -1;

    pthread_mutex_lock(&br_lock);
    if (br_fd >= 0) {
        result = br_broken ? -1 : 0;
        goto done;
    }
    env = SDL_getenv("BR_DISPLAY");
    if (!env || SDL_strlen(env) >= sizeof(spec)) {
        goto done;
    }
    SDL_strlcpy(spec, env, sizeof(spec));
    port = SDL_strchr(spec, ':');
    token = port ? SDL_strchr(port + 1, ':') : NULL;
    if (!port || !token) {
        goto done;
    }
    *port++ = '\0';
    *token++ = '\0';

    SDL_zero(addr);
    addr.sin_family = AF_INET;
    addr.sin_port = htons((Uint16)SDL_atoi(port));
    if (inet_pton(AF_INET, spec, &addr.sin_addr) != 1) {
        goto done;
    }
    fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        goto done;
    }
    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        close(fd);
        goto done;
    }
    /* 入力イベントや音声の小さな送信が遅れないようにする */
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    br_fd = fd;
    br_broken = SDL_FALSE;
    result = send_locked(BR_MSG_HELLO, token, SDL_strlen(token), NULL, 0);

done:
    pthread_mutex_unlock(&br_lock);
    return result;
}

int BR_Send(Uint32 type, const void *a, size_t alen, const void *b, size_t blen)
{
    int result;
    pthread_mutex_lock(&br_lock);
    result = send_locked(type, a, alen, b, blen);
    pthread_mutex_unlock(&br_lock);
    return result;
}

int BR_PollEvent(Sint32 ev[4])
{
    int i;
    if (br_fd < 0 || br_broken) {
        return -1;
    }
    while (br_inlen < sizeof(br_inbuf)) {
        ssize_t n = recv(br_fd, br_inbuf + br_inlen, sizeof(br_inbuf) - br_inlen, MSG_DONTWAIT);
        if (n > 0) {
            br_inlen += (size_t)n;
        } else if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            return 0; /* まだ 1 件ぶん届いていない */
        } else if (n < 0 && errno == EINTR) {
            continue;
        } else {
            br_broken = SDL_TRUE; /* アプリ側が閉じた */
            return -1;
        }
    }
    br_inlen = 0;
    for (i = 0; i < 4; i++) {
        Uint32 v;
        SDL_memcpy(&v, br_inbuf + i * 4, 4);
        ev[i] = (Sint32)SDL_SwapLE32(v);
    }
    return 1;
}

#endif
