package site.springcat.bionicrunner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * かな漢字変換の辞書 (SKK 辞書。assets/skk/SKK-JISYO.L.txt)。
 * 行は「読み /候補1/候補2/…/」の形で、読みの順 (Java の文字列の順) に並べてあるので、二分探索で引く。
 * 送り仮名のある語は「かk /書/掛/…/」のように、送り仮名の最初の子音で引く (かく → かk + く)。
 *
 * 辞書は読むだけで、何も書き足さない (打った内容を覚えたり、保存したりしない)。
 */
final class SkkDict {

    private final String data;
    private final int[] starts;

    SkkDict(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(6 << 20);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        data = new String(bo.toByteArray(), StandardCharsets.UTF_8);
        int count = 0;
        for (int i = 0; i < data.length(); i++) if (data.charAt(i) == '\n') count++;
        starts = new int[count];
        int k = 0, s = 0;
        for (int i = 0; i < data.length() && k < count; i++) {
            if (data.charAt(i) == '\n') {
                starts[k++] = s;
                s = i + 1;
            }
        }
    }

    int size() {
        return starts.length;
    }

    /** 読みそのものの候補 (無ければ空) */
    List<String> lookup(String key) {
        int lo = 0, hi = starts.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int s = starts[mid];
            int sp = data.indexOf(" /", s);
            int cmp = data.substring(s, sp).compareTo(key);
            if (cmp == 0) {
                int end = data.indexOf('\n', sp);
                List<String> out = new ArrayList<>();
                for (String c : data.substring(sp + 2, end).split("/")) if (!c.isEmpty()) out.add(c);
                return out;
            }
            if (cmp < 0) lo = mid + 1;
            else hi = mid - 1;
        }
        return new ArrayList<>();
    }

    /** 送り仮名の最初の文字の子音 (SKK の決まり。あ行は母音そのもの) */
    static char okuriConsonant(char c) {
        String[][] rows = {
                {"あぁ", "a"}, {"いぃ", "i"}, {"うぅゔ", "u"}, {"えぇ", "e"}, {"おぉ", "o"},
                {"かきくけこ", "k"}, {"がぎぐげご", "g"}, {"さしすせそ", "s"}, {"ざじずぜぞ", "z"},
                {"たちつてとっ", "t"}, {"だぢづでど", "d"}, {"なにぬねの", "n"}, {"はひふへほ", "h"},
                {"ばびぶべぼ", "b"}, {"ぱぴぷぺぽ", "p"}, {"まみむめも", "m"}, {"やゃゆゅよょ", "y"},
                {"らりるれろ", "r"}, {"わゎを", "w"}, {"ん", "n"},
        };
        for (String[] r : rows) if (r[0].indexOf(c) >= 0) return r[1].charAt(0);
        return 0;
    }

    /**
     * 読み (ひらがな) から変換の候補を作る: 読みそのもの → 送り仮名のある語 (語幹が長い順) →
     * ひらがな → カタカナ。同じものは 1 つにする
     */
    List<String> candidates(String reading) {
        List<String> out = new ArrayList<>();
        List<String> exact = lookup(reading);
        for (String c : exact) addOnce(out, c);
        // 送り仮名が 3 文字までの語 (書く・書きます・言った など)
        List<String> longOkuri = new ArrayList<>();
        for (int i = reading.length() - 1; i >= 1; i--) {
            String stem = reading.substring(0, i), okuri = reading.substring(i);
            char cons = okuriConsonant(okuri.charAt(0));
            if (cons == 0) continue;
            for (String c : lookup(stem + cons)) {
                // 読みそのものが辞書にあるときは、送り仮名の長い候補 (似ほんご など) は後ろに回す
                if (okuri.length() <= (exact.isEmpty() ? 3 : 1)) addOnce(out, c + okuri);
                else longOkuri.add(c + okuri);
            }
        }
        // 辞書に無い長めの読みは、外来語のことが多いのでカタカナを先に (ばいおりん → バイオリン)
        if (exact.isEmpty() && reading.length() >= 4) addOnce(out, RomajiKana.katakana(reading));
        addOnce(out, reading);
        for (String c : longOkuri) addOnce(out, c);
        addOnce(out, RomajiKana.katakana(reading));
        return out;
    }

    private static void addOnce(List<String> out, String s) {
        if (!out.contains(s)) out.add(s);
    }
}
