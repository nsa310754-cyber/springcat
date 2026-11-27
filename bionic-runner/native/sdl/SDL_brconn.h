/*
 * Bionic Runner の画面・音声サーバ (アプリ本体) との接続。
 * SDL2 の "dummy" 映像/音声ドライバを置き換えたものから使う。
 *
 * 接続先は環境変数 BR_DISPLAY="ホスト:ポート:合言葉" で渡される (アプリがシェルに設定する)。
 * 通信はすべてリトルエンディアン。
 *   プログラム → アプリ: [u32 種別][u32 長さ][本体]
 *   アプリ → プログラム: [u32 種別][i32 a][i32 b][i32 c] の 16 バイト固定
 */
#ifndef SDL_brconn_h_
#define SDL_brconn_h_

#include "../../SDL_internal.h"

/* プログラム → アプリ */
#define BR_MSG_HELLO       1 /* 合言葉 (文字列) */
#define BR_MSG_MODE        2 /* u32 幅, u32 高さ, ウィンドウ名 (UTF-8) */
#define BR_MSG_FRAME       3 /* u32 幅, u32 高さ, 画素 (1 画素 4 バイト: R,G,B,未使用) */
#define BR_MSG_AUDIO_OPEN  4 /* u32 周波数, u32 チャンネル数。形式は符号付き 16bit 固定 */
#define BR_MSG_AUDIO       5 /* PCM データ */
#define BR_MSG_AUDIO_CLOSE 6

/* アプリ → プログラム */
#define BR_EV_KEY          1 /* a=SDL のスキャンコード, b=1 押した / 0 離した */
#define BR_EV_MOUSE_MOTION 2 /* a=x, b=y (ウィンドウ内の座標) */
#define BR_EV_MOUSE_BUTTON 3 /* a=x, b=y, c=1 押した / 0 離した (左ボタン) */
#define BR_EV_QUIT         4
#define BR_EV_TEXT         5 /* a=Unicode の符号位置 */

/* BR_DISPLAY が設定されているか (= Bionic Runner の中で動いているか) */
SDL_bool BR_Available(void);

/* 未接続なら接続する。成功 0、失敗 -1 */
int BR_Connect(void);

/* メッセージを 1 つ送る。本体は a と b の 2 つに分けて渡せる (b は NULL 可)。失敗 -1 */
int BR_Send(Uint32 type, const void *a, size_t alen, const void *b, size_t blen);

/* 届いているイベントを 1 つ取り出す。あれば 1、無ければ 0、接続が切れていたら -1 */
int BR_PollEvent(Sint32 ev[4]);

#endif /* SDL_brconn_h_ */
