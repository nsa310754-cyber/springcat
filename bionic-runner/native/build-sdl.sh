#!/bin/sh
# Bionic Runner の画面と音に出力する SDL2 と、その補助ライブラリ (SDL2_image / SDL2_mixer /
# SDL2_ttf) を、glibc 用に 4 種類の CPU 向けにビルドする。補助ライブラリは外部のライブラリに
# 頼らない構成 (同梱の小さなデコーダだけを使う) にしてあり、どれも glibc と SDL2 だけで動く。
# 出力先は native/sdl-out/<ABI>/lib/ で、fetch-glibc.sh が glibc 一式と一緒に APK の assets へ入れる。
#
# 必要なもの: curl, make と、各 CPU 向けの GNU クロスコンパイラ
#   (Debian/Ubuntu: gcc gcc-aarch64-linux-gnu gcc-arm-linux-gnueabihf gcc-i686-linux-gnu)
# 同梱の glibc (2.41) より新しい glibc を持つ環境でビルドしないこと。
#   使い方: native/build-sdl.sh [ABI ...]   (省略時は 4 つすべて)
set -eu
cd "$(dirname "$0")"
HERE=$(pwd)
SDL_VERSION=2.32.10
IMAGE_VERSION=2.8.8
MIXER_VERSION=2.8.1
TTF_VERSION=2.24.0
WORK=${SDL_WORK:-$HERE/sdl-build}
OUT=$HERE/sdl-out
mkdir -p "$WORK" "$OUT"

if [ ! -d "$WORK/SDL2-$SDL_VERSION" ]; then
    curl -fsSL -o "$WORK/SDL2.tar.gz" \
        "https://github.com/libsdl-org/SDL/releases/download/release-$SDL_VERSION/SDL2-$SDL_VERSION.tar.gz"
    tar xzf "$WORK/SDL2.tar.gz" -C "$WORK"
fi
SRC=$WORK/SDL2-$SDL_VERSION
for item in SDL_image:SDL2_image-$IMAGE_VERSION SDL_mixer:SDL2_mixer-$MIXER_VERSION SDL_ttf:SDL2_ttf-$TTF_VERSION; do
    repo=${item%%:*}
    dir=${item##*:}
    if [ ! -d "$WORK/$dir" ]; then
        curl -fsSL -o "$WORK/$dir.tar.gz" \
            "https://github.com/libsdl-org/$repo/releases/download/release-${dir##*-}/$dir.tar.gz"
        tar xzf "$WORK/$dir.tar.gz" -C "$WORK"
    fi
done

# 補助ライブラリを 1 つビルドして install へ入れる: build_extra <ソースの名前> <configure の追加引数...>
build_extra() {
    name=$1
    shift
    mkdir -p "$b/$name"
    (cd "$b/$name" && "$WORK/$name/configure" --host="$host" --prefix="$b/install" \
        --with-sdl-prefix="$b/install" --disable-static --enable-shared --disable-sdltest \
        "$@" >configure.log 2>&1) || { tail -20 "$b/$name/configure.log"; exit 1; }
    make -C "$b/$name" -j"$(nproc)" >"$b/$name/make.log" 2>&1 \
        || { grep -n "error" "$b/$name/make.log" | head -20; exit 1; }
    make -C "$b/$name" install >>"$b/$name/make.log" 2>&1
}
# 何もしない "dummy" ドライバの中身を、アプリへ送るものに差し替える
cp "$HERE"/sdl/SDL_null*.c "$HERE"/sdl/SDL_brconn.c "$HERE"/sdl/SDL_brconn.h "$SRC/src/video/dummy/"
cp "$HERE"/sdl/SDL_dummyaudio.c "$HERE"/sdl/SDL_dummyaudio.h "$SRC/src/audio/dummy/"

[ $# -gt 0 ] || set -- arm64-v8a armeabi-v7a x86_64 x86
for abi in "$@"; do
    case $abi in
        arm64-v8a) host=aarch64-linux-gnu ;;
        armeabi-v7a) host=arm-linux-gnueabihf ;;
        x86_64) host=x86_64-linux-gnu ;;
        x86) host=i686-linux-gnu ;;
        *) echo "不明な ABI: $abi" >&2; exit 1 ;;
    esac
    echo "== SDL2 $SDL_VERSION for $abi ($host)"
    b=$WORK/build-$abi
    rm -rf "$b"
    mkdir -p "$b"
    # 出力先はアプリだけなので、X11・Wayland・OpenGL・各種サウンドサーバなどはすべて外す。
    # これで依存する共有ライブラリは glibc だけになる。
    (cd "$b" && "$SRC/configure" --host="$host" --prefix="$b/install" \
        --disable-static --enable-shared \
        --enable-video-dummy --enable-dummyaudio \
        --disable-video-x11 --disable-video-wayland --disable-video-kmsdrm \
        --disable-video-opengl --disable-video-opengles --disable-video-vulkan \
        --disable-video-rpi --disable-video-vivante --disable-video-directfb \
        --disable-video-offscreen \
        --disable-alsa --disable-pulseaudio --disable-jack --disable-pipewire \
        --disable-esd --disable-arts --disable-nas --disable-sndio --disable-oss \
        --disable-diskaudio --disable-libsamplerate \
        --disable-libudev --disable-dbus --disable-ime --disable-ibus --disable-fcitx \
        --disable-hidapi-libusb --disable-rpath >"$b/configure.log" 2>&1) \
        || { tail -20 "$b/configure.log"; exit 1; }
    make -C "$b" -j"$(nproc)" >"$b/make.log" 2>&1 || { grep -n "error" "$b/make.log" | head -20; exit 1; }
    make -C "$b" install >>"$b/make.log" 2>&1
    # PNG と JPEG は内蔵の stb_image で読む (BMP・GIF なども対応)。TIFF・WebP・AVIF・JXL は外す
    build_extra "SDL2_image-$IMAGE_VERSION" --enable-stb-image \
        --disable-avif --disable-jxl --disable-tif --disable-webp
    # WAV・OGG・MP3・FLAC は内蔵デコーダで再生する。MOD・MIDI・Opus などは外す
    build_extra "SDL2_mixer-$MIXER_VERSION" --enable-music-ogg-stb --enable-music-flac-drflac \
        --enable-music-mp3-minimp3 --disable-music-mod --disable-music-midi --disable-music-gme \
        --disable-music-opus --disable-music-wavpack --disable-music-cmd
    # 文字の描画は同梱の FreeType で行う (複雑な文字組み用の HarfBuzz は使わない)
    build_extra "SDL2_ttf-$TTF_VERSION" --enable-freetype-builtin --disable-harfbuzz

    rm -rf "$OUT/$abi"
    mkdir -p "$OUT/$abi/lib"
    for lib in libSDL2-2.0.so.0 libSDL2_image-2.0.so.0 libSDL2_mixer-2.0.so.0 libSDL2_ttf-2.0.so.0; do
        cp -L "$b/install/lib/$lib" "$OUT/$abi/lib/"
        "$host-strip" --strip-unneeded "$OUT/$abi/lib/$lib" 2>/dev/null \
            || strip --strip-unneeded "$OUT/$abi/lib/$lib"
    done
    ls -la "$OUT/$abi/lib/"
done
echo "SDL2 $SDL_VERSION, SDL2_image $IMAGE_VERSION, SDL2_mixer $MIXER_VERSION, SDL2_ttf $TTF_VERSION" \
    > "$OUT/VERSION.txt"
