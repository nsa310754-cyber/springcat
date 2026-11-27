package site.springcat.bionicrunner;

/**
 * コマンドプロンプトの出力 (シェルや実行したプログラムが出す文字) を読み解き、画面に出す形に振り分ける。
 *
 *  - ふだん (ログ表示): 文字を順に並べる。色の指定 (SGR: ESC[…m) は色として反映し、
 *    それ以外のエスケープシーケンスは文字として出さずに捨てる。
 *  - 画面全体に描くプログラム (画面消去 ESC[2J・カーソル位置の指定 ESC[行;列H・代替画面 ESC[?1049h
 *    のどれかを出したもの): 全画面モードに切り替え、そこからの出力はそのまま文字端末 ({@link VtScreen})
 *    へ渡す。プログラムが終わる (シェルのプロンプトが出る) か、代替画面を抜ける (ESC[?1049l) と、
 *    ログ表示に戻る。
 *
 * 出力は細切れに届くので、エスケープシーケンスの途中で区切れても正しく扱えるよう、1 文字ずつ読む。
 * 文字は既に正しく復号されたもの (UTF-8 の複数バイト文字が途中で切れていない文字列) を受け取る。
 * Android の API は使っていないので、PC 上の JVM でも単体で検証できる。
 */
final class AnsiLog {

    /** 振り分けた結果の受け取り手 */
    interface Sink {
        /** ログに文字を足す。fg / bg は 0xAARRGGBB の色、-1 は「指定なし (既定の色)」 */
        void text(String s, int fg, int bg, boolean bold);

        /** ログを消す (cls) */
        void clearLog();

        /** 全画面モードに切り替える。altScreen は代替画面に入ったか (ESC[?1049h など) */
        void enterFull(boolean altScreen);

        /** 全画面モードで、文字端末へ渡す出力 (エスケープシーケンスを含むそのままの形) */
        void full(String raw);

        /** 全画面モードを終えて、ログ表示に戻る */
        void exitFull();

        /** シェルがプロンプトを出した (実行していたコマンドが終わり、次の行を受け付ける) */
        default void prompt() {
        }
    }

    /** シェルのプロンプトの直前に出す印 (プログラムが終わった合図)。シェルの PS1 に入れる */
    static final char PROMPT = '';
    /** ログを消す合図 (cls が出す) */
    static final char CLEAR = '';

    private static final int NORMAL = 0, ESC = 1, CSI = 2, OSC = 3, SKIP1 = 4;
    private final Sink sink;
    private int state = NORMAL;
    /** いま読んでいるエスケープシーケンス (そのままの形) */
    private final StringBuilder seq = new StringBuilder();
    private boolean oscEsc;
    /** まとめて渡すために溜めている文字 (ログ表示では同じ色の文字、全画面モードでは出力そのまま) */
    private final StringBuilder run = new StringBuilder();
    private boolean full;
    private boolean lastWasCr;
    // ログ表示の色の状態
    private int fg = -1, bg = -1;
    private boolean bold, reverse;

    AnsiLog(Sink sink) {
        this.sink = sink;
    }

    boolean isFull() {
        return full;
    }

    /** 届いた出力を読む */
    void feed(CharSequence text) {
        for (int i = 0; i < text.length(); i++) step(text.charAt(i));
        flush();
    }

    /** 全画面モードを外から終える (「ログに戻る」ボタンなど) */
    void leaveFull() {
        if (!full) return;
        flush();
        full = false;
        state = NORMAL;
        seq.setLength(0);
        sink.exitFull();
    }

    private void step(char c) {
        if (c == PROMPT) {
            // プロンプトが出る = 実行していたプログラムが終わった
            if (state != NORMAL) state = NORMAL;
            seq.setLength(0);
            if (full) leaveFull();
            sink.prompt();
            return;
        }
        if (c == CLEAR) {
            if (full) leaveFull();
            flush();
            sink.clearLog();
            return;
        }
        switch (state) {
            case ESC:
                seq.append(c);
                escape(c);
                return;
            case CSI:
                seq.append(c);
                if (c >= 0x40 && c <= 0x7E) {
                    state = NORMAL;
                    csiDone();
                } else if (c < 0x20 && c != 0x1B) {
                    // 制御文字が割り込んだ: そのシーケンスは捨てる (VtScreen と同じ扱い)
                    state = NORMAL;
                    seq.setLength(0);
                }
                return;
            case OSC:
                seq.append(c);
                if (c == 7 || (oscEsc && c == '\\')) {
                    state = NORMAL;
                    sequenceDone(false);
                }
                oscEsc = c == 27;
                return;
            case SKIP1:
                seq.append(c);
                state = NORMAL;
                sequenceDone(false);
                return;
            default:
        }
        if (c == 27) {
            state = ESC;
            seq.setLength(0);
            seq.append(c);
            return;
        }
        if (full) {
            run.append(c);
            return;
        }
        plain(c);
    }

