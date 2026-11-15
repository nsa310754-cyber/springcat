/*
 * tapupgrade — タップしてコインを集め、どんどんアップグレードするゲーム
 *
 * Bionic Runner (Android) 用。SDL2 + SDL2_ttf を動的リンクする glibc 版 ELF。
 *   - 左の丸をタップ → コイン獲得
 *   - 右のボタンをタップ → アップグレード購入
 *   - 画面ボタン: Space / Z = 丸をタップ, ↑↓ = 選択, Enter / X = 購入, Esc = 保存して終了
 *   - 進行は $HOME/.tapupgrade.sav に自動保存 (10 秒ごと + 終了時)
 *   - 閉じている間も自動収入の一部 (最大 8 時間ぶん) がたまる
 *
 * 画像・音・フォントの別ファイルは不要 (音はプログラムで生成、文字は端末のフォント)。
 */
#include <SDL2/SDL.h>
#include <SDL2/SDL_ttf.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define W 640
#define H 360
#define NUP 6
#define MAXFX 48
#define SAVE_NAME ".tapupgrade.sav"
#define AUDIO_RATE 22050

/* ---------------- ゲームの状態 ---------------- */
typedef struct {
    const char *name_ja, *name_en;
    double base_cost, growth;
    int count;
} Upgrade;

static Upgrade ups[NUP] = {
    {"指の力",     "Finger",    15,      1.15, 0},  /* タップ +1           */
    {"ロボット",   "Robot",     100,     1.15, 0},  /* 毎秒 +1             */
    {"工場",       "Factory",   1100,    1.15, 0},  /* 毎秒 +8             */
    {"研究所",     "Lab",       12000,   1.15, 0},  /* 毎秒 +47            */
    {"宇宙基地",   "Spaceport", 130000,  1.15, 0},  /* 毎秒 +260           */
    {"倍率アップ", "Multiplier", 5000,   9.0,  0},  /* 全部の収入 ×2       */
};
static const double cps_each[NUP] = {0, 1, 8, 47, 260, 0};

static double coins = 0, total_earned = 0;
static long long taps = 0;
static int selected = 0;
static int japanese = 1;

static double cost_of(int i) { return ups[i].base_cost * pow(ups[i].growth, ups[i].count); }
static double multiplier(void) { return pow(2.0, ups[5].count); }
static double tap_value(void) { return (1 + ups[0].count) * multiplier(); }
static double cps(void) {
    double s = 0;
    for (int i = 1; i <= 4; i++) s += cps_each[i] * ups[i].count;
    return s * multiplier();
}

/* 数字を 1.23K / 4.56M のように短くする */
static void fmt_num(double v, char *out, size_t n) {
    static const char *suf[] = {"", "K", "M", "B", "T", "Qa", "Qi", "Sx", "Sp", "Oc", "No", "Dc"};
    if (v < 1000) { snprintf(out, n, "%.0f", floor(v)); return; }
    int k = 0;
    while (v >= 1000 && k < 11) { v /= 1000; k++; }
    if (v >= 1000) { snprintf(out, n, "%.2e", v * pow(1000, k)); return; }
    snprintf(out, n, v < 10 ? "%.2f%s" : v < 100 ? "%.1f%s" : "%.0f%s", v, suf[k]);
}

static void effect_text(int i, char *out, size_t n) {
    char a[32];
    switch (i) {
    case 0: snprintf(out, n, japanese ? "タップ +%.0f" : "Tap +%.0f", multiplier()); break;
    case 5: snprintf(out, n, japanese ? "全部の収入 ×2" : "All income x2"); break;
    default:
        fmt_num(cps_each[i] * multiplier(), a, sizeof a);
        snprintf(out, n, japanese ? "毎秒 +%s" : "+%s /sec", a);
    }
}

/* ---------------- 保存 ---------------- */
static char save_path[1024];

static void save_game(void) {
    if (!save_path[0]) return;
    char tmp[1100];
    snprintf(tmp, sizeof tmp, "%s.tmp", save_path);
    FILE *f = fopen(tmp, "w");
    if (!f) return;
    fprintf(f, "tapupgrade 1\n%.17g %.17g %lld %lld\n", coins, total_earned, taps, (long long)time(NULL));
    for (int i = 0; i < NUP; i++) fprintf(f, "%d\n", ups[i].count);
    fclose(f);
    rename(tmp, save_path);
}

