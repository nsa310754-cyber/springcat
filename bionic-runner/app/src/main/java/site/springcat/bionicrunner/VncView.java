package site.springcat.bionicrunner;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * X の画面 (Xvnc) を映して操作する画面。VNC の通信手順 (RFB 3.8) を自前で話す。
 *
 * 映すだけでなく、指の操作をマウスに、キーボードの入力を X のキーに置き換えて送る。
 *   1 本指で軽く触る … 左クリック          1 本指でなぞる … 左ボタンを押したまま動かす (ウィンドウの移動など)
 *   長押し          … 右クリック          2 本指        … 拡大・縮小と、見る場所の移動
 */
final class VncView extends View {

    /** 接続の状態を画面の持ち主に伝える */
    interface Listener {
        void onConnected(int width, int height, String name);

        void onDisconnected(String reason);
    }

    // X のキー (keysym)
    static final int XK_BACKSPACE = 0xff08, XK_TAB = 0xff09, XK_RETURN = 0xff0d, XK_ESCAPE = 0xff1b,
            XK_HOME = 0xff50, XK_LEFT = 0xff51, XK_UP = 0xff52, XK_RIGHT = 0xff53, XK_DOWN = 0xff54,
            XK_PAGE_UP = 0xff55, XK_PAGE_DOWN = 0xff56, XK_END = 0xff57, XK_INSERT = 0xff63,
            XK_DELETE = 0xffff, XK_F1 = 0xffbe, XK_SHIFT_L = 0xffe1, XK_CONTROL_L = 0xffe3,
            XK_ALT_L = 0xffe9, XK_SUPER_L = 0xffeb;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final Object lock = new Object();
    private final ScaleGestureDetector scaler;
    Listener listener;

    private volatile LocalSocket socket;
    private DataOutputStream out;
    private final Object writeLock = new Object();
    private volatile boolean closed;

    /** 画面の中身 (0xAARRGGBB)。lock で守る */
    private int fbWidth, fbHeight;
    private int[] fb;
    private Bitmap bitmap;

    /** 表示の倍率と位置 (利用者が 2 本指で変えたぶん) */
    private float zoom = 1f, panX, panY;
    /** 1 本指の操作の途中経過 */
    private float downX, downY;
    private boolean dragging, pressed, multi, longFired;
    private int buttons;
    private int lastFx, lastFy;
    /** 次の 1 回の軽い操作を右クリックにする (操作ボタンから) */
    boolean rightNext;
    /** 次のキーに Ctrl・Alt を付ける (操作ボタンから) */
    boolean ctrlNext, altNext;
    Runnable onModifiersUsed;

    private final Runnable longPress = () -> {
        if (pressed && !dragging && !multi) {
            longFired = true;
            click(4);
        }
    };

