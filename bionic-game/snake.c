/*
 * snake - Bionic Runner 向けの SDL2 スネークゲーム
 *
 * 仕様(環境)に合わせた作り:
 *  - 形式4: glibc 動的リンク + SDL2 を動的リンク(aarch64 / arm64)
 *  - ソフトウェア描画のみ: SDL_CreateRenderer(win, -1, 0)
 *  - ウィンドウ 640x360(アプリ側が画面に合わせて拡大表示)
 *  - 依存は SDL2 コアのみ(image/mixer/ttf 不要)。文字は内蔵ビットマップフォント、
 *    画像・音は外部ファイルにせずプログラムで生成する(実行ファイル1個で完結)
 *  - 入力: 矢印=方向 / Space・Enter・Z=開始&リスタート / Esc=終了 /
 *          画面タップ(= マウス左ボタン)= その方向へ曲がる
 *  - SDL_QUIT を受け取ったら必ず終了する
 *
 * ビルド(Ubuntu 24.04 arm64 等):
 *   gcc -O2 -o snake snake.c $(pkg-config --cflags --libs sdl2) -lm
 */
#define SDL_MAIN_HANDLED
#include <SDL.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <math.h>

/* ---- 画面・格子 ---- */
#define WIN_W 640
#define WIN_H 360
#define CELL  20
#define GW    (WIN_W / CELL)   /* 32 */
#define GH    (WIN_H / CELL)   /* 18 */
#define MAXLEN (GW * GH)

typedef struct { int x, y; } Pt;

/* ---- ゲーム状態 ---- */
enum { ST_TITLE, ST_PLAY, ST_OVER };

static Pt   snake[MAXLEN];
static int  snake_len;
static int  dir_x, dir_y;      /* 現在の進行方向 */
static int  pend_x, pend_y;    /* 次に適用する方向(逆走防止) */
static Pt   food;
static int  score;
static int  best = 0;
static int  state;
static unsigned rng = 1;

static unsigned xrand(void) { rng = rng * 1103515245u + 12345u; return (rng >> 16) & 0x7fff; }

/* ---- 音(SDL2 の AudioDevice だけで簡易ビープ。失敗しても無音で続行)---- */
static SDL_AudioDeviceID audio_dev = 0;
static int   g_rate = 44100;
static volatile int g_beep_left = 0;   /* 残りサンプル数 */
static int   g_beep_freq = 440;
static int   g_phase = 0;

static void audio_cb(void *u, Uint8 *stream, int len) {
    (void)u;
    Sint16 *buf = (Sint16 *)stream;
    int n = len / (int)sizeof(Sint16);
    for (int i = 0; i < n; i++) {
        if (g_beep_left > 0) {
            int period = g_rate / (g_beep_freq > 0 ? g_beep_freq : 440);
            if (period < 2) period = 2;
            buf[i] = ((g_phase / (period / 2)) & 1) ? 2600 : -2600;
            g_phase++;
            g_beep_left--;
        } else {
            buf[i] = 0;
            g_phase = 0;
        }
    }
}

static void beep(int freq, int ms) {
    if (!audio_dev) return;
    g_beep_freq = freq;
    g_beep_left = g_rate * ms / 1000;
}

static void audio_init(void) {
    if (SDL_InitSubSystem(SDL_INIT_AUDIO) != 0) return;
    SDL_AudioSpec want, have;
    SDL_zero(want);
    want.freq = 44100;
    want.format = AUDIO_S16SYS;
    want.channels = 1;
    want.samples = 1024;
    want.callback = audio_cb;
    audio_dev = SDL_OpenAudioDevice(NULL, 0, &want, &have, 0);
    if (audio_dev) {
        g_rate = have.freq;
        SDL_PauseAudioDevice(audio_dev, 0);
    }
}