/* 戻り値: 閉じていた間に入ったコイン */
static double load_game(void) {
    FILE *f = fopen(save_path, "r");
    if (!f) return 0;
    char magic[32]; int ver; long long saved_at = 0;
    double offline = 0;
    if (fscanf(f, "%31s %d", magic, &ver) == 2 && strcmp(magic, "tapupgrade") == 0 &&
        fscanf(f, "%lf %lf %lld %lld", &coins, &total_earned, &taps, &saved_at) == 4) {
        for (int i = 0; i < NUP; i++) {
            int c = 0;
            if (fscanf(f, "%d", &c) == 1 && c >= 0 && c < 100000) ups[i].count = c;
        }
        if (!(coins >= 0)) coins = 0;            /* NaN や負の値を弾く */
        if (!(total_earned >= coins)) total_earned = coins;
        long long dt = (long long)time(NULL) - saved_at;
        if (dt > 0) {
            if (dt > 8 * 3600) dt = 8 * 3600;
            offline = cps() * dt * 0.5;          /* 閉じている間は半分の速さ */
            coins += offline; total_earned += offline;
        }
    }
    fclose(f);
    return offline;
}

/* ---------------- 音 (プログラムで生成) ---------------- */
static SDL_AudioDeviceID audio_dev;

static void play_tone(float f0, float f1, float ms, float vol) {
    if (!audio_dev) return;
    if (SDL_GetQueuedAudioSize(audio_dev) > AUDIO_RATE * sizeof(Sint16) / 8) SDL_ClearQueuedAudio(audio_dev);
    int n = (int)(AUDIO_RATE * ms / 1000);
    Sint16 *buf = malloc(n * sizeof(Sint16));
    if (!buf) return;
    double ph = 0;
    for (int i = 0; i < n; i++) {
        double t = (double)i / n;
        double f = f0 + (f1 - f0) * t;
        ph += f / AUDIO_RATE;
        double sq = fmod(ph, 1.0) < 0.5 ? 1 : -1;
        double env = (1 - t) * (1 - t);
        buf[i] = (Sint16)(sq * env * vol * 9000);
    }
    SDL_QueueAudio(audio_dev, buf, n * sizeof(Sint16));
    free(buf);
}

/* ---------------- 描画の小道具 ---------------- */
static SDL_Renderer *R;
static TTF_Font *font_big, *font_mid, *font_small;

static void color(Uint32 rgb, Uint8 a) {
    SDL_SetRenderDrawColor(R, rgb >> 16, (rgb >> 8) & 255, rgb & 255, a);
}

static void fill_rect(int x, int y, int w, int h, Uint32 rgb) {
    SDL_Rect r = {x, y, w, h};
    color(rgb, 255);
    SDL_RenderFillRect(R, &r);
}

static void fill_circle(int cx, int cy, int r, Uint32 rgb) {
    color(rgb, 255);
    for (int dy = -r; dy <= r; dy++) {
        int dx = (int)sqrt((double)r * r - dy * dy);
        SDL_RenderDrawLine(R, cx - dx, cy + dy, cx + dx, cy + dy);
    }
}

/* align: 0=左, 1=中央, 2=右。描いた幅を返す */
static int text(TTF_Font *f, const char *s, int x, int y, Uint32 rgb, int align) {
    if (!f || !s || !*s) return 0;
    SDL_Color c = {rgb >> 16, (rgb >> 8) & 255, rgb & 255, 255};
    SDL_Surface *sf = TTF_RenderUTF8_Blended(f, s, c);
    if (!sf) return 0;
    SDL_Texture *t = SDL_CreateTextureFromSurface(R, sf);
    SDL_Rect d = {x, y, sf->w, sf->h};
    if (align == 1) d.x -= sf->w / 2;
    if (align == 2) d.x -= sf->w;
    int w = sf->w;
    if (t) { SDL_RenderCopy(R, t, NULL, &d); SDL_DestroyTexture(t); }
    SDL_FreeSurface(sf);
    return w;
}

/* ---------------- 演出 (+N の文字が浮かぶ) ---------------- */
typedef struct { float x, y, life; char s[24]; } Fx;
static Fx fx[MAXFX];

