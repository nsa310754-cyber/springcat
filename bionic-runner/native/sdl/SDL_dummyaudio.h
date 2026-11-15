/* Bionic Runner 用の音声ドライバ ("dummy" ドライバの置き換え) の内部データ */
#include "../../SDL_internal.h"

#ifndef SDL_dummyaudio_h_
#define SDL_dummyaudio_h_

#include "../SDL_sysaudio.h"

/* Hidden "this" pointer for the audio functions */
#define _THIS SDL_AudioDevice *_this

struct SDL_PrivateAudioData
{
    Uint8 *mixbuf;
    Uint64 next_ms; /* 次のバッファを送ってよい時刻 */
};

#endif /* SDL_dummyaudio_h_ */
