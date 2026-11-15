package site.springcat.bionicrunner;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * 実行ファイルのヘッダを読んで「この端末の Android (bionic) で動くか」を判定する。
 * Android 依存の API は使わないので、PC 上の JVM でも単体で検証できる。
 */
public final class ElfInfo {

    public enum Kind {
        /** 動的リンクで、ローダが Android の /system/bin/linker(64) = bionic 用 */
        BIONIC_DYNAMIC,
        /** 静的リンク (ローダ不要。カーネルの ABI だけに依存するので Android でも動く) */
        STATIC,
        /** glibc / musl など Android 以外の Linux 用 (ローダが端末に無いので動かない) */
        FOREIGN_LIBC,
        /** 共有ライブラリ (.so) など、単体では実行しないもの */
        SHARED_OBJECT,
        /** #! で始まるシェルスクリプト等 */
        SCRIPT,
        /** Windows の .exe / .dll (PE 形式) */
        WINDOWS_PE,
        /** ELF でもスクリプトでもない */
        UNKNOWN
    }

    public Kind kind = Kind.UNKNOWN;
    public boolean is64;
    public int machine;
    /** PT_INTERP の中身 (動的リンクのローダのパス)。無ければ null */
    public String interpreter;
    /** スクリプトの #! 行 */
    public String shebang;
    /**
     * 読み込む部分 (PT_LOAD) の境界の大きさのうち最小のもの。0 は不明。
     * 端末のメモリのページがこれより大きいと、カーネルはこのファイルを読み込めない。
     */
    public long loadAlign;

    private static final int PT_LOAD = 1;
    private static final int PT_DYNAMIC = 2;
    private static final int PT_INTERP = 3;
    private static final int ET_EXEC = 2;
    private static final int ET_DYN = 3;

    public static ElfInfo read(File f) throws IOException {
        ElfInfo info = new ElfInfo();
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] h = new byte[64];
            int n = readUpTo(raf, h);
            if (n >= 2 && h[0] == '#' && h[1] == '!') {
                info.kind = Kind.SCRIPT;
                String s = new String(h, 2, n - 2, StandardCharsets.ISO_8859_1);
                int nl = s.indexOf('\n');
                info.shebang = (nl >= 0 ? s.substring(0, nl) : s).trim();
                return info;
            }
            if (n >= 2 && h[0] == 'M' && h[1] == 'Z') {
                info.kind = Kind.WINDOWS_PE;
                return info;
            }
            if (n < 52 || h[0] != 0x7f || h[1] != 'E' || h[2] != 'L' || h[3] != 'F') {
                return info;
            }
            info.is64 = h[4] == 2;
            boolean le = h[5] != 2;
            int type = u16(h, 16, le);
            info.machine = u16(h, 18, le);

            long phoff;
            int phentsize, phnum;
            if (info.is64) {
                phoff = u64(h, 32, le);
                phentsize = u16(h, 54, le);
                phnum = u16(h, 56, le);
            } else {
                phoff = u32(h, 28, le);
                phentsize = u16(h, 42, le);
                phnum = u16(h, 44, le);
            }

            boolean hasDynamic = false;
            boolean pieFlag = false;
            int minEnt = info.is64 ? 56 : 32;
            if (phentsize >= minEnt && phnum > 0 && phnum < 4096) {
                byte[] ph = new byte[phentsize];
                for (int i = 0; i < phnum; i++) {
                    raf.seek(phoff + (long) i * phentsize);
                    if (readUpTo(raf, ph) < minEnt) break;
                    int pType = (int) u32(ph, 0, le);
                    if (pType == PT_LOAD) {
                        long align = info.is64 ? u64(ph, 48, le) : u32(ph, 28, le);
                        if (align > 0 && (info.loadAlign == 0 || align < info.loadAlign)) {
                            info.loadAlign = align;
                        }
                    } else if (pType == PT_DYNAMIC) {
                        hasDynamic = true;
                        long off = info.is64 ? u64(ph, 8, le) : u32(ph, 4, le);
                        long size = info.is64 ? u64(ph, 32, le) : u32(ph, 16, le);
                        pieFlag = hasPieFlag(raf, off, size, info.is64, le);
                    } else if (pType == PT_INTERP) {
                        long off = info.is64 ? u64(ph, 8, le) : u32(ph, 4, le);
                        long size = info.is64 ? u64(ph, 32, le) : u32(ph, 16, le);
                        if (size > 0 && size < 4096) {
                            byte[] b = new byte[(int) size];
                            raf.seek(off);
                            int got = readUpTo(raf, b);
                            int end = 0;
                            while (end < got && b[end] != 0) end++;
                            info.interpreter = new String(b, 0, end, StandardCharsets.ISO_8859_1);
                        }
                    }
                }
            }

