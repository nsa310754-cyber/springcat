// WebView2 の確認用の最小の Win32 プログラム (C++)。Bionic Runner の Wine で動かす。
//
// ウィンドウの中に WebView2 で HTML (日本語の文字と、JavaScript で数を数えるボタン) を出す。
// WebView2Loader.dll を同じフォルダから読み込み、ランタイムは「入っているものを探す」ふつうの方法で見つける
// (Bionic Runner は環境変数 WEBVIEW2_BROWSER_EXECUTABLE_FOLDER で、取り込んだランタイムの場所を教える)。
// 経過とエラーは標準出力に出す (コマンドプロンプトや wine --diag で見える)。害はありません。
// ファイルの中身は ASCII だけ (日本語は \x の番号で書く)。
#include <windows.h>
#include <stdio.h>
#include "WebView2.h"

typedef HRESULT(STDAPICALLTYPE *CreateEnvFn)(PCWSTR, PCWSTR, ICoreWebView2EnvironmentOptions *,
                                              ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler *);
typedef HRESULT(STDAPICALLTYPE *VersionFn)(PCWSTR, LPWSTR *);

static HWND g_hwnd;
static ICoreWebView2Controller *g_controller;
static ICoreWebView2 *g_webview;

static void say(const char *fmt, HRESULT hr)
{
    printf(fmt, (unsigned)hr);
    printf("\n");
    fflush(stdout);
}

static const wchar_t HTML[] =
    L"<html><head><meta charset='utf-8'></head>"
    L"<body style='font-family:sans-serif;background:#0f172a;color:#fff;padding:24px'>"
    L"<h1>\x3053\x3093\x306b\x3061\x306f\x3001WebView2</h1>"                       // こんにちは、WebView2
    L"<p id='ua'></p>"
    L"<button style='font-size:20px;padding:8px 20px' onclick=\"n++;document.getElementById('c').textContent=n\">"
    L"\x62bc\x3057\x3066\x304f\x3060\x3055\x3044</button> "                          // 押してください
    L"<span id='c' style='font-size:24px'>0</span>"
    L"<script>var n=0;document.getElementById('ua').textContent=navigator.userAgent;</script>"
    L"</body></html>";

// 完了の知らせを受け取る小さな COM の部品 (WRL を使わずに書く)
template <class I>
class Handler : public I
{
    LONG ref = 1;

public:
    virtual ~Handler() {}
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void **ppv) override
    {
        if (riid == IID_IUnknown || riid == __uuidof_iid()) {
            *ppv = this;
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return InterlockedIncrement(&ref); }
    ULONG STDMETHODCALLTYPE Release() override
    {
        LONG r = InterlockedDecrement(&ref);
        if (r == 0) delete this;
        return r;
    }
    virtual REFIID __uuidof_iid() = 0;
};

class ControllerDone : public Handler<ICoreWebView2CreateCoreWebView2ControllerCompletedHandler>
{
    REFIID __uuidof_iid() override { return IID_ICoreWebView2CreateCoreWebView2ControllerCompletedHandler; }
    HRESULT STDMETHODCALLTYPE Invoke(HRESULT result, ICoreWebView2Controller *controller) override
    {
        say("controller: 0x%08x", result);
        if (FAILED(result) || !controller) {
            MessageBoxW(g_hwnd, L"WebView2 \x306e\x8868\x793a\x90e8\x54c1\x3092\x4f5c\x308c\x307e\x305b\x3093\x3067\x3057\x305f", // の表示部品を作れませんでした
                        L"WebView2", MB_OK | MB_ICONERROR);
            return S_OK;
        }
        g_controller = controller;
        g_controller->AddRef();
        g_controller->get_CoreWebView2(&g_webview);
        RECT r;
        GetClientRect(g_hwnd, &r);
        g_controller->put_Bounds(r);
        HRESULT hr = g_webview->NavigateToString(HTML);
        say("navigate: 0x%08x", hr);
        return S_OK;
    }
};

class EnvironmentDone : public Handler<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler>
{
    REFIID __uuidof_iid() override { return IID_ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler; }
    HRESULT STDMETHODCALLTYPE Invoke(HRESULT result, ICoreWebView2Environment *env) override
    {
        say("environment: 0x%08x", result);
        if (FAILED(result) || !env) {
            MessageBoxW(g_hwnd, L"WebView2 \x30e9\x30f3\x30bf\x30a4\x30e0\x3092\x8d77\x52d5\x3067\x304d\x307e\x305b\x3093\x3067\x3057\x305f", // ランタイムを起動できませんでした
                        L"WebView2", MB_OK | MB_ICONERROR);
            return S_OK;
        }
        ControllerDone *h = new ControllerDone();
        HRESULT hr = env->CreateCoreWebView2Controller(g_hwnd, h);
        h->Release();
        say("create controller: 0x%08x", hr);
        return S_OK;
    }
};

static LRESULT CALLBACK proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp)
{
    switch (msg) {
    case WM_SIZE:
        if (g_controller) {
            RECT r;
            GetClientRect(hwnd, &r);
            g_controller->put_Bounds(r);
        }
        return 0;
    case WM_DESTROY:
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

int WINAPI wWinMain(HINSTANCE inst, HINSTANCE, PWSTR, int show)
{
    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    WNDCLASSW wc = {};
    wc.lpfnWndProc = proc;
    wc.hInstance = inst;
    wc.hCursor = LoadCursor(nullptr, IDC_ARROW);
    wc.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);
    wc.lpszClassName = L"BionicRunnerWebView2";
    RegisterClassW(&wc);
    g_hwnd = CreateWindowW(wc.lpszClassName, L"WebView2 + Wine", WS_OVERLAPPEDWINDOW, CW_USEDEFAULT, CW_USEDEFAULT,
                           720, 480, nullptr, nullptr, inst, nullptr);
    ShowWindow(g_hwnd, show);

    HMODULE loader = LoadLibraryW(L"WebView2Loader.dll");
    if (!loader) {
        say("WebView2Loader.dll not found (0x%08x)", HRESULT_FROM_WIN32(GetLastError()));
        MessageBoxW(g_hwnd, L"WebView2Loader.dll \x304c\x3042\x308a\x307e\x305b\x3093", L"WebView2", MB_OK | MB_ICONERROR); // がありません
        return 1;
    }
    VersionFn ver = (VersionFn)(void *)GetProcAddress(loader, "GetAvailableCoreWebView2BrowserVersionString");
    LPWSTR v = nullptr;
    HRESULT hr = ver ? ver(nullptr, &v) : E_FAIL;
    printf("runtime version: %ls (0x%08x)\n", v ? v : L"(none)", (unsigned)hr);
    fflush(stdout);
    CreateEnvFn create = (CreateEnvFn)(void *)GetProcAddress(loader, "CreateCoreWebView2EnvironmentWithOptions");
    EnvironmentDone *h = new EnvironmentDone();
    hr = create ? create(nullptr, nullptr, nullptr, h) : E_FAIL;
    h->Release();
    say("create environment: 0x%08x", hr);

    MSG m;
    while (GetMessageW(&m, nullptr, 0, 0) > 0) {
        TranslateMessage(&m);
        DispatchMessageW(&m);
    }
    return 0;
}
