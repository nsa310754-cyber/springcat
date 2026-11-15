/*
 * SDL2 の "dummy" 音声ドライバを、Bionic Runner のスピーカーへ出すものに置き換えたもの。
 * 符号付き 16bit の PCM をアプリへ送る。送る速さは実時間に合わせてこちらで調整する。
 */
#include "../../SDL_internal.h"

#ifdef SDL_AUDIO_DRIVER_DUMMY

#include "SDL_timer.h"
#include "SDL_audio.h"
#include "SDL_endian.h"
#include "../SDL_audio_c.h"
#include "SDL_dummyaudio.h"
#include "../../video/dummy/SDL_brconn.h"

static int BRAUDIO_OpenDevice(_THIS, const char *devname)
{
    struct SDL_PrivateAudioData *h;
    Uint32 params[2];

    if (_this->iscapture) {
        return SDL_SetError("Recording is not supported in Bionic Runner");
    }
    if (BR_Connect() < 0) {
        return SDL_SetError("Could not connect to the Bionic Runner audio output");
    }

    /* アプリ側が再生できる形式に固定する。違う形式を望んだプログラムには SDL が変換して渡す */
    _this->spec.format = AUDIO_S16LSB;
    if (_this->spec.channels > 2) {
        _this->spec.channels = 2;
    }
    SDL_CalculateAudioSpec(&_this->spec);

    h = (struct SDL_PrivateAudioData *)SDL_calloc(1, sizeof(*h));
    if (!h) {
        return SDL_OutOfMemory();
    }
    h->mixbuf = (Uint8 *)SDL_malloc(_this->spec.size);
    if (!h->mixbuf) {
        SDL_free(h);
        return SDL_OutOfMemory();
    }
    SDL_memset(h->mixbuf, _this->spec.silence, _this->spec.size);
    h->next_ms = SDL_GetTicks64();
    _this->hidden = h;

    params[0] = SDL_SwapLE32((Uint32)_this->spec.freq);
    params[1] = SDL_SwapLE32((Uint32)_this->spec.channels);
    BR_Send(BR_MSG_AUDIO_OPEN, params, sizeof(params), NULL, 0);
    return 0;
}

static void BRAUDIO_WaitDevice(_THIS)
{
    struct SDL_PrivateAudioData *h = _this->hidden;
    Uint64 now = SDL_GetTicks64();
    if (h->next_ms > now) {
        SDL_Delay((Uint32)(h->next_ms - now));
    } else if (now - h->next_ms > 200) {
        h->next_ms = now; /* 大きく遅れたら追いかけず、今から数え直す */
    }
}

static void BRAUDIO_PlayDevice(_THIS)
{
    struct SDL_PrivateAudioData *h = _this->hidden;
    BR_Send(BR_MSG_AUDIO, h->mixbuf, _this->spec.size, NULL, 0);
    h->next_ms += ((Uint64)_this->spec.samples * 1000) / (Uint64)_this->spec.freq;
}

static Uint8 *BRAUDIO_GetDeviceBuf(_THIS)
{
    return _this->hidden->mixbuf;
}

static void BRAUDIO_CloseDevice(_THIS)
{
    BR_Send(BR_MSG_AUDIO_CLOSE, NULL, 0, NULL, 0);
    SDL_free(_this->hidden->mixbuf);
    SDL_free(_this->hidden);
}

static SDL_bool BRAUDIO_Init(SDL_AudioDriverImpl *impl)
{
    if (!BR_Available()) {
        return SDL_FALSE;
    }
    impl->OpenDevice = BRAUDIO_OpenDevice;
    impl->WaitDevice = BRAUDIO_WaitDevice;
    impl->PlayDevice = BRAUDIO_PlayDevice;
    impl->GetDeviceBuf = BRAUDIO_GetDeviceBuf;
    impl->CloseDevice = BRAUDIO_CloseDevice;
    impl->OnlyHasDefaultOutputDevice = SDL_TRUE;
    return SDL_TRUE;
}

AudioBootStrap DUMMYAUDIO_bootstrap = {
    "bionicrunner", "Bionic Runner audio output", BRAUDIO_Init, SDL_FALSE
};

#endif
