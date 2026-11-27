/*
 * Neon Drift — Bionic Runner 用のサンプルゲーム (Android の bionic libc 用 ELF)。
 *
 * 洞窟の中を進む光の機体を、押している間だけ上昇・離すと落下、の 1 ボタンで操る。
 * 壁と柱に触れたら終わり。光の玉を集めると得点。進むほど速く、狭くなる。
 *
 * SDL などのライブラリは使わず、Bionic Runner の画面・音声サーバ (環境変数 BR_DISPLAY) に
 * 直接つないで、絵 (320x180 の画素) と音 (22050Hz の PCM) を送り、タッチとキー入力を受け取る。
 * 通信の決まりは native/sdl/SDL_brconn.h と同じ。すべてリトルエンディアン。
 *   プログラム → アプリ: [u32 種別][u32 長さ][本体]
 *   アプリ → プログラム: [u32 種別][i32 a][i32 b][i32 c] の 16 バイト固定
 *
 * 画像・音・文字はすべてプログラムの中で作るので、ファイル 1 個で動く。
 * Android 用 (bionic) に作ってあるので、root が無くても動く。
 */
#include <errno.h>
#include <math.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <time.h>
#include <unistd.h>

#define W 320
#define H 180
#define FPS 60
#define RATE 22050

/* 通信の種別 */
enum { MSG_HELLO = 1, MSG_MODE = 2, MSG_FRAME = 3, MSG_AUDIO_OPEN = 4, MSG_AUDIO = 5 };
enum { EV_KEY = 1, EV_MOUSE_MOTION = 2, EV_MOUSE_BUTTON = 3, EV_QUIT = 4, EV_TEXT = 5 };
/* SDL のスキャンコード (アプリの画面上のボタンが送るもの) */
enum { SC_Z = 29, SC_X = 27, SC_ENTER = 40, SC_ESC = 41, SC_SPACE = 44, SC_UP = 82 };

static uint32_t fb[W * H];
static int sock = -1;

/* ------------------------------------------------------------------ 乱数 */

static uint32_t rng = 0x9E3779B9u;

static uint32_t rnd(void)
{
    rng ^= rng << 13;
    rng ^= rng >> 17;
    rng ^= rng << 5;
    return rng;
}

static float frand(float lo, float hi)
{
    return lo + (hi - lo) * (float)(rnd() & 0xFFFFFF) / (float)0xFFFFFF;
}

/* ------------------------------------------------------------------ 通信 */

static int send_all(const void *data, size_t len)
{
    const uint8_t *p = data;
    while (len > 0) {
        ssize_t n = send(sock, p, len, MSG_NOSIGNAL);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        p += n;
        len -= (size_t)n;
    }
    return 0;
}

static void le32(uint8_t *p, uint32_t v)
{
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
    p[2] = (uint8_t)(v >> 16);
    p[3] = (uint8_t)(v >> 24);
}

/* メッセージを 1 つ送る。本体は head と body の 2 つに分けて渡せる */
static int send_msg(uint32_t type, const void *head, size_t hlen, const void *body, size_t blen)
{
    uint8_t h[8];
    le32(h, type);
    le32(h + 4, (uint32_t)(hlen + blen));
    if (send_all(h, 8) < 0) return -1;
    if (hlen && send_all(head, hlen) < 0) return -1;
    if (blen && send_all(body, blen) < 0) return -1;
    return 0;
}

static int connect_display(void)
{
    const char *env = getenv("BR_DISPLAY");
    char spec[256];
    if (!env || !*env || strlen(env) >= sizeof(spec)) return -1;
    strcpy(spec, env);
    char *port = strchr(spec, ':');
    char *token = port ? strchr(port + 1, ':') : NULL;
    if (!port || !token) return -1;
    *port++ = '\0';
    *token++ = '\0';

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((uint16_t)atoi(port));
    if (inet_pton(AF_INET, spec, &addr.sin_addr) != 1) return -1;
    sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) return -1;
    if (connect(sock, (struct sockaddr *)&addr, sizeof(addr)) != 0) return -1;
    int one = 1;
    setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));

    if (send_msg(MSG_HELLO, token, strlen(token), NULL, 0) < 0) return -1;
    const char *title = "Neon Drift";
    uint8_t mode[8];
    le32(mode, W);
    le32(mode + 4, H);
    if (send_msg(MSG_MODE, mode, 8, title, strlen(title)) < 0) return -1;
    uint8_t ao[8];
    le32(ao, RATE);
    le32(ao + 4, 1);
    if (send_msg(MSG_AUDIO_OPEN, ao, 8, NULL, 0) < 0) return -1;
    return 0;
}