    VncView(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(0xFF000000);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float old = zoom;
                zoom = Math.max(1f, Math.min(8f, zoom * d.getScaleFactor()));
                // 指の間の点を中心に拡大する
                float k = zoom / old;
                panX = d.getFocusX() - (d.getFocusX() - panX) * k;
                panY = d.getFocusY() - (d.getFocusY() - panY) * k;
                invalidate();
                return true;
            }
        });
    }

    // ------------------------------------------------------------------ 接続

    /** path (UNIX ソケット) の Xvnc につなぐ。別スレッドで読み続ける */
    void connect(final String path) {
        closed = false;
        new Thread(() -> {
            String reason;
            try {
                LocalSocket s = new LocalSocket();
                s.connect(new LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM));
                socket = s;
                run(s);
                reason = "X サーバーが終了しました";
            } catch (IOException e) {
                reason = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            close();
            if (closed) return; // こちらから切った
            final String r = reason;
            ui.post(() -> {
                if (listener != null) listener.onDisconnected(r);
            });
        }, "vnc").start();
    }

    void disconnect() {
        closed = true;
        close();
    }

    private void close() {
        LocalSocket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // 閉じている
            }
        }
    }

    boolean connected() {
        return socket != null;
    }

    private void run(LocalSocket s) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 1 << 16));
        synchronized (writeLock) {
            out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 8192));
        }
        // 1. 版の取り決め
        byte[] ver = new byte[12];
        in.readFully(ver);
        String v = new String(ver, StandardCharsets.US_ASCII);
        if (!v.startsWith("RFB ")) throw new IOException("VNC ではない相手です");
        boolean v33 = v.startsWith("RFB 003.003");
        write(("RFB 003." + (v33 ? "003" : "008") + "\n").getBytes(StandardCharsets.US_ASCII));
        // 2. 認証: 「無し」だけに対応 (ソケット自体がアプリ専用の場所にあるため)
        if (v33) {
            int type = in.readInt();
            if (type != 1) throw new IOException("対応していない認証方式です (" + type + ")");
        } else {
            int n = in.readUnsignedByte();
            if (n == 0) throw new IOException(readReason(in));
            boolean none = false;
            for (int i = 0; i < n; i++) if (in.readUnsignedByte() == 1) none = true;
            if (!none) throw new IOException("パスワード付きの VNC には対応していません");
            write(new byte[]{1});
            if (in.readInt() != 0) throw new IOException(readReason(in));
        }
        // 3. 初期化: 画面を共有する
        write(new byte[]{1});
        int w = in.readUnsignedShort(), h = in.readUnsignedShort();
        in.readFully(new byte[16]); // 相手の色の形式 (こちらの形式を指定し直すので使わない)
        int nameLen = in.readInt();
        byte[] name = new byte[Math.min(Math.max(nameLen, 0), 4096)];
        in.readFully(name);
        resize(w, h);
        // 色の形式: 1 点 32 ビット、リトルエンディアン、赤は 16 ビット目から (= 青・緑・赤・空 の順の 4 バイト)
        write(new byte[]{0, 0, 0, 0, 32, 24, 0, 1, 0, (byte) 255, 0, (byte) 255, 0, (byte) 255, 16, 8, 0, 0, 0, 0});
        // 送ってもらう形式: CopyRect・Raw と、画面の大きさの変更 (-223)
        sendEncodings(new int[]{1, 0, -223});
        final String title = new String(name, StandardCharsets.UTF_8);
        ui.post(() -> {
            if (listener != null) listener.onConnected(fbWidth, fbHeight, title);
        });
        requestUpdate(false);
        byte[] row = new byte[0];
        while (!closed) {
            int type = in.readUnsignedByte();
            switch (type) {
                case 0: { // 画面の更新
                    in.readUnsignedByte();
                    int rects = in.readUnsignedShort();
                    boolean full = false;
                    for (int i = 0; i < rects; i++) {
                        int x = in.readUnsignedShort(), y = in.readUnsignedShort();
                        int rw = in.readUnsignedShort(), rh = in.readUnsignedShort();
                        int enc = in.readInt();
                        if (enc == 0) {
                            if (row.length < rw * 4) row = new byte[rw * 4];
                            raw(in, row, x, y, rw, rh);
                        } else if (enc == 1) {
                            copyRect(in.readUnsignedShort(), in.readUnsignedShort(), x, y, rw, rh);
                        } else if (enc == -223) {
                            resize(rw, rh);
                            full = true;
                        } else {
                            throw new IOException("知らない形式の画面データです (" + enc + ")");
                        }
                    }
                    postInvalidate();
                    requestUpdate(!full);
                    break;
                }
                case 1: { // 色の表 (32 ビットの色では使わない): 読み飛ばす
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                    int n = in.readUnsignedShort();
                    in.readFully(new byte[n * 6]);
                    break;
                }
                case 2: // ベル
                    break;
                case 3: { // X の側でコピーされた文字 → Android のクリップボードへ
                    in.readFully(new byte[3]);
                    int len = in.readInt();
                    if (len < 0 || len > 8 * 1024 * 1024) throw new IOException("受け取った文字が大きすぎます");
                    byte[] t = new byte[len];
                    in.readFully(t);
                    final String text = new String(t, StandardCharsets.ISO_8859_1);
                    ui.post(() -> {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("X11", text));
                    });
                    break;
                }
                default:
                    throw new IOException("知らない通知です (" + type + ")");
            }
        }
    }

    private static String readReason(DataInputStream in) throws IOException {
        int len = in.readInt();
        byte[] b = new byte[Math.min(Math.max(len, 0), 4096)];
        in.readFully(b);
        return "VNC の接続を断られました: " + new String(b, StandardCharsets.UTF_8);
    }

    private void resize(int w, int h) {
        if (w <= 0 || h <= 0 || w > 8192 || h > 8192) return;
        synchronized (lock) {
            fbWidth = w;
            fbHeight = h;
            fb = new int[w * h];
            java.util.Arrays.fill(fb, 0xFF000000);
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(0xFF000000);
        }
    }

    private void raw(DataInputStream in, byte[] row, int x, int y, int w, int h) throws IOException {
        int[] line = new int[w];
        for (int j = 0; j < h; j++) {
            in.readFully(row, 0, w * 4);
            for (int i = 0, k = 0; i < w; i++, k += 4) {
                line[i] = 0xFF000000 | ((row[k + 2] & 255) << 16) | ((row[k + 1] & 255) << 8) | (row[k] & 255);
            }
            synchronized (lock) {
                int yy = y + j;
                if (fb == null || yy >= fbHeight || x + w > fbWidth) continue;
                System.arraycopy(line, 0, fb, yy * fbWidth + x, w);
            }
        }
        synchronized (lock) {
            if (fb != null && x + w <= fbWidth && y + h <= fbHeight && w > 0 && h > 0) {
                bitmap.setPixels(fb, y * fbWidth + x, fbWidth, x, y, w, h);
            }
        }
    }

    private void copyRect(int sx, int sy, int x, int y, int w, int h) {
        synchronized (lock) {
            if (fb == null || w <= 0 || h <= 0 || sx + w > fbWidth || sy + h > fbHeight
                    || x + w > fbWidth || y + h > fbHeight) {
                return;
            }
            int[] tmp = new int[w * h];
            for (int j = 0; j < h; j++) System.arraycopy(fb, (sy + j) * fbWidth + sx, tmp, j * w, w);
            for (int j = 0; j < h; j++) System.arraycopy(tmp, j * w, fb, (y + j) * fbWidth + x, w);
            bitmap.setPixels(fb, y * fbWidth + x, fbWidth, x, y, w, h);
        }
    }

    // ------------------------------------------------------------------ 送る

    private void write(byte[] b) throws IOException {
        synchronized (writeLock) {
            out.write(b);
            out.flush();
        }
    }

    /** 入力を送る。切れていたら何もしない (UI スレッドから呼ばれるので、待たせないよう別スレッドで) */
    private void sendLater(final byte[] b) {
        if (socket == null) return;
        Sender.post(() -> {
            try {
                write(b);
            } catch (IOException | RuntimeException ignored) {
                // 切れた: 読む側のスレッドが知らせる
            }
        });
    }

    private void sendEncodings(int[] encs) throws IOException {
        byte[] b = new byte[4 + encs.length * 4];
        b[0] = 2;
        b[2] = (byte) (encs.length >> 8);
        b[3] = (byte) encs.length;
        for (int i = 0; i < encs.length; i++) {
            int e = encs[i];
            b[4 + i * 4] = (byte) (e >> 24);
            b[5 + i * 4] = (byte) (e >> 16);
            b[6 + i * 4] = (byte) (e >> 8);
            b[7 + i * 4] = (byte) e;
        }
        write(b);
    }

    private void requestUpdate(boolean incremental) throws IOException {
        int w, h;
        synchronized (lock) {
            w = fbWidth;
            h = fbHeight;
        }
        write(new byte[]{3, (byte) (incremental ? 1 : 0), 0, 0, 0, 0,
                (byte) (w >> 8), (byte) w, (byte) (h >> 8), (byte) h});
    }

    private void pointer(int mask, int x, int y) {
        lastFx = x;
        lastFy = y;
        sendLater(new byte[]{5, (byte) mask, (byte) (x >> 8), (byte) x, (byte) (y >> 8), (byte) y});
    }

    void key(int keysym, boolean down) {
        sendLater(new byte[]{4, (byte) (down ? 1 : 0), 0, 0, (byte) (keysym >> 24), (byte) (keysym >> 16),
                (byte) (keysym >> 8), (byte) keysym});
    }

    /** キーを 1 回押して離す。操作ボタンで指定した Ctrl・Alt も付ける */
    void tapKey(int keysym, boolean ctrl, boolean alt) {
        tapKey(keysym, ctrl, alt, false, false);
    }

    /** Shift・Meta (Win) も付けられる版 (専用キーボードやつないだキーボードから) */
    void tapKey(int keysym, boolean ctrl, boolean alt, boolean shift, boolean meta) {
        boolean c = ctrl || ctrlNext, a = alt || altNext;
        if (meta) key(XK_SUPER_L, true);
        if (shift) key(XK_SHIFT_L, true);
        if (c) key(XK_CONTROL_L, true);
        if (a) key(XK_ALT_L, true);
        key(keysym, true);
        key(keysym, false);
        if (a) key(XK_ALT_L, false);
        if (c) key(XK_CONTROL_L, false);
        if (shift) key(XK_SHIFT_L, false);
        if (meta) key(XK_SUPER_L, false);
        if (ctrlNext || altNext) {
            ctrlNext = altNext = false;
            if (onModifiersUsed != null) onModifiersUsed.run();
        }
    }

    /** 文字をキーとして送る。ASCII と Latin-1 はその番号、それ以外は Unicode の番号 + 0x01000000 */
    void typeText(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\n') tapKey(XK_RETURN, false, false);
            else if (cp == '\t') tapKey(XK_TAB, false, false);
            else if (cp >= 0x20 && cp <= 0x7e || cp >= 0xa0 && cp <= 0xff) tapKey(cp, false, false);
            else if (cp > 0xff) tapKey(0x01000000 | cp, false, false);
        }
    }

    /** 今のマウスの位置で、ボタン (1 = 左、4 = 右、8・16 = ホイール上下) を押して離す */
    void click(int button) {
        pointer(buttons | button, lastFx, lastFy);
        pointer(buttons, lastFx, lastFy);
    }

    /** Android のクリップボードの文字を X へ渡す (X 側で中ボタンや Shift+Insert で貼り付けられる) */
    void sendClipboard(String text) {
        byte[] t = text.getBytes(StandardCharsets.ISO_8859_1);
        byte[] b = new byte[8 + t.length];
        b[0] = 6;
        b[4] = (byte) (t.length >> 24);
        b[5] = (byte) (t.length >> 16);
        b[6] = (byte) (t.length >> 8);
        b[7] = (byte) t.length;
        System.arraycopy(t, 0, b, 8, t.length);
        sendLater(b);
    }

    // ------------------------------------------------------------------ 表示

    /** 拡大を元に戻す (画面全体が収まる大きさ) */
    void resetZoom() {
        zoom = 1f;
        panX = panY = 0;
        invalidate();
    }

    /** 画面に収まる倍率と、そのときの左上の位置 {倍率, x, y} */
    private float[] layout() {
        int w, h;
        synchronized (lock) {
            w = fbWidth;
            h = fbHeight;
        }
        if (w == 0 || h == 0 || getWidth() == 0) return null;
        float fit = Math.min(getWidth() / (float) w, getHeight() / (float) h);
        float s = fit * zoom;
        float ox = (getWidth() - w * fit) / 2f * zoom + panX;
        float oy = (getHeight() - h * fit) / 2f * zoom + panY;
        // 拡大していないときは真ん中に置く。拡大したときは画面の外へ行き過ぎないようにする
        if (zoom > 1f) {
            float minX = Math.min(0, getWidth() - w * s), minY = Math.min(0, getHeight() - h * s);
            float cx = Math.max(minX, Math.min(Math.max(0, (getWidth() - w * s) / 2), ox));
            float cy = Math.max(minY, Math.min(Math.max(0, (getHeight() - h * s) / 2), oy));
            panX += cx - ox;
            panY += cy - oy;
            ox = cx;
            oy = cy;
        } else {
            panX = panY = 0;
        }
        return new float[]{s, ox, oy};
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float[] l = layout();
        if (l == null) return;
        matrix.setScale(l[0], l[0]);
        matrix.postTranslate(l[1], l[2]);
        // 拡大したときは点をそのまま大きく (ぼかさない) して、文字を読みやすくする
        paint.setFilterBitmap(l[0] < 1f);
        synchronized (lock) {
            if (bitmap != null) canvas.drawBitmap(bitmap, matrix, paint);
        }
    }

    // ------------------------------------------------------------------ 指の操作

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaler.onTouchEvent(e);
        float[] l = layout();
        if (l == null) return true;
        int fx = (int) ((e.getX() - l[1]) / l[0]), fy = (int) ((e.getY() - l[2]) / l[0]);
        fx = Math.max(0, Math.min(fbWidth - 1, fx));
        fy = Math.max(0, Math.min(fbHeight - 1, fy));
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                requestFocus();
                downX = e.getX();
                downY = e.getY();
                pressed = true;
                dragging = multi = longFired = false;
                pointer(buttons, fx, fy);
                ui.postDelayed(longPress, 550);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                // 2 本目の指: マウスの操作をやめて、拡大と移動に切り替える
                multi = true;
                ui.removeCallbacks(longPress);
                if (buttons != 0) {
                    buttons = 0;
                    pointer(0, lastFx, lastFy);
                }
                downX = midX(e);
                downY = midY(e);
                break;
            case MotionEvent.ACTION_MOVE:
                if (multi) {
                    if (e.getPointerCount() >= 2) {
                        panX += midX(e) - downX;
                        panY += midY(e) - downY;
                        downX = midX(e);
                        downY = midY(e);
                        invalidate();
                    }
                    break;
                }
                if (longFired) break;
                if (!dragging && Math.hypot(e.getX() - downX, e.getY() - downY) > 12 * getResources()
                        .getDisplayMetrics().density) {
                    dragging = true;
                    ui.removeCallbacks(longPress);
                    // なぞり始めた場所で左ボタンを押す
                    float[] l0 = layout();
                    int sx = (int) ((downX - l0[1]) / l0[0]), sy = (int) ((downY - l0[2]) / l0[0]);
                    buttons = 1;
                    pointer(buttons, Math.max(0, Math.min(fbWidth - 1, sx)), Math.max(0, Math.min(fbHeight - 1, sy)));
                }
                if (dragging) pointer(buttons, fx, fy);
                break;
            case MotionEvent.ACTION_UP:
                ui.removeCallbacks(longPress);
                pressed = false;
                if (multi) break;
                if (dragging) {
                    buttons = 0;
                    pointer(0, fx, fy);
                } else if (!longFired) {
                    pointer(0, fx, fy);
                    click(rightNext ? 4 : 1);
                    if (rightNext) {
                        rightNext = false;
                        if (onModifiersUsed != null) onModifiersUsed.run();
                    }
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                ui.removeCallbacks(longPress);
                pressed = false;
                if (buttons != 0) {
                    buttons = 0;
                    pointer(0, fx, fy);
                }
                break;
            default:
        }
        return true;
    }

    private static float midX(MotionEvent e) {
        return e.getPointerCount() < 2 ? e.getX() : (e.getX(0) + e.getX(1)) / 2f;
    }

    private static float midY(MotionEvent e) {
        return e.getPointerCount() < 2 ? e.getY() : (e.getY(0) + e.getY(1)) / 2f;
    }

    // ------------------------------------------------------------------ キーボード

    void showKeyboard() {
        requestFocus();
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo info) {
        // 変換や予測をさせず、打った文字がそのまま届くようにする
        info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN;
        return new BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence t, int newCursorPosition) {
                typeText(t.toString());
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int before, int after) {
                for (int i = 0; i < before; i++) tapKey(XK_BACKSPACE, false, false);
                for (int i = 0; i < after; i++) tapKey(XK_DELETE, false, false);
                return true;
            }
        };
    }

    /** Android のキー番号を X のキーに。文字のキーは 0 (文字として送る) */
    static int keysym(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return XK_RETURN;
            case KeyEvent.KEYCODE_DEL: return XK_BACKSPACE;
            case KeyEvent.KEYCODE_FORWARD_DEL: return XK_DELETE;
            case KeyEvent.KEYCODE_TAB: return XK_TAB;
            case KeyEvent.KEYCODE_ESCAPE: return XK_ESCAPE;
            case KeyEvent.KEYCODE_DPAD_LEFT: return XK_LEFT;
            case KeyEvent.KEYCODE_DPAD_UP: return XK_UP;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return XK_RIGHT;
            case KeyEvent.KEYCODE_DPAD_DOWN: return XK_DOWN;
            case KeyEvent.KEYCODE_MOVE_HOME: return XK_HOME;
            case KeyEvent.KEYCODE_MOVE_END: return XK_END;
            case KeyEvent.KEYCODE_PAGE_UP: return XK_PAGE_UP;
            case KeyEvent.KEYCODE_PAGE_DOWN: return XK_PAGE_DOWN;
            case KeyEvent.KEYCODE_INSERT: return XK_INSERT;
            default:
        }
        if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) {
            return XK_F1 + (keyCode - KeyEvent.KEYCODE_F1);
        }
        return 0;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, e);
        int sym = keysym(keyCode);
        if (sym != 0) {
            // 特別なキー (矢印・F1〜F12 など) は Shift・Meta (Win) も付けて送る (Shift+Tab・Win+E など)
            tapKey(sym, e.isCtrlPressed(), e.isAltPressed(), e.isShiftPressed(), e.isMetaPressed());
            return true;
        }
        // 文字のキー: Ctrl・Alt・Meta を外した文字を、その修飾キーと一緒に送る (Shift は文字に反映済み)
        int c = e.getUnicodeChar(e.getMetaState()
                & ~(KeyEvent.META_CTRL_MASK | KeyEvent.META_ALT_MASK | KeyEvent.META_META_MASK));
        if (keyCode == KeyEvent.KEYCODE_SPACE) c = ' ';
        if (c != 0 && (c & KeyCharacterMapCombining) == 0) {
            int s = c <= 0xff ? c : 0x01000000 | c;
            tapKey(s, e.isCtrlPressed(), e.isAltPressed(), false, e.isMetaPressed());
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    private static final int KeyCharacterMapCombining = android.view.KeyCharacterMap.COMBINING_ACCENT;

    /** 入力を順番どおりに送るための 1 本のスレッド */
    private static final class Sender {
        private static final java.util.concurrent.ExecutorService POOL =
                java.util.concurrent.Executors.newSingleThreadExecutor();

        static void post(Runnable r) {
            POOL.execute(r);
        }
    }
}