    private void escape(char c) {
        switch (c) {
            case '[':
                state = CSI;
                return;
            case ']':
            case 'P':
            case '_':
            case '^':
                state = OSC;
                oscEsc = false;
                return;
            case '(':
            case ')':
            case '*':
            case '+':
            case '#':
                state = SKIP1;
                return;
            default:
                state = NORMAL;
                // ESC c (端末の初期化) は、全画面で描くプログラムが最初に出すことが多い
                sequenceDone(c == 'c');
        }
    }

    /** CSI (ESC[ …) を 1 つ読み終えた */
    private void csiDone() {
        String s = seq.toString();
        char fin = s.charAt(s.length() - 1);
        String params = s.substring(2, s.length() - 1);
        boolean priv = params.startsWith("?");
        if (full) {
            run.append(s);
            seq.setLength(0);
            if (priv && fin == 'l' && altScreen(params)) {
                // 代替画面を抜けた: 全画面モードを終える (この出力までは画面に渡す)
                leaveFull();
            }
            return;
        }
        if (!priv && fin == 'm') {
            sgr(params);
            seq.setLength(0);
            return;
        }
        boolean enter = (priv && fin == 'h' && altScreen(params))
                || (!priv && fin == 'J' && (params.equals("2") || params.equals("3")))
                || (!priv && (fin == 'H' || fin == 'f'));
        if (enter) {
            flush();
            full = true;
            sink.enterFull(priv);
            run.append(s);
        }
        // それ以外 (ログ表示ではカーソル移動などは意味を持たない) は捨てる
        seq.setLength(0);
    }

    /** 代替画面の番号 (1049・47・1047) を含むか */
    private static boolean altScreen(String params) {
        for (String p : params.substring(1).split(";")) {
            if (p.equals("1049") || p.equals("47") || p.equals("1047")) return true;
        }
        return false;
    }

    /** CSI 以外のエスケープシーケンスを読み終えた。reset は ESC c */
    private void sequenceDone(boolean reset) {
        if (full) {
            run.append(seq);
        } else if (reset) {
            flush();
            full = true;
            sink.enterFull(false);
            run.append(seq);
        }
        // ログ表示では、そのほかのもの (ウィンドウ名の設定など) は捨てる
        seq.setLength(0);
    }

    /** ログ表示の文字 1 つ。改行の扱いはこれまでどおり (\r\n と \r は \n にする) */
    private void plain(char c) {
        if (c == '\r') {
            run.append('\n');
            lastWasCr = true;
            return;
        }
        if (c == '\n' && lastWasCr) {
            lastWasCr = false;
            return; // \r\n の \n (\r で改行済み)
        }
        lastWasCr = false;
        run.append(c);
    }

    private void flush() {
        if (run.length() == 0) return;
        String s = run.toString();
        run.setLength(0);
        if (full) {
            sink.full(s);
        } else {
            int f = reverse ? (bg == -1 ? VtScreen.DEFAULT_BG : bg) : fg;
            int b = reverse ? (fg == -1 ? VtScreen.DEFAULT_FG : fg) : bg;
            if (bold && f != -1) f = VtScreen.brighten(f);
            sink.text(s, f, b, bold);
        }
    }

    /** 色の指定 (ESC[…m) を読む。文字の途中で色が変わるので、溜めた文字を先に渡す */
    private void sgr(String params) {
        flush();
        String[] parts = params.isEmpty() ? new String[]{"0"} : params.split("[;:]", -1);
        int[] p = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                p[i] = parts[i].isEmpty() ? 0 : Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return; // 読めない指定は無視する
            }
        }
        for (int i = 0; i < p.length; i++) {
            int v = p[i];
            if (v == 0) {
                fg = bg = -1;
                bold = reverse = false;
            } else if (v == 1) {
                bold = true;
            } else if (v == 22) {
                bold = false;
            } else if (v == 7) {
                reverse = true;
            } else if (v == 27) {
                reverse = false;
            } else if (v >= 30 && v <= 37) {
                fg = VtScreen.palette(v - 30);
            } else if (v == 39) {
                fg = -1;
            } else if (v >= 40 && v <= 47) {
                bg = VtScreen.palette(v - 40);
            } else if (v == 49) {
                bg = -1;
            } else if (v >= 90 && v <= 97) {
                fg = VtScreen.palette(v - 90 + 8);
            } else if (v >= 100 && v <= 107) {
                bg = VtScreen.palette(v - 100 + 8);
            } else if ((v == 38 || v == 48) && i + 2 < p.length && p[i + 1] == 5) {
                int color = VtScreen.palette(p[i + 2]);
                if (v == 38) fg = color;
                else bg = color;
                i += 2;
            } else if ((v == 38 || v == 48) && i + 4 < p.length && p[i + 1] == 2) {
                int color = 0xFF000000 | (p[i + 2] & 255) << 16 | (p[i + 3] & 255) << 8 | (p[i + 4] & 255);
                if (v == 38) fg = color;
                else bg = color;
                i += 4;
            }
        }
    }
}