static uint8_t inbuf[16];
static size_t inlen;

/* 届いている入力を 1 つ取り出す。あれば 1、無ければ 0、接続が切れたら -1 */
static int poll_event(int32_t ev[4])
{
    while (inlen < 16) {
        ssize_t n = recv(sock, inbuf + inlen, 16 - inlen, MSG_DONTWAIT);
        if (n > 0) {
            inlen += (size_t)n;
        } else if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            return 0;
        } else if (n < 0 && errno == EINTR) {
            continue;
        } else {
            return -1;
        }
    }
    inlen = 0;
    for (int i = 0; i < 4; i++) {
        ev[i] = (int32_t)((uint32_t)inbuf[i * 4] | (uint32_t)inbuf[i * 4 + 1] << 8
                          | (uint32_t)inbuf[i * 4 + 2] << 16 | (uint32_t)inbuf[i * 4 + 3] << 24);
    }
    return 1;
}

static int send_frame(void)
{
    uint8_t head[8];
    le32(head, W);
    le32(head + 4, H);
    /* 画素は 1 つ 4 バイト (R, G, B, 未使用)。fb の uint32_t はリトルエンディアンでその並びになる */
    return send_msg(MSG_FRAME, head, 8, fb, sizeof(fb));
}

/* ------------------------------------------------------------------ 音 */

typedef struct {
    int on;
    int noise;      /* 1 = 雑音、0 = 矩形波 */
    float freq, slide; /* 周波数 (Hz) と、1 秒あたりの変化の倍率 */
    float vol, decay;  /* 音量 (0..1) と、1 秒あたりの減衰の倍率 */
    float phase;
    float left;        /* 残り時間 (秒) */
} Voice;

static Voice voices[8];
static float thrust_vol; /* 上昇中に鳴らす低い噴射音の音量 */
static float thrust_phase;
static uint32_t noise_state = 1;

static void play(int noise, float freq, float slide, float vol, float decay, float len)
{
    for (int i = 0; i < 8; i++) {
        if (!voices[i].on) {
            voices[i] = (Voice){1, noise, freq, slide, vol, decay, 0, len};
            return;
        }
    }
}

static float noise_sample(void)
{
    noise_state ^= noise_state << 13;
    noise_state ^= noise_state >> 17;
    noise_state ^= noise_state << 5;
    return (float)(noise_state & 0xFFFF) / 32768.0f - 1.0f;
}

/* n サンプルぶんの音を作って送る */
static void send_audio(int n)
{
    static int16_t buf[RATE / 10];
    if (n <= 0) return;
    if (n > (int)(sizeof(buf) / sizeof(buf[0]))) n = sizeof(buf) / sizeof(buf[0]);
    const float dt = 1.0f / RATE;
    for (int s = 0; s < n; s++) {
        float mix = 0;
        for (int i = 0; i < 8; i++) {
            Voice *v = &voices[i];
            if (!v->on) continue;
            float x;
            if (v->noise) {
                x = noise_sample();
            } else {
                v->phase += v->freq * dt;
                if (v->phase >= 1) v->phase -= floorf(v->phase);
                x = v->phase < 0.5f ? 1.0f : -1.0f;
            }
            mix += x * v->vol;
            v->freq *= powf(v->slide, dt);
            v->vol *= powf(v->decay, dt);
            v->left -= dt;
            if (v->left <= 0 || v->vol < 0.002f) v->on = 0;
        }
        /* 噴射音: 低い雑音を、なめらかに強弱させる */
        thrust_phase += 55.0f * dt;
        if (thrust_phase >= 1) thrust_phase -= 1;
        mix += noise_sample() * thrust_vol * 0.5f * (0.6f + 0.4f * sinf(thrust_phase * 6.2832f));
        if (mix > 1) mix = 1;
        if (mix < -1) mix = -1;
        buf[s] = (int16_t)(mix * 9000);
    }
    /* PCM はリトルエンディアンの 16bit。この CPU もリトルエンディアンなのでそのまま送れる */
    send_msg(MSG_AUDIO, buf, (size_t)n * 2, NULL, 0);
}

/* ------------------------------------------------------------------ 描画 */

static inline uint32_t rgb(int r, int g, int b)
{
    return (uint32_t)r | (uint32_t)g << 8 | (uint32_t)b << 16;
}

