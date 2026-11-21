package site.springcat.bionicrunner;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Windows の実行ファイル (PE 形式。exe・dll) の構造を読む。32bit (PE32) と 64bit (PE32+) の両方に対応。
 *
 * 攻撃者が作ったファイルを読む前提で、次のように作ってある:
 *   - 読み取りはすべて範囲を確かめる (ファイルの外や、先頭から 64MB より後ろは読まない)
 *   - セクションの数・インポートの数・文字列の長さに上限を付ける (巨大な値を書かれても止まらない)
 *   - 表を順にたどる処理は回数に上限を付ける (循環した参照でも終わる)
 *   - 壊れたファイルは例外で落ちず、{@link #error} に理由を入れて「壊れた PE」として返す
 *
 * Android の API は使っていないので、PC の Java でもそのまま動く (単体テストを PC で動かすため)。
 */
final class PeInfo {

    /** ファイルの先頭から、これより後ろは読まない */
    static final long MAX_READ = 64L * 1024 * 1024;
    static final int MAX_SECTIONS = 96;
    static final int MAX_DLLS = 256;
    static final int MAX_FUNCS_PER_DLL = 4096;
    static final int MAX_FUNCS = 20000;
    static final int MAX_NAME = 256;
    static final int MAX_TLS_CALLBACKS = 64;

    static final int SCN_CODE = 0x00000020;
    static final int SCN_EXECUTE = 0x20000000;
    static final int SCN_READ = 0x40000000;
    static final int SCN_WRITE = 0x80000000;

    static final class Section {
        String name = "";
        long virtualAddress, virtualSize, rawPointer, rawSize;
        int characteristics;
        /** 中身のばらつき (0〜8)。8 に近いほどランダム (暗号化・圧縮) に近い。中身が無ければ -1 */
        double entropy = -1;

        boolean executable() {
            return (characteristics & (SCN_EXECUTE | SCN_CODE)) != 0;
        }

        boolean writable() {
            return (characteristics & SCN_WRITE) != 0;
        }
    }

    static final class Import {
        String dll = "";
        final List<String> functions = new ArrayList<>();
        /** 名前ではなく番号で読み込む関数の数 */
        int ordinals;
    }

    /** PE として読めたか。false なら error に理由がある (MZ でない・壊れている) */
    boolean valid;
    /** MZ と PE の印はあったか (true で valid が false なら「壊れた PE」) */
    boolean looksLikePe;
    String error;
    boolean is64;
    int machine;
    boolean isDll;
    long imageBase;
    long entryRva;
    long sizeOfHeaders;
    long fileSize;
    final List<Section> sections = new ArrayList<>();
    final List<Import> imports = new ArrayList<>();
    int importFunctionCount;
    int importOrdinalCount;
    /** エントリーポイント (最初に動く場所) があるセクション。どこにも無ければ null */
    Section entrySection;
    int tlsCallbacks;
    boolean hasTls;
    long resourceRva, resourceSize;
    /** 自分が提供する (ほかのプログラムに見せる) 関数の名前 */
    final java.util.Set<String> exports = new java.util.HashSet<>();
    /** COFF の記号の表 (デバッグ用。最後のセクションの後ろに置かれることがある) の場所と大きさ */
    long symbolTableOffset, symbolTableSize;
    /** 最後のセクションより後ろに付け足されたデータ (署名・記号の表の部分は除く) */
    long overlayOffset, overlaySize;
    double overlayEntropy = -1;
    /** コードの中に「書き込みも実行もできるメモリを取る」引数の並び (VirtualAlloc(…, 0x3000, 0x40)) があるか */
    boolean rwxAllocPattern;

    private RandomAccessFile file;
    private long limit;

    private PeInfo() {
    }

    /** ファイルを読む。どんなファイルでも例外は投げず、結果に理由を入れて返す */
    static PeInfo read(File f) {
        PeInfo p = new PeInfo();
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            p.file = raf;
            p.fileSize = raf.length();
            p.limit = Math.min(p.fileSize, MAX_READ);
            p.parse();
            p.valid = p.error == null;
        } catch (IOException | RuntimeException e) {
            // 読み取りの失敗や、想定外の値による計算の失敗も「壊れた PE」として扱う
            p.valid = false;
            if (p.error == null) p.error = "読み取れませんでした (" + e.getClass().getSimpleName() + ")";
        } finally {
            p.file = null;
        }
        return p;
    }

    // ------------------------------------------------------------ 範囲を確かめて読む

    private byte[] bytes(long off, int len) throws IOException {
        if (off < 0 || len < 0 || off + len > limit) throw new IOException("範囲外");
        byte[] b = new byte[len];
        file.seek(off);
        file.readFully(b);
        return b;
    }

    private int u16(long off) throws IOException {
        byte[] b = bytes(off, 2);
        return (b[0] & 0xff) | (b[1] & 0xff) << 8;
    }

    private long u32(long off) throws IOException {
        byte[] b = bytes(off, 4);
        return (b[0] & 0xffL) | (b[1] & 0xffL) << 8 | (b[2] & 0xffL) << 16 | (b[3] & 0xffL) << 24;
    }

    private long u64(long off) throws IOException {
        return u32(off) | u32(off + 4) << 32;
    }

    /** 0 で終わる ASCII の文字列 (最大 MAX_NAME 文字)。読めなければ null */
    private String cstring(long off) {
        if (off < 0 || off >= limit) return null;
        try {
            int len = (int) Math.min(MAX_NAME, limit - off);
            byte[] b = bytes(off, len);
            int n = 0;
            while (n < b.length && b[n] != 0) n++;
            if (n == 0 || n == b.length) return n == 0 ? "" : null; // 終わりが見つからない
            for (int i = 0; i < n; i++) if (b[i] < 0x20 || b[i] > 0x7e) return null;
            return new String(b, 0, n, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            return null;
        }
    }

    /** 読み込んだあとの場所 (RVA) をファイルの中の位置に直す。どのセクションにも無ければ -1 */
    long rvaToOffset(long rva) {
        if (rva < 0) return -1;
        if (rva < sizeOfHeaders) return rva < limit ? rva : -1;
        for (Section s : sections) {
            long span = Math.max(s.virtualSize, s.rawSize);
            if (rva >= s.virtualAddress && rva < s.virtualAddress + span) {
                long delta = rva - s.virtualAddress;
                if (delta >= s.rawSize) return -1; // ファイルには中身の無い部分
                long off = s.rawPointer + delta;
                return off < limit ? off : -1;
            }
        }
        return -1;
    }

    private Section sectionOfRva(long rva) {
        for (Section s : sections) {
            long span = Math.max(s.virtualSize, s.rawSize);
            if (rva >= s.virtualAddress && rva < s.virtualAddress + span) return s;
        }
        return null;
    }

    // ------------------------------------------------------------ 解析

    private void parse() throws IOException {
        if (limit < 64 || u16(0) != 0x5a4d) {
            error = "Windows の実行ファイル (MZ) ではありません";
            return;
        }
        long pe = u32(0x3c);
        if (pe < 4 || pe > limit - 24 || pe > 0x10000000L) {
            error = "壊れた PE: ヘッダーの場所がおかしい";
            return;
        }
        if (u32(pe) != 0x4550) {
            error = "壊れた PE: PE の印がありません";
            return;
        }
        looksLikePe = true;
        long coff = pe + 4;
        machine = u16(coff);
        int nsec = u16(coff + 2);
        int optSize = u16(coff + 16);
        int chars = u16(coff + 18);
        long symPtr = u32(coff + 8), symCount = u32(coff + 12);
        if (symPtr > 0 && symPtr < fileSize && symCount < 10_000_000L) {
            long strTab = symPtr + symCount * 18;
            long strSize = strTab + 4 <= limit ? u32(strTab) : 0;
            if (strSize < 4 || strTab + strSize > fileSize) strSize = 4;
            symbolTableOffset = symPtr;
            symbolTableSize = Math.min(fileSize, strTab + strSize) - symPtr;
        }
        isDll = (chars & 0x2000) != 0;
        long opt = coff + 20;
        int magic = u16(opt);
        if (magic == 0x20b) is64 = true;
        else if (magic != 0x10b) {
            error = "壊れた PE: 32bit でも 64bit でもない (0x" + Integer.toHexString(magic) + ")";
            return;
        }
        entryRva = u32(opt + 16);
        imageBase = is64 ? u64(opt + 24) : u32(opt + 28);
        sizeOfHeaders = u32(opt + 60);
        long dirCountOff = opt + (is64 ? 108 : 92);
        long dirs = opt + (is64 ? 112 : 96);
        long ndirs = optSize >= (dirCountOff - opt + 4) ? u32(dirCountOff) : 0;
        if (ndirs > 16) ndirs = 16;
        long[] dirRva = new long[16], dirSize = new long[16];
        for (int i = 0; i < ndirs; i++) {
            long d = dirs + i * 8L;
            if (d + 8 > opt + optSize) break; // オプションヘッダーの外は読まない
            dirRva[i] = u32(d);
            dirSize[i] = u32(d + 4);
        }

        // セクション
        if (nsec > MAX_SECTIONS) {
            error = "壊れた PE: セクションが多すぎます (" + nsec + ")";
            return;
        }
        long sec = opt + optSize;
        for (int i = 0; i < nsec; i++) {
            long h = sec + i * 40L;
            byte[] b = bytes(h, 40);
            Section s = new Section();
            int n = 0;
            while (n < 8 && b[n] != 0) n++;
            StringBuilder nm = new StringBuilder();
            for (int k = 0; k < n; k++) nm.append((b[k] >= 0x20 && b[k] < 0x7f) ? (char) b[k] : '?');
            s.name = nm.toString();
            s.virtualSize = u32(h + 8);
            s.virtualAddress = u32(h + 12);
            s.rawSize = u32(h + 16);
            s.rawPointer = u32(h + 20);
            s.characteristics = (int) u32(h + 36);
            // ファイルの外にはみ出す部分は無いものとする
            if (s.rawPointer >= fileSize) s.rawSize = 0;
            else if (s.rawPointer + s.rawSize > fileSize) s.rawSize = fileSize - s.rawPointer;
            sections.add(s);
        }
        if (sizeOfHeaders > limit) sizeOfHeaders = Math.min(limit, 0x1000);

        for (Section s : sections) s.entropy = entropy(s.rawPointer, s.rawSize);
        entrySection = entryRva == 0 ? null : sectionOfRva(entryRva);

        parseExports(dirRva[0]);
        parseImports(dirRva[1], dirSize[1]);
        if (dirRva[9] != 0) parseTls(dirRva[9]);
        resourceRva = dirRva[2];
        resourceSize = dirSize[2];
        parseOverlay(dirRva[4], dirSize[4]);
        findRwxAlloc();
    }

    /** 提供する関数の名前の一覧 (最大 MAX_FUNCS 個) */
    private void parseExports(long rva) {
        if (rva == 0) return;
        long off = rvaToOffset(rva);
        if (off < 0) return;
        try {
            long names = u32(off + 24);
            long namesRva = u32(off + 32);
            long table = rvaToOffset(namesRva);
            if (table < 0) return;
            for (long i = 0; i < Math.min(names, MAX_FUNCS); i++) {
                String n = cstring(rvaToOffset(u32(table + i * 4)));
                if (n != null && !n.isEmpty()) exports.add(n.toLowerCase(java.util.Locale.ROOT));
            }
        } catch (IOException e) {
            // 途中まで
        }
    }

    private void parseImports(long rva, long size) {
        if (rva == 0) return;
        long off = rvaToOffset(rva);
        if (off < 0) return;
        for (int i = 0; i < MAX_DLLS; i++) {
            long d = off + i * 20L;
            long oft, nameRva, ft;
            try {
                oft = u32(d);
                nameRva = u32(d + 12);
                ft = u32(d + 16);
            } catch (IOException e) {
                return;
            }
            if (oft == 0 && nameRva == 0 && ft == 0) return; // 表の終わり
            Import imp = new Import();
            String name = cstring(rvaToOffset(nameRva));
            imp.dll = name == null ? "?" : name;
            long thunk = rvaToOffset(oft != 0 ? oft : ft);
            int step = is64 ? 8 : 4;
            for (int k = 0; thunk >= 0 && k < MAX_FUNCS_PER_DLL && importFunctionCount + importOrdinalCount < MAX_FUNCS; k++) {
                long v;
                try {
                    v = is64 ? u64(thunk + (long) k * step) : u32(thunk + (long) k * step);
                } catch (IOException e) {
                    break;
                }
                if (v == 0) break;
                boolean byOrdinal = is64 ? (v & 0x8000000000000000L) != 0 : (v & 0x80000000L) != 0;
                if (byOrdinal) {
                    imp.ordinals++;
                    importOrdinalCount++;
                } else {
                    String fn = cstring(rvaToOffset((v & 0x7fffffffL) + 2));
                    if (fn != null && !fn.isEmpty()) {
                        imp.functions.add(fn);
                        importFunctionCount++;
                    }
                }
            }
            imports.add(imp);
        }
    }

    private void parseTls(long rva) {
        long off = rvaToOffset(rva);
        if (off < 0) return;
        hasTls = true;
        try {
            long cbVa = is64 ? u64(off + 24) : u32(off + 12);
            if (cbVa == 0) return;
            long cbRva = cbVa - imageBase;
            long cb = rvaToOffset(cbRva);
            if (cb < 0) return;
            int step = is64 ? 8 : 4;
            for (int i = 0; i < MAX_TLS_CALLBACKS; i++) {
                long v = is64 ? u64(cb + (long) i * step) : u32(cb + (long) i * step);
                if (v == 0) break;
                tlsCallbacks++;
            }
        } catch (IOException e) {
            // 途中までの数で止める
        }
    }

    private void parseOverlay(long certOffset, long certSize) {
        long end = sizeOfHeaders;
        for (Section s : sections) if (s.rawSize > 0) end = Math.max(end, s.rawPointer + s.rawSize);
        if (end >= fileSize) return;
        long size = fileSize - end;
        // 署名 (証明書の表) はファイルの末尾にあり、セキュリティの表の場所はファイルの中の位置で書かれる
        if (certSize > 0 && certOffset >= end && certOffset + certSize <= fileSize + 8) {
            size = Math.max(0, certOffset - end) + Math.max(0, fileSize - (certOffset + certSize));
        }
        // 記号の表 (デバッグ用) が末尾にあれば、それも付け足されたデータとは数えない
        if (symbolTableSize > 0 && symbolTableOffset >= end && symbolTableOffset < fileSize) {
            size -= Math.min(size, Math.min(symbolTableSize, fileSize - symbolTableOffset));
        }
        if (size <= 0) return;
        overlayOffset = end;
        overlaySize = size;
        overlayEntropy = entropy(end, Math.min(size, 4L * 1024 * 1024));
    }

    /** 実行できるセクションから、VirtualAlloc(…, MEM_COMMIT|MEM_RESERVE, PAGE_EXECUTE_READWRITE) の引数の並びを探す */
    private void findRwxAlloc() {
        // 64bit: mov r8d,0x3000 と mov r9d,0x40 が近くにある。32bit: push 0x40; push 0x3000 など
        byte[] r8 = {0x41, (byte) 0xb8, 0x00, 0x30, 0x00, 0x00};
        byte[] r9 = {0x41, (byte) 0xb9, 0x40, 0x00, 0x00, 0x00};
        byte[] x86 = {0x6a, 0x40, 0x68, 0x00, 0x30, 0x00, 0x00};
        // 32bit で引数をスタックに mov で置く形: mov dword [esp+0xc],0x40 と mov dword [esp+8],0x3000
        byte[] x86p = {(byte) 0xc7, 0x44, 0x24, 0x0c, 0x40, 0x00, 0x00, 0x00};
        byte[] x86a = {(byte) 0xc7, 0x44, 0x24, 0x08, 0x00, 0x30, 0x00, 0x00};
        long budget = 16L * 1024 * 1024;
        for (Section s : sections) {
            if (!s.executable() || s.rawSize <= 0) continue;
            long len = Math.min(s.rawSize, budget);
            budget -= len;
            try {
                byte[] b = bytes(s.rawPointer, (int) len);
                for (int i = 0; i + 7 <= b.length; i++) {
                    if (is64) {
                        if (match(b, i, r9) && near(b, i, r8, 32)) {
                            rwxAllocPattern = true;
                            return;
                        }
                    } else if (match(b, i, x86) || match(b, i, x86p) && near(b, i, x86a, 32)) {
                        rwxAllocPattern = true;
                        return;
                    }
                }
            } catch (IOException e) {
                return;
            }
            if (budget <= 0) return;
        }
    }

    private static boolean match(byte[] b, int at, byte[] p) {
        if (at < 0 || at + p.length > b.length) return false;
        for (int i = 0; i < p.length; i++) if (b[at + i] != p[i]) return false;
        return true;
    }

    private static boolean near(byte[] b, int at, byte[] p, int dist) {
        for (int i = Math.max(0, at - dist); i <= at + dist; i++) if (match(b, i, p)) return true;
        return false;
    }

    /** ファイルの一部の中身のばらつき (0〜8)。読めない・空なら -1 */
    private double entropy(long off, long len) {
        if (len <= 0 || off < 0 || off >= limit) return -1;
        len = Math.min(len, limit - off);
        long[] count = new long[256];
        byte[] buf = new byte[65536];
        long left = len, total = 0;
        try {
            file.seek(off);
            while (left > 0) {
                int n = file.read(buf, 0, (int) Math.min(buf.length, left));
                if (n <= 0) break;
                for (int i = 0; i < n; i++) count[buf[i] & 0xff]++;
                left -= n;
                total += n;
            }
        } catch (IOException e) {
            return -1;
        }
        if (total == 0) return -1;
        double h = 0;
        for (long c : count) {
            if (c == 0) continue;
            double p = (double) c / total;
            h -= p * (Math.log(p) / Math.log(2));
        }
        return h;
    }

    /**
     * 名前で読み込む関数があるか (大文字小文字と、末尾の A/W の違いは問わない)。
     * 自分でも同じ名前の関数を提供しているもの (ほかの DLL への取り次ぎ。Windows 自身の DLL に多い) は数えない
     */
    boolean imports(String function) {
        String l = function.toLowerCase(java.util.Locale.ROOT);
        if (exports.contains(l) || exports.contains(l + "a") || exports.contains(l + "w")) return false;
        for (Import i : imports) {
            for (String f : i.functions) {
                if (f.equalsIgnoreCase(function) || f.equalsIgnoreCase(function + "A")
                        || f.equalsIgnoreCase(function + "W")) {
                    return true;
                }
            }
        }
        return false;
    }

    boolean importsAny(String... functions) {
        for (String f : functions) if (imports(f)) return true;
        return false;
    }
}