/* ---- 内蔵 5x7 ビットマップフォント(0-9, A-Z, 空白)---- */
/* 各グリフは 7 バイト。各バイトの下位 5 ビットが 1 行(bit4 が左端)。 */
static const unsigned char FONT_DIGIT[10][7] = {
    {0x0E,0x11,0x13,0x15,0x19,0x11,0x0E}, /*0*/
    {0x04,0x0C,0x04,0x04,0x04,0x04,0x0E}, /*1*/
    {0x0E,0x11,0x01,0x02,0x04,0x08,0x1F}, /*2*/
    {0x1F,0x02,0x04,0x02,0x01,0x11,0x0E}, /*3*/
    {0x02,0x06,0x0A,0x12,0x1F,0x02,0x02}, /*4*/
    {0x1F,0x10,0x1E,0x01,0x01,0x11,0x0E}, /*5*/
    {0x06,0x08,0x10,0x1E,0x11,0x11,0x0E}, /*6*/
    {0x1F,0x01,0x02,0x04,0x08,0x08,0x08}, /*7*/
    {0x0E,0x11,0x11,0x0E,0x11,0x11,0x0E}, /*8*/
    {0x0E,0x11,0x11,0x0F,0x01,0x02,0x0C}, /*9*/
};
static const unsigned char FONT_ALPHA[26][7] = {
    {0x0E,0x11,0x11,0x1F,0x11,0x11,0x11}, /*A*/
    {0x1E,0x11,0x11,0x1E,0x11,0x11,0x1E}, /*B*/
    {0x0E,0x11,0x10,0x10,0x10,0x11,0x0E}, /*C*/
    {0x1E,0x11,0x11,0x11,0x11,0x11,0x1E}, /*D*/
    {0x1F,0x10,0x10,0x1E,0x10,0x10,0x1F}, /*E*/
    {0x1F,0x10,0x10,0x1E,0x10,0x10,0x10}, /*F*/
    {0x0E,0x11,0x10,0x17,0x11,0x11,0x0F}, /*G*/
    {0x11,0x11,0x11,0x1F,0x11,0x11,0x11}, /*H*/
    {0x0E,0x04,0x04,0x04,0x04,0x04,0x0E}, /*I*/
    {0x07,0x02,0x02,0x02,0x02,0x12,0x0C}, /*J*/
    {0x11,0x12,0x14,0x18,0x14,0x12,0x11}, /*K*/
    {0x10,0x10,0x10,0x10,0x10,0x10,0x1F}, /*L*/
    {0x11,0x1B,0x15,0x15,0x11,0x11,0x11}, /*M*/
    {0x11,0x11,0x19,0x15,0x13,0x11,0x11}, /*N*/
    {0x0E,0x11,0x11,0x11,0x11,0x11,0x0E}, /*O*/
    {0x1E,0x11,0x11,0x1E,0x10,0x10,0x10}, /*P*/
    {0x0E,0x11,0x11,0x11,0x15,0x12,0x0D}, /*Q*/
    {0x1E,0x11,0x11,0x1E,0x14,0x12,0x11}, /*R*/
    {0x0F,0x10,0x10,0x0E,0x01,0x01,0x1E}, /*S*/
    {0x1F,0x04,0x04,0x04,0x04,0x04,0x04}, /*T*/
    {0x11,0x11,0x11,0x11,0x11,0x11,0x0E}, /*U*/
    {0x11,0x11,0x11,0x11,0x11,0x0A,0x04}, /*V*/
    {0x11,0x11,0x11,0x15,0x15,0x1B,0x11}, /*W*/
    {0x11,0x11,0x0A,0x04,0x0A,0x11,0x11}, /*X*/
    {0x11,0x11,0x0A,0x04,0x04,0x04,0x04}, /*Y*/
    {0x1F,0x01,0x02,0x04,0x08,0x10,0x1F}, /*Z*/
};

static const unsigned char *glyph(char c) {
    if (c >= '0' && c <= '9') return FONT_DIGIT[c - '0'];
    if (c >= 'a' && c <= 'z') c = (char)(c - 'a' + 'A');
    if (c >= 'A' && c <= 'Z') return FONT_ALPHA[c - 'A'];
    return NULL; /* 空白・その他は何も描かない */
}

static void draw_text(SDL_Renderer *r, int x, int y, int s, const char *str) {
    for (const char *p = str; *p; p++) {
        const unsigned char *g = glyph(*p);
        if (g) {
            for (int row = 0; row < 7; row++) {
                for (int col = 0; col < 5; col++) {
                    if (g[row] & (1 << (4 - col))) {
                        SDL_Rect rc = { x + col * s, y + row * s, s, s };
                        SDL_RenderFillRect(r, &rc);
                    }
                }
            }
        }
        x += 6 * s; /* 文字送り */
    }
}

static int text_w(const char *str, int s) { return (int)strlen(str) * 6 * s; }

/* ---- ゲーム進行 ---- */
static void place_food(void) {
    for (;;) {
        int fx = (int)(xrand() % GW);
        int fy = (int)(xrand() % GH);
        int on = 0;
        for (int i = 0; i < snake_len; i++)
            if (snake[i].x == fx && snake[i].y == fy) { on = 1; break; }
        if (!on) { food.x = fx; food.y = fy; return; }
    }
}

