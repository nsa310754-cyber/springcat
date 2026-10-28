package site.ragdollp.binrunner;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * アップロードされたファイルを「拡張子ではなく中身(マジックナンバー)」で判定し、
 * ELF の場合はヘッダを解析してアーキテクチャ・リンカ種別(Bionic / glibc / 静的)まで特定する。
 *
 * ここは純 Java で完全に動作する部分。実行可否の最終判断 (DecisionEngine) と
 * 実際の実行 (Executor) に必要な情報をすべてここで集める。
 */
public final class BinaryAnalyzer {

    /** 検出したファイル種別。 */
    public enum Kind { ELF, PE, MACHO, GZIP, ZIP, TAR, SCRIPT, UNKNOWN }

    /** ELF のリンカ種別。 */
    public enum Libc { BIONIC, GNU_OR_MUSL, STATIC, UNKNOWN }

    public static final class Result {
        public Kind kind = Kind.UNKNOWN;
        public long size;

        // --- ELF 固有 ---
        public boolean elf64;
        public boolean littleEndian = true;
        public int eType;             // 2=EXEC, 3=DYN(PIE/共有)
        public int eMachine;          // EM_* 値
        public String archName = "?"; // 人間向けのアーキ名
        public String interpreter;    // PT_INTERP の中身 (動的リンク時)
        public Libc libc = Libc.UNKNOWN;

        /** 判定の要約を 1 行で。 */
        public String oneLine() {
            switch (kind) {
                case ELF:
                    return "ELF (" + (elf64 ? "64bit" : "32bit") + " / " + archName + " / "
                            + (eType == 3 ? "PIE/DYN" : eType == 2 ? "EXEC" : "type" + eType) + " / "
                            + libcLabel() + ")";
                case PE:    return "PE — Windows 実行ファイル (.exe / .dll)";
                case MACHO: return "Mach-O — macOS/iOS 実行ファイル";
                case GZIP:  return "gzip 圧縮 (.gz / .tar.gz の外側)";
                case ZIP:   return "ZIP アーカイブ (.zip / .jar / .apk など)";
                case TAR:   return "tar アーカイブ (複数ファイルの書庫)";
                case SCRIPT:return "テキストスクリプト (#! 付き)";
                default:    return "不明な形式";
            }
        }

        public String libcLabel() {
            switch (libc) {
                case BIONIC:      return "Bionic=Android ネイティブ";
                case GNU_OR_MUSL: return "glibc/musl=通常 Linux";
                case STATIC:      return "静的リンク";
                default:          return "リンカ不明";
            }
        }
    }

    // ELF e_machine 定数
    private static final int EM_386 = 3, EM_ARM = 40, EM_X86_64 = 62,
            EM_AARCH64 = 183, EM_RISCV = 243, EM_MIPS = 8, EM_PPC = 20, EM_PPC64 = 21;

    private BinaryAnalyzer() {}

    /** ファイル先頭を読んで種別を判定する。ELF ならヘッダも解析する。 */
    public static Result analyze(File f) {
        Result r = new Result();
        r.size = f.length();
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] head = new byte[Math.min(64, (int) Math.min(raf.length(), 64))];
            raf.seek(0);
            int n = raf.read(head);
            if (n < 4) { r.kind = Kind.UNKNOWN; return r; }

