/*
 * Star Catch: Bionic Runner の動作確認用のサンプルゲーム。
 * 落ちてくる星をカゴで受け止め、爆弾は避ける。画像・音・フォントはすべてプログラム内で作るので、
 * 実行ファイル 1 個で動く。アプリ同梱の SDL2 (ソフトウェア描画) を使う。
 *
 * 操作: 画面をドラッグ、または ◀ ▶ でカゴを動かす。タップ / Space / Enter で開始。Esc で一時停止。
 */
#include <SDL.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define W 270
#define H 400
#define FLOOR_Y 352
#define BASKET_W 46
#define MAX_ITEMS 40
#define MAX_PARTS 200
#define MAX_VOICES 10
#define RATE 22050

enum { STAR, BIGSTAR, BOMB, HEART };
enum { TITLE, PLAY, PAUSE, OVER };

typedef struct { int alive, kind; float x, y, vy, t; } Item;
typedef struct { int alive; float x, y, vx, vy, life; Uint32 color; } Part;
typedef struct { int on, noise; float freq, slide, t, dur, vol, phase; } Voice;

static SDL_Renderer *R;
static int ox, oy; /* 画面の揺れ (爆弾を受けたとき) */
static Uint32 rng = 2463534242u;

static float frand(void) {
    rng ^= rng << 13; rng ^= rng >> 17; rng ^= rng << 5;
    return (rng >> 8) / 16777216.0f;
}

/* ------------------------------------------------------------------ 効果音 */

static Voice voices[MAX_VOICES];
static SDL_AudioDeviceID audio;
static Uint32 noise_state = 22222u;

static void audio_cb(void *u, Uint8 *stream, int len) {
    Sint16 *out = (Sint16 *)stream;
    (void)u;
    for (int i = 0; i < len / 2; i++) {
        float s = 0;
        for (int v = 0; v < MAX_VOICES; v++) {
            Voice *vo = &voices[v];
            if (!vo->on) continue;
            float env = 1.0f - vo->t / vo->dur;
            if (env <= 0) { vo->on = 0; continue; }
            float w;
            if (vo->noise) {
                noise_state = noise_state * 1664525u + 1013904223u;
                w = ((noise_state >> 16) & 0xffff) / 32768.0f - 1.0f;
            } else {
                w = vo->phase < 0.5f ? 1.0f : -1.0f; /* 矩形波 */
                vo->phase += vo->freq / RATE;
                if (vo->phase >= 1) vo->phase -= 1;
            }
            s += w * vo->vol * env * env;
            vo->freq += vo->slide / RATE;
            if (vo->freq < 20) vo->freq = 20;
            vo->t += 1.0f / RATE;
        }
        if (s > 1) s = 1;
        if (s < -1) s = -1;
        out[i] = (Sint16)(s * 8000);
    }
}

static void play(float freq, float slide, float dur, float vol, int noise) {
    if (!audio) return;
    SDL_LockAudioDevice(audio);
    for (int v = 0; v < MAX_VOICES; v++) {
        if (!voices[v].on) {
            Voice nv = { 1, noise, freq, slide, 0, dur, vol, 0 };
            voices[v] = nv;
            break;
        }
    }
    SDL_UnlockAudioDevice(audio);
}

/* ------------------------------------------------------------------ 描画 */