static void add_fx(float x, float y, const char *s) {
    for (int i = 0; i < MAXFX; i++)
        if (fx[i].life <= 0) {
            fx[i].x = x + (rand() % 40 - 20); fx[i].y = y; fx[i].life = 1;
            snprintf(fx[i].s, sizeof fx[i].s, "%s", s);
            return;
        }
}

/* ---------------- 画面の配置 ---------------- */
#define ORB_X 160
#define ORB_Y 215
#define ORB_R 92
#define BTN_X 328
#define BTN_W 300
#define BTN_H 52
#define BTN_Y0 12
#define BTN_GAP 6

static SDL_Rect button_rect(int i) {
    SDL_Rect r = {BTN_X, BTN_Y0 + i * (BTN_H + BTN_GAP), BTN_W, BTN_H};
    return r;
}

static float orb_pulse = 0, buy_flash[NUP];
static double offline_gain = 0;
static float offline_msg = 0;

static void do_tap(int x, int y) {
    double v = tap_value();
    coins += v; total_earned += v; taps++;
    orb_pulse = 1;
    char s[24], n[20];
    fmt_num(v, n, sizeof n);
    snprintf(s, sizeof s, "+%s", n);
    add_fx(x, y - 20, s);
    play_tone(660 + rand() % 120, 990, 45, 0.6f);
}

static void do_buy(int i) {
    double c = cost_of(i);
    if (coins < c) { play_tone(180, 120, 90, 0.5f); return; }
    coins -= c;
    ups[i].count++;
    buy_flash[i] = 1;
    play_tone(520, 1400, 110, 0.7f);
}

static int inside_orb(int x, int y) {
    int dx = x - ORB_X, dy = y - ORB_Y;
    return dx * dx + dy * dy <= (ORB_R + 10) * (ORB_R + 10);
}

static void handle_click(int x, int y) {
    if (inside_orb(x, y)) { do_tap(x, y); return; }
    for (int i = 0; i < NUP; i++) {
        SDL_Rect r = button_rect(i);
        if (x >= r.x && x < r.x + r.w && y >= r.y && y < r.y + r.h) { selected = i; do_buy(i); return; }
    }
}

static void draw(void) {
    char a[64], b[96];
    fill_rect(0, 0, W, H, 0x141824);
    fill_rect(316, 0, W - 316, H, 0x1b2030);

    /* 所持コインと毎秒 */
    fmt_num(coins, a, sizeof a);
    text(font_big, a, ORB_X, 14, 0xffd34d, 1);
    fmt_num(cps(), a, sizeof a);
    {
        char t[32]; fmt_num(tap_value(), t, sizeof t);
        snprintf(b, sizeof b, japanese ? "毎秒 %s ・ 1タップ %s" : "%s /sec  -  %s per tap", a, t);
    }
    text(font_small, b, ORB_X, 62, 0xb8c0d4, 1);

    /* 丸 (タップする所) */
    int r = ORB_R + (int)(orb_pulse * 8);
    fill_circle(ORB_X, ORB_Y + 5, r, 0x0c0e14);                 /* 影 */
    fill_circle(ORB_X, ORB_Y, r, 0xe8a317);
    fill_circle(ORB_X, ORB_Y, r - 10, 0xffc93c);
    fill_circle(ORB_X - r / 3, ORB_Y - r / 3, r / 5, 0xfff1b8);  /* 光 */
    text(font_mid, japanese ? "タップ!" : "TAP!", ORB_X, ORB_Y - 14, 0x6b3d00, 1);

    /* 浮かぶ文字 */
    for (int i = 0; i < MAXFX; i++)
        if (fx[i].life > 0) {
            Uint8 c = (Uint8)(255 * fx[i].life);
            text(font_mid, fx[i].s, (int)fx[i].x, (int)fx[i].y, (c << 16) | (c << 8) | (c / 3), 1);
        }

    /* アップグレードのボタン */
    for (int i = 0; i < NUP; i++) {
        SDL_Rect br = button_rect(i);
        double c = cost_of(i);
        int ok = coins >= c;
        Uint32 bg = ok ? 0x2c4a7a : 0x262b3a;
        if (buy_flash[i] > 0) bg = 0x4f8a4f;
        fill_rect(br.x, br.y, br.w, br.h, bg);
        if (i == selected) {
            color(0xffffff, 255);
            SDL_RenderDrawRect(R, &br);
            SDL_Rect in = {br.x + 1, br.y + 1, br.w - 2, br.h - 2};
            SDL_RenderDrawRect(R, &in);
        }
        Uint32 fg = ok ? 0xffffff : 0x8a90a2;
        snprintf(b, sizeof b, "%s  Lv.%d", japanese ? ups[i].name_ja : ups[i].name_en, ups[i].count);
        text(font_mid, b, br.x + 10, br.y + 3, fg, 0);
        effect_text(i, b, sizeof b);
        text(font_small, b, br.x + 10, br.y + 30, ok ? 0x9fd6ff : 0x6f7588, 0);
        fmt_num(c, a, sizeof a);
        text(font_mid, a, br.x + br.w - 10, br.y + 14, ok ? 0xffd34d : 0x8a7a50, 2);
    }

    /* 閉じていた間の収入 */
    if (offline_msg > 0) {
        fmt_num(offline_gain, a, sizeof a);
        snprintf(b, sizeof b, japanese ? "おかえり! 留守中に +%s" : "Welcome back! +%s", a);
        fill_rect(20, H - 44, 280, 32, 0x2f5d3a);
        text(font_small, b, ORB_X, H - 39, 0xffffff, 1);
    }
    SDL_RenderPresent(R);
}

