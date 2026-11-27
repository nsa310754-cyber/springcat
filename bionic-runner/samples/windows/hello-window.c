/*
 * Windows (64bit) のウィンドウを 1 つ出す小さなプログラム。Bionic Runner の Wine で動かし、
 * X11 の画面に出ることを確かめる用。ボタンを押すと数を数え、閉じると終わる。
 * (画面の文字が英語なのは、Wine 付属の字体に日本語が無いため。日本語を出すには
 *  日本語の字体 (.ttf) を C:\windows\Fonts に入れる)
 */
#include <windows.h>
#include <stdio.h>

static int count;

static LRESULT CALLBACK proc(HWND w, UINT msg, WPARAM wp, LPARAM lp)
{
    switch (msg) {
    case WM_CREATE:
        CreateWindowW(L"BUTTON", L"Push me", WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON,
                      20, 60, 160, 36, w, (HMENU)1, NULL, NULL);
        return 0;
    case WM_COMMAND:
        if (LOWORD(wp) == 1) {
            count++;
            InvalidateRect(w, NULL, TRUE);
        }
        return 0;
    case WM_PAINT: {
        PAINTSTRUCT ps;
        HDC dc = BeginPaint(w, &ps);
        wchar_t text[64];
        swprintf(text, 64, L"Hello Windows! Pushed: %d", count);
        TextOutW(dc, 20, 20, text, lstrlenW(text));
        EndPaint(w, &ps);
        return 0;
    }
    case WM_DESTROY:
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(w, msg, wp, lp);
}

int WINAPI wWinMain(HINSTANCE inst, HINSTANCE prev, PWSTR cmd, int show)
{
    WNDCLASSW wc = {0};
    wc.lpfnWndProc = proc;
    wc.hInstance = inst;
    wc.hCursor = LoadCursor(NULL, IDC_ARROW);
    wc.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);
    wc.lpszClassName = L"BionicRunnerHello";
    RegisterClassW(&wc);
    HWND w = CreateWindowW(wc.lpszClassName, L"Bionic Runner + Wine", WS_OVERLAPPEDWINDOW,
                           CW_USEDEFAULT, CW_USEDEFAULT, 420, 180, NULL, NULL, inst, NULL);
    ShowWindow(w, show);
    MSG m;
    while (GetMessageW(&m, NULL, 0, 0) > 0) {
        TranslateMessage(&m);
        DispatchMessageW(&m);
    }
    return 0;
}