static inline void pset(int x, int y, uint32_t c)
{
    if ((unsigned)x < W && (unsigned)y < H) fb[y * W + x] = c;
}

/* 光を足す (明るい色ほど重ねると白に近づく) */
static inline void padd(int x, int y, int r, int g, int b)
{
    if ((unsigned)x >= W || (unsigned)y >= H) return;
    uint32_t c = fb[y * W + x];
    int cr = (int)(c & 255) + r, cg = (int)(c >> 8 & 255) + g, cb = (int)(c >> 16 & 255) + b;
    fb[y * W + x] = rgb(cr > 255 ? 255 : cr, cg > 255 ? 255 : cg, cb > 255 ? 255 : cb);
}

static void fill(int x, int y, int w, int h, uint32_t c)
{
    for (int j = y; j < y + h; j++) {
        for (int i = x; i < x + w; i++) pset(i, j, c);
    }
}

/* ぼんやり光る丸。r は半径、明るさは中心ほど強い */
static void glow(float cx, float cy, float r, int cr, int cg, int cb)
{
    int x0 = (int)(cx - r), x1 = (int)(cx + r) + 1, y0 = (int)(cy - r), y1 = (int)(cy + r) + 1;
    for (int y = y0; y <= y1; y++) {
        for (int x = x0; x <= x1; x++) {
            float dx = x - cx, dy = y - cy;
            float d = (dx * dx + dy * dy) / (r * r);
            if (d >= 1) continue;
            float k = (1 - d) * (1 - d);
            padd(x, y, (int)(cr * k), (int)(cg * k), (int)(cb * k));
        }
    }
}

/* 5x7 の文字。1 行 5 ビット (上位が左) を 7 行 */
static const uint8_t FONT[][7] = {
    {0x0E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E}, /* 0 */
    {0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E}, /* 1 */
    {0x0E, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1F}, /* 2 */
    {0x1F, 0x02, 0x04, 0x02, 0x01, 0x11, 0x0E}, /* 3 */
    {0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02}, /* 4 */
    {0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E}, /* 5 */
    {0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E}, /* 6 */
    {0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08}, /* 7 */
    {0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E}, /* 8 */
    {0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C}, /* 9 */
    {0x0E, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11}, /* A */
    {0x1E, 0x11, 0x11, 0x1E, 0x11, 0x11, 0x1E}, /* B */
    {0x0E, 0x11, 0x10, 0x10, 0x10, 0x11, 0x0E}, /* C */
    {0x1C, 0x12, 0x11, 0x11, 0x11, 0x12, 0x1C}, /* D */
    {0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x1F}, /* E */
    {0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x10}, /* F */
    {0x0E, 0x11, 0x10, 0x17, 0x11, 0x11, 0x0F}, /* G */
    {0x11, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11}, /* H */
    {0x0E, 0x04, 0x04, 0x04, 0x04, 0x04, 0x0E}, /* I */
    {0x07, 0x02, 0x02, 0x02, 0x02, 0x12, 0x0C}, /* J */
    {0x11, 0x12, 0x14, 0x18, 0x14, 0x12, 0x11}, /* K */
    {0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x1F}, /* L */
    {0x11, 0x1B, 0x15, 0x15, 0x11, 0x11, 0x11}, /* M */
    {0x11, 0x11, 0x19, 0x15, 0x13, 0x11, 0x11}, /* N */
    {0x0E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E}, /* O */
    {0x1E, 0x11, 0x11, 0x1E, 0x10, 0x10, 0x10}, /* P */
    {0x0E, 0x11, 0x11, 0x11, 0x15, 0x12, 0x0D}, /* Q */
    {0x1E, 0x11, 0x11, 0x1E, 0x14, 0x12, 0x11}, /* R */
    {0x0F, 0x10, 0x10, 0x0E, 0x01, 0x01, 0x1E}, /* S */
    {0x1F, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04}, /* T */
    {0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E}, /* U */
    {0x11, 0x11, 0x11, 0x11, 0x11, 0x0A, 0x04}, /* V */
    {0x11, 0x11, 0x11, 0x15, 0x15, 0x15, 0x0A}, /* W */
    {0x11, 0x11, 0x0A, 0x04, 0x0A, 0x11, 0x11}, /* X */
    {0x11, 0x11, 0x11, 0x0A, 0x04, 0x04, 0x04}, /* Y */
    {0x1F, 0x01, 0x02, 0x04, 0x08, 0x10, 0x1F}, /* Z */
    {0x00, 0x0C, 0x0C, 0x00, 0x0C, 0x0C, 0x00}, /* : */
    {0x04, 0x04, 0x04, 0x04, 0x04, 0x00, 0x04}, /* ! */
    {0x00, 0x00, 0x00, 0x00, 0x00, 0x0C, 0x0C}, /* . */
    {0x00, 0x00, 0x00, 0x1F, 0x00, 0x00, 0x00}, /* - */
};

