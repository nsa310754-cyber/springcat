/* Bionic Runner から届く入力を SDL のイベントに変える ("dummy" ドライバの置き換え) */
#include "../../SDL_internal.h"

#ifdef SDL_VIDEO_DRIVER_DUMMY

#include "../../events/SDL_events_c.h"
#include "../../events/SDL_keyboard_c.h"
#include "../../events/SDL_mouse_c.h"

#include "SDL_nullvideo.h"
#include "SDL_nullevents_c.h"
#include "SDL_brconn.h"

extern SDL_Window *BR_window;

void DUMMY_PumpEvents(_THIS)
{
    static SDL_bool quit_sent = SDL_FALSE;
    Sint32 ev[4];
    int got;

    while ((got = BR_PollEvent(ev)) > 0) {
        switch (ev[0]) {
        case BR_EV_KEY:
            if (ev[1] > 0 && ev[1] < SDL_NUM_SCANCODES) {
                SDL_SendKeyboardKey(ev[2] ? SDL_PRESSED : SDL_RELEASED, (SDL_Scancode)ev[1]);
            }
            break;
        case BR_EV_MOUSE_MOTION:
            SDL_SendMouseMotion(BR_window, 0, 0, ev[1], ev[2]);
            break;
        case BR_EV_MOUSE_BUTTON:
            /* タッチは前触れなく別の場所に現れるので、押す前にその位置へ動かしておく */
            SDL_SendMouseMotion(BR_window, 0, 0, ev[1], ev[2]);
            SDL_SendMouseButton(BR_window, 0, ev[3] ? SDL_PRESSED : SDL_RELEASED, SDL_BUTTON_LEFT);
            break;
        case BR_EV_TEXT: {
            char text[8];
            char *end = SDL_UCS4ToUTF8((Uint32)ev[1], text);
            *end = '\0';
            SDL_SendKeyboardText(text);
            break;
        }
        case BR_EV_QUIT:
            SDL_SendQuit();
            break;
        default:
            break;
        }
    }
    /* アプリ側の画面が閉じられた: 終了を 1 回だけ通知する */
    if (got < 0 && !quit_sent) {
        quit_sent = SDL_TRUE;
        SDL_SendQuit();
    }
}

#endif /* SDL_VIDEO_DRIVER_DUMMY */
