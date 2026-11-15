/* ウィンドウのフレームバッファを Bionic Runner の画面へ送る ("dummy" ドライバの置き換え) */
#include "../../SDL_internal.h"

#ifdef SDL_VIDEO_DRIVER_DUMMY

#include "SDL_endian.h"
#include "../SDL_sysvideo.h"
#include "SDL_nullframebuffer_c.h"
#include "SDL_brconn.h"

#define BR_SURFACE "_SDL_BionicRunnerSurface"

int SDL_DUMMY_CreateWindowFramebuffer(_THIS, SDL_Window *window, Uint32 *format, void **pixels, int *pitch)
{
    SDL_Surface *surface;
    /* メモリ上の並びが R,G,B,未使用 になる形式。アプリ側は変換なしで画像にできる */
    const Uint32 surface_format = SDL_PIXELFORMAT_XBGR8888;
    const char *title = SDL_GetWindowTitle(window);
    Uint32 size[2];
    int w, h;

    SDL_DUMMY_DestroyWindowFramebuffer(_this, window);

    SDL_GetWindowSizeInPixels(window, &w, &h);
    surface = SDL_CreateRGBSurfaceWithFormat(0, w, h, 0, surface_format);
    if (!surface) {
        return -1;
    }

    SDL_SetWindowData(window, BR_SURFACE, surface);
    *format = surface_format;
    *pixels = surface->pixels;
    *pitch = surface->pitch;

    size[0] = SDL_SwapLE32((Uint32)w);
    size[1] = SDL_SwapLE32((Uint32)h);
    BR_Send(BR_MSG_MODE, size, sizeof(size), title, title ? SDL_strlen(title) : 0);
    return 0;
}

int SDL_DUMMY_UpdateWindowFramebuffer(_THIS, SDL_Window *window, const SDL_Rect *rects, int numrects)
{
    SDL_Surface *surface;
    Uint32 size[2];

    surface = (SDL_Surface *)SDL_GetWindowData(window, BR_SURFACE);
    if (!surface) {
        return SDL_SetError("Couldn't find the Bionic Runner surface for window");
    }

    /* 変更された範囲に関係なく毎回 1 枚まるごと送る (行の詰め物は無い: 1 画素 4 バイト) */
    size[0] = SDL_SwapLE32((Uint32)surface->w);
    size[1] = SDL_SwapLE32((Uint32)surface->h);
    if (BR_Send(BR_MSG_FRAME, size, sizeof(size), surface->pixels,
                (size_t)surface->pitch * (size_t)surface->h) < 0) {
        return SDL_SetError("Lost the connection to the Bionic Runner display");
    }
    return 0;
}

void SDL_DUMMY_DestroyWindowFramebuffer(_THIS, SDL_Window *window)
{
    SDL_Surface *surface;

    surface = (SDL_Surface *)SDL_SetWindowData(window, BR_SURFACE, NULL);
    SDL_FreeSurface(surface);
}

#endif /* SDL_VIDEO_DRIVER_DUMMY */