static const uint8_t *glyph(char ch)
{
    if (ch >= '0' && ch <= '9') return FONT[ch - '0'];
    if (ch >= 'a' && ch <= 'z') ch = (char)(ch - 32);
    if (ch >= 'A' && ch <= 'Z') return FONT[10 + ch - 'A'];
    switch (ch) {
        case ':': return FONT[36];
        case '!': return FONT[37];
        case '.': return FONT[38];
        case '-': return FONT[39];
        default: return NULL;
    }
}

/* 文字を書く。scale 倍に拡大し、影を付けて読みやすくする */
static void text(int x, int y, const char *s, int scale, uint32_t c)
{
    for (int pass = 0; pass < 2; pass++) {
        int ox = pass == 0 ? 1 : 0;
        uint32_t col = pass == 0 ? rgb(0, 0, 0) : c;
        int cx = x;
        for (const char *p = s; *p; p++) {
            const uint8_t *g = glyph(*p);
            if (g) {
                for (int row = 0; row < 7; row++) {
                    for (int bit = 0; bit < 5; bit++) {
                        if (g[row] & (0x10 >> bit)) {
                            fill(cx + bit * scale + ox, y + row * scale + ox, scale, scale, col);
                        }
                    }
                }
            }
            cx += 6 * scale;
        }
    }
}

static int text_width(const char *s, int scale)
{
    return (int)strlen(s) * 6 * scale - scale;
}

static void text_center(int y, const char *s, int scale, uint32_t c)
{
    text((W - text_width(s, scale)) / 2, y, s, scale, c);
}

/* ------------------------------------------------------------------ ゲーム */

#define CAP 1024          /* 洞窟の形を覚えておく列の数 (画面幅より十分大きく) */
#define SHIP_X 64
#define MAX_THINGS 64
#define MAX_PARTS 256

typedef struct {
    float x, y;  /* 世界の座標 (x は進んだ距離) */
    float h;     /* 柱の高さ (玉では使わない) */
    int kind;    /* 0 = 空き、1 = 柱、2 = 光の玉 */
} Thing;

typedef struct {
    float x, y, vx, vy, life, max;
    int r, g, b;
} Part;

enum { TITLE, PLAY, OVER };

static float cave_top[CAP], cave_bot[CAP];
static long generated;       /* ここまでの列 (世界の x) は形が決まっている */
static float gen_center, gen_target, gen_slope;
static Thing things[MAX_THINGS];
static Part parts[MAX_PARTS];
static float stars[60][3];

static int state = TITLE;
static float dist;           /* 進んだ距離 (画面の左端の世界座標) */
static float speed;
static float ship_y, ship_v;
static int holding;
static int paused;
static int orbs;
static int score, best;
static float shake;
static float over_timer;
static float flash;
static long next_thing;
static int last_level;
static int frame_no;

static float gap_at(long x)
{
    /* 進むほど狭くなる。最初は 120 画素、最後は 58 画素 */
    float g = 120.0f - (float)x / 160.0f;
    return g < 58 ? 58 : g;
}

/* 洞窟の形を、世界の x = upto まで作る */
static void generate(long upto)
{
    while (generated <= upto) {
        long x = generated;
        if (x % 90 == 0) gen_target = frand(50, H - 50);
        float d = gen_target - gen_center;
        gen_slope += (d > 0 ? 0.02f : -0.02f);
        if (gen_slope > 0.9f) gen_slope = 0.9f;
        if (gen_slope < -0.9f) gen_slope = -0.9f;
        if (fabsf(d) < 6) gen_slope *= 0.9f;
        gen_center += gen_slope;
        float g = gap_at(x);
        float wob = sinf((float)x * 0.13f) * 2.0f + sinf((float)x * 0.047f) * 3.0f;
        float top = gen_center - g / 2 + wob, bot = gen_center + g / 2 + wob;
        if (top < 6) {
            bot += 6 - top;
            top = 6;
        }
        if (bot > H - 6) {
            top -= bot - (H - 6);
            bot = H - 6;
        }
        cave_top[x % CAP] = top;
        cave_bot[x % CAP] = bot;
        generated++;
    }
}