static void reset_game(void) {
    snake_len = 4;
    for (int i = 0; i < snake_len; i++) { snake[i].x = 8 - i; snake[i].y = GH / 2; }
    dir_x = 1; dir_y = 0;
    pend_x = 1; pend_y = 0;
    score = 0;
    place_food();
    state = ST_PLAY;
}

static void set_dir(int dx, int dy) {
    if (dx == -dir_x && dy == -dir_y) return; /* 逆走は無視 */
    pend_x = dx; pend_y = dy;
}

/* 1 ステップ進める */
static void step(void) {
    dir_x = pend_x; dir_y = pend_y;
    int nx = snake[0].x + dir_x;
    int ny = snake[0].y + dir_y;
    int ate = (nx == food.x && ny == food.y);

    if (nx < 0 || nx >= GW || ny < 0 || ny >= GH) { state = ST_OVER; beep(160, 220); goto done; }
    for (int i = 0; i < snake_len; i++) {
        if (i == snake_len - 1 && !ate) continue; /* 尾は次に空くので衝突扱いしない */
        if (snake[i].x == nx && snake[i].y == ny) { state = ST_OVER; beep(160, 220); goto done; }
    }
    if (ate && snake_len < MAXLEN) snake_len++;
    memmove(&snake[1], &snake[0], sizeof(Pt) * (snake_len - 1));
    snake[0].x = nx; snake[0].y = ny;
    if (ate) {
        score++;
        if (score > best) best = score;
        beep(880, 60);
        if (snake_len < MAXLEN) place_food();
        else state = ST_OVER; /* クリア(埋め尽くし) */
    }
done:;
}

/* ---- 描画 ---- */
static void fill(SDL_Renderer *r, int x, int y, int w, int h, Uint8 cr, Uint8 cg, Uint8 cb) {
    SDL_SetRenderDrawColor(r, cr, cg, cb, 255);
    SDL_Rect rc = { x, y, w, h };
    SDL_RenderFillRect(r, &rc);
}

static void draw_center(SDL_Renderer *r, int cy, int s, const char *str, Uint8 cr, Uint8 cg, Uint8 cb) {
    SDL_SetRenderDrawColor(r, cr, cg, cb, 255);
    draw_text(r, (WIN_W - text_w(str, s)) / 2, cy, s, str);
}

static void render(SDL_Renderer *r) {
    /* 背景 */
    SDL_SetRenderDrawColor(r, 16, 18, 24, 255);
    SDL_RenderClear(r);

    /* 盤面の薄い格子 */
    SDL_SetRenderDrawColor(r, 28, 32, 40, 255);
    for (int x = 0; x <= GW; x++) SDL_RenderDrawLine(r, x * CELL, 0, x * CELL, WIN_H);
    for (int y = 0; y <= GH; y++) SDL_RenderDrawLine(r, 0, y * CELL, WIN_W, y * CELL);

    /* 餌 */
    fill(r, food.x * CELL + 3, food.y * CELL + 3, CELL - 6, CELL - 6, 239, 68, 68);

    /* 蛇 */
    for (int i = snake_len - 1; i >= 0; i--) {
        Uint8 g = (Uint8)(i == 0 ? 74 : 134);
        fill(r, snake[i].x * CELL + 1, snake[i].y * CELL + 1, CELL - 2, CELL - 2,
             (Uint8)(i == 0 ? 34 : 22), (Uint8)(i == 0 ? 197 : 163), g);
    }

    /* スコア */
    char buf[48];
    snprintf(buf, sizeof(buf), "SCORE %d   BEST %d", score, best);
    SDL_SetRenderDrawColor(r, 229, 231, 235, 255);
    draw_text(r, 6, 4, 2, buf);

    if (state == ST_TITLE) {
        draw_center(r, 110, 6, "SNAKE", 74, 222, 128);
        draw_center(r, 180, 2, "ARROWS OR TAP TO MOVE", 229, 231, 235);
        draw_center(r, 205, 2, "PRESS SPACE OR TAP TO START", 250, 204, 21);
    } else if (state == ST_OVER) {
        draw_center(r, 120, 5, "GAME OVER", 248, 113, 113);
        snprintf(buf, sizeof(buf), "SCORE %d", score);
        draw_center(r, 175, 3, buf, 229, 231, 235);
        draw_center(r, 215, 2, "SPACE RETRY   ESC QUIT", 250, 204, 21);
    }
    SDL_RenderPresent(r);
}

