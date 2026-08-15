package site.ragdollp.filemanager;

import java.util.ArrayList;
import java.util.List;

/**
 * 依存を増やさない超軽量な JSON 組み立てヘルパ。
 * コンテナ (オブジェクト/配列) ごとに「要素を既に書いたか」をスタックで管理し、
 * カンマ区切りを自動挿入する。値は必ずエスケープして出力する。
 */
final class JsonBuilder {

    private final StringBuilder sb = new StringBuilder();
    // 各コンテナが既に 1 要素以上を持つか (true=次はカンマが必要)
    private final List<Boolean> stack = new ArrayList<>();

    /** 現在のコンテナに新しい要素を書く直前の区切り処理。 */
    private void sep() {
        int i = stack.size() - 1;
        if (i >= 0) {
            if (stack.get(i)) sb.append(',');
            else stack.set(i, true);
        }
    }

    JsonBuilder obj() { sep(); sb.append('{'); stack.add(false); return this; }
    JsonBuilder endObj() { sb.append('}'); pop(); return this; }

    JsonBuilder objInArr() { sep(); sb.append('{'); stack.add(false); return this; }
    JsonBuilder endObjInArr() { sb.append('}'); pop(); return this; }

    JsonBuilder arr(String name) {
        sep();
        sb.append(quote(name)).append(':').append('[');
        stack.add(false);
        return this;
    }
    JsonBuilder endArr() { sb.append(']'); pop(); return this; }

    JsonBuilder kv(String k, String v) {
        sep();
        sb.append(quote(k)).append(':').append(v == null ? "null" : quote(v));
        return this;
    }
    JsonBuilder kv(String k, boolean v) {
        sep();
        sb.append(quote(k)).append(':').append(v ? "true" : "false");
        return this;
    }
    JsonBuilder kvNum(String k, long v) {
        sep();
        sb.append(quote(k)).append(':').append(v);
        return this;
    }

    JsonBuilder strInArr(String v) {
        sep();
        sb.append(v == null ? "null" : quote(v));
        return this;
    }

    private void pop() {
        if (!stack.isEmpty()) stack.remove(stack.size() - 1);
    }

    @Override public String toString() { return sb.toString(); }

    /** JSON 文字列リテラルへエスケープ (ダブルクォート込みで返す)。 */
    static String quote(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        b.append('"');
        return b.toString();
    }
}