static float top_at(float x)
{
    return cave_top[(long)x % CAP];
}

static float bot_at(float x)
{
    return cave_bot[(long)x % CAP];
}

static void add_thing(int kind, float x, float y, float h)
{
    for (int i = 0; i < MAX_THINGS; i++) {
        if (!things[i].kind) {
            things[i] = (Thing){x, y, h, kind};
            return;
        }
    }
}

static void spawn_things(void)
{
    /* 画面の少し右に、柱と光の玉を置いていく */
    long ahead = (long)dist + W + 40;
    generate(ahead + 8);
    while (next_thing < ahead) {
        long x = next_thing;
        float top = top_at((float)x), bot = bot_at((float)x), g = bot - top;
        if (x > 500 && rnd() % 100 < 45) {
            /* 柱: 上か下の壁から伸びる。抜ける隙間は機体が通れるだけ必ず残す */
            float h = g * frand(0.28f, 0.45f);
            if (rnd() & 1) add_thing(1, (float)x, top, h);
            else add_thing(1, (float)x, bot - h, h);
        } else {
            /* 光の玉を 3 つ、ゆるい弧を描くように並べる */
            float y = frand(top + 14, bot - 14);
            for (int k = 0; k < 3; k++) {
                float yy = y + sinf((float)k * 1.2f) * 8;
                if (yy > top + 8 && yy < bot - 8) add_thing(2, (float)(x + k * 16), yy, 0);
            }
        }
        next_thing += (long)frand(90, 150);
    }
}

static void burst(float x, float y, int n, float spd, int r, int g, int b, float life)
{
    for (int k = 0; k < n; k++) {
        for (int i = 0; i < MAX_PARTS; i++) {
            if (parts[i].life <= 0) {
                float a = frand(0, 6.2832f), s = frand(0.2f, 1.0f) * spd;
                float l = life * frand(0.5f, 1.0f);
                parts[i] = (Part){x, y, cosf(a) * s, sinf(a) * s, l, l, r, g, b};
                break;
            }
        }
    }
}

static void load_best(void)
{
    const char *home = getenv("HOME");
    char path[512];
    snprintf(path, sizeof(path), "%s/.neondrift_best", home ? home : ".");
    FILE *f = fopen(path, "r");
    if (f) {
        if (fscanf(f, "%d", &best) != 1) best = 0;
        fclose(f);
    }
}

static void save_best(void)
{
    const char *home = getenv("HOME");
    char path[512];
    snprintf(path, sizeof(path), "%s/.neondrift_best", home ? home : ".");
    FILE *f = fopen(path, "w");
    if (f) {
        fprintf(f, "%d\n", best);
        fclose(f);
    }
}

static void reset(void)
{
    rng ^= (uint32_t)time(NULL) * 2654435761u;
    memset(things, 0, sizeof(things));
    memset(parts, 0, sizeof(parts));
    generated = 0;
    gen_center = gen_target = H / 2;
    gen_slope = 0;
    dist = 0;
    speed = 1.6f;
    generate(W + 60);
    next_thing = W + 40;
    ship_y = H / 2;
    ship_v = 0;
    orbs = 0;
    score = 0;
    shake = 0;
    flash = 0;
    last_level = 0;
}

static void crash(void)
{
    state = OVER;
    over_timer = 0;
    shake = 7;
    flash = 1;
    burst(SHIP_X, ship_y, 70, 3.2f, 255, 120, 220, 1.2f);
    burst(SHIP_X, ship_y, 40, 1.6f, 120, 240, 255, 1.6f);
    play(1, 1, 1, 0.9f, 0.08f, 0.9f);
    play(0, 220, 0.05f, 0.35f, 0.2f, 0.6f);
    if (score > best) {
        best = score;
        save_best();
    }
}

