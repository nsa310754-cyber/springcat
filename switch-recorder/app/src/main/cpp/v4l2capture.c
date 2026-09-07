// V4L2 直接キャプチャ (root 前提のフォールバック用)。
// USB キャプチャーカードが Camera2 の外部カメラとして公開されない端末でも、
// カーネルの uvcvideo が /dev/videoN を作っていれば、root で権限を付与したうえで
// ここから直接フレームを取得できる。
//
// MJPEG(各フレームが完全な JPEG)を最優先。取れない場合は YUYV を試す。
// pixfmt: 0 = MJPEG(JPEG バイト列), 1 = YUYV(raw), -1 = 失敗。

#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/select.h>
#include <linux/videodev2.h>
#include <android/log.h>

#define TAG "SwitchRecV4L2"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define MAX_BUFS 4

typedef struct {
    int fd;
    int width, height;
    int pixfmt;                 // 0=MJPEG, 1=YUYV
    int nbuf;
    void *starts[MAX_BUFS];
    size_t lengths[MAX_BUFS];
    int streaming;
} v4l2_ctx;

static int xioctl(int fd, unsigned long req, void *arg) {
    int r;
    do { r = ioctl(fd, req, arg); } while (r == -1 && errno == EINTR);
    return r;
}

// path を開き handle(ポインタ)を返す。失敗は 0。
JNIEXPORT jlong JNICALL
Java_site_ragdollp_switchrec_V4l2Capture_nativeOpen(JNIEnv *env, jclass clazz, jstring jpath) {
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    int fd = open(path, O_RDWR | O_NONBLOCK, 0);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (fd < 0) { LOGE("open failed: %s", strerror(errno)); return 0; }

    struct v4l2_capability cap;
    memset(&cap, 0, sizeof(cap));
    if (xioctl(fd, VIDIOC_QUERYCAP, &cap) < 0) { LOGE("QUERYCAP failed"); close(fd); return 0; }
    if (!(cap.capabilities & V4L2_CAP_VIDEO_CAPTURE)) { LOGE("not a capture device"); close(fd); return 0; }

    v4l2_ctx *c = (v4l2_ctx *) calloc(1, sizeof(v4l2_ctx));
    c->fd = fd; c->pixfmt = -1;
    return (jlong)(intptr_t) c;
}

