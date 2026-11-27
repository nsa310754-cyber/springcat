/*
 * SDL2 の "dummy" 映像ドライバを、Bionic Runner の画面へ出すものに置き換えたもの。
 * ウィンドウの中身 (ソフトウェア描画のフレームバッファ) をアプリへ送り、
 * アプリから届くタッチ・キー入力を SDL のイベントに変える。OpenGL は使えない。
 */
#include "../../SDL_internal.h"

#ifdef SDL_VIDEO_DRIVER_DUMMY

#include "SDL_video.h"
#include "SDL_mouse.h"
#include "../SDL_sysvideo.h"
#include "../SDL_pixels_c.h"
#include "../../events/SDL_events_c.h"

#include "SDL_nullvideo.h"
#include "SDL_nullevents_c.h"
#include "SDL_nullframebuffer_c.h"
#include "SDL_brconn.h"

#define BRVID_DRIVER_NAME "bionicrunner"

/* イベントの届け先。ウィンドウは 1 枚だけ扱う (最後に作られたもの) */
SDL_Window *BR_window = NULL;

static int BR_CreateWindow(_THIS, SDL_Window *window)
{
    BR_window = window;
    SDL_SetMouseFocus(window);
    SDL_SetKeyboardFocus(window);
    return 0;
}

static void BR_DestroyWindow(_THIS, SDL_Window *window)
{
    if (BR_window == window) {
        BR_window = NULL;
    }
}

static int BR_VideoInit(_THIS)
{
    /* 全画面を要求されたときの大きさ。ウィンドウ表示なら好きな大きさを指定できる */
    static const int sizes[][2] = { { 1280, 720 }, { 1024, 768 }, { 800, 600 }, { 640, 480 }, { 320, 240 } };
    SDL_DisplayMode mode;
    size_t i;

    SDL_zero(mode);
    mode.format = SDL_PIXELFORMAT_XBGR8888;
    mode.w = sizes[0][0];
    mode.h = sizes[0][1];
    mode.refresh_rate = 60;
    if (SDL_AddBasicVideoDisplay(&mode) < 0) {
        return -1;
    }
    for (i = 0; i < SDL_arraysize(sizes); i++) {
        mode.w = sizes[i][0];
        mode.h = sizes[i][1];
        SDL_AddDisplayMode(&_this->displays[0], &mode);
    }
    return 0;
}

static void BR_VideoQuit(_THIS)
{
}

static void BR_DeleteDevice(SDL_VideoDevice *device)
{
    SDL_free(device);
}

static SDL_VideoDevice *BR_CreateDevice(void)
{
    SDL_VideoDevice *device;

    if (!BR_Available()) {
        SDL_SetError("BR_DISPLAY is not set: this SDL build only draws to the Bionic Runner app");
        return NULL;
    }
    if (BR_Connect() < 0) {
        SDL_SetError("Could not connect to the Bionic Runner display");
        return NULL;
    }

    device = (SDL_VideoDevice *)SDL_calloc(1, sizeof(SDL_VideoDevice));
    if (!device) {
        SDL_OutOfMemory();
        return NULL;
    }
    /* GPU は無いので、ウィンドウ用テクスチャなどを試させない */
    device->is_dummy = SDL_TRUE;

    device->VideoInit = BR_VideoInit;
    device->VideoQuit = BR_VideoQuit;
    device->PumpEvents = DUMMY_PumpEvents;
    device->CreateSDLWindow = BR_CreateWindow;
    device->DestroyWindow = BR_DestroyWindow;
    device->CreateWindowFramebuffer = SDL_DUMMY_CreateWindowFramebuffer;
    device->UpdateWindowFramebuffer = SDL_DUMMY_UpdateWindowFramebuffer;
    device->DestroyWindowFramebuffer = SDL_DUMMY_DestroyWindowFramebuffer;
    device->free = BR_DeleteDevice;
    return device;
}

VideoBootStrap DUMMY_bootstrap = {
    BRVID_DRIVER_NAME, "Bionic Runner display",
    BR_CreateDevice,
    NULL /* no ShowMessageBox implementation */
};

#ifdef SDL_INPUT_LINUXEV
/* 元のドライバにあった「evdev 入力付き」の別名。SDL 本体が参照するので残すが、使わない */
static SDL_VideoDevice *BR_NoDevice(void)
{
    return NULL;
}

VideoBootStrap DUMMY_evdev_bootstrap = {
    "bionicrunner-unused", "unused",
    BR_NoDevice,
    NULL
};
#endif

#endif /* SDL_VIDEO_DRIVER_DUMMY */
