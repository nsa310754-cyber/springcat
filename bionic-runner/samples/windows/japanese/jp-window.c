/*
 * 日本語の表示の確認用 (ウィンドウ版)。Bionic Runner の Wine で動かすと、X11 の画面に出る。
 *
 * 日本語のタイトル・文字・ボタン・入力欄を出す。字体は、日本語版 Windows のプログラムがよく指定する
 * 「MS UI Gothic」「ＭＳ ゴシック」「メイリオ」「ＭＳ 明朝」と、指定なし (Windows の標準の字体) を並べる。
 * どれも □ にならずに読めれば正しい。ボタンを押すと日本語のメッセージボックスを出す。
 * ファイルの中身はすべて ASCII で書いてある (日本語は \x の番号で書く。コンパイラーの設定に左右されないように)。
 */
#include <windows.h>

#define ID_BUTTON 1

/* 文字列 (UTF-16)。番号は Unicode */
static const WCHAR TITLE[] = L"\x65e5\x672c\x8a9e\x306e\x8868\x793a\x306e\x78ba\x8a8d";      /* 日本語の表示の確認 */
static const WCHAR BUTTON[] = L"\x62bc\x3057\x3066\x304f\x3060\x3055\x3044";                  /* 押してください */
static const WCHAR PUSHED[] = L"\x30dc\x30bf\x30f3\x304c\x62bc\x3055\x308c\x307e\x3057\x305f"; /* ボタンが押されました */
static const WCHAR EDIT[] = L"\x3053\x3053\x306b\x65e5\x672c\x8a9e\x3092\x5165\x529b\x3067\x304d\x307e\x3059"; /* ここに日本語を入力できます */
static const WCHAR SAMPLE[] = L"\x3042\x3044\x3046\x3048\x304a \x30a2\x30a4\x30a6\x30a8\x30aa \x6f22\x5b57 \xff21\xff22\xff23 123"; /* あいうえお アイウエオ 漢字 ＡＢＣ 123 */

/* 並べる字体の名前 (NULL は指定なし = Windows の標準の字体) */
static const WCHAR *const FACES[] = {
    NULL,
    L"MS UI Gothic",
    L"\xff2d\xff33 \x30b4\x30b7\x30c3\x30af", /* ＭＳ ゴシック */
    L"\x30e1\x30a4\x30ea\x30aa",              /* メイリオ */
    L"\xff2d\xff33 \x660e\x671d",             /* ＭＳ 明朝 */
};

static HFONT fonts[5];

static void paint(HWND hwnd)
{
    PAINTSTRUCT ps;
    HDC dc = BeginPaint(hwnd, &ps);
    int i, y = 12;
    SetBkMode(dc, TRANSPARENT);
    for (i = 0; i < 5; i++) {
        WCHAR line[200];
        HGDIOBJ old = SelectObject(dc, fonts[i] ? (HGDIOBJ)fonts[i] : GetStockObject(DEFAULT_GUI_FONT));
        wsprintfW(line, L"%s: %s", FACES[i] ? FACES[i] : L"(DEFAULT_GUI_FONT)", SAMPLE);
        TextOutW(dc, 12, y, line, lstrlenW(line));
        SelectObject(dc, old);
        y += 30;
    }
    EndPaint(hwnd, &ps);
}

static LRESULT CALLBACK proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp)
{
    switch (msg) {
    case WM_CREATE: {
        HWND b, e;
        int i;
        for (i = 1; i < 5; i++) {
            fonts[i] = CreateFontW(20, 0, 0, 0, FW_NORMAL, 0, 0, 0, SHIFTJIS_CHARSET, 0, 0, 0, 0, FACES[i]);
        }
        b = CreateWindowW(L"BUTTON", BUTTON, WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON,
                          12, 170, 180, 34, hwnd, (HMENU)ID_BUTTON, NULL, NULL);
        e = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", EDIT, WS_CHILD | WS_VISIBLE | ES_AUTOHSCROLL,
                            12, 214, 420, 28, hwnd, NULL, NULL, NULL);
        SendMessageW(b, WM_SETFONT, (WPARAM)GetStockObject(DEFAULT_GUI_FONT), TRUE);
        SendMessageW(e, WM_SETFONT, (WPARAM)fonts[1], TRUE);
        return 0;
    }
    case WM_COMMAND:
        if (LOWORD(wp) == ID_BUTTON) MessageBoxW(hwnd, PUSHED, TITLE, MB_OK | MB_ICONINFORMATION);
        return 0;
    case WM_PAINT:
        paint(hwnd);
        return 0;
    case WM_DESTROY:
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

int WINAPI wWinMain(HINSTANCE inst, HINSTANCE prev, PWSTR cmd, int show)
{
    WNDCLASSW wc = {0};
    MSG m;
    HWND hwnd;
    (void)prev;
    (void)cmd;
    wc.lpfnWndProc = proc;
    wc.hInstance = inst;
    wc.hCursor = LoadCursor(NULL, IDC_ARROW);
    wc.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);
    wc.lpszClassName = L"BionicRunnerJapanese";
    RegisterClassW(&wc);
    hwnd = CreateWindowW(wc.lpszClassName, TITLE, WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT,
                         640, 300, NULL, NULL, inst, NULL);
    ShowWindow(hwnd, show);
    while (GetMessageW(&m, NULL, 0, 0) > 0) {
        TranslateMessage(&m);
        DispatchMessageW(&m);
    }
    return 0;
}
