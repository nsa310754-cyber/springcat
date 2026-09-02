package site.ragdollp.springkey.receiver;

import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 送信側から届いた 1 行の JSON を解釈し、
 * 文字は IME（無ければアクセシビリティ）へ、マウス/ナビはアクセシビリティへ振り分ける。
 */
public class InputDispatcher {

    /** @return 画面ログに出す短い説明（無ければ null） */
    public static String handle(String line) {
        if (line == null) return null;
        line = line.trim();
        if (line.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(line);
            String t = o.optString("t");
            switch (t) {
                case "txt": {
                    String s = o.optString("s");
                    if (s.isEmpty()) return null;
                    if (!(ime() != null && ime().commit(s))) {
                        if (acc() != null) acc().typeIntoFocused(s);
                    }
                    return "文字: " + s;
                }
                case "key": {
                    return key(o.optString("k"));
                }
                case "nav": {
                    String k = o.optString("k");
                    if (acc() != null) acc().nav(k);
                    return "ナビ: " + k;
                }
                case "m": {
                    if (acc() != null) acc().moveCursor((float) o.optDouble("dx", 0), (float) o.optDouble("dy", 0));
                    return null;
                }
                case "click": {
                    String b = o.optString("b", "left");
                    if (acc() != null) acc().click(b);
                    return "クリック: " + b;
                }
                case "scroll": {
                    if (acc() != null) acc().scroll((float) o.optDouble("dx", 0), (float) o.optDouble("dy", 0));
                    return null;
                }
                case "shortcut": {
                    return shortcut(o.optJSONArray("keys"));
                }
                default:
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String key(String k) {
        if (k == null) return null;
        switch (k) {
            case "backspace":
                if (!(ime() != null && ime().backspace())) {
                    if (acc() != null) acc().backspaceFocused();
                }
                return "⌫";
            case "enter":
                if (!(ime() != null && ime().sendKey(KeyEvent.KEYCODE_ENTER))) {
                    if (acc() != null) acc().typeIntoFocused("\n");
                }
                return "Enter";
            case "tab":
                if (!(ime() != null && ime().sendKey(KeyEvent.KEYCODE_TAB))) {
                    if (acc() != null) acc().typeIntoFocused("\t");
                }
                return "Tab";
            case "space":
                if (!(ime() != null && ime().commit(" "))) {
                    if (acc() != null) acc().typeIntoFocused(" ");
                }
                return "␣";
            default: {
                int code = keyCode(k);
                if (code != 0 && ime() != null) ime().sendKey(code);
                return k;
            }
        }
    }

    private static String shortcut(JSONArray keys) {
        if (keys == null || keys.length() == 0) return null;
        int meta = 0;
        String last = null;
        StringBuilder label = new StringBuilder();
        for (int i = 0; i < keys.length(); i++) {
            String s = keys.optString(i);
            label.append(i == 0 ? "" : "+").append(s);
            switch (s) {
                case "ctrl": meta |= KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON; break;
                case "alt": meta |= KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON; break;
                case "shift": meta |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON; break;
                case "meta": meta |= KeyEvent.META_META_ON | KeyEvent.META_META_LEFT_ON; break;
                default: last = s;
            }
        }
        if (last != null) {
            int code = keyCode(last);
            if (code != 0 && ime() != null) ime().sendKeyWithMeta(code, meta);
        }
        return "⌨ " + label;
    }

    /** キー名 → Android KeyCode */
    private static int keyCode(String k) {
        if (k == null || k.isEmpty()) return 0;
        switch (k) {
            case "left": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "right": return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "up": return KeyEvent.KEYCODE_DPAD_UP;
            case "down": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "home": return KeyEvent.KEYCODE_MOVE_HOME;
            case "end": return KeyEvent.KEYCODE_MOVE_END;
            case "pageup": return KeyEvent.KEYCODE_PAGE_UP;
            case "pagedown": return KeyEvent.KEYCODE_PAGE_DOWN;
            case "insert": return KeyEvent.KEYCODE_INSERT;
            case "del": return KeyEvent.KEYCODE_FORWARD_DEL;
            case "esc": return KeyEvent.KEYCODE_ESCAPE;
            case "enter": return KeyEvent.KEYCODE_ENTER;
            case "tab": return KeyEvent.KEYCODE_TAB;
        }
        if (k.length() == 1) {
            char c = k.charAt(0);
            if (c >= 'a' && c <= 'z') return KeyEvent.KEYCODE_A + (c - 'a');
            if (c >= 'A' && c <= 'Z') return KeyEvent.KEYCODE_A + (c - 'A');
            if (c >= '0' && c <= '9') return KeyEvent.KEYCODE_0 + (c - '0');
        }
        if (k.length() >= 2 && (k.charAt(0) == 'f' || k.charAt(0) == 'F')) {
            try {
                int n = Integer.parseInt(k.substring(1));
                if (n >= 1 && n <= 12) return KeyEvent.KEYCODE_F1 + (n - 1);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private static SpringKeyIme ime() { return SpringKeyIme.get(); }
    private static SpringKeyAccessibilityService acc() { return SpringKeyAccessibilityService.get(); }
}
