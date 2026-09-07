package site.ragdollp.switchrec;

/** libv4l2capture.so の薄いラッパー。root で権限付与済みの /dev/videoN を直接読む。 */
public final class V4l2Capture {
    static { System.loadLibrary("v4l2capture"); }

    public static final int PIX_MJPEG = 0;
    public static final int PIX_YUYV  = 1;

    private long handle = 0;
    public int width, height, pixfmt = -1;

    private static native long nativeOpen(String path);
    private static native int[] nativeConfigure(long h, int wantW, int wantH);
    private static native byte[] nativeGrab(long h);
    private static native void nativeClose(long h);

    /** デバイスを開いて希望解像度でストリーミング開始。成功時 true。 */
    public boolean open(String path, int wantW, int wantH) {
        handle = nativeOpen(path);
        if (handle == 0) return false;
        int[] r = nativeConfigure(handle, wantW, wantH);
        width = r[0]; height = r[1]; pixfmt = r[2];
        if (pixfmt < 0) { close(); return false; }
        return true;
    }

    /** 1 フレーム取得。MJPEG は JPEG バイト列、YUYV は raw。失敗は null。 */
    public byte[] grab() {
        return handle == 0 ? null : nativeGrab(handle);
    }

    public void close() {
        if (handle != 0) { nativeClose(handle); handle = 0; }
    }
}
