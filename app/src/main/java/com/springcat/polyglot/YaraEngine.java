package com.springcat.polyglot;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small, self-contained YARA-compatible rule engine that runs entirely on the
 * device — no network, no native libraries. It implements a practical subset of
 * the YARA language:
 *
 *   strings:  text ("..."), hex ({ 4D 5A ?? [2-4] 90 }) and regex (/.../) with
 *             the nocase / wide / ascii / fullword modifiers.
 *   condition: and / or / not / parentheses, true / false, "$a",
 *              "all|any|N of them", "all|any|N of ($a, $b*, $*)",
 *              "#a <op> N", "filesize <op> N" (with KB / MB suffixes),
 *              and references to other rules by name.
 *
 * It is NOT a full libyara: modules (pe, math, hash…), string offsets/@, "at",
 * "in", and regex features beyond java.util.regex are not supported. Anything it
 * cannot parse is reported as an error rather than silently ignored.
 */
public final class YaraEngine {

    private YaraEngine() { }

    // ------------------------------------------------------------- model ----

    private enum Kind { TEXT, HEX, REGEX }

    private static final class YString {
        String id;
        Kind kind;
        boolean nocase, wide, ascii, fullword;
        String text;            // TEXT / REGEX source
        List<int[]> hex;        // HEX: each token {value, mask} or JUMP marker
    }

    private static final class Rule {
        String name;
        List<String> tags = new ArrayList<>();
        List<YString> strings = new ArrayList<>();
        String condition = "";
    }

    // ------------------------------------------------------------ public ----

    /** Scan {@code data} with the given rule text and return a formatted report. */
    public static String scan(String rulesText, byte[] data) {
        List<Rule> rules;
        try {
            rules = parseRules(rulesText);
        } catch (ParseException e) {
            return "Rule error: " + e.getMessage();
        }
        if (rules.isEmpty()) {
            return "No rules found. A rule looks like:\n\n"
                    + "rule Example {\n  strings:\n    $a = \"hello\"\n  condition:\n    $a\n}";
        }

        StringBuilder out = new StringBuilder();
        out.append("Scanned ").append(data.length).append(" byte(s).\n");

        Map<String, Boolean> ruleResults = new LinkedHashMap<>();
        List<String> matched = new ArrayList<>();
        List<String> details = new ArrayList<>();

        for (Rule r : rules) {
            // Pre-compute per-string hit counts against the data.
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (YString s : r.strings) {
                int c;
                try {
                    c = countMatches(s, data);
                } catch (Exception e) {
                    return "Rule '" + r.name + "', string " + s.id + ": " + e.getMessage();
                }
                counts.put(s.id, c);
            }

            boolean result;
            try {
                result = new CondEval(r, counts, data.length, ruleResults).eval();
            } catch (ParseException e) {
                return "Rule '" + r.name + "', condition: " + e.getMessage();
            }
            ruleResults.put(r.name, result);

            if (result) {
                StringBuilder d = new StringBuilder();
                d.append("  ✔ ").append(r.name);
                if (!r.tags.isEmpty()) d.append("  [").append(String.join(", ", r.tags)).append("]");
                d.append("\n");
                for (YString s : r.strings) {
                    int c = counts.get(s.id);
                    if (c > 0) d.append("      ").append(s.id).append("  ×").append(c).append("\n");
                }
                matched.add(r.name);
                details.add(d.toString());
            }
        }

        if (matched.isEmpty()) {
            out.append("\nNo rules matched.");
        } else {
            out.append(matched.size()).append(" rule(s) matched:\n");
            for (String d : details) out.append(d);
        }

        // List non-matching rules for context.
        List<String> notMatched = new ArrayList<>();
        for (Rule r : rules) if (!Boolean.TRUE.equals(ruleResults.get(r.name))) notMatched.add(r.name);
        if (!notMatched.isEmpty()) {
            out.append("\nNot matched: ").append(String.join(", ", notMatched));
        }
        return out.toString();
    }

    // -------------------------------------------------------- string match ---

    private static int countMatches(YString s, byte[] data) {
        switch (s.kind) {
            case TEXT:  return countText(s, data);
            case HEX:   return countHex(s.hex, data);
            case REGEX: return countRegex(s, data);
        }
        return 0;
    }