static void update(void)
{
    const float dt = 1.0f / FPS;
    frame_no++;
    for (int i = 0; i < 60; i++) {
        /* 背景の星: 奥のものほどゆっくり流れる */
        stars[i][0] -= stars[i][2] * (state == PLAY ? speed : 0.6f) * 0.35f;
        if (stars[i][0] < 0) {
            stars[i][0] += W;
            stars[i][1] = frand(0, H);
        }
    }
    for (int i = 0; i < MAX_PARTS; i++) {
        Part *p = &parts[i];
        if (p->life <= 0) continue;
        p->x += p->vx;
        p->y += p->vy;
        p->vx *= 0.96f;
        p->vy = p->vy * 0.96f + 0.02f;
        p->life -= dt;
    }
    if (shake > 0) shake *= 0.86f;
    if (flash > 0) flash -= dt * 2.5f;

    if (state == TITLE) {
        /* タイトルでは機体がふわふわ漂う */
        ship_y = H / 2 + sinf((float)frame_no * 0.05f) * 18;
        dist += 0.6f;
        generate((long)dist + W + 8);
        thrust_vol *= 0.9f;
        return;
    }
    if (state == OVER) {
        over_timer += dt;
        thrust_vol *= 0.85f;
        return;
    }
    if (paused) {
        thrust_vol = 0;
        return;
    }

    /* 機体: 押している間は上へ、離すと重力で下へ */
    ship_v += holding ? -0.19f : 0.15f;
    if (ship_v > 3.2f) ship_v = 3.2f;
    if (ship_v < -3.0f) ship_v = -3.0f;
    ship_y += ship_v;
    thrust_vol += ((holding ? 0.25f : 0.0f) - thrust_vol) * 0.2f;
    if (holding && (frame_no & 1)) {
        Part *slot = NULL;
        for (int i = 0; i < MAX_PARTS && !slot; i++) if (parts[i].life <= 0) slot = &parts[i];
        if (slot) *slot = (Part){SHIP_X - 6, ship_y + 2, -frand(0.8f, 1.6f), frand(0.3f, 0.9f),
                                 0.4f, 0.4f, 255, 160, 60};
    }

    dist += speed;
    speed = 1.6f + dist / 2600.0f;
    if (speed > 4.2f) speed = 4.2f;
    spawn_things();

    float wx = dist + SHIP_X;
    score = (int)(dist / 10) + orbs * 25;
    int level = (int)(dist / 1500);
    if (level > last_level) {
        last_level = level;
        play(0, 660, 1.0f, 0.25f, 0.5f, 0.12f);
        play(0, 990, 1.0f, 0.2f, 0.5f, 0.25f);
    }

    /* 壁に触れたか (機体の前後数画素の列を調べる) */
    for (int k = -4; k <= 4; k++) {
        float t = top_at(wx + k), b = bot_at(wx + k);
        float half = 4 - fabsf((float)k) * 0.5f;
        if (ship_y - half < t || ship_y + half > b) {
            crash();
            return;
        }
    }
    for (int i = 0; i < MAX_THINGS; i++) {
        Thing *t = &things[i];
        if (!t->kind) continue;
        if (t->x < dist - 20) {
            t->kind = 0; /* 画面の左へ抜けた */
            continue;
        }
        if (t->kind == 1) {
            if (wx + 4 > t->x && wx - 4 < t->x + 8 && ship_y + 3 > t->y && ship_y - 3 < t->y + t->h) {
                crash();
                return;
            }
        } else {
            float dx = t->x - wx, dy = t->y - ship_y;
            if (dx * dx + dy * dy < 10 * 10) {
                t->kind = 0;
                orbs++;
                burst(t->x - dist, t->y, 14, 1.8f, 120, 255, 200, 0.5f);
                play(0, 880 + (orbs % 5) * 110, 2.5f, 0.25f, 0.05f, 0.09f);
            }
        }
    }
}