static void color(Uint32 c) { SDL_SetRenderDrawColor(R, c >> 16 & 255, c >> 8 & 255, c & 255, 255); }
static void rect(int x, int y, int w, int h) { SDL_Rect q = { x + ox, y + oy, w, h }; SDL_RenderFillRect(R, &q); }
static void hline(float x1, float x2, float y) {
    if (x2 < x1) return;
    SDL_RenderDrawLine(R, (int)x1 + ox, (int)y + oy, (int)x2 + ox, (int)y + oy);
}
static void disc(float cx, float cy, float r) {
    for (int dy = -(int)r; dy <= (int)r; dy++) {
        float hw = sqrtf(r * r - dy * dy);
        hline(cx - hw, cx + hw, cy + dy);
    }
}
/* 4 本のとげがある星 */
static void sparkle(float cx, float cy, float r) {
    float t = r * 0.32f;
    for (int dy = -(int)r; dy <= (int)r; dy++) {
        float hw = (r - fabsf((float)dy)) * 0.32f;
        hline(cx - hw, cx + hw, cy + dy);
    }
    for (int dy = -(int)t; dy <= (int)t; dy++) {
        float hw = r * (1.0f - fabsf((float)dy) / t);
        hline(cx - hw, cx + hw, cy + dy);
    }
    disc(cx, cy, r * 0.38f);
}
static void heart(float cx, float cy, float r) {
    disc(cx - r * 0.5f, cy - r * 0.25f, r * 0.55f);
    disc(cx + r * 0.5f, cy - r * 0.25f, r * 0.55f);
    for (int dy = 0; dy <= (int)(r * 1.1f); dy++) {
        float hw = r * 1.05f * (1.0f - dy / (r * 1.1f));
        hline(cx - hw, cx + hw, cy - r * 0.1f + dy);
    }
}

/* 5x7 のビットマップ文字 (英大文字・数字・一部の記号) */
static const char *GLYPH_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ:!-.+x/";
static const unsigned char GLYPHS[][7] = {
    {14,17,19,21,25,17,14},{4,12,4,4,4,4,14},{14,17,1,2,4,8,31},{31,2,4,2,1,17,14},
    {2,6,10,18,31,2,2},{31,16,30,1,1,17,14},{6,8,16,30,17,17,14},{31,1,2,4,8,8,8},
    {14,17,17,14,17,17,14},{14,17,17,15,1,2,12},
    {14,17,17,31,17,17,17},{30,17,17,30,17,17,30},{14,17,16,16,16,17,14},{28,18,17,17,17,18,28},
    {31,16,16,30,16,16,31},{31,16,16,30,16,16,16},{14,17,16,23,17,17,15},{17,17,17,31,17,17,17},
    {14,4,4,4,4,4,14},{7,2,2,2,2,18,12},{17,18,20,24,20,18,17},{16,16,16,16,16,16,31},
    {17,27,21,21,17,17,17},{17,17,25,21,19,17,17},{14,17,17,17,17,17,14},{30,17,17,30,16,16,16},
    {14,17,17,17,21,18,13},{30,17,17,30,20,18,17},{15,16,16,14,1,1,30},{31,4,4,4,4,4,4},
    {17,17,17,17,17,17,14},{17,17,17,17,17,10,4},{17,17,17,21,21,21,10},{17,17,10,4,10,17,17},
    {17,17,17,10,4,4,4},{31,1,2,4,8,16,31},
    {0,12,12,0,12,12,0},{4,4,4,4,4,0,4},{0,0,0,31,0,0,0},{0,0,0,0,0,12,12},{0,4,4,31,4,4,0},
    {0,0,17,10,4,10,17},{1,1,2,4,8,16,16},
};

static int text_w(const char *s, int sc) { int n = (int)strlen(s); return n ? n * 6 * sc - sc : 0; }

static void text(const char *s, int x, int y, int sc, Uint32 c) {
    color(c);
    for (; *s; s++, x += 6 * sc) {
        const char *p = strchr(GLYPH_CHARS, *s);
        if (*s == ' ' || !p) continue;
        const unsigned char *g = GLYPHS[p - GLYPH_CHARS];
        for (int row = 0; row < 7; row++)
            for (int col = 0; col < 5; col++)
                if (g[row] >> (4 - col) & 1) {
                    SDL_Rect q = { x + col * sc, y + row * sc, sc, sc };
                    SDL_RenderFillRect(R, &q);
                }
    }
}