    private static boolean isWordByte(int b) {
        b &= 0xFF;
        return (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z')
                || (b >= '0' && b <= '9') || b == '_';
    }

    private static int fold(int b) {
        b &= 0xFF;
        if (b >= 'A' && b <= 'Z') return b + 32;
        return b;
    }

    private static int countText(YString s, byte[] data) {
        int total = 0;
        boolean ascii = s.ascii || !s.wide; // default is ascii unless only "wide"
        if (ascii) total += countTextForm(s, data, false);
        if (s.wide) total += countTextForm(s, data, true);
        return total;
    }

    private static int countTextForm(YString s, byte[] data, boolean wide) {
        String str = s.text;
        int step = wide ? 2 : 1;
        int len = str.length() * step;
        int count = 0;
        for (int off = 0; off + len <= data.length; off++) {
            boolean ok = true;
            for (int i = 0; i < str.length(); i++) {
                int db = data[off + i * step] & 0xFF;
                int cb = str.charAt(i) & 0xFF;
                if (s.nocase ? fold(db) != fold(cb) : db != cb) { ok = false; break; }
                if (wide && (data[off + i * step + 1] & 0xFF) != 0) { ok = false; break; }
            }
            if (!ok) continue;
            if (s.fullword) {
                int before = off - step >= 0 ? data[off - step] & 0xFF : -1;
                int after = off + len < data.length ? data[off + len] & 0xFF : -1;
                if (isWordByte(before) || isWordByte(after)) continue;
            }
            count++;
        }
        return count;
    }

    // hex token encoding: {value, mask} for a byte; {-1, min, max} for a jump.
    private static int countHex(List<int[]> tokens, byte[] data) {
        int count = 0;
        for (int off = 0; off < data.length; off++) {
            if (hexMatch(tokens, 0, data, off)) count++;
        }
        return count;
    }

    private static boolean hexMatch(List<int[]> tokens, int ti, byte[] data, int pos) {
        if (ti == tokens.size()) return true;
        int[] t = tokens.get(ti);
        if (t[0] == -1) { // jump [min-max]
            int min = t[1], max = t[2];
            for (int j = min; j <= max; j++) {
                if (pos + j <= data.length && hexMatch(tokens, ti + 1, data, pos + j)) return true;
            }
            return false;
        }
        if (pos >= data.length) return false;
        int value = t[0], mask = t[1];
        if (((data[pos] & 0xFF) & mask) != (value & mask)) return false;
        return hexMatch(tokens, ti + 1, data, pos + 1);
    }

    private static int countRegex(YString s, byte[] data) {
        // Map bytes 1:1 to chars via ISO-8859-1 so the regex is byte-accurate.
        String hay = new String(data, StandardCharsets.ISO_8859_1);
        int flags = Pattern.DOTALL;
        if (s.nocase) flags |= Pattern.CASE_INSENSITIVE;
        Pattern p = Pattern.compile(s.text, flags);
        Matcher m = p.matcher(hay);
        int count = 0;
        int from = 0;
        while (m.find(from)) {
            count++;
            from = Math.max(m.end(), m.start() + 1);
        }
        return count;
    }

    // --------------------------------------------------- condition eval -----

    private static final class CondEval {
        final Map<String, Integer> counts;
        final Set<String> ids;
        final long filesize;
        final Map<String, Boolean> rules;
        final List<String> tok;
        int p = 0;

        CondEval(Rule r, Map<String, Integer> counts, long filesize, Map<String, Boolean> rules) {
            this.counts = counts;
            this.ids = counts.keySet();
            this.filesize = filesize;
            this.rules = rules;
            this.tok = tokenize(r.condition);
        }

        boolean eval() {
            if (tok.isEmpty()) throw new ParseException("empty condition");
            boolean v = parseOr();
            if (p != tok.size()) throw new ParseException("unexpected '" + tok.get(p) + "'");
            return v;
        }

        String peek() { return p < tok.size() ? tok.get(p) : null; }
        boolean isKw(String k) { return k.equalsIgnoreCase(peek() == null ? "" : peek()); }
        String next() { return tok.get(p++); }
        void expect(String k) {
            if (!k.equals(peek())) throw new ParseException("expected '" + k + "'");
            p++;
        }

        boolean parseOr() {
            boolean v = parseAnd();
            while (isKw("or")) { p++; boolean r = parseAnd(); v = v || r; }
            return v;
        }
        boolean parseAnd() {
            boolean v = parseNot();
            while (isKw("and")) { p++; boolean r = parseNot(); v = v && r; }
            return v;
        }
        boolean parseNot() {
            if (isKw("not")) { p++; return !parseNot(); }
            return parsePrimary();
        }

        boolean parsePrimary() {
            String t = peek();
            if (t == null) throw new ParseException("unexpected end of condition");

            if (t.equals("(")) { p++; boolean v = parseOr(); expect(")"); return v; }
            if (t.equalsIgnoreCase("true")) { p++; return true; }
            if (t.equalsIgnoreCase("false")) { p++; return false; }

            // Quantified: all/any/N  of  ...
            if (t.equalsIgnoreCase("all") || t.equalsIgnoreCase("any")
                    || (isNumber(t) && "of".equalsIgnoreCase(tokAt(p + 1)))) {
                return parseOf();
            }

            // Numeric comparison: starts with filesize, #id or a number.
            if (t.equalsIgnoreCase("filesize") || t.startsWith("#") || isNumber(t)) {
                long left = parseNumeric();
                String op = peek();
                if (isRelop(op)) {
                    p++;
                    long right = parseNumeric();
                    return compare(left, op, right);
                }
                return left != 0;
            }

            // String presence: $id
            if (t.startsWith("$")) { p++; return countOf(t) > 0; }

            // Bareword: reference to another rule.
            if (isIdent(t)) {
                p++;
                Boolean rv = rules.get(t);
                if (rv == null) throw new ParseException("unknown identifier '" + t + "'");
                return rv;
            }
            throw new ParseException("unexpected '" + t + "'");
        }

        boolean parseOf() {
            String q = next(); // all / any / number
            expect("of");
            List<String> set = parseSet();
            int matched = 0;
            for (String id : set) if (countOf(id) > 0) matched++;
            if (q.equalsIgnoreCase("all")) return matched == set.size();
            if (q.equalsIgnoreCase("any")) return matched >= 1;
            return matched >= Integer.parseInt(q);
        }

        List<String> parseSet() {
            List<String> result = new ArrayList<>();
            if (isKw("them")) { p++; result.addAll(ids); return result; }
            expect("(");
            while (true) {
                String item = next();               // "$id", "$id*" (via id + *), or "$"
                String base = item;
                boolean star = false;
                if (item.equals("$") && "*".equals(peek())) { p++; star = true; base = "$"; }
                else if ("*".equals(peek())) { p++; star = true; }
                if (star) {
                    String prefix = base.equals("$") ? "$" : base;
                    for (String id : ids) if (id.startsWith(prefix)) result.add(id);
                } else {
                    result.add(item);
                }
                if (",".equals(peek())) { p++; continue; }
                break;
            }
            expect(")");
            return result;
        }

        long parseNumeric() {
            String t = next();
            long base;
            if (t.equalsIgnoreCase("filesize")) return filesize;
            if (t.startsWith("#")) return countOf("$" + t.substring(1));
            if (t.startsWith("$")) return countOf(t) > 0 ? 1 : 0;
            if (isNumber(t)) base = Long.parseLong(t);
            else throw new ParseException("expected a number, got '" + t + "'");
            String suf = peek();
            if ("KB".equalsIgnoreCase(suf)) { p++; base *= 1024L; }
            else if ("MB".equalsIgnoreCase(suf)) { p++; base *= 1024L * 1024L; }
            return base;
        }

        int countOf(String id) {
            Integer c = counts.get(id);
            return c == null ? 0 : c;
        }

        String tokAt(int i) { return i < tok.size() ? tok.get(i) : null; }
    }

    private static boolean compare(long a, String op, long b) {
        switch (op) {
            case ">":  return a > b;
            case "<":  return a < b;
            case ">=": return a >= b;
            case "<=": return a <= b;
            case "==": return a == b;
            case "!=": return a != b;
        }
        return false;
    }

    private static boolean isRelop(String s) {
        return s != null && (s.equals(">") || s.equals("<") || s.equals(">=")
                || s.equals("<=") || s.equals("==") || s.equals("!="));
    }
    private static boolean isNumber(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }
    private static boolean isIdent(String s) {
        if (s == null || s.isEmpty()) return false;
        char c0 = s.charAt(0);
        if (!(Character.isLetter(c0) || c0 == '_')) return false;
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) return false;
        }
        return true;
    }

    private static final Pattern COND_TOKEN = Pattern.compile(
            ">=|<=|==|!=|[<>()=,*]|[#@$][A-Za-z_][A-Za-z0-9_]*|[A-Za-z_][A-Za-z0-9_]*|[0-9]+|\\$");

    private static List<String> tokenize(String cond) {
        List<String> t = new ArrayList<>();
        Matcher m = COND_TOKEN.matcher(cond);
        while (m.find()) t.add(m.group());
        return t;
    }

    // ------------------------------------------------------------ parsing ----

    private static final class ParseException extends RuntimeException {
        ParseException(String m) { super(m); }
    }

    private static List<Rule> parseRules(String raw) {
        String text = stripComments(raw);
        List<Rule> rules = new ArrayList<>();
        int i = 0, n = text.length();
        while (i < n) {
            int idx = indexOfWord(text, "rule", i);
            if (idx < 0) break;
            int j = idx + 4;
            j = skipWs(text, j);
            int nameStart = j;
            while (j < n && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_')) j++;
            if (j == nameStart) throw new ParseException("expected a rule name after 'rule'");
            Rule rule = new Rule();
            rule.name = text.substring(nameStart, j);

            j = skipWs(text, j);
            if (j < n && text.charAt(j) == ':') { // tags
                j++;
                while (true) {
                    j = skipWs(text, j);
                    int ts = j;
                    while (j < n && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_')) j++;
                    if (j == ts) break;
                    rule.tags.add(text.substring(ts, j));
                }
                j = skipWs(text, j);
            }
            if (j >= n || text.charAt(j) != '{') throw new ParseException("expected '{' for rule " + rule.name);
            int bodyStart = j + 1;
            int bodyEnd = matchBrace(text, j);
            if (bodyEnd < 0) throw new ParseException("unbalanced braces in rule " + rule.name);
            String body = text.substring(bodyStart, bodyEnd);
            parseBody(rule, body);
            rules.add(rule);
            i = bodyEnd + 1;
        }
        return rules;
    }

    private static void parseBody(Rule rule, String body) {
        int condPos = indexOfWord(body, "condition", 0);
        if (condPos < 0) throw new ParseException("rule " + rule.name + " has no condition");
        int stringsPos = indexOfWord(body, "strings", 0);

        if (stringsPos >= 0 && stringsPos < condPos) {
            int s = body.indexOf(':', stringsPos);
            String sec = body.substring(s + 1, condPos);
            parseStrings(rule, sec);
        }
        int c = body.indexOf(':', condPos);
        rule.condition = body.substring(c + 1).trim();
    }

    private static void parseStrings(Rule rule, String sec) {
        int i = 0, n = sec.length();
        while (i < n) {
            i = sec.indexOf('$', i);
            if (i < 0) break;
            int idStart = i;
            i++;
            while (i < n && (Character.isLetterOrDigit(sec.charAt(i)) || sec.charAt(i) == '_')) i++;
            String id = sec.substring(idStart, i);
            i = skipWs(sec, i);
            if (i >= n || sec.charAt(i) != '=') throw new ParseException("expected '=' after " + id);
            i = skipWs(sec, i + 1);
            if (i >= n) throw new ParseException("missing value for " + id);

            YString ys = new YString();
            ys.id = id;
            char c = sec.charAt(i);
            int valEnd;
            if (c == '"') {
                int end = i + 1;
                StringBuilder sb = new StringBuilder();
                while (end < n && sec.charAt(end) != '"') {
                    char ch = sec.charAt(end);
                    if (ch == '\\' && end + 1 < n) { sb.append(unescape(sec.charAt(end + 1))); end += 2; }
                    else { sb.append(ch); end++; }
                }
                if (end >= n) throw new ParseException("unterminated string " + id);
                ys.kind = Kind.TEXT;
                ys.text = sb.toString();
                valEnd = end + 1;
            } else if (c == '{') {
                int end = sec.indexOf('}', i);
                if (end < 0) throw new ParseException("unterminated hex string " + id);
                ys.kind = Kind.HEX;
                ys.hex = parseHex(sec.substring(i + 1, end), id);
                valEnd = end + 1;
            } else if (c == '/') {
                int end = i + 1;
                StringBuilder sb = new StringBuilder();
                while (end < n && sec.charAt(end) != '/') {
                    char ch = sec.charAt(end);
                    if (ch == '\\' && end + 1 < n) { sb.append(ch).append(sec.charAt(end + 1)); end += 2; }
                    else { sb.append(ch); end++; }
                }
                if (end >= n) throw new ParseException("unterminated regex " + id);
                ys.kind = Kind.REGEX;
                ys.text = sb.toString();
                valEnd = end + 1;
            } else {
                throw new ParseException("unrecognised value for " + id);
            }

            // Modifiers up to end of line (or next '$').
            int modEnd = valEnd;
            while (modEnd < n && sec.charAt(modEnd) != '\n' && sec.charAt(modEnd) != '$') modEnd++;
            String mods = sec.substring(valEnd, modEnd).toLowerCase();
            ys.nocase = mods.contains("nocase");
            ys.wide = mods.contains("wide");
            ys.ascii = mods.contains("ascii");
            ys.fullword = mods.contains("fullword");
            rule.strings.add(ys);
            i = valEnd;
        }
    }

    private static List<int[]> parseHex(String hex, String id) {
        List<int[]> tokens = new ArrayList<>();
        int i = 0, n = hex.length();
        while (i < n) {
            char c = hex.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '[') { // jump [min-max] or [n] or [min-]
                int end = hex.indexOf(']', i);
                if (end < 0) throw new ParseException("unterminated jump in " + id);
                String body = hex.substring(i + 1, end).trim();
                int min, max;
                if (body.contains("-")) {
                    String[] parts = body.split("-", 2);
                    min = parts[0].trim().isEmpty() ? 0 : Integer.parseInt(parts[0].trim());
                    max = parts[1].trim().isEmpty() ? 256 : Integer.parseInt(parts[1].trim());
                } else {
                    min = max = Integer.parseInt(body);
                }
                tokens.add(new int[]{-1, min, max});
                i = end + 1;
            } else if (c == '(' || c == ')' || c == '|') {
                throw new ParseException("hex alternation ( | ) is not supported (" + id + ")");
            } else {
                char hi = c;
                char lo = (i + 1 < n) ? hex.charAt(i + 1) : ' ';
                int value = 0, mask = 0;
                value |= nibble(hi, id) << 4; mask |= (hi == '?' ? 0 : 0xF0);
                value |= nibble(lo, id);       mask |= (lo == '?' ? 0 : 0x0F);
                tokens.add(new int[]{value, mask});
                i += 2;
            }
        }
        return tokens;
    }

    private static int nibble(char c, String id) {
        if (c == '?') return 0;
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        throw new ParseException("bad hex digit '" + c + "' in " + id);
    }

    private static char unescape(char c) {
        switch (c) {
            case 'n': return '\n';
            case 't': return '\t';
            case 'r': return '\r';
            case '\\': return '\\';
            case '"': return '"';
            default: return c;
        }
    }

    /** Remove // and /* *\/ comments, but never inside "..." text strings. */
    private static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0, n = s.length();
        boolean inStr = false;
        while (i < n) {
            char c = s.charAt(i);
            if (inStr) {
                out.append(c);
                if (c == '\\' && i + 1 < n) { out.append(s.charAt(i + 1)); i += 2; continue; }
                if (c == '"') inStr = false;
                i++;
                continue;
            }
            if (c == '"') { inStr = true; out.append(c); i++; continue; }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) i++;
                i += 2;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** Index of a whole-word keyword, skipping matches inside "..." strings. */
    private static int indexOfWord(String s, String word, int from) {
        int n = s.length(), wl = word.length();
        boolean inStr = false;
        for (int i = from; i <= n - wl; i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (s.regionMatches(i, word, 0, wl)) {
                char before = i > 0 ? s.charAt(i - 1) : ' ';
                char after = i + wl < n ? s.charAt(i + wl) : ' ';
                boolean bw = Character.isLetterOrDigit(before) || before == '_';
                boolean aw = Character.isLetterOrDigit(after) || after == '_';
                if (!bw && !aw) return i;
            }
        }
        return -1;
    }

    /** Given index of '{', return index of the matching '}', skipping strings. */
    private static int matchBrace(String s, int open) {
        int depth = 0, n = s.length();
        boolean inStr = false, inRe = false;
        for (int i = open; i < n; i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') { i++; }
                else if (c == '"') inStr = false;
                continue;
            }
            if (inRe) {
                if (c == '\\') { i++; }
                else if (c == '/') inRe = false;
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == '/' && i > open && "=(,| \t\n".indexOf(s.charAt(i - 1)) >= 0) { inRe = true; continue; }
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    private static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }
}