static void render(void)
{
    int sx = shake > 0.3f ? (int)frand(-shake, shake) : 0;
    int sy = shake > 0.3f ? (int)frand(-shake, shake) : 0;

    /* 背景: 上が濃紺、下が紫のグラデーション */
    for (int y = 0; y < H; y++) {
        uint32_t c = rgb(8 + y / 12, 6 + y / 20, 28 + y / 6);
        for (int x = 0; x < W; x++) fb[y * W + x] = c;
    }
    for (int i = 0; i < 60; i++) {
        int b = (int)(stars[i][2] * 90);
        padd((int)stars[i][0], (int)stars[i][1], b, b, b + 30);
    }

    /* 洞窟の壁: 縁は明るいネオンの線、奥へ行くほど暗くなる。色は進むにつれて移り変わる */
    static const int PAL[4][3] = {{255, 60, 200}, {60, 220, 255}, {160, 90, 255}, {90, 255, 170}};
    float ht = dist * 0.0004f;
    int i0 = (int)ht % 4, i1 = (i0 + 1) % 4;
    float fr = ht - floorf(ht);
    int er = (int)(PAL[i0][0] + (PAL[i1][0] - PAL[i0][0]) * fr);
    int eg = (int)(PAL[i0][1] + (PAL[i1][1] - PAL[i0][1]) * fr);
    int eb = (int)(PAL[i0][2] + (PAL[i1][2] - PAL[i0][2]) * fr);
    for (int x = 0; x < W; x++) {
        float wxf = dist + x;
        int t = (int)top_at(wxf) + sy, b = (int)bot_at(wxf) + sy;
        int px = x + sx;
        for (int y = 0; y < t; y++) {
            int k = t - y > 48 ? 48 : t - y;
            int f = 60 - k; /* 縁の近くほど明るい */
            pset(px, y, rgb(er * f / 340, eg * f / 340, eb * f / 300));
        }
        for (int y = b; y < H; y++) {
            int k = y - b > 48 ? 48 : y - b;
            int f = 60 - k;
            pset(px, y, rgb(er * f / 340, eg * f / 340, eb * f / 300));
        }
        pset(px, t, rgb(er, eg, eb));
        pset(px, b, rgb(er, eg, eb));
        padd(px, t + 1, er / 3, eg / 3, eb / 3);
        padd(px, b - 1, er / 3, eg / 3, eb / 3);
        padd(px, t + 2, er / 8, eg / 8, eb / 8);
        padd(px, b - 2, er / 8, eg / 8, eb / 8);
    }

    /* 柱と光の玉 */
    for (int i = 0; i < MAX_THINGS; i++) {
        Thing *t = &things[i];
        if (!t->kind) continue;
        float x = t->x - dist + sx;
        if (x < -20 || x > W + 20) continue;
        if (t->kind == 1) {
            fill((int)x, (int)t->y + sy, 8, (int)t->h, rgb(40, 10, 50));
            for (int y = (int)t->y; y < (int)(t->y + t->h); y++) {
                pset((int)x, y + sy, rgb(255, 80, 120));
                pset((int)x + 7, y + sy, rgb(255, 80, 120));
            }
            glow(x + 4, t->y + t->h / 2 + sy, t->h / 2 + 6, 60, 10, 30);
        } else {
            float pulse = 0.75f + 0.25f * sinf((float)frame_no * 0.15f + t->x);
            glow(x, t->y + sy, 9 * pulse, 40, 160, 120);
            glow(x, t->y + sy, 3.5f, 200, 255, 230);
        }
    }

    /* 粒 (噴射・爆発・玉の取得) */
    for (int i = 0; i < MAX_PARTS; i++) {
        Part *p = &parts[i];
        if (p->life <= 0) continue;
        float k = p->life / p->max;
        padd((int)p->x + sx, (int)p->y + sy, (int)(p->r * k), (int)(p->g * k), (int)(p->b * k));
        padd((int)p->x + sx + 1, (int)p->y + sy, (int)(p->r * k / 2), (int)(p->g * k / 2),
             (int)(p->b * k / 2));
    }

    /* 機体: 光る三角形 */
    if (state != OVER) {
        float tilt = ship_v * 0.8f;
        glow(SHIP_X + sx, ship_y + sy, 14, 30, 80, 110);
        for (int y = -4; y <= 4; y++) {
            int len = 10 - (y < 0 ? -y : y) * 2;
            for (int x = 0; x < len; x++) {
                int px = SHIP_X - 5 + x + sx, py = (int)(ship_y + y + tilt * x / 10) + sy;
                pset(px, py, x == len - 1 || y == -4 || y == 4 ? rgb(255, 255, 255)
                                                               : rgb(120, 230, 255));
            }
        }
    }

    if (flash > 0) {
        int f = (int)(flash * 120);
        for (int i = 0; i < W * H; i++) {
            uint32_t c = fb[i];
            int r = (int)(c & 255) + f, g = (int)(c >> 8 & 255) + f, b = (int)(c >> 16 & 255) + f;
            fb[i] = rgb(r > 255 ? 255 : r, g > 255 ? 255 : g, b > 255 ? 255 : b);
        }
    }

    char line[64];
    if (state == TITLE) {
        text_center(42, "NEON DRIFT", 3, rgb(120, 240, 255));
        text_center(76, "HOLD TO RISE - RELEASE TO FALL", 1, rgb(230, 230, 255));
        text_center(88, "AVOID WALLS  COLLECT ORBS", 1, rgb(160, 255, 200));
        if ((frame_no / 30) % 2 == 0) text_center(116, "TAP TO START", 2, rgb(255, 220, 120));
        snprintf(line, sizeof(line), "BEST %d", best);
        text_center(150, line, 1, rgb(200, 200, 220));
        return;
    }
    snprintf(line, sizeof(line), "%d", score);
    text(6, 6, line, 2, rgb(255, 255, 255));
    snprintf(line, sizeof(line), "BEST %d", best > score ? best : score);
    text(W - text_width(line, 1) - 6, 6, line, 1, rgb(190, 190, 220));
    if (paused) {
        text_center(70, "PAUSED", 3, rgb(255, 220, 120));
        text_center(104, "TAP TO RESUME", 1, rgb(230, 230, 255));
    }
    if (state == OVER && over_timer > 0.5f) {
        text_center(56, "GAME OVER", 3, rgb(255, 110, 160));
        snprintf(line, sizeof(line), "SCORE %d   ORBS %d", score, orbs);
        text_center(92, line, 1, rgb(255, 255, 255));
        if (score >= best && score > 0) text_center(106, "NEW RECORD!", 1, rgb(255, 230, 100));
        if (over_timer > 1.0f && (frame_no / 30) % 2 == 0) {
            text_center(128, "TAP TO RETRY", 2, rgb(255, 220, 120));
        }
    }
}