/* 影付きで中央に書く */
static void ctext(const char *s, int y, int sc, Uint32 c) {
    int x = (W - text_w(s, sc)) / 2;
    text(s, x + sc / 2 + 1, y + sc / 2 + 1, sc, 0x000000);
    text(s, x, y, sc, c);
}

/* ------------------------------------------------------------------ ゲーム */

static Item items[MAX_ITEMS];
static Part parts[MAX_PARTS];
static int state = TITLE, score, best, lives, combo, level, new_best;
static float bx = W / 2, target = W / 2, spawn_t, shake, banner_t, over_t, clock_t2;
static int touching;
static char best_path[512];

static void load_best(void) {
    const char *home = getenv("HOME");
    snprintf(best_path, sizeof(best_path), "%s/.starcatch_best", home ? home : ".");
    FILE *f = fopen(best_path, "r");
    if (f) { if (fscanf(f, "%d", &best) != 1) best = 0; fclose(f); }
}

static void save_best(void) {
    FILE *f = fopen(best_path, "w");
    if (f) { fprintf(f, "%d\n", best); fclose(f); }
}

static void burst(float x, float y, Uint32 c, int n) {
    for (int i = 0; i < MAX_PARTS && n > 0; i++) {
        if (parts[i].alive) continue;
        float a = frand() * 6.2832f, sp = 40 + frand() * 110;
        Part p = { 1, x, y, cosf(a) * sp, sinf(a) * sp - 40, 0.5f + frand() * 0.4f, c };
        parts[i] = p;
        n--;
    }
}

static void start_game(void) {
    memset(items, 0, sizeof(items));
    score = 0; lives = 3; combo = 0; level = 1; new_best = 0;
    spawn_t = 0.6f; bx = target = W / 2; banner_t = 1.5f;
    state = PLAY;
    play(523, 600, 0.12f, 0.35f, 0);
    play(784, 600, 0.18f, 0.25f, 0);
}

static void spawn(void) {
    for (int i = 0; i < MAX_ITEMS; i++) {
        if (items[i].alive) continue;
        float r = frand(), bomb = 0.20f + level * 0.015f;
        if (bomb > 0.38f) bomb = 0.38f;
        int kind = STAR;
        if (r < bomb) kind = BOMB;
        else if (r < bomb + 0.06f) kind = BIGSTAR;
        else if (r < bomb + 0.085f && lives < 3) kind = HEART;
        Item it = { 1, kind, 16 + frand() * (W - 32), -14, 70 + level * 11 + frand() * 35, frand() * 6 };
        items[i] = it;
        return;
    }
}

