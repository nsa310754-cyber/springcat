/*
 * Bubble Pop (32bit 版の確認用のミニゲーム)。Bionic Runner の Wine で、32bit の exe が動くかを確かめる。
 *
 * 下から浮かんでくる泡をタップ (クリック) で割る。30 秒で何個割れるか。小さい泡ほど点が高い。
 * 画面の上に、この exe が 32bit で、64bit の Windows (WoW64) の上で動いているかを出す。
 * 描画は Windows の標準の GDI だけ (画面のちらつきを防ぐため、裏の画像に描いてから写す)。
 * ファイルの中身はすべて ASCII で書いてある (日本語は \x の番号で書く。コンパイラーの設定に左右されないように)。
 * 害はありません (ファイルの読み書きもネットの通信もしない)。
 */
#include <windows.h>

#define W 480
#define H 640
#define MAX_BUBBLES 24
#define GAME_MS 30000
#define TICK_MS 33

typedef struct {
    float x, y, r, vy, phase;
    int alive;
    COLORREF color;
} Bubble;

static Bubble bubbles[MAX_BUBBLES];
static int score, popped, playing, best;
static DWORD start_tick, last_spawn;
static unsigned int seed = 12345;
static WCHAR wow_text[96];

/* 文字列 (UTF-16)。番号は Unicode */
static const WCHAR TITLE[] = L"Bubble Pop (32bit)";
static const WCHAR TAP_START[] = L"\x30bf\x30c3\x30d7\x3067\x30b9\x30bf\x30fc\x30c8";            /* タップでスタート */
static const WCHAR HOW[] = L"\x6ce1\x3092\x30bf\x30c3\x30d7\x3057\x3066\x5272\x308d\x3046 (30 \x79d2)"; /* 泡をタップして割ろう (30 秒) */
static const WCHAR SCORE[] = L"\x70b9\x6570";                                                  /* 点数 */
static const WCHAR LEFT[] = L"\x6b8b\x308a";                                                   /* 残り */
static const WCHAR SEC[] = L"\x79d2";                                                          /* 秒 */
static const WCHAR END[] = L"\x7d42\x308f\x308a\xff01";                                        /* 終わり！ */
static const WCHAR BEST[] = L"\x6700\x9ad8";                                                   /* 最高 */
static const WCHAR POPPED[] = L"\x5272\x3063\x305f\x6570";                                     /* 割った数 */

static unsigned int rnd(void)
{
    seed = seed * 1103515245u + 12345u;
    return (seed >> 16) & 0x7fff;
}

/* この exe が 32bit か、64bit の Windows の上 (WoW64) で動いているかを調べる */
static void detect_wow64(void)
{
    typedef BOOL (WINAPI *IsWow64Process_t)(HANDLE, PBOOL);
    BOOL wow = FALSE;
    IsWow64Process_t f = (IsWow64Process_t)(void *)GetProcAddress(GetModuleHandleW(L"kernel32.dll"), "IsWow64Process");
    if (f) f(GetCurrentProcess(), &wow);
    wsprintfW(wow_text, L"exe: %dbit  /  WoW64: %s", (int)(sizeof(void *) * 8), wow ? L"yes" : L"no");
}

static void spawn(void)
{
    int i;
    static const COLORREF colors[] = {RGB(96, 165, 250), RGB(244, 114, 182), RGB(52, 211, 153),
                                      RGB(250, 204, 21), RGB(167, 139, 250), RGB(251, 146, 60)};
    for (i = 0; i < MAX_BUBBLES; i++) {
        if (!bubbles[i].alive) {
            Bubble *b = &bubbles[i];
            b->r = (float)(18 + rnd() % 34);
            b->x = b->r + (float)(rnd() % (unsigned)(W - 2 * (int)b->r));
            b->y = (float)(H + b->r);
            b->vy = 1.2f + (float)(rnd() % 100) / 40.0f;
            b->phase = (float)(rnd() % 628) / 100.0f;
            b->color = colors[rnd() % 6];
            b->alive = 1;
            return;
        }
    }
}

static void start_game(void)
{
    int i;
    for (i = 0; i < MAX_BUBBLES; i++) bubbles[i].alive = 0;
    score = popped = 0;
    playing = 1;
    start_tick = last_spawn = GetTickCount();
    seed ^= start_tick;
}

static void update(void)
{
    int i;
    DWORD now = GetTickCount();
    if (!playing) return;
    if (now - start_tick >= GAME_MS) {
        playing = 0;
        if (score > best) best = score;
        for (i = 0; i < MAX_BUBBLES; i++) bubbles[i].alive = 0; /* 結果の文字が見えるように泡を消す */
        return;
    }
    if (now - last_spawn > 450) {
        spawn();
        last_spawn = now;
    }
    for (i = 0; i < MAX_BUBBLES; i++) {
        Bubble *b = &bubbles[i];
        if (!b->alive) continue;
        b->y -= b->vy;
        b->phase += 0.08f;
        if (b->y < -b->r) b->alive = 0;
    }
}

/* 小さな角度の正弦 (数学ライブラリを使わずに、ゆらゆら動かすため) */
static float wobble(float p)
{
    float t;
    while (p > 3.14159f) p -= 6.28318f;
    while (p < -3.14159f) p += 6.28318f;
    t = p * (1.27324f - 0.405285f * (p < 0 ? -p : p));
    return t;
}

static void click(int mx, int my)
{
    int i;
    if (!playing) {
        start_game();
        return;
    }
    for (i = MAX_BUBBLES - 1; i >= 0; i--) {
        Bubble *b = &bubbles[i];
        float bx = b->x + wobble(b->phase) * 8.0f, dx, dy;
        if (!b->alive) continue;
        dx = (float)mx - bx;
        dy = (float)my - b->y;
        if (dx * dx + dy * dy <= b->r * b->r) {
            b->alive = 0;
            popped++;
            score += 60 - (int)b->r; /* 小さいほど高い点 */
            return;
        }
    }
}

