package site.springcat.bionicrunner;

/**
 * Windows のアイコンファイル (.ico) を読む。Android の画像読み込みは .ico に対応していないため。
 *
 * .ico には大きさ違いの絵が何枚か入っている。いちばん大きく色数の多い 1 枚を選び、
 * 中身が PNG ならそのバイト列を、昔ながらの形式 (BMP) なら画素の配列に直して返す。
 * Android の API は使っていないので、PC 上の JVM でも単体で検証できる。
 */
final class IcoReader {

    /** 取り出した 1 枚。png が null でなければ PNG のバイト列、そうでなければ argb に画素が入っている */
    static final class Image {
        byte[] png;
        int width, height;
        /** 1 画素 = 0xAARRGGBB。上の行から順 */
        int[] argb;
    }

    private IcoReader() {
    }

    static boolean isIco(byte[] d) {
        return d.length >= 22 && d[0] == 0 && d[1] == 0 && d[2] == 1 && d[3] == 0 && u16(d, 4) > 0;
    }

    /** 読めなければ null */
    static Image read(byte[] d) {
        if (!isIco(d)) return null;
        int count = u16(d, 4), best = -1, bestScore = -1;
        for (int i = 0; i < count; i++) {
            int e = 6 + i * 16;
            if (e + 16 > d.length) break;
            int w = d[e] & 255, bpp = u16(d, e + 6);
            if (w == 0) w = 256;
            int score = w * 64 + Math.min(bpp, 32);
            if (score > bestScore) {
                bestScore = score;
                best = e;
            }
        }
        if (best < 0) return null;
        long size = u32(d, best + 8), off = u32(d, best + 12);
        if (off < 0 || size <= 0 || off + size > d.length) return null;
        int at = (int) off, len = (int) size;
        Image img = new Image();
        if (len >= 8 && (d[at] & 255) == 0x89 && d[at + 1] == 'P' && d[at + 2] == 'N' && d[at + 3] == 'G') {
            img.png = java.util.Arrays.copyOfRange(d, at, at + len);
            return img;
        }
        try {
            return dib(d, at, len, img) ? img : null;
        } catch (RuntimeException e) {
            return null; // 壊れたファイル
        }
    }

    /**
     * BMP の中身 (ヘッダ + 色の表 + 画素 + 透明かどうかの 1 ビットの表)。
     * 高さは「絵 + 透明の表」の 2 倍で書かれていて、行は下から順に並ぶ。
     */
    private static boolean dib(byte[] d, int at, int len, Image img) {
        if (len < 40 || u32(d, at) < 40) return false;
        int header = (int) u32(d, at);
        int w = (int) u32(d, at + 4), h = (int) u32(d, at + 8) / 2;
        int bpp = u16(d, at + 14);
        if (w <= 0 || h <= 0 || w > 1024 || h > 1024 || u32(d, at + 16) != 0) return false;
        int colors = bpp <= 8 ? (int) u32(d, at + 32) : 0;
        if (bpp <= 8 && colors == 0) colors = 1 << bpp;
        int palette = at + header;
        int pixels = palette + colors * 4;
        int stride = ((w * bpp + 31) / 32) * 4;
        int mask = pixels + stride * h;
        int maskStride = ((w + 31) / 32) * 4;
        boolean hasMask = mask + maskStride * h <= at + len;
        if (pixels + stride * h > at + len) return false;
        int[] out = new int[w * h];
        boolean anyAlpha = false;
        for (int y = 0; y < h; y++) {
            int row = pixels + (h - 1 - y) * stride;
            for (int x = 0; x < w; x++) {
                int c;
                switch (bpp) {
                    case 32: {
                        int p = row + x * 4;
                        int a = d[p + 3] & 255;
                        if (a != 0) anyAlpha = true;
                        c = a << 24 | (d[p + 2] & 255) << 16 | (d[p + 1] & 255) << 8 | (d[p] & 255);
                        break;
                    }
                    case 24: {
                        int p = row + x * 3;
                        c = 0xFF000000 | (d[p + 2] & 255) << 16 | (d[p + 1] & 255) << 8 | (d[p] & 255);
                        break;
                    }
                    case 8:
                    case 4:
                    case 1: {
                        int bit = x * bpp;
                        int v = ((d[row + bit / 8] & 255) >> (8 - bpp - bit % 8)) & ((1 << bpp) - 1);
                        int p = palette + v * 4;
                        c = 0xFF000000 | (d[p + 2] & 255) << 16 | (d[p + 1] & 255) << 8 | (d[p] & 255);
                        break;
                    }
                    default:
                        return false;
                }
                out[y * w + x] = c;
            }
        }
        // 32 ビットで透明度がすべて 0 のものは「透明度を使っていない」古い書き方なので、表の方を使う
        boolean useMask = hasMask && (bpp != 32 || !anyAlpha);
        if (bpp == 32 && !anyAlpha && !hasMask) {
            for (int i = 0; i < out.length; i++) out[i] |= 0xFF000000;
        }
        if (useMask) {
            for (int y = 0; y < h; y++) {
                int row = mask + (h - 1 - y) * maskStride;
                for (int x = 0; x < w; x++) {
                    boolean transparent = ((d[row + x / 8] & 255) >> (7 - x % 8) & 1) != 0;
                    out[y * w + x] = transparent ? 0 : out[y * w + x] | 0xFF000000;
                }
            }
        }
        img.width = w;
        img.height = h;
        img.argb = out;
        return true;
    }

    private static int u16(byte[] b, int o) {
        return (b[o] & 255) | (b[o + 1] & 255) << 8;
    }

    private static long u32(byte[] b, int o) {
        return (u16(b, o) | (long) u16(b, o + 2) << 16) & 0xFFFFFFFFL;
    }
}
