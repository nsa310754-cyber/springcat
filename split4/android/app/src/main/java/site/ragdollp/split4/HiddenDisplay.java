package site.ragdollp.split4;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;

/**
 * 画面に映らない「透明ディスプレイ」(このアプリ専用の仮想ディスプレイ)。
 *
 * ここに起動したアプリは「表示中」として扱われるため一時停止されず、
 * 動画の再生や次の動画の自動再生がそのまま続く (音はふつうに鳴る)。
 * 描画された映像は ImageReader で受け取ってすぐ捨てるので、どこにも表示されない。
 * このディスプレイを解放すると、上で動いていたアプリは終了する (Android の仕様)。
 */
final class HiddenDisplay {
    private static final int WIDTH = 720;
    private static final int HEIGHT = 1280;
    private static final int DPI = 320;

    private static VirtualDisplay display;
    private static ImageReader reader;
    private static HandlerThread thread;

    private HiddenDisplay() {}

    /** 透明ディスプレイを (無ければ作って) その displayId を返す。 */
    static synchronized int ensure(Context c) {
        if (display != null) return display.getDisplay().getDisplayId();
        thread = new HandlerThread("split4-hidden");
        thread.start();
        reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2);
        // フレームを受け取らないと描画側が詰まるので、来たそばから捨てる
        reader.setOnImageAvailableListener(r -> {
            Image img = r.acquireLatestImage();
            if (img != null) img.close();
        }, new Handler(thread.getLooper()));
        DisplayManager dm = c.getApplicationContext().getSystemService(DisplayManager.class);
        display = dm.createVirtualDisplay("Split4 Hidden", WIDTH, HEIGHT, DPI, reader.getSurface(),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
        return display.getDisplay().getDisplayId();
    }

    static synchronized boolean isActive() {
        return display != null;
    }

    static synchronized int id() {
        return display == null ? -1 : display.getDisplay().getDisplayId();
    }

    /** 透明ディスプレイを解放する (上で動いているアプリは終了する)。 */
    static synchronized void release() {
        if (display != null) {
            display.release();
            display = null;
        }
        if (reader != null) {
            reader.close();
            reader = null;
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
    }
}
