package site.springcat.bionicrunner;

import java.util.HashMap;
import java.util.Map;

/**
 * ローマ字をひらがなに直す (専用キーボードのローマ字入力)。Windows の日本語入力とほぼ同じ綴り:
 *   ka → か、shi/si → し、tsu/tu → つ、kya → きゃ、nn / n' → ん、子音を 2 つ重ねる (kka) → っか、
 *   xa / la → ぁ (小さい字)、xtu / ltu → っ、- → ー、 , → 、 . → 。 [ ] → 「 」
 * 打った内容はどこにも残さない (この部品は文字を受け取って返すだけ)。
 */
final class RomajiKana {

    private static final Map<String, String> TABLE = new HashMap<>();
    /** 表の綴りの頭の部分 (まだ続きを待つべきか) */
    private static final java.util.Set<String> PREFIXES = new java.util.HashSet<>();

    private static void put(String romaji, String kana) {
        TABLE.put(romaji, kana);
        for (int i = 1; i < romaji.length(); i++) PREFIXES.add(romaji.substring(0, i));
    }

    /** 子音 c と、あいうえお の 5 つの段 */
    private static void row(String c, String a, String i, String u, String e, String o) {
        String[] v = {"a", "i", "u", "e", "o"}, k = {a, i, u, e, o};
        for (int j = 0; j < 5; j++) if (k[j] != null) put(c + v[j], k[j]);
    }

    static {
        row("", "あ", "い", "う", "え", "お");
        row("k", "か", "き", "く", "け", "こ");
        row("s", "さ", "し", "す", "せ", "そ");
        row("t", "た", "ち", "つ", "て", "と");
        row("n", "な", "に", "ぬ", "ね", "の");
        row("h", "は", "ひ", "ふ", "へ", "ほ");
        row("m", "ま", "み", "む", "め", "も");
        row("y", "や", "い", "ゆ", "いぇ", "よ");
        row("r", "ら", "り", "る", "れ", "ろ");
        row("w", "わ", "うぃ", "う", "うぇ", "を");
        row("g", "が", "ぎ", "ぐ", "げ", "ご");
        row("z", "ざ", "じ", "ず", "ぜ", "ぞ");
        row("d", "だ", "ぢ", "づ", "で", "ど");
        row("b", "ば", "び", "ぶ", "べ", "ぼ");
        row("p", "ぱ", "ぴ", "ぷ", "ぺ", "ぽ");
        row("c", "か", "し", "く", "せ", "こ");
        row("q", "くぁ", "くぃ", "く", "くぇ", "くぉ");
        row("f", "ふぁ", "ふぃ", "ふ", "ふぇ", "ふぉ");
        row("v", "ゔぁ", "ゔぃ", "ゔ", "ゔぇ", "ゔぉ");
        row("j", "じゃ", "じ", "じゅ", "じぇ", "じょ");
        row("l", "ぁ", "ぃ", "ぅ", "ぇ", "ぉ");
        row("x", "ぁ", "ぃ", "ぅ", "ぇ", "ぉ");
        // 拗音 (きゃ など)
        String[][] yo = {{"ky", "き"}, {"gy", "ぎ"}, {"sy", "し"}, {"zy", "じ"}, {"jy", "じ"}, {"ty", "ち"},
                {"cy", "ち"}, {"dy", "ぢ"}, {"ny", "に"}, {"hy", "ひ"}, {"by", "び"}, {"py", "ぴ"}, {"my", "み"},
                {"ry", "り"}, {"fy", "ふ"}, {"vy", "ゔ"}};
        for (String[] p : yo) row(p[0], p[1] + "ゃ", p[1] + "ぃ", p[1] + "ゅ", p[1] + "ぇ", p[1] + "ょ");
        row("sh", "しゃ", "し", "しゅ", "しぇ", "しょ");
        row("ch", "ちゃ", "ち", "ちゅ", "ちぇ", "ちょ");
        row("ts", "つぁ", "つぃ", "つ", "つぇ", "つぉ");
        row("th", "てゃ", "てぃ", "てゅ", "てぇ", "てょ");
        row("dh", "でゃ", "でぃ", "でゅ", "でぇ", "でょ");
        row("tw", "とぁ", "とぃ", "とぅ", "とぇ", "とぉ");
        row("dw", "どぁ", "どぃ", "どぅ", "どぇ", "どぉ");
        row("wh", "うぁ", "うぃ", "う", "うぇ", "うぉ");
        row("kw", "くぁ", "くぃ", "くぅ", "くぇ", "くぉ");
        row("gw", "ぐぁ", "ぐぃ", "ぐぅ", "ぐぇ", "ぐぉ");
        row("xy", "ゃ", "ぃ", "ゅ", "ぇ", "ょ");
        row("ly", "ゃ", "ぃ", "ゅ", "ぇ", "ょ");
        put("xtu", "っ");
        put("ltu", "っ");
        put("xtsu", "っ");
        put("ltsu", "っ");
        put("xwa", "ゎ");
        put("lwa", "ゎ");
        put("xka", "ゕ");
        put("xke", "ゖ");
        put("n'", "ん");
        put("xn", "ん");
        put("-", "ー");
        put(",", "、");
        put(".", "。");
        put("[", "「");
        put("]", "」");
        put("/", "・");
        put("~", "〜");
    }