/* 入力共通処理 */
static int handle_key(SDL_Keycode k) {
    switch (k) {
        case SDLK_ESCAPE: return 0; /* 終了 */
        case SDLK_UP:    set_dir(0, -1); break;
        case SDLK_DOWN:  set_dir(0,  1); break;
        case SDLK_LEFT:  set_dir(-1, 0); break;
        case SDLK_RIGHT: set_dir( 1, 0); break;
        case SDLK_SPACE: case SDLK_RETURN: case SDLK_z: case SDLK_x:
            if (state != ST_PLAY) reset_game();
            break;
        default: break;
    }
    return 1;
}

/* タップ(マウス左): 蛇の頭から見た方向へ曲がる。タイトル/ゲームオーバーでは開始 */
static void handle_tap(int px, int py) {
    if (state != ST_PLAY) { reset_game(); return; }
    int hx = snake[0].x * CELL + CELL / 2;
    int hy = snake[0].y * CELL + CELL / 2;
    int ddx = px - hx, ddy = py - hy;
    if (abs(ddx) > abs(ddy)) set_dir(ddx > 0 ? 1 : -1, 0);
    else                      set_dir(0, ddy > 0 ? 1 : -1);
}

int main(int argc, char **argv) {
    (void)argc; (void)argv;
    rng = (unsigned)time(NULL) ^ 0x9e3779b9u;

    SDL_SetMainReady();
    if (SDL_Init(SDL_INIT_VIDEO) != 0) {
        fprintf(stderr, "SDL_Init failed: %s\n", SDL_GetError());
        return 1;
    }
    audio_init(); /* 失敗しても続行 */

    SDL_Window *win = SDL_CreateWindow("snake", SDL_WINDOWPOS_CENTERED, SDL_WINDOWPOS_CENTERED,
                                       WIN_W, WIN_H, SDL_WINDOW_SHOWN);
    if (!win) { fprintf(stderr, "window: %s\n", SDL_GetError()); SDL_Quit(); return 1; }
    /* ソフトウェア描画のみ(アクセラレーションや GL は使わない) */
    SDL_Renderer *ren = SDL_CreateRenderer(win, -1, 0);
    if (!ren) { fprintf(stderr, "renderer: %s\n", SDL_GetError()); SDL_DestroyWindow(win); SDL_Quit(); return 1; }

    printf("snake started. window should appear. arrows/tap to move, esc to quit.\n");
    fflush(stdout);

    reset_game();
    state = ST_TITLE;

    const int step_ms = 120;          /* 1 マス進む間隔 */
    Uint32 last_step = SDL_GetTicks();
    int selftest = getenv("SNAKE_SELFTEST") != NULL;
    int test_frames = 0;

    int running = 1;
    while (running) {
        SDL_Event e;
        while (SDL_PollEvent(&e)) {
            if (e.type == SDL_QUIT) { running = 0; }
            else if (e.type == SDL_KEYDOWN) { if (!handle_key(e.key.keysym.sym)) running = 0; }
            else if (e.type == SDL_MOUSEBUTTONDOWN && e.button.button == SDL_BUTTON_LEFT) {
                handle_tap(e.button.x, e.button.y);
            }
        }

        Uint32 now = SDL_GetTicks();
        if (state == ST_PLAY && now - last_step >= (Uint32)step_ms) {
            last_step = now;
            step();
        }
        render(ren);

        if (selftest) {
            /* 画面なしでロジック/描画を一巡させる自己テスト。
               餌へ貪欲に向かい、成長・餌再配置・自己衝突まで通す。 */
            if (state != ST_PLAY) reset_game();
            int ddx = food.x - snake[0].x, ddy = food.y - snake[0].y;
            if (abs(ddx) >= abs(ddy)) { if (ddx) set_dir(ddx > 0 ? 1 : -1, 0); else set_dir(0, ddy > 0 ? 1 : -1); }
            else                      { set_dir(0, ddy > 0 ? 1 : -1); }
            last_step = 0; /* 毎フレーム step させる */
            if (++test_frames > 3000) running = 0;
        } else {
            SDL_Delay(16); /* ~60fps */
        }
    }

    if (audio_dev) SDL_CloseAudioDevice(audio_dev);
    SDL_DestroyRenderer(ren);
    SDL_DestroyWindow(win);
    SDL_Quit();
    printf("snake exited. best=%d\n", best);
    fflush(stdout);
    return 0;
}