            if (u(head[0]) == 0x7F && head[1] == 'E' && head[2] == 'L' && head[3] == 'F') {
                r.kind = Kind.ELF;
                parseElf(raf, r);
            } else if (head[0] == 'M' && head[1] == 'Z') {
                r.kind = Kind.PE;
            } else if (u(head[0]) == 0x1F && u(head[1]) == 0x8B) {
                r.kind = Kind.GZIP;
            } else if (head[0] == 'P' && head[1] == 'K') {
                r.kind = Kind.ZIP;
            } else if (head[0] == '#' && head[1] == '!') {
                r.kind = Kind.SCRIPT;
            } else if (isMachO(head)) {
                r.kind = Kind.MACHO;
            } else if (isTar(raf)) {
                r.kind = Kind.TAR;
            } else {
                r.kind = Kind.UNKNOWN;
            }
        } catch (IOException e) {
            r.kind = Kind.UNKNOWN;
        }
        return r;
    }

    private static void parseElf(RandomAccessFile raf, Result r) throws IOException {
        byte[] e = new byte[64];
        raf.seek(0);
        if (raf.read(e) < 20) return;

        r.elf64 = (e[4] == 2);              // EI_CLASS: 1=32,2=64
        r.littleEndian = (e[5] == 1);       // EI_DATA: 1=LE,2=BE

        r.eType = u16(e, 16, r.littleEndian);
        r.eMachine = u16(e, 18, r.littleEndian);
        r.archName = archName(r.eMachine);

        // プログラムヘッダ表の位置と数
        long phoff;
        int phentsize, phnum;
        if (r.elf64) {
            phoff = u64(e, 32, r.littleEndian);
            phentsize = u16(e, 54, r.littleEndian);
            phnum = u16(e, 56, r.littleEndian);
        } else {
            phoff = u32(e, 28, r.littleEndian);
            phentsize = u16(e, 42, r.littleEndian);
            phnum = u16(e, 44, r.littleEndian);
        }

        // PT_INTERP(=3) を探してインタプリタ(=動的リンカ)のパスを読む
        final int PT_INTERP = 3;
        boolean sawInterp = false;
        for (int i = 0; i < phnum && phentsize > 0 && phoff > 0; i++) {
            long ph = phoff + (long) i * phentsize;
            if (ph + phentsize > raf.length()) break;
            byte[] p = new byte[phentsize];
            raf.seek(ph);
            if (raf.read(p) < 8) break;
            int pType = (int) u32(p, 0, r.littleEndian);
            if (pType == PT_INTERP) {
                long pOffset;
                long pFilesz;
                if (r.elf64) { pOffset = u64(p, 8, r.littleEndian);  pFilesz = u64(p, 32, r.littleEndian); }
                else         { pOffset = u32(p, 4, r.littleEndian);  pFilesz = u32(p, 16, r.littleEndian); }
                if (pFilesz > 0 && pFilesz < 4096 && pOffset + pFilesz <= raf.length()) {
                    byte[] s = new byte[(int) pFilesz];
                    raf.seek(pOffset);
                    raf.read(s);
                    r.interpreter = cstr(s);
                    sawInterp = true;
                }
                break;
            }
        }

        if (!sawInterp) {
            r.libc = Libc.STATIC;           // PT_INTERP が無い = 静的リンク
        } else if (r.interpreter != null
                && (r.interpreter.contains("/system/bin/linker") || r.interpreter.contains("/apex/"))) {
            r.libc = Libc.BIONIC;           // Android の動的リンカ
        } else if (r.interpreter != null
                && (r.interpreter.contains("ld-linux") || r.interpreter.contains("ld-musl")
                    || r.interpreter.contains("/lib/ld") || r.interpreter.contains("/lib64/ld"))) {
            r.libc = Libc.GNU_OR_MUSL;      // 通常 Linux の動的リンカ
        } else {
            r.libc = Libc.UNKNOWN;
        }
    }

    private static boolean isMachO(byte[] h) {
        long m = u32(h, 0, false); // big-endian 読み
        // feedface/feedfacf(BE) と cafebabe(fat) / cffaedfe,cefaedfe(LE)
        return m == 0xFEEDFACEL || m == 0xFEEDFACFL || m == 0xCAFEBABEL
                || m == 0xCFFAEDFEL || m == 0xCEFAEDFEL;
    }

    /** tar は 257 バイト目に "ustar" を持つ。 */
    private static boolean isTar(RandomAccessFile raf) throws IOException {
        if (raf.length() < 265) return false;
        byte[] m = new byte[5];
        raf.seek(257);
        if (raf.read(m) < 5) return false;
        return m[0] == 'u' && m[1] == 's' && m[2] == 't' && m[3] == 'a' && m[4] == 'r';
    }

    private static String archName(int m) {
        switch (m) {
            case EM_AARCH64: return "ARM64 (aarch64)";
            case EM_ARM:     return "ARM 32bit";
            case EM_X86_64:  return "x86-64";
            case EM_386:     return "x86 (i386)";
            case EM_RISCV:   return "RISC-V";
            case EM_MIPS:    return "MIPS";
            case EM_PPC:     return "PowerPC";
            case EM_PPC64:   return "PowerPC64";
            default:         return "machine=" + m;
        }
    }

    // --- バイト読みユーティリティ ---
    private static int u(byte b) { return b & 0xFF; }

    private static int u16(byte[] b, int o, boolean le) {
        return le ? (u(b[o]) | (u(b[o + 1]) << 8))
                  : (u(b[o + 1]) | (u(b[o]) << 8));
    }

    private static long u32(byte[] b, int o, boolean le) {
        if (le) return (u(b[o]) | ((long) u(b[o + 1]) << 8) | ((long) u(b[o + 2]) << 16) | ((long) u(b[o + 3]) << 24));
        return (u(b[o + 3]) | ((long) u(b[o + 2]) << 8) | ((long) u(b[o + 1]) << 16) | ((long) u(b[o]) << 24));
    }

    private static long u64(byte[] b, int o, boolean le) {
        long lo = u32(b, le ? o : o + 4, le) & 0xFFFFFFFFL;
        long hi = u32(b, le ? o + 4 : o, le) & 0xFFFFFFFFL;
        return (hi << 32) | lo;
    }

    private static String cstr(byte[] s) {
        int end = 0;
        while (end < s.length && s[end] != 0) end++;
        return new String(s, 0, end);
    }
}