static void update(float dt) {
    clock_t2 += dt;
    if (shake > 0) shake -= dt;
    if (banner_t > 0) banner_t -= dt;
    for (int i = 0; i < MAX_PARTS; i++) {
        Part *p = &parts[i];
        if (!p->alive) continue;
        p->x += p->vx * dt; p->y += p->vy * dt; p->vy += 260 * dt; p->life -= dt;
        if (p->life <= 0) p->alive = 0;
    }
    if (state == OVER) { over_t += dt; return; }
    if (state != PLAY) return;

    /* カゴの移動: キーが押されていればキー、そうでなければ指の位置へ近づける */
    const Uint8 *k = SDL_GetKeyboardState(NULL);
    int dir = (k[SDL_SCANCODE_RIGHT] || k[SDL_SCANCODE_D]) - (k[SDL_SCANCODE_LEFT] || k[SDL_SCANCODE_A]);
    if (dir) { bx += dir * 230 * dt; target = bx; }
    else { float f = dt * 16; if (f > 1) f = 1; bx += (target - bx) * f; }
    if (bx < BASKET_W / 2) bx = BASKET_W / 2;
    if (bx > W - BASKET_W / 2) bx = W - BASKET_W / 2;

    spawn_t -= dt;
    if (spawn_t <= 0) {
        spawn();
        float iv = 0.95f - level * 0.06f;
        spawn_t = (iv < 0.36f ? 0.36f : iv) * (0.7f + frand() * 0.6f);
    }

    for (int i = 0; i < MAX_ITEMS; i++) {
        Item *it = &items[i];
        if (!it->alive) continue;
        float py = it->y;
        it->y += it->vy * dt;
        it->t += dt;
        /* カゴの縁を上から通り抜けた瞬間に、横の位置が合っていれば受け止めた扱い */
        if (py < FLOOR_Y - 6 && it->y >= FLOOR_Y - 6 && fabsf(it->x - bx) < BASKET_W / 2 + 7) {
            it->alive = 0;
            if (it->kind == BOMB) {
                lives--; combo = 0; shake = 0.35f;
                burst(it->x, FLOOR_Y - 8, 0xff5a3c, 26);
                play(120, -60, 0.45f, 0.5f, 1);
                if (lives <= 0) {
                    state = OVER; over_t = 0;
                    if (score > best) { best = score; new_best = 1; save_best(); }
                    play(392, -300, 0.7f, 0.35f, 0);
                }
            } else if (it->kind == HEART) {
                if (lives < 3) lives++;
                burst(it->x, FLOOR_Y - 8, 0xff6ea8, 16);
                play(660, 900, 0.2f, 0.3f, 0);
            } else {
                int gain = it->kind == BIGSTAR ? 5 : 1 + combo / 5;
                score += gain; combo++;
                burst(it->x, FLOOR_Y - 8, it->kind == BIGSTAR ? 0x7fe0ff : 0xffe04a,
                      it->kind == BIGSTAR ? 24 : 12);
                play(it->kind == BIGSTAR ? 880 : 660 + (combo % 8) * 40, 1200, 0.1f, 0.3f, 0);
                int nl = score / 15 + 1;
                if (nl > level) { level = nl; banner_t = 1.4f; play(523, 1500, 0.35f, 0.25f, 0); }
            }
        } else if (it->y > H + 16) {
            it->alive = 0;
            if (it->kind == STAR || it->kind == BIGSTAR) { if (combo) play(200, -100, 0.08f, 0.15f, 0); combo = 0; }
        }
    }
}

static void draw_item(const Item *it) {
    float pulse = 1.0f + 0.12f * sinf(it->t * 9);
    switch (it->kind) {
    case STAR: color(0xffe04a); sparkle(it->x, it->y, 9 * pulse); color(0xfff7c2); disc(it->x - 1, it->y - 1, 2); break;
    case BIGSTAR: color(0x7fe0ff); sparkle(it->x, it->y, 13 * pulse); color(0xffffff); disc(it->x - 2, it->y - 2, 3); break;
    case HEART: color(0xff6ea8); heart(it->x, it->y, 8 * pulse); break;
    case BOMB:
        color(0x2a2a35); disc(it->x, it->y, 9);
        color(0x5a5a6e); disc(it->x - 3, it->y - 3, 2.5f);
        color(0x8a6a40); rect((int)it->x - 1, (int)it->y - 14, 3, 5);
        color(((int)(it->t * 20)) & 1 ? 0xffd040 : 0xff5030); disc(it->x + 1, it->y - 15, 2.5f);
        break;
    }
}

