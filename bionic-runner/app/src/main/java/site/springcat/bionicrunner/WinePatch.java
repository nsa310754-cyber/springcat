package site.springcat.bionicrunner;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Debian 版の Wine (ntdll.so と wineserver64) を、Android でも動くように少しだけ書き換える。
 *
 * Wine は wineserver との連絡用のフォルダを /run/user/&lt;番号&gt;/wine (無ければ /tmp の下) に作る。
 * Android にはどちらも無く、アプリからは作れない。Debian の変更 (temporary-directory.patch) には
 * 環境変数 TMPDIR で場所を変える仕組みもあるが、TMPDIR を付けると必ず強制終了する不具合がある
 * (getenv の結果を free してしまう)。
 * そこで、プログラムの中の「/run/user」を使う 1 か所 (lea 命令) が、アプリのフォルダの場所を
 * 指すように書き換える。場所の文字列は、ふだんは使われないエラーの文章の場所に書く。
 * 書き換えるのは各ファイル 2 か所 (命令の 4 バイトと、文章の場所) だけで、ほかには触らない。
 * 書き換える場所が 1 つに決まらないときは何もせず、失敗として扱う。
 *
 * Android の機能を使わない (PC でも同じコードを試せるように)。
 */
final class WinePatch {

    /** 書き換え前の場所 */
    static final String OLD = "/run/user";
    /** 場所の文字列を書く場所 (ふだんは出ないエラーの文章) */
    static final String SLOT =
            "fixed a temporary directory error, please try running the same command again\n";

    private WinePatch() {
    }

    /** ファイルを書き換える。すでに書き換え済みで同じ場所なら何もしない */
    static void patchFile(File f, String newDir) throws IOException {
        byte[] d;
        try (InputStream in = new FileInputStream(f)) {
            d = readAll(in, (int) f.length());
        }
        if (patch(d, newDir)) {
            File tmp = new File(f.getParentFile(), f.getName() + ".patch");
            try (OutputStream os = new FileOutputStream(tmp)) {
                os.write(d);
            }
            tmp.setExecutable(f.canExecute(), false);
            tmp.setReadable(true, false);
            if (!tmp.renameTo(f)) {
                tmp.delete();
                throw new IOException("書き換えたファイルを置けません: " + f.getName());
            }
        }
    }

    /**
     * d を書き換える。書き換えたら true、すでに同じ書き換えがされていれば false。
     * 書き換えられない (場所が 1 つに決まらないなど) ときは IOException
     */
    static boolean patch(byte[] d, String newDir) throws IOException {
        byte[] value = newDir.getBytes(StandardCharsets.UTF_8);
        byte[] slot = SLOT.getBytes(StandardCharsets.UTF_8);
        if (value.length + 1 > slot.length + 1) throw new IOException("場所の文字列が長すぎます");
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        if (d.length < 64 || d[0] != 0x7f || d[1] != 'E' || d[2] != 'L' || d[3] != 'F' || d[4] != 2
                || b.getShort(18) != 62) {
            throw new IOException("x86_64 の ELF ではありません");
        }
        long phoff = b.getLong(0x20);
        int phes = b.getShort(0x36) & 0xffff, phn = b.getShort(0x38) & 0xffff;
        int n = 0;
        long[][] segs = new long[phn][];
        for (int i = 0; i < phn; i++) {
            int p = (int) (phoff + (long) i * phes);
            if (b.getInt(p) != 1) continue; // PT_LOAD だけ
            segs[n++] = new long[]{b.getLong(p + 8), b.getLong(p + 16), b.getLong(p + 32), b.getInt(p + 4)};
        }

        int oldAt = findString(d, OLD.getBytes(StandardCharsets.UTF_8));
        int slotAt = findString(d, slot);
        if (slotAt < 0) {
            // 書き換え済みかもしれない: 文章の場所に同じ場所の文字列が入っていれば何もしない
            if (oldAt >= 0 && findString(d, value) >= 0 && countRefs(d, segs, n, vaddr(segs, n, oldAt)) == 0) {
                return false;
            }
            throw new IOException("書き換える場所が見つかりません (Wine の版が違います)");
        }
        if (oldAt < 0) throw new IOException("書き換える場所が見つかりません (Wine の版が違います)");
        long oldV = vaddr(segs, n, oldAt), slotV = vaddr(segs, n, slotAt);
        if (oldV < 0 || slotV < 0) throw new IOException("ファイルの形が想定と違います");
        if (countRefs(d, segs, n, oldV) != 1) throw new IOException("書き換える命令が 1 つに決まりません");

        // 命令の参照先を、文章の場所に向ける
        for (int s = 0; s < n; s++) {
            long off = segs[s][0], va = segs[s][1], fs = segs[s][2];
            if ((segs[s][3] & 1) == 0) continue; // 実行できる部分だけ
            for (long j = off; j + 7 <= off + fs; j++) {
                int at = (int) j;
                if (isRipLea(d, at) && va + (j - off) + 7 + b.getInt(at + 3) == oldV) {
                    b.putInt(at + 3, (int) (slotV - (va + (j - off) + 7)));
                }
            }
        }
        // 文章の場所に、新しい場所の文字列を書く (残りは 0 で埋める)
        for (int i = 0; i < slot.length; i++) d[slotAt + i] = i < value.length ? value[i] : 0;
        return true;
    }

