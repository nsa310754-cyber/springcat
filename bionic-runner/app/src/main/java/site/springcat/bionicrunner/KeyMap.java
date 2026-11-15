package site.springcat.bionicrunner;

import android.view.KeyEvent;

/** Android のキーコードを SDL のスキャンコード (USB HID の番号) に直す */
final class KeyMap {

    private KeyMap() {
    }

    static final int UP = 82, DOWN = 81, LEFT = 80, RIGHT = 79;
    static final int Z = 29, X = 27, SPACE = 44, ENTER = 40, ESCAPE = 41;

    /** 対応するものが無ければ 0 */
    static int scancode(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            return 4 + (keyCode - KeyEvent.KEYCODE_A);
        }
        if (keyCode >= KeyEvent.KEYCODE_1 && keyCode <= KeyEvent.KEYCODE_9) {
            return 30 + (keyCode - KeyEvent.KEYCODE_1);
        }
        if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) {
            return 58 + (keyCode - KeyEvent.KEYCODE_F1);
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_0: return 39;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_START: return ENTER;
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BUTTON_SELECT: return ESCAPE;
            case KeyEvent.KEYCODE_DEL: return 42;
            case KeyEvent.KEYCODE_TAB: return 43;
            case KeyEvent.KEYCODE_SPACE: return SPACE;
            case KeyEvent.KEYCODE_MINUS: return 45;
            case KeyEvent.KEYCODE_EQUALS: return 46;
            case KeyEvent.KEYCODE_LEFT_BRACKET: return 47;
            case KeyEvent.KEYCODE_RIGHT_BRACKET: return 48;
            case KeyEvent.KEYCODE_BACKSLASH: return 49;
            case KeyEvent.KEYCODE_SEMICOLON: return 51;
            case KeyEvent.KEYCODE_APOSTROPHE: return 52;
            case KeyEvent.KEYCODE_GRAVE: return 53;
            case KeyEvent.KEYCODE_COMMA: return 54;
            case KeyEvent.KEYCODE_PERIOD: return 55;
            case KeyEvent.KEYCODE_SLASH: return 56;
            case KeyEvent.KEYCODE_INSERT: return 73;
            case KeyEvent.KEYCODE_MOVE_HOME: return 74;
            case KeyEvent.KEYCODE_PAGE_UP: return 75;
            case KeyEvent.KEYCODE_FORWARD_DEL: return 76;
            case KeyEvent.KEYCODE_MOVE_END: return 77;
            case KeyEvent.KEYCODE_PAGE_DOWN: return 78;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return RIGHT;
            case KeyEvent.KEYCODE_DPAD_LEFT: return LEFT;
            case KeyEvent.KEYCODE_DPAD_DOWN: return DOWN;
            case KeyEvent.KEYCODE_DPAD_UP: return UP;
            case KeyEvent.KEYCODE_CTRL_LEFT: return 224;
            case KeyEvent.KEYCODE_SHIFT_LEFT: return 225;
            case KeyEvent.KEYCODE_ALT_LEFT: return 226;
            case KeyEvent.KEYCODE_CTRL_RIGHT: return 228;
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return 229;
            case KeyEvent.KEYCODE_ALT_RIGHT: return 230;
            // ゲームパッド: よくある割り当て (決定 = Z、キャンセル = X) に寄せる
            case KeyEvent.KEYCODE_BUTTON_A: return Z;
            case KeyEvent.KEYCODE_BUTTON_B: return X;
            default: return 0;
        }
    }
}
