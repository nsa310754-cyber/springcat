package site.springcat.bionicrunner;

import java.util.Arrays;

/**
 * 文字端末の画面の中身 (どのマスに何色の何の文字があるか) を持ち、プログラムの出力に含まれる
 * 画面制御 (カーソル移動・色・消去などのエスケープシーケンス) を解釈する。
 * xterm が解釈するもののうち、文字で描くゲームがよく使う範囲に対応する。
 *
 * 描画は別のクラス ({@link TermGameActivity}) が行う。Android の API は使っていないので、
 * PC 上の JVM でも単体で検証できる。
 */
final class VtScreen {

    /** プログラムへの返事 (カーソル位置の問い合わせへの応答など) を送る先 */
    interface Reply {
        void send(String text);
    }

    static final int DEFAULT_FG = 0xFFD1D5DB, DEFAULT_BG = 0xFF000000;
    /** 全角文字の右半分を表す印 (左半分のマスに文字が入っている) */
    static final int WIDE_TAIL = -1;

    private static final int[] BASE = {
            0xFF000000, 0xFFCD3131, 0xFF0DBC79, 0xFFE5E510, 0xFF2472C8, 0xFFBC3FBC, 0xFF11A8CD,
            0xFFE5E5E5, 0xFF666666, 0xFFF14C4C, 0xFF23D18B, 0xFFF5F543, 0xFF3B8EEA, 0xFFD670D6,
            0xFF29B8DB, 0xFFFFFFFF};

    final int cols, rows;
    /** マスごとの文字 (Unicode の符号位置。空きは空白)・文字色・背景色 */
    final int[] ch, fg, bg;
    int curX, curY;
    boolean cursorVisible = true;

    private final Reply reply;
    private int penFg = DEFAULT_FG, penBg = DEFAULT_BG;
    private boolean bold, reverse;
    private int savedX, savedY;
    private int top, bottom; // スクロールする範囲 (行。bottom は含む)
    /** 右端まで書いた直後。次の文字で折り返す (その場ではまだ折り返さない) */
    private boolean wrapPending;

    // 解釈の途中経過
    private static final int GROUND = 0, ESC = 1, CSI = 2, OSC = 3, SKIP1 = 4;
    private int state = GROUND;
    private final int[] params = new int[16];
    private int paramCount;
    private boolean paramSeen, privateMode;
    private boolean oscEsc;
    // UTF-8 の途中経過
    private int utfLeft, utfCode;

    VtScreen(int cols, int rows, Reply reply) {
        this.cols = cols;
        this.rows = rows;
        this.reply = reply;
        ch = new int[cols * rows];
        fg = new int[cols * rows];
        bg = new int[cols * rows];
        reset();
    }

    private void reset() {
        penFg = DEFAULT_FG;
        penBg = DEFAULT_BG;
        bold = reverse = false;
        curX = curY = savedX = savedY = 0;
        top = 0;
        bottom = rows - 1;
        cursorVisible = true;
        wrapPending = false;
        erase(0, cols * rows);
    }

    private void erase(int from, int to) {
        Arrays.fill(ch, from, to, ' ');
        Arrays.fill(fg, from, to, penFg);
        Arrays.fill(bg, from, to, penBg);
    }

    /** プログラムの出力 (バイト列) を解釈して画面に反映する */
    synchronized void feed(byte[] data, int len) {
        for (int i = 0; i < len; i++) {
            int b = data[i] & 255;
            if (utfLeft > 0) {
                if ((b & 0xC0) == 0x80) {
                    utfCode = (utfCode << 6) | (b & 0x3F);
                    if (--utfLeft == 0) code(utfCode);
                    continue;
                }
                utfLeft = 0; // 壊れた並び。捨てて、このバイトから読み直す
            }
            if (b < 0x80) code(b);
            else if ((b & 0xE0) == 0xC0) start(b & 0x1F, 1);
            else if ((b & 0xF0) == 0xE0) start(b & 0x0F, 2);
            else if ((b & 0xF8) == 0xF0) start(b & 0x07, 3);
        }
    }

    synchronized void feed(String text) {
        byte[] b = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        feed(b, b.length);
    }