/* ---------------- フォント ---------------- */
static TTF_Font *open_font(const char *path, int size) {
    TTF_Font *f = TTF_OpenFontIndex(path, size, 0);
    return f;
}

static int load_fonts(void) {
    const char *env = getenv("TAPUPGRADE_FONT");           /* テスト用に差し替え可 */
    const char *cjk[] = {env, "/system/fonts/NotoSansCJK-Regular.ttc", NULL};
    for (int i = 0; i < 2; i++) {
        if (!cjk[i]) continue;
        font_big = open_font(cjk[i], 40);
        if (font_big) {
            font_mid = open_font(cjk[i], 20);
            font_small = open_font(cjk[i], 15);
            japanese = 1;
            return 1;
        }
    }
    const char *latin[] = {"/system/fonts/Roboto-Regular.ttf", "/system/fonts/DroidSans.ttf", NULL};
    for (int i = 0; latin[i]; i++) {
        font_big = open_font(latin[i], 40);
        if (font_big) {
            font_mid = open_font(latin[i], 20);
            font_small = open_font(latin[i], 15);
            japanese = 0;
            return 1;
        }
    }
    return 0;
}

/* ---------------- メイン ---------------- */
int main(int argc, char **argv) {
    int test_frames = 0;           /* --test N : N フレーム動かして画面を BMP に保存して終了 (確認用) */
    const char *shot = NULL;
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--test") && i + 1 < argc) test_frames = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--shot") && i + 1 < argc) shot = argv[++i];
        else if (!strcmp(argv[i], "--reset")) {
            const char *home = getenv("HOME");
            if (home) { snprintf(save_path, sizeof save_path, "%s/%s", home, SAVE_NAME); remove(save_path); }
            printf("セーブデータを消しました\n");
            return 0;
        } else if (!strcmp(argv[i], "--help") || !strcmp(argv[i], "-h")) {
            printf("tapupgrade - タップしてアップグレードするゲーム\n"
                   "  tapupgrade          ゲームを始める\n"
                   "  tapupgrade --reset  セーブデータを消す\n");
            return 0;
        }
    }

    srand((unsigned)time(NULL));
    const char *home = getenv("HOME");
    if (home && *home && !test_frames) snprintf(save_path, sizeof save_path, "%s/%s", home, SAVE_NAME);

    if (SDL_Init(SDL_INIT_VIDEO | SDL_INIT_AUDIO | SDL_INIT_EVENTS) != 0) {
        if (SDL_Init(SDL_INIT_VIDEO | SDL_INIT_EVENTS) != 0) {
            fprintf(stderr, "SDL_Init: %s\n", SDL_GetError());
            return 1;
        }
    }
    if (TTF_Init() != 0) { fprintf(stderr, "TTF_Init: %s\n", TTF_GetError()); return 1; }
    if (!load_fonts()) {
        fprintf(stderr, "フォントを開けません (/system/fonts/NotoSansCJK-Regular.ttc)\n");
        return 1;
    }

    SDL_Window *win = SDL_CreateWindow("tapupgrade", SDL_WINDOWPOS_CENTERED, SDL_WINDOWPOS_CENTERED, W, H, 0);
    if (!win) { fprintf(stderr, "SDL_CreateWindow: %s\n", SDL_GetError()); return 1; }
    R = SDL_CreateRenderer(win, -1, 0);
    if (!R) { fprintf(stderr, "SDL_CreateRenderer: %s\n", SDL_GetError()); return 1; }
    SDL_SetRenderDrawBlendMode(R, SDL_BLENDMODE_BLEND);

    SDL_AudioSpec want = {0}, have;
    want.freq = AUDIO_RATE; want.format = AUDIO_S16SYS; want.channels = 1; want.samples = 512;
    audio_dev = SDL_OpenAudioDevice(NULL, 0, &want, &have, 0);
    if (audio_dev) SDL_PauseAudioDevice(audio_dev, 0);

    offline_gain = load_game();
    if (offline_gain >= 1) offline_msg = 5;

    printf("tapupgrade 起動。丸をタップしてコインを集めよう。Esc で保存して終了。\n");
    fflush(stdout);

    Uint32 last = SDL_GetTicks(), last_save = last;
    int running = 1, frame = 0;
    while (running) {
        SDL_Event e;
        while (SDL_PollEvent(&e)) {
            switch (e.type) {
            case SDL_QUIT: running = 0; break;
            case SDL_MOUSEBUTTONDOWN:
                if (e.button.button == SDL_BUTTON_LEFT) handle_click(e.button.x, e.button.y);
                break;
            case SDL_KEYDOWN:
                switch (e.key.keysym.sym) {
                case SDLK_SPACE: case SDLK_z:
                    if (!e.key.repeat) do_tap(ORB_X, ORB_Y - 40);
                    break;
                case SDLK_UP:   selected = (selected + NUP - 1) % NUP; break;
                case SDLK_DOWN: selected = (selected + 1) % NUP; break;
                case SDLK_RETURN: case SDLK_KP_ENTER: case SDLK_x: do_buy(selected); break;
                case SDLK_ESCAPE: running = 0; break;
                default: break;
                }
                break;
            }
        }

        Uint32 now = SDL_GetTicks();
        float dt = (now - last) / 1000.0f;
        if (dt > 0.25f) dt = 0.25f;
        last = now;

        double gain = cps() * dt;
        coins += gain; total_earned += gain;

        orb_pulse *= powf(0.001f, dt);
        for (int i = 0; i < NUP; i++) if (buy_flash[i] > 0) buy_flash[i] -= dt * 4;
        for (int i = 0; i < MAXFX; i++)
            if (fx[i].life > 0) { fx[i].life -= dt * 1.2f; fx[i].y -= dt * 70; }
        if (offline_msg > 0) offline_msg -= dt;

        if (test_frames) {          /* 確認用: 自動でタップ・購入する */
            if (frame % 3 == 0) do_tap(ORB_X, ORB_Y);
            if (frame == 5) { coins = 2500; }
            if (frame == 6) { do_buy(0); do_buy(1); do_buy(1); }
            if (frame == 10) { coins = 12345; selected = 2; }
        }

        draw();

        if (now - last_save > 10000) { save_game(); last_save = now; }
        if (test_frames && ++frame >= test_frames) {
            if (shot) {
                SDL_Surface *s = SDL_CreateRGBSurfaceWithFormat(0, W, H, 32, SDL_PIXELFORMAT_ARGB8888);
                if (s && SDL_RenderReadPixels(R, NULL, SDL_PIXELFORMAT_ARGB8888, s->pixels, s->pitch) == 0)
                    SDL_SaveBMP(s, shot);
                SDL_FreeSurface(s);
            }
            running = 0;
        }
        SDL_Delay(16);
    }

    save_game();
    printf("保存しました。また遊んでね。\n");
    if (audio_dev) SDL_CloseAudioDevice(audio_dev);
    TTF_CloseFont(font_small); TTF_CloseFont(font_mid); TTF_CloseFont(font_big);
    TTF_Quit();
    SDL_DestroyRenderer(R);
    SDL_DestroyWindow(win);
    SDL_Quit();
    return 0;
}