    /**
     * win32u.so の NtUserInitializeClientPfnArrays の中の「pthread_once(&init_once, init_user)」を、
     * 「init_user()」を直接呼ぶように書き換える (call 命令の行き先 4 バイトだけ)。
     *
     * Box64 の pthread_once は、ほかのスレッドが同じ初期化を実行中だと最大 10 ミリ秒だけ待ち、
     * それを過ぎると「終わった」とみなして先へ進む。スマホでは、Windows の画面まわりの初期化
     * (init_user: 描画・設定・ウィンドウステーションの準備) が行われないまま進み、ウィンドウステーション
     * (WinSta0) が作られず、Windows の環境づくりが止まっていた。
     * この関数は 1 つのプログラムの中で 1 回 (user32.dll を読み込むとき) しか呼ばれないので、
     * 直接呼んでも同じ。書き換え済みなら false。場所が 1 つに決まらなければ IOException
     */
    static boolean patchInitUser(byte[] d) throws IOException {
        // lea rsi,[rip+init_user]; lea rdi,[rip+init_once]; mov [rip+x],rcx; call pthread_once@plt
        int found = -1;
        for (int i = 0; i + 26 <= d.length; i++) {
            if ((d[i] & 0xff) == 0x48 && (d[i + 1] & 0xff) == 0x8d && (d[i + 2] & 0xff) == 0x35
                    && (d[i + 7] & 0xff) == 0x48 && (d[i + 8] & 0xff) == 0x8d && (d[i + 9] & 0xff) == 0x3d
                    && (d[i + 14] & 0xff) == 0x48 && (d[i + 15] & 0xff) == 0x89 && (d[i + 16] & 0xff) == 0x0d
                    && (d[i + 21] & 0xff) == 0xe8) {
                if (found >= 0) throw new IOException("書き換える場所が 1 つに決まりません (win32u.so)");
                found = i;
            }
        }
        if (found < 0) throw new IOException("書き換える場所が見つかりません (Wine の版が違います)");
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        // ファイルの中の位置の差は、読み込んだあとの位置の差と同じ (同じ .text の中なので)
        long initUser = found + 7 + (long) b.getInt(found + 3);
        int call = found + 21;
        long next = call + 5;
        int rel = (int) (initUser - next);
        if (b.getInt(call + 1) == rel) return false;
        b.putInt(call + 1, rel);
        return true;
    }

    /** win32u.so を書き換える (patchInitUser)。書き換え済みなら何もしない */
    static void patchWin32uFile(File f) throws IOException {
        byte[] d;
        try (InputStream in = new FileInputStream(f)) {
            d = readAll(in, (int) f.length());
        }
        if (!patchInitUser(d)) return;
        File tmp = new File(f.getParentFile(), f.getName() + ".patch");
        try (OutputStream os = new FileOutputStream(tmp)) {
            os.write(d);
        }
        tmp.setReadable(true, false);
        if (!tmp.renameTo(f)) {
            tmp.delete();
            throw new IOException("書き換えたファイルを置けません: " + f.getName());
        }
    }

    /** lea reg, [rip+disp32] (64bit のレジスタ) か */
    private static boolean isRipLea(byte[] d, int at) {
        return (d[at] == 0x48 || d[at] == 0x4c) && (d[at + 1] & 0xff) == 0x8d && (d[at + 2] & 0xc7) == 5;
    }

    private static int countRefs(byte[] d, long[][] segs, int n, long target) {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        int count = 0;
        for (int s = 0; s < n; s++) {
            long off = segs[s][0], va = segs[s][1], fs = segs[s][2];
            if ((segs[s][3] & 1) == 0) continue;
            for (long j = off; j + 7 <= off + fs; j++) {
                int at = (int) j;
                if (isRipLea(d, at) && va + (j - off) + 7 + b.getInt(at + 3) == target) count++;
            }
        }
        return count;
    }

    private static long vaddr(long[][] segs, int n, int fileOff) {
        for (int s = 0; s < n; s++) {
            if (fileOff >= segs[s][0] && fileOff < segs[s][0] + segs[s][2]) return segs[s][1] + fileOff - segs[s][0];
        }
        return -1;
    }

    /** 0 で始まり 0 で終わる、ちょうどその文字列の場所 (1 つだけのとき) */
    private static int findString(byte[] d, byte[] s) {
        int found = -1;
        outer:
        for (int i = 1; i + s.length < d.length; i++) {
            if (d[i - 1] != 0 || d[i + s.length] != 0) continue;
            for (int k = 0; k < s.length; k++) if (d[i + k] != s[k]) continue outer;
            if (found >= 0) return -1;
            found = i;
        }
        return found;
    }

    private static byte[] readAll(InputStream in, int size) throws IOException {
        byte[] d = new byte[size];
        int got = 0;
        while (got < size) {
            int r = in.read(d, got, size - got);
            if (r < 0) throw new IOException("読み込みが途中で終わりました");
            got += r;
        }
        return d;
    }
}