            if (info.interpreter != null) {
                info.kind = isBionicLinker(info.interpreter)
                        ? Kind.BIONIC_DYNAMIC : Kind.FOREIGN_LIBC;
            } else if (type == ET_EXEC) {
                info.kind = Kind.STATIC;
            } else if (type == ET_DYN) {
                // ローダ指定なしの ET_DYN は .so か static-pie。static-pie はリンカが
                // DF_1_PIE を立てるのでそれで見分ける (古いリンカ製だと .so 扱いになる)
                info.kind = (hasDynamic && !pieFlag) ? Kind.SHARED_OBJECT : Kind.STATIC;
            }
        }
        return info;
    }

    /** 動的セクションの DT_FLAGS_1 に DF_1_PIE (位置独立実行ファイルの印) があるか */
    private static boolean hasPieFlag(RandomAccessFile raf, long off, long size,
                                      boolean is64, boolean le) throws IOException {
        final long DT_FLAGS_1 = 0x6ffffffbL;
        final long DF_1_PIE = 0x08000000L;
        int ent = is64 ? 16 : 8;
        if (size <= 0 || size > (1 << 20)) return false;
        byte[] d = new byte[(int) size];
        raf.seek(off);
        int got = readUpTo(raf, d);
        for (int o = 0; o + ent <= got; o += ent) {
            long tag = is64 ? u64(d, o, le) : u32(d, o, le);
            long val = is64 ? u64(d, o + 8, le) : u32(d, o + 4, le);
            if (tag == 0) break; // DT_NULL
            if (tag == DT_FLAGS_1) return (val & DF_1_PIE) != 0;
        }
        return false;
    }

    private static boolean isBionicLinker(String interp) {
        return interp.equals("/system/bin/linker")
                || interp.equals("/system/bin/linker64")
                || interp.equals("/system/bin/linker_asan")
                || interp.equals("/system/bin/linker_asan64")
                || interp.startsWith("/apex/com.android.runtime/bin/linker");
    }

    /** ELF の e_machine を Android の ABI 名に直す。対応外は null */
    public String abi() {
        switch (machine) {
            case 3: return "x86";
            case 40: return "armeabi-v7a";
            case 62: return "x86_64";
            case 183: return "arm64-v8a";
            case 243: return is64 ? "riscv64" : null;
            default: return null;
        }
    }

    public String machineName() {
        String abi = abi();
        if (abi != null) return abi;
        switch (machine) {
            case 8: return "MIPS";
            case 20: return "PowerPC";
            case 21: return "PowerPC64";
            case 22: return "s390";
            case 258: return "LoongArch";
            default: return "不明なCPU (e_machine=" + machine + ")";
        }
    }

    /** メモリのページが pageSize バイトの端末で読み込める作りか (不明なら読み込めるとみなす) */
    public boolean fitsPageSize(long pageSize) {
        return loadAlign == 0 || loadAlign >= pageSize;
    }

    public boolean isElf() {
        return kind == Kind.BIONIC_DYNAMIC || kind == Kind.STATIC
                || kind == Kind.FOREIGN_LIBC || kind == Kind.SHARED_OBJECT;
    }

    /** この端末の CPU で動く形式か (supportedAbis は Build.SUPPORTED_ABIS) */
    public boolean cpuMatches(String[] supportedAbis) {
        String abi = abi();
        if (abi == null) return false;
        for (String s : supportedAbis) if (s.equals(abi)) return true;
        return false;
    }

    private static int readUpTo(RandomAccessFile raf, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int r = raf.read(buf, total, buf.length - total);
            if (r < 0) break;
            total += r;
        }
        return total;
    }

    private static int u16(byte[] b, int o, boolean le) {
        int a = b[o] & 0xff, c = b[o + 1] & 0xff;
        return le ? (a | c << 8) : (a << 8 | c);
    }

    private static long u32(byte[] b, int o, boolean le) {
        long v = 0;
        for (int i = 0; i < 4; i++) {
            int idx = le ? o + 3 - i : o + i;
            v = (v << 8) | (b[idx] & 0xff);
        }
        return v;
    }

    private static long u64(byte[] b, int o, boolean le) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            int idx = le ? o + 7 - i : o + i;
            v = (v << 8) | (b[idx] & 0xff);
        }
        return v;
    }
}