    private void start(int bits, int more) {
        utfCode = bits;
        utfLeft = more;
    }

    private void code(int c) {
        switch (state) {
            case ESC:
                escape(c);
                return;
            case CSI:
                csi(c);
                return;
            case OSC:
                // ウィンドウ名の設定など。画面には出さず、終わりの印 (BEL か ESC \) まで読み飛ばす
                if (c == 7 || (oscEsc && c == '\\')) state = GROUND;
                oscEsc = c == 27;
                return;
            case SKIP1:
                state = GROUND; // 文字集合の指定 (ESC ( B など) の 2 文字目
                return;
            default:
        }
        switch (c) {
            case 27:
                state = ESC;
                break;
            case '\r':
                curX = 0;
                wrapPending = false;
                break;
            case '\n':
            case 11:
            case 12:
                lineFeed();
                break;
            case '\b':
                if (curX > 0) curX--;
                wrapPending = false;
                break;
            case '\t':
                curX = Math.min(cols - 1, (curX / 8 + 1) * 8);
                break;
            case 7:
            case 0:
                break;
            default:
                if (c >= 32 && c != 127) put(c);
        }
    }

    private static boolean wide(int c) {
        return c >= 0x1100 && (c <= 0x115F || (c >= 0x2E80 && c <= 0xA4CF) || (c >= 0xAC00 && c <= 0xD7A3)
                || (c >= 0xF900 && c <= 0xFAFF) || (c >= 0xFE30 && c <= 0xFE4F)
                || (c >= 0xFF00 && c <= 0xFF60) || (c >= 0xFFE0 && c <= 0xFFE6)
                || (c >= 0x1F300 && c <= 0x1FAFF) || (c >= 0x20000 && c <= 0x3FFFD));
    }

    private void put(int c) {
        int w = wide(c) ? 2 : 1;
        if (wrapPending || curX + w > cols) {
            curX = 0;
            lineFeed();
        }
        int at = curY * cols + curX;
        int f = reverse ? penBg : penFg, b = reverse ? penFg : penBg;
        if (bold && !reverse) f = brighten(f);
        ch[at] = c;
        fg[at] = f;
        bg[at] = b;
        if (w == 2) {
            ch[at + 1] = WIDE_TAIL;
            fg[at + 1] = f;
            bg[at + 1] = b;
        }
        curX += w;
        if (curX >= cols) {
            curX = cols - 1;
            wrapPending = true;
        }
    }

    /** 太字は、基本 8 色なら明るい色で表す (多くの端末と同じ) */
    static int brighten(int color) {
        for (int i = 0; i < 8; i++) if (BASE[i] == color) return BASE[i + 8];
        return color;
    }

    private void lineFeed() {
        wrapPending = false;
        if (curY == bottom) scrollUp(1);
        else if (curY < rows - 1) curY++;
    }

    private void scrollUp(int n) {
        for (int k = 0; k < n; k++) {
            int from = (top + 1) * cols, to = top * cols, len = (bottom - top) * cols;
            System.arraycopy(ch, from, ch, to, len);
            System.arraycopy(fg, from, fg, to, len);
            System.arraycopy(bg, from, bg, to, len);
            erase(bottom * cols, (bottom + 1) * cols);
        }
    }

    private void scrollDown(int n) {
        for (int k = 0; k < n; k++) {
            int from = top * cols, to = (top + 1) * cols, len = (bottom - top) * cols;
            System.arraycopy(ch, from, ch, to, len);
            System.arraycopy(fg, from, fg, to, len);
            System.arraycopy(bg, from, bg, to, len);
            erase(top * cols, (top + 1) * cols);
        }
    }

    private void escape(int c) {
        state = GROUND;
        switch (c) {
            case '[':
                state = CSI;
                paramCount = 0;
                paramSeen = false;
                privateMode = false;
                Arrays.fill(params, 0);
                break;
            case ']':
            case 'P':
            case '_':
            case '^':
                state = OSC;
                oscEsc = false;
                break;
            case '(':
            case ')':
            case '*':
            case '+':
            case '#':
                state = SKIP1;
                break;
            case '7':
                savedX = curX;
                savedY = curY;
                break;
            case '8':
                curX = savedX;
                curY = savedY;
                wrapPending = false;
                break;
            case 'D':
                lineFeed();
                break;
            case 'E':
                curX = 0;
                lineFeed();
                break;
            case 'M': // 1 行上へ。いちばん上なら画面を下へずらす
                if (curY == top) scrollDown(1);
                else if (curY > 0) curY--;
                break;
            case 'c':
                reset();
                break;
            default:
        }
    }