    private final StringBuilder kana = new StringBuilder();
    private final StringBuilder pending = new StringBuilder();

    /** 1 文字 (小文字のローマ字か記号) を足す */
    void feed(char ch) {
        pending.append(ch);
        resolve(false);
    }

    /** ローマ字にせず、そのまま足す (大文字・数字など) */
    void feedRaw(char ch) {
        resolve(true);
        kana.append(ch);
    }

    private void resolve(boolean flush) {
        while (pending.length() > 0) {
            String p = pending.toString();
            String hit = TABLE.get(p);
            if (hit != null && !(p.equals("n") && !flush)) {
                kana.append(hit);
                pending.setLength(0);
                return;
            }
            if (!flush && PREFIXES.contains(p)) return; // 続きを待つ
            char c0 = p.charAt(0);
            if (p.startsWith("nn")) {
                // nn は「ん」。ただし、そのあとに母音か y が来たら (onna → おんな)、2 つ目の n は次の字の頭
                if (p.length() == 2 && !flush) return;
                kana.append('ん');
                boolean nextIsVowel = p.length() >= 3 && "aiueoy".indexOf(p.charAt(2)) >= 0;
                pending.delete(0, nextIsVowel ? 1 : 2);
                continue;
            }
            if (p.length() >= 2 && c0 == p.charAt(1) && c0 != 'n' && "bcdfghjklmpqrstvwxyz".indexOf(c0) >= 0) {
                kana.append('っ'); // 子音を重ねたら小さい「っ」
                pending.deleteCharAt(0);
                continue;
            }
            if (c0 == 'n' && (p.length() >= 2 || flush)) {
                kana.append('ん'); // n のあとに母音・y 以外が来たら「ん」
                pending.deleteCharAt(0);
                continue;
            }
            if (flush && p.length() == 1 && TABLE.containsKey(p)) {
                kana.append(TABLE.get(p));
                pending.setLength(0);
                return;
            }
            if (!flush && p.length() == 1) return;
            kana.append(c0); // ローマ字にならない文字はそのまま
            pending.deleteCharAt(0);
        }
    }

    /** 打ちかけの「n」なども含めて、かなに確定した文字列 */
    String finish() {
        resolve(true);
        return kana.toString();
    }

    /** 今の表示 (かな + 打ちかけのローマ字) */
    String display() {
        // nn は、続きを待っている間も「ん」と見せる (次に母音が来ると、2 つ目の n はその字の頭になる)
        String p = pending.toString();
        return kana + (p.equals("nn") ? "ん" : p);
    }

    boolean isEmpty() {
        return kana.length() == 0 && pending.length() == 0;
    }

    /** 1 文字消す (打ちかけのローマ字があればそれを、無ければかなを) */
    void backspace() {
        if (pending.length() > 0) pending.setLength(pending.length() - 1);
        else if (kana.length() > 0) kana.setLength(kana.length() - 1);
    }

    void clear() {
        kana.setLength(0);
        pending.setLength(0);
    }

    /** ひらがなをカタカナに */
    static String katakana(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c >= 'ぁ' && c <= 'ゖ' ? (char) (c + 0x60) : c);
        }
        return sb.toString();
    }

    /** かなの記号を半角の英数字の記号に戻す (英数に戻したいとき用) */
    static boolean isKanaMark(char c) {
        return "ー、。「」・〜".indexOf(c) >= 0;
    }
}