static void draw(void) {
    ox = oy = 0;
    if (shake > 0) { ox = (int)((frand() - 0.5f) * 8); oy = (int)((frand() - 0.5f) * 8); }

    /* 夜空のグラデーション */
    for (int y = 0; y < H; y++) {
        float f = (float)y / H;
        SDL_SetRenderDrawColor(R, (Uint8)(12 + 40 * f), (Uint8)(14 + 10 * f), (Uint8)(44 + 36 * f), 255);
        SDL_RenderDrawLine(R, 0, y, W, y);
    }
    /* 背景のまたたく星 */
    for (int i = 0; i < 46; i++) {
        int x = (i * 97 + 13) % W, y = (i * 53 + 7) % (FLOOR_Y - 20);
        int b = 110 + (int)(90 * sinf(clock_t2 * (1.5f + (i % 5) * 0.4f) + i));
        SDL_SetRenderDrawColor(R, b, b, b + 20 > 255 ? 255 : b + 20, 255);
        SDL_RenderDrawPoint(R, x + ox, y + oy);
    }
    /* 地面 */
    color(0x2b1d4a); rect(-8, FLOOR_Y + 12, W + 16, H - FLOOR_Y);
    color(0x45306e); rect(-8, FLOOR_Y + 12, W + 16, 3);

    for (int i = 0; i < MAX_ITEMS; i++) if (items[i].alive) draw_item(&items[i]);

    if (state == PLAY || state == PAUSE || state == OVER) {
        /* カゴ (下すぼまりの台形) */
        float top = FLOOR_Y - 6;
        color(0xe08a3c);
        for (int dy = 0; dy < 16; dy++) {
            float hw = BASKET_W / 2 - dy * 0.55f;
            hline(bx - hw, bx + hw, top + dy);
        }
        color(0xffb35c); rect((int)(bx - BASKET_W / 2) - 2, (int)top - 2, BASKET_W + 4, 3);
        color(0xb0662a);
        for (int x = -2; x <= 2; x++) rect((int)(bx + x * 8), (int)top + 3, 2, 11);
    }

    for (int i = 0; i < MAX_PARTS; i++) {
        Part *p = &parts[i];
        if (!p->alive) continue;
        color(p->color);
        int s = p->life > 0.3f ? 3 : 2;
        rect((int)p->x, (int)p->y, s, s);
    }

    ox = oy = 0;
    char buf[64];
    if (state == TITLE) {
        color(0xffe04a); sparkle(W / 2, 92, 22);
        ctext("STAR", 128, 5, 0xffe04a);
        ctext("CATCH", 170, 5, 0x7fe0ff);
        if (((int)(clock_t2 * 2)) & 1) ctext("TAP TO START", 236, 2, 0xffffff);
        ctext("DRAG OR ARROWS TO MOVE", 282, 1, 0xc8c8e8);
        ctext("CATCH STARS  AVOID BOMBS", 296, 1, 0xc8c8e8);
        snprintf(buf, sizeof buf, "BEST %d", best);
        ctext(buf, 326, 2, 0xffb35c);
        return;
    }
    snprintf(buf, sizeof buf, "%d", score);
    text(buf, 9, 9, 3, 0x000000);
    text(buf, 8, 8, 3, 0xffffff);
    snprintf(buf, sizeof buf, "LV %d", level);
    text(buf, 8, 34, 1, 0xc8c8e8);
    for (int i = 0; i < 3; i++) { color(i < lives ? 0xff6ea8 : 0x4a3a5a); heart(W - 18 - i * 20, 18, 7); }
    if (combo >= 3 && state == PLAY) {
        snprintf(buf, sizeof buf, "x%d", combo);
        text(buf, W - 14 - text_w(buf, 2), 34, 2, 0xffe04a);
    }
    if (banner_t > 0 && state == PLAY) {
        snprintf(buf, sizeof buf, "LEVEL %d", level);
        ctext(buf, 150, 3, 0x7fe0ff);
    }
    if (state == PAUSE) {
        ctext("PAUSE", 150, 4, 0xffffff);
        ctext("TAP TO RESUME", 200, 2, 0xc8c8e8);
    }
    if (state == OVER) {
        ctext("GAME OVER", 120, 4, 0xff6a5a);
        snprintf(buf, sizeof buf, "SCORE %d", score);
        ctext(buf, 172, 2, 0xffffff);
        snprintf(buf, sizeof buf, "BEST %d", best);
        ctext(buf, 196, 2, 0xffb35c);
        if (new_best && ((int)(clock_t2 * 3)) & 1) ctext("NEW BEST!", 226, 2, 0xffe04a);
        if (over_t > 0.9f) ctext("TAP TO RETRY", 270, 2, 0xffffff);
    }
}