    private void csi(int c) {
        if (c >= '0' && c <= '9') {
            if (paramCount < params.length) {
                params[paramCount] = Math.min(params[paramCount] * 10 + (c - '0'), 99999);
            }
            paramSeen = true;
            return;
        }
        if (c == ';' || c == ':') {
            if (paramCount < params.length) paramCount++;
            paramSeen = true;
            return;
        }
        if (c == '?' || c == '>' || c == '=' || c == '<') {
            privateMode = true;
            return;
        }
        if (c >= 0x20 && c <= 0x2F) return; // 中間の文字 (空白など)。無視して続ける
        if (c < 0x40) { // 制御文字が割り込んだ。中断する
            state = GROUND;
            return;
        }
        state = GROUND;
        int n = paramSeen ? Math.min(paramCount + 1, params.length) : 0;
        int p0 = n > 0 ? params[0] : 0, p1 = n > 1 ? params[1] : 0;
        int one = Math.max(1, p0); // 省略や 0 は 1 とみなす (移動量など)
        wrapPending = false;
        switch (c) {
            case 'A':
                curY = Math.max(curY > top ? top : 0, curY - one);
                break;
            case 'B':
            case 'e':
                curY = Math.min(curY <= bottom ? bottom : rows - 1, curY + one);
                break;
            case 'C':
            case 'a':
                curX = Math.min(cols - 1, curX + one);
                break;
            case 'D':
                curX = Math.max(0, curX - one);
                break;
            case 'E':
                curX = 0;
                curY = Math.min(rows - 1, curY + one);
                break;
            case 'F':
                curX = 0;
                curY = Math.max(0, curY - one);
                break;
            case 'G':
            case '`':
                curX = clamp(one - 1, cols);
                break;
            case 'd':
                curY = clamp(one - 1, rows);
                break;
            case 'H':
            case 'f':
                curY = clamp(one - 1, rows);
                curX = clamp(Math.max(1, p1) - 1, cols);
                break;
            case 'J': {
                int at = curY * cols + curX;
                if (p0 == 0) erase(at, cols * rows);
                else if (p0 == 1) erase(0, at + 1);
                else erase(0, cols * rows);
                break;
            }
            case 'K': {
                int row = curY * cols;
                if (p0 == 0) erase(row + curX, row + cols);
                else if (p0 == 1) erase(row, row + curX + 1);
                else erase(row, row + cols);
                break;
            }
            case 'X':
                erase(curY * cols + curX, curY * cols + Math.min(cols, curX + one));
                break;
            case 'L':
                if (curY >= top && curY <= bottom) {
                    int keep = top;
                    top = curY;
                    scrollDown(Math.min(one, bottom - curY + 1));
                    top = keep;
                }
                break;
            case 'M':
                if (curY >= top && curY <= bottom) {
                    int keep = top;
                    top = curY;
                    scrollUp(Math.min(one, bottom - curY + 1));
                    top = keep;
                }
                break;
            case 'P': { // その行の文字を削除して左へ詰める
                int row = curY * cols, k = Math.min(one, cols - curX), len = cols - curX - k;
                System.arraycopy(ch, row + curX + k, ch, row + curX, len);
                System.arraycopy(fg, row + curX + k, fg, row + curX, len);
                System.arraycopy(bg, row + curX + k, bg, row + curX, len);
                erase(row + cols - k, row + cols);
                break;
            }
            case '@': { // 空白を挿入して右へずらす
                int row = curY * cols, k = Math.min(one, cols - curX), len = cols - curX - k;
                System.arraycopy(ch, row + curX, ch, row + curX + k, len);
                System.arraycopy(fg, row + curX, fg, row + curX + k, len);
                System.arraycopy(bg, row + curX, bg, row + curX + k, len);
                erase(row + curX, row + curX + k);
                break;
            }
            case 'S':
                scrollUp(Math.min(one, rows));
                break;
            case 'T':
                scrollDown(Math.min(one, rows));
                break;
            case 'r':
                top = clamp(one - 1, rows);
                bottom = clamp((p1 == 0 ? rows : p1) - 1, rows);
                if (top >= bottom) {
                    top = 0;
                    bottom = rows - 1;
                }
                curX = curY = 0;
                break;
            case 's':
                savedX = curX;
                savedY = curY;
                break;
            case 'u':
                curX = savedX;
                curY = savedY;
                break;
            case 'm':
                sgr(n);
                break;
            case 'h':
            case 'l':
                if (privateMode) {
                    for (int i = 0; i < Math.max(1, n); i++) mode(params[i], c == 'h');
                }
                break;
            case 'n':
                if (p0 == 6) reply.send("\u001B[" + (curY + 1) + ";" + (curX + 1) + "R");
                else if (p0 == 5) reply.send("\u001B[0n");
                break;
            case 'c':
                if (!privateMode) reply.send("\u001B[?1;2c");
                break;
            default:
        }
    }