// 希望解像度でフォーマット設定 + mmap + STREAMON。
// 戻り値: int[]{width, height, pixfmt}。失敗時 pixfmt=-1。
JNIEXPORT jintArray JNICALL
Java_site_ragdollp_switchrec_V4l2Capture_nativeConfigure(JNIEnv *env, jclass clazz,
                                                         jlong handle, jint wantW, jint wantH) {
    jintArray out = (*env)->NewIntArray(env, 3);
    jint res[3] = {0, 0, -1};
    v4l2_ctx *c = (v4l2_ctx *)(intptr_t) handle;
    if (!c) { (*env)->SetIntArrayRegion(env, out, 0, 3, res); return out; }

    // MJPEG を優先、ダメなら YUYV。
    unsigned int order[2] = { V4L2_PIX_FMT_MJPEG, V4L2_PIX_FMT_YUYV };
    for (int i = 0; i < 2; i++) {
        struct v4l2_format fmt;
        memset(&fmt, 0, sizeof(fmt));
        fmt.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        fmt.fmt.pix.width = wantW;
        fmt.fmt.pix.height = wantH;
        fmt.fmt.pix.pixelformat = order[i];
        fmt.fmt.pix.field = V4L2_FIELD_NONE;
        if (xioctl(c->fd, VIDIOC_S_FMT, &fmt) < 0) continue;
        if (fmt.fmt.pix.pixelformat != order[i]) continue; // ドライバが別形式に変えた
        c->width = fmt.fmt.pix.width;
        c->height = fmt.fmt.pix.height;
        c->pixfmt = (order[i] == V4L2_PIX_FMT_MJPEG) ? 0 : 1;
        break;
    }
    if (c->pixfmt < 0) { LOGE("no MJPEG/YUYV format"); (*env)->SetIntArrayRegion(env, out, 0, 3, res); return out; }

    struct v4l2_requestbuffers req;
    memset(&req, 0, sizeof(req));
    req.count = MAX_BUFS;
    req.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    req.memory = V4L2_MEMORY_MMAP;
    if (xioctl(c->fd, VIDIOC_REQBUFS, &req) < 0) { LOGE("REQBUFS failed"); c->pixfmt = -1; goto done; }
    c->nbuf = req.count;

    for (int i = 0; i < c->nbuf; i++) {
        struct v4l2_buffer buf;
        memset(&buf, 0, sizeof(buf));
        buf.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        buf.memory = V4L2_MEMORY_MMAP;
        buf.index = i;
        if (xioctl(c->fd, VIDIOC_QUERYBUF, &buf) < 0) { LOGE("QUERYBUF failed"); c->pixfmt = -1; goto done; }
        c->lengths[i] = buf.length;
        c->starts[i] = mmap(NULL, buf.length, PROT_READ | PROT_WRITE, MAP_SHARED, c->fd, buf.m.offset);
        if (c->starts[i] == MAP_FAILED) { LOGE("mmap failed"); c->pixfmt = -1; goto done; }
        if (xioctl(c->fd, VIDIOC_QBUF, &buf) < 0) { LOGE("QBUF failed"); c->pixfmt = -1; goto done; }
    }

    enum v4l2_buf_type type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    if (xioctl(c->fd, VIDIOC_STREAMON, &type) < 0) { LOGE("STREAMON failed"); c->pixfmt = -1; goto done; }
    c->streaming = 1;

    res[0] = c->width; res[1] = c->height; res[2] = c->pixfmt;
    LOGI("configured %dx%d pixfmt=%d nbuf=%d", c->width, c->height, c->pixfmt, c->nbuf);
done:
    (*env)->SetIntArrayRegion(env, out, 0, 3, res);
    return out;
}

// 1 フレーム取得。MJPEG は JPEG バイト列、YUYV は raw。失敗/タイムアウトは null。
JNIEXPORT jbyteArray JNICALL
Java_site_ragdollp_switchrec_V4l2Capture_nativeGrab(JNIEnv *env, jclass clazz, jlong handle) {
    v4l2_ctx *c = (v4l2_ctx *)(intptr_t) handle;
    if (!c || !c->streaming) return NULL;

    fd_set fds; FD_ZERO(&fds); FD_SET(c->fd, &fds);
    struct timeval tv; tv.tv_sec = 2; tv.tv_usec = 0;
    int r = select(c->fd + 1, &fds, NULL, NULL, &tv);
    if (r <= 0) return NULL; // タイムアウト or エラー

    struct v4l2_buffer buf;
    memset(&buf, 0, sizeof(buf));
    buf.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    buf.memory = V4L2_MEMORY_MMAP;
    if (xioctl(c->fd, VIDIOC_DQBUF, &buf) < 0) return NULL;

    jsize n = (jsize) buf.bytesused;
    jbyteArray arr = (*env)->NewByteArray(env, n);
    if (arr) {
        (*env)->SetByteArrayRegion(env, arr, 0, n, (const jbyte *) c->starts[buf.index]);
    }
    xioctl(c->fd, VIDIOC_QBUF, &buf); // 再エンキュー
    return arr;
}

JNIEXPORT void JNICALL
Java_site_ragdollp_switchrec_V4l2Capture_nativeClose(JNIEnv *env, jclass clazz, jlong handle) {
    v4l2_ctx *c = (v4l2_ctx *)(intptr_t) handle;
    if (!c) return;
    if (c->streaming) {
        enum v4l2_buf_type type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        xioctl(c->fd, VIDIOC_STREAMOFF, &type);
    }
    for (int i = 0; i < c->nbuf; i++) {
        if (c->starts[i] && c->starts[i] != MAP_FAILED) munmap(c->starts[i], c->lengths[i]);
    }
    if (c->fd >= 0) close(c->fd);
    free(c);
}
