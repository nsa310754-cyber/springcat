package site.springcat.bionicrunner;

import android.view.KeyEvent;

/**
 * キー (Android の keyCode と、Ctrl・Alt・Shift・Meta の押し具合) を、文字端末へ送るバイト列
 * (xterm と同じエスケープシーケンス) に変える。
 *
 *   矢印・Home・End          ESC [ A〜D・H・F (アプリケーションカーソルモードでは ESC O A〜)
 *   F1〜F4                   ESC O P〜S
 *   F5〜F12                  ESC [ 15~ 17~ 18~ 19~ 20~ 21~ 23~ 24~
 *   Ins・Del・PgUp・PgDn     ESC [ 2~ 3~ 5~ 6~
 *   修飾キーと一緒のとき     ESC [ 1;m A や ESC [ 15;m~ (m = 1 + Shift 1 + Alt 2 + Ctrl 4 + Meta 8)
 *   Ctrl + 文字              制御文字 (Ctrl-C = 0x03 など)
 *   Alt (Meta) + 文字        ESC を前に付ける
 *
 * 押したキーの中身は、変換して返すだけで、どこにも残さない (パスワードも打つため)。
 * Android の定数しか使わないので、PC の上でも単体で確かめられる。
 */
final class KeySeq {

    private KeySeq() {
    }

    /** 修飾キーの番号 (xterm の決まり) */
    static int modifierParam(int meta) {
        int m = 1;
        if ((meta & KeyEvent.META_SHIFT_ON) != 0) m += 1;
        if ((meta & KeyEvent.META_ALT_ON) != 0) m += 2;
        if ((meta & KeyEvent.META_CTRL_ON) != 0) m += 4;
        if ((meta & KeyEvent.META_META_ON) != 0) m += 8;
        return m;
    }

    /**
     * キーをバイト列に変える。変えられないキー (音量など) は null。
     * ch は、そのキーの文字 (Shift は反映、Ctrl・Alt・Meta は外したもの)。文字の無いキーは 0。
     * appCursor は、端末がアプリケーションカーソルモード (ESC [?1h) か。
     */
    static String encode(int keyCode, int meta, int ch, boolean appCursor) {
        boolean shift = (meta & KeyEvent.META_SHIFT_ON) != 0;
        boolean alt = (meta & KeyEvent.META_ALT_ON) != 0;
        boolean ctrl = (meta & KeyEvent.META_CTRL_ON) != 0;
        boolean metaKey = (meta & KeyEvent.META_META_ON) != 0;
        int m = modifierParam(meta);
        String esc = "\u001B";
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: return cursor('A', m, appCursor);
            case KeyEvent.KEYCODE_DPAD_DOWN: return cursor('B', m, appCursor);
            case KeyEvent.KEYCODE_DPAD_RIGHT: return cursor('C', m, appCursor);
            case KeyEvent.KEYCODE_DPAD_LEFT: return cursor('D', m, appCursor);
            case KeyEvent.KEYCODE_MOVE_HOME: return cursor('H', m, appCursor);
            case KeyEvent.KEYCODE_MOVE_END: return cursor('F', m, appCursor);
            case KeyEvent.KEYCODE_INSERT: return tilde(2, m);
            case KeyEvent.KEYCODE_FORWARD_DEL: return tilde(3, m);
            case KeyEvent.KEYCODE_PAGE_UP: return tilde(5, m);
            case KeyEvent.KEYCODE_PAGE_DOWN: return tilde(6, m);
            case KeyEvent.KEYCODE_F1: return ss3('P', m);
            case KeyEvent.KEYCODE_F2: return ss3('Q', m);
            case KeyEvent.KEYCODE_F3: return ss3('R', m);
            case KeyEvent.KEYCODE_F4: return ss3('S', m);
            case KeyEvent.KEYCODE_F5: return tilde(15, m);
            case KeyEvent.KEYCODE_F6: return tilde(17, m);
            case KeyEvent.KEYCODE_F7: return tilde(18, m);
            case KeyEvent.KEYCODE_F8: return tilde(19, m);
            case KeyEvent.KEYCODE_F9: return tilde(20, m);
            case KeyEvent.KEYCODE_F10: return tilde(21, m);
            case KeyEvent.KEYCODE_F11: return tilde(23, m);
            case KeyEvent.KEYCODE_F12: return tilde(24, m);
            case KeyEvent.KEYCODE_ESCAPE: return (alt || metaKey ? esc : "") + esc;
            case KeyEvent.KEYCODE_TAB:
                if (shift) return esc + "[Z";
                return (alt || metaKey ? esc : "") + "\t";
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return (alt || metaKey ? esc : "") + "\r";
            case KeyEvent.KEYCODE_DEL: // Backspace
                return (alt || metaKey ? esc : "") + (ctrl ? "\b" : "\u007F");
            default:
                break;
        }
        if (keyCode == KeyEvent.KEYCODE_SPACE) ch = ' ';
        if (ch <= 0) return null;
        if (ctrl) {
            int c = control(ch);
            if (c >= 0) ch = c;
        }
        String s = new String(Character.toChars(ch));
        return (alt || metaKey ? esc : "") + s;
    }

    /** Ctrl と一緒の文字を制御文字に (xterm と同じ)。制御文字にならなければ -1 */
    static int control(int ch) {
        if (ch >= 'a' && ch <= 'z') return ch - 'a' + 1;
        if (ch >= 'A' && ch <= 'Z') return ch - 'A' + 1;
        switch (ch) {
            case '@': case '2': case ' ': return 0;
            case '[': case '3': return 27;
            case '\\': case '4': return 28;
            case ']': case '5': return 29;
            case '^': case '6': return 30;
            case '_': case '7': case '-': case '/': return 31;
            case '?': case '8': return 127;
            default: return -1;
        }
    }

    private static String cursor(char c, int m, boolean appCursor) {
        if (m > 1) return "\u001B[1;" + m + c;
        return (appCursor ? "\u001BO" : "\u001B[") + c;
    }

    private static String ss3(char c, int m) {
        return m > 1 ? "\u001B[1;" + m + c : "\u001BO" + c;
    }

    private static String tilde(int n, int m) {
        return "\u001B[" + n + (m > 1 ? ";" + m : "") + "~";
    }

    /** KeyEvent から変える (文字は、Ctrl・Alt・Meta を外して求める) */
    static String encode(KeyEvent e, boolean appCursor) {
        int meta = e.getMetaState();
        int ch = e.getUnicodeChar(meta & ~(KeyEvent.META_CTRL_MASK | KeyEvent.META_ALT_MASK
                | KeyEvent.META_META_MASK));
        if ((ch & android.view.KeyCharacterMap.COMBINING_ACCENT) != 0) ch = 0;
        return encode(e.getKeyCode(), meta, ch, appCursor);
    }
}