    private static int clamp(int v, int size) {
        return v < 0 ? 0 : v >= size ? size - 1 : v;
    }

    private void mode(int which, boolean on) {
        if (which == 25) {
            cursorVisible = on;
        } else if (which == 1049 || which == 47 || which == 1047) {
            // 「別画面」への切り替え。1 枚しか持たないので、消去で代える
            erase(0, cols * rows);
            curX = curY = 0;
        }
    }

    private void sgr(int n) {
        if (n == 0) n = 1; // ESC[m は ESC[0m と同じ
        for (int i = 0; i < n; i++) {
            int p = params[i];
            if (p == 0) {
                penFg = DEFAULT_FG;
                penBg = DEFAULT_BG;
                bold = reverse = false;
            } else if (p == 1) {
                bold = true;
            } else if (p == 22) {
                bold = false;
            } else if (p == 7) {
                reverse = true;
            } else if (p == 27) {
                reverse = false;
            } else if (p >= 30 && p <= 37) {
                penFg = BASE[p - 30];
            } else if (p == 39) {
                penFg = DEFAULT_FG;
            } else if (p >= 40 && p <= 47) {
                penBg = BASE[p - 40];
            } else if (p == 49) {
                penBg = DEFAULT_BG;
            } else if (p >= 90 && p <= 97) {
                penFg = BASE[p - 90 + 8];
            } else if (p >= 100 && p <= 107) {
                penBg = BASE[p - 100 + 8];
            } else if ((p == 38 || p == 48) && i + 1 < n) {
                int color;
                if (params[i + 1] == 5 && i + 2 < n) {
                    color = palette(params[i + 2]);
                    i += 2;
                } else if (params[i + 1] == 2 && i + 4 < n) {
                    color = 0xFF000000 | (params[i + 2] & 255) << 16 | (params[i + 3] & 255) << 8
                            | (params[i + 4] & 255);
                    i += 4;
                } else {
                    break;
                }
                if (p == 38) penFg = color;
                else penBg = color;
            }
        }
    }

    /** 256 色表: 0-15 は基本色、16-231 は 6x6x6 の色立方体、232-255 は灰色の段階 */
    static int palette(int i) {
        i &= 255;
        if (i < 16) return BASE[i];
        if (i < 232) {
            int v = i - 16, r = v / 36, g = v / 6 % 6, b = v % 6;
            return 0xFF000000 | level(r) << 16 | level(g) << 8 | level(b);
        }
        int gray = 8 + (i - 232) * 10;
        return 0xFF000000 | gray << 16 | gray << 8 | gray;
    }

    private static int level(int v) {
        return v == 0 ? 0 : 55 + v * 40;
    }

    /** 画面の文字だけを取り出す (検証用) */
    synchronized String text() {
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < rows; y++) {
            int end = cols;
            while (end > 0 && ch[y * cols + end - 1] == ' ') end--;
            for (int x = 0; x < end; x++) {
                int c = ch[y * cols + x];
                if (c != WIDE_TAIL) sb.appendCodePoint(c);
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
