package site.ragdollp.binrunner;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * ツールチェーン無しで、純 Java だけで「Android で直接動く」ネイティブ実行ファイル(ELF)を生成する。
 *
 * 生成するのは <b>静的リンクの ELF 実行ファイル</b>。動的リンカ(ld)を使わずカーネルの
 * システムコールを直接叩くので、Android(Bionic 環境)でもそのまま実行できる
 * ＝「Bionic 用の実行ファイル」。中身は「渡した文字列を標準出力に書いて exit(0)」という
 * 最小プログラム(write/exit の 2 システムコール)。
 *
 * ※ ここでの "exe" は Windows の .exe ではなく、Android/Linux ネイティブの実行ファイルのこと。
 *
 * 対応アーキ: aarch64(arm64-v8a)/ x86-64。32bit 端末では aarch64 を生成する(要 64bit 端末)。
 */
public final class ElfGenerator {

    public static final long BASE = 0x400000L; // ロードアドレス
    private static final int EH = 64;           // ELF64 ヘッダ長
    private static final int PH = 56;           // プログラムヘッダ長(1 個)
    private static final int HDR = EH + PH;     // 120

    private ElfGenerator() {}

    public static final class Gen {
        public byte[] bytes;
        public String arch;   // 生成したアーキ名
        public int eMachine;  // ELF e_machine
    }

    /** 端末 ABI に合わせて生成(x86_64 以外は aarch64)。 */
    public static Gen forAbi(String abi, String text) {
        if (abi != null && abi.startsWith("x86_64")) {
            Gen g = new Gen(); g.bytes = x86_64(text); g.arch = "x86-64"; g.eMachine = 62; return g;
        }
        Gen g = new Gen(); g.bytes = aarch64(text); g.arch = "ARM64 (aarch64)"; g.eMachine = 183; return g;
    }

    /** aarch64: write(1,msg,len); exit(0) を svc #0 で実行する静的 ELF。 */
    public static byte[] aarch64(String text) {
        byte[] msg = (text + "\n").getBytes(StandardCharsets.UTF_8);
        int len = msg.length & 0xFFFF;
        final int CODE = 36;                    // 下記 9 命令 ×4 バイト = 固定 36
        long msgVaddr = BASE + HDR + CODE;
        int lo = (int) (msgVaddr & 0xFFFF);
        int hi = (int) ((msgVaddr >> 16) & 0xFFFF);

        ByteArrayOutputStream c = new ByteArrayOutputStream();
        w32le(c, 0xD2800020);                   // mov  x0, #1            (fd=stdout)
        w32le(c, 0xD2800001 | (lo << 5));        // movz x1, #lo          (buf 下位16bit)
        w32le(c, 0xF2A00001 | (hi << 5));        // movk x1, #hi, lsl #16 (buf 上位16bit)
        w32le(c, 0xD2800002 | (len << 5));       // mov  x2, #len         (count)
        w32le(c, 0xD2800808);                    // mov  x8, #64          (sys_write)
        w32le(c, 0xD4000001);                    // svc  #0
        w32le(c, 0xD2800000);                    // mov  x0, #0           (status=0)
        w32le(c, 0xD2800BA8);                    // mov  x8, #93          (sys_exit)
        w32le(c, 0xD4000001);                    // svc  #0
        return assemble(183, c.toByteArray(), msg);
    }

    /** x86-64: write(1,msg,len); exit(0) を syscall で実行する静的 ELF。 */
    public static byte[] x86_64(String text) {
        byte[] msg = (text + "\n").getBytes(StandardCharsets.UTF_8);
        int len = msg.length;
        final int CODE = 37;                    // 下記の合計バイト数(固定)
        long msgVaddr = BASE + HDR + CODE;

        ByteArrayOutputStream c = new ByteArrayOutputStream();
        c.write(0xB8); le32(c, 1);               // mov eax, 1            (sys_write)
        c.write(0xBF); le32(c, 1);               // mov edi, 1            (fd=stdout)
        c.write(0x48); c.write(0xBE); le64(c, msgVaddr); // movabs rsi, &msg (buf)
        c.write(0xBA); le32(c, len);             // mov edx, len          (count)
        c.write(0x0F); c.write(0x05);            // syscall
        c.write(0xB8); le32(c, 60);              // mov eax, 60           (sys_exit)
        c.write(0x48); c.write(0x31); c.write(0xFF); // xor rdi, rdi      (status=0)
        c.write(0x0F); c.write(0x05);            // syscall
        return assemble(62, c.toByteArray(), msg);
    }

    /** ELF64 ヘッダ + 1 つの PT_LOAD(R+X)+ コード + メッセージ を組み立てる。 */
    private static byte[] assemble(int machine, byte[] code, byte[] msg) {
        int total = HDR + code.length + msg.length;
        byte[] b = new byte[total];

        // --- ELF ヘッダ(e_ident ほか) ---
        b[0] = 0x7F; b[1] = 'E'; b[2] = 'L'; b[3] = 'F';
        b[4] = 2;  // EI_CLASS = 64bit
        b[5] = 1;  // EI_DATA  = little endian
        b[6] = 1;  // EI_VERSION
        b[7] = 0;  // EI_OSABI = System V (静的なので Android でも可)
        le16(b, 16, 2);                 // e_type = ET_EXEC
        le16(b, 18, machine);           // e_machine
        le32i(b, 20, 1);                // e_version
        le64(b, 24, BASE + HDR);        // e_entry(= コード先頭)
        le64(b, 32, EH);                // e_phoff = 64
        le64(b, 40, 0);                 // e_shoff
        le32i(b, 48, 0);                // e_flags
        le16(b, 52, EH);                // e_ehsize
        le16(b, 54, PH);                // e_phentsize
        le16(b, 56, 1);                 // e_phnum
        le16(b, 58, 0);                 // e_shentsize
        le16(b, 60, 0);                 // e_shnum
        le16(b, 62, 0);                 // e_shstrndx

        // --- プログラムヘッダ(ELF64: type, flags, offset, vaddr, paddr, filesz, memsz, align) ---
        int p = EH;
        le32i(b, p,      1);            // p_type  = PT_LOAD
        le32i(b, p + 4,  5);            // p_flags = R(4)+X(1)
        le64(b, p + 8,   0);            // p_offset
        le64(b, p + 16,  BASE);         // p_vaddr
        le64(b, p + 24,  BASE);         // p_paddr
        le64(b, p + 32,  total);        // p_filesz
        le64(b, p + 40,  total);        // p_memsz
        le64(b, p + 48,  0x1000);       // p_align

        System.arraycopy(code, 0, b, HDR, code.length);
        System.arraycopy(msg, 0, b, HDR + code.length, msg.length);
        return b;
    }

    // --- バイト書き込みユーティリティ ---
    private static void w32le(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF); o.write((v >>> 8) & 0xFF); o.write((v >>> 16) & 0xFF); o.write((v >>> 24) & 0xFF);
    }
    private static void le32(ByteArrayOutputStream o, int v) { w32le(o, v); }
    private static void le64(ByteArrayOutputStream o, long v) {
        for (int i = 0; i < 8; i++) o.write((int) ((v >>> (8 * i)) & 0xFF));
    }
    private static void le16(byte[] b, int off, int v) { b[off] = (byte) v; b[off + 1] = (byte) (v >>> 8); }
    private static void le32i(byte[] b, int off, int v) {
        for (int i = 0; i < 4; i++) b[off + i] = (byte) (v >>> (8 * i));
    }
    private static void le64(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) b[off + i] = (byte) (v >>> (8 * i));
    }
}