static void text_center(HDC dc, const WCHAR *s, int y, int size, COLORREF color)
{
    HFONT f = CreateFontW(size, 0, 0, 0, FW_BOLD, 0, 0, 0, DEFAULT_CHARSET, 0, 0, ANTIALIASED_QUALITY, 0, L"Tahoma");
    HGDIOBJ old = SelectObject(dc, f);
    RECT r = {0, y, W, y + size + 8};
    SetTextColor(dc, color);
    DrawTextW(dc, s, -1, &r, DT_CENTER | DT_SINGLELINE);
    SelectObject(dc, old);
    DeleteObject(f);
}

static void draw(HDC dc)
{
    int i;
    WCHAR line[128];
    RECT all = {0, 0, W, H};
    HBRUSH bg = CreateSolidBrush(RGB(15, 23, 42));
    FillRect(dc, &all, bg);
    DeleteObject(bg);
    SetBkMode(dc, TRANSPARENT);

    for (i = 0; i < MAX_BUBBLES; i++) {
        Bubble *b = &bubbles[i];
        float bx;
        HBRUSH br;
        HPEN pen;
        HGDIOBJ ob, op;
        if (!b->alive) continue;
        bx = b->x + wobble(b->phase) * 8.0f;
        br = CreateSolidBrush(b->color);
        pen = CreatePen(PS_SOLID, 2, RGB(255, 255, 255));
        ob = SelectObject(dc, br);
        op = SelectObject(dc, pen);
        Ellipse(dc, (int)(bx - b->r), (int)(b->y - b->r), (int)(bx + b->r), (int)(b->y + b->r));
        SelectObject(dc, ob);
        SelectObject(dc, op);
        DeleteObject(br);
        DeleteObject(pen);
        /* 光の反射 */
        br = CreateSolidBrush(RGB(255, 255, 255));
        ob = SelectObject(dc, br);
        op = SelectObject(dc, GetStockObject(NULL_PEN));
        Ellipse(dc, (int)(bx - b->r * 0.55f), (int)(b->y - b->r * 0.6f), (int)(bx - b->r * 0.2f),
                (int)(b->y - b->r * 0.25f));
        SelectObject(dc, ob);
        SelectObject(dc, op);
        DeleteObject(br);
    }

    text_center(dc, wow_text, 8, 16, RGB(148, 163, 184));
    if (playing) {
        int left = (int)((GAME_MS - (GetTickCount() - start_tick) + 999) / 1000);
        wsprintfW(line, L"%s %d   %s %d %s", SCORE, score, LEFT, left, SEC);
        text_center(dc, line, 32, 26, RGB(255, 255, 255));
    } else {
        text_center(dc, TITLE, 200, 40, RGB(255, 255, 255));
        text_center(dc, HOW, 260, 20, RGB(203, 213, 225));
        if (start_tick) {
            text_center(dc, END, 320, 34, RGB(250, 204, 21));
            wsprintfW(line, L"%s %d   %s %d   %s %d", SCORE, score, POPPED, popped, BEST, best);
            text_center(dc, line, 370, 22, RGB(255, 255, 255));
        }
        text_center(dc, TAP_START, 440, 28, RGB(96, 165, 250));
    }
}

static LRESULT CALLBACK proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp)
{
    switch (msg) {
    case WM_CREATE:
        SetTimer(hwnd, 1, TICK_MS, NULL);
        return 0;
    case WM_TIMER:
        update();
        InvalidateRect(hwnd, NULL, FALSE);
        return 0;
    case WM_LBUTTONDOWN:
        click((short)LOWORD(lp), (short)HIWORD(lp));
        return 0;
    case WM_ERASEBKGND:
        return 1; /* 背景は draw で塗る (ちらつき防止) */
    case WM_PAINT: {
        PAINTSTRUCT ps;
        HDC dc = BeginPaint(hwnd, &ps);
        HDC mem = CreateCompatibleDC(dc);
        HBITMAP bmp = CreateCompatibleBitmap(dc, W, H);
        HGDIOBJ old = SelectObject(mem, bmp);
        draw(mem);
        BitBlt(dc, 0, 0, W, H, mem, 0, 0, SRCCOPY);
        SelectObject(mem, old);
        DeleteObject(bmp);
        DeleteDC(mem);
        EndPaint(hwnd, &ps);
        return 0;
    }
    case WM_DESTROY:
        KillTimer(hwnd, 1);
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

int WINAPI wWinMain(HINSTANCE inst, HINSTANCE prev, PWSTR cmd, int show)
{
    WNDCLASSW wc = {0};
    RECT r = {0, 0, W, H};
    MSG m;
    HWND hwnd;
    (void)prev;
    (void)cmd;
    detect_wow64();
    wc.lpfnWndProc = proc;
    wc.hInstance = inst;
    wc.hCursor = LoadCursor(NULL, IDC_HAND);
    wc.lpszClassName = L"BionicRunnerBubblePop";
    RegisterClassW(&wc);
    AdjustWindowRect(&r, WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX, FALSE);
    hwnd = CreateWindowW(wc.lpszClassName, TITLE, WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX,
                         CW_USEDEFAULT, CW_USEDEFAULT, r.right - r.left, r.bottom - r.top, NULL, NULL, inst, NULL);
    ShowWindow(hwnd, show);
    while (GetMessageW(&m, NULL, 0, 0) > 0) {
        TranslateMessage(&m);
        DispatchMessageW(&m);
    }
    return 0;
}