/* 押した / 離した。画面のタップと、上・Z・Space・Enter のどれでも同じ */
static void press(int down)
{
    if (down) {
        if (state == TITLE) {
            reset();
            state = PLAY;
            holding = 1;
            play(0, 440, 2.0f, 0.2f, 0.3f, 0.15f);
            return;
        }
        if (state == OVER) {
            if (over_timer > 1.0f) {
                reset();
                state = PLAY;
                holding = 1;
                play(0, 440, 2.0f, 0.2f, 0.3f, 0.15f);
            }
            return;
        }
        if (paused) {
            paused = 0;
            holding = 0;
            return;
        }
    }
    holding = down;
}

static double now(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec + ts.tv_nsec / 1e9;
}

int main(void)
{
    signal(SIGPIPE, SIG_IGN);
    if (connect_display() < 0) {
        fprintf(stderr, "画面につなげませんでした。Bionic Runner のコマンドプロンプトから実行してください\n");
        return 1;
    }
    printf("Neon Drift: 押している間は上昇、離すと落下。壁と柱をよけて光の玉を集めます。\n"
           "画面をタップ / 上・Z・Space で操作、Esc で一時停止、✕ か戻るで終了。\n");
    fflush(stdout);
    for (int i = 0; i < 60; i++) {
        stars[i][0] = frand(0, W);
        stars[i][1] = frand(0, H);
        stars[i][2] = frand(0.2f, 1.0f);
    }
    load_best();
    reset();
    state = TITLE;

    double next = now(), audio_clock = next;
    for (;;) {
        int32_t ev[4];
        int r;
        while ((r = poll_event(ev)) > 0) {
            switch (ev[0]) {
                case EV_QUIT:
                    goto quit;
                case EV_MOUSE_BUTTON:
                    press(ev[3]);
                    break;
                case EV_KEY:
                    if (ev[1] == SC_ESC && ev[2]) {
                        if (state == PLAY) paused = !paused;
                        holding = 0;
                    } else if (ev[1] == SC_UP || ev[1] == SC_Z || ev[1] == SC_SPACE
                               || ev[1] == SC_ENTER) {
                        press(ev[2]);
                    }
                    break;
                default:
                    break;
            }
        }
        if (r < 0) break; /* アプリ側が画面を閉じた */

        update();
        render();
        if (send_frame() < 0) break;
        /* 音は経過した時間ぶんだけ作る。遅れが積もりすぎたら追いつくのをあきらめる */
        double t = now();
        if (t - audio_clock > 0.2) audio_clock = t - 0.05;
        int n = (int)((t - audio_clock) * RATE);
        if (n > 0) {
            send_audio(n);
            audio_clock += (double)n / RATE;
        }

        next += 1.0 / FPS;
        double wait = next - now();
        if (wait > 0) {
            struct timespec ts = {0, (long)(wait * 1e9)};
            nanosleep(&ts, NULL);
        } else if (wait < -0.25) {
            next = now(); /* 大きく遅れたら、取り戻そうとせずに今から数え直す */
        }
    }
quit:
    if (score > best) {
        best = score;
        save_best();
    }
    close(sock);
    printf("Neon Drift を終了しました。最高記録: %d\n", best);
    return 0;
}