/* 開始・再開の操作 (タップ / Space / Enter / Z) */
static void press(void) {
    if (state == TITLE) start_game();
    else if (state == PAUSE) state = PLAY;
    else if (state == OVER && over_t > 0.9f) start_game();
}

int main(int argc, char **argv) {
    (void)argc; (void)argv;
    setvbuf(stdout, NULL, _IONBF, 0);
    if (SDL_Init(SDL_INIT_VIDEO | SDL_INIT_AUDIO) != 0) {
        printf("SDL を初期化できませんでした: %s\n", SDL_GetError());
        printf("Bionic Runner の中で、root 権限ありで実行してください。\n");
        return 1;
    }
    SDL_Window *win = SDL_CreateWindow("Star Catch", SDL_WINDOWPOS_UNDEFINED, SDL_WINDOWPOS_UNDEFINED, W, H, 0);
    if (!win) { printf("ウィンドウを作れませんでした: %s\n", SDL_GetError()); return 1; }
    R = SDL_CreateRenderer(win, -1, SDL_RENDERER_SOFTWARE);
    if (!R) { printf("描画の準備ができませんでした: %s\n", SDL_GetError()); return 1; }

    SDL_AudioSpec want, have;
    SDL_zero(want);
    want.freq = RATE; want.format = AUDIO_S16SYS; want.channels = 1; want.samples = 512; want.callback = audio_cb;
    audio = SDL_OpenAudioDevice(NULL, 0, &want, &have, 0);
    if (audio) SDL_PauseAudioDevice(audio, 0);
    else printf("音を出せません (%s)。音なしで続けます。\n", SDL_GetError());

    rng ^= SDL_GetTicks() * 2654435761u + 1;
    load_best();
    printf("Star Catch を起動しました。ゲーム画面で遊んでください。\n");

    Uint32 last = SDL_GetTicks();
    int running = 1;
    while (running) {
        SDL_Event e;
        while (SDL_PollEvent(&e)) {
            switch (e.type) {
            case SDL_QUIT: running = 0; break;
            case SDL_MOUSEBUTTONDOWN:
                if (state == PLAY) { touching = 1; target = (float)e.button.x; }
                else press();
                break;
            case SDL_MOUSEMOTION:
                if (state == PLAY && (touching || (e.motion.state & SDL_BUTTON_LMASK))) target = (float)e.motion.x;
                break;
            case SDL_MOUSEBUTTONUP: touching = 0; break;
            case SDL_KEYDOWN:
                if (e.key.repeat) break;
                switch (e.key.keysym.scancode) {
                case SDL_SCANCODE_SPACE: case SDL_SCANCODE_RETURN: case SDL_SCANCODE_Z: press(); break;
                case SDL_SCANCODE_ESCAPE:
                    if (state == PLAY) state = PAUSE;
                    else if (state == PAUSE) state = PLAY;
                    break;
                default: break;
                }
                break;
            default: break;
            }
        }
        Uint32 now = SDL_GetTicks();
        float dt = (now - last) / 1000.0f;
        last = now;
        if (dt > 0.1f) dt = 0.1f; /* 止まっていた後に一気に進まないように */
        update(dt);
        draw();
        SDL_RenderPresent(R);
        /* 毎フレーム全画素をアプリへ送るので、30 fps 程度に抑える */
        Uint32 spent = SDL_GetTicks() - now;
        if (spent < 33) SDL_Delay(33 - spent);
    }
    if (score > best) { best = score; save_best(); }
    if (audio) SDL_CloseAudioDevice(audio);
    SDL_Quit();
    printf("Star Catch を終了しました。最高記録: %d\n", best);
    return 0;
}
