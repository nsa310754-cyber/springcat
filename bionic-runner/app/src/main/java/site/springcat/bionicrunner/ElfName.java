package site.springcat.bionicrunner;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * Linux の実行ファイル (ELF) の登録名に、種類が分かる拡張子を付ける。元の拡張子は問わない。
 *
 *   bionic 用 (Android の libc で動く)   … 名前.Belf
 *   glibc 用 (普通の Linux の libc で動く) … 名前.Gelf
 *   そのほかの ELF (静的リンクなど)        … 名前.elf
 *
 * 元の名前に付いていた実行ファイルらしい拡張子 (.elf・.bin・.out・.so など) は外してから付ける。
 * 圧縮ファイルから展開したもの (tar.gz など) とスクリプトは、そのままにする。
 * 前の版で登録したものも {@link #migrate} で付け直す。
 */
final class ElfName {

    /** 外してから付け直す拡張子 (小文字で比べる) */
    private static final String[] STRIP = {".belf", ".gelf", ".elf", ".bin", ".out", ".run", ".so",
            ".exe", ".axf"};

    private ElfName() {
    }

    /** 種類に合った拡張子。ELF でなければ null */
    static String suffix(ElfInfo info) {
        if (info == null) return null;
        switch (info.kind) {
            case BIONIC_DYNAMIC:
                return ".Belf";
            case FOREIGN_LIBC:
                String interp = info.interpreter == null ? "" : info.interpreter;
                return interp.contains("ld-linux") ? ".Gelf" : ".elf";
            case STATIC:
            case SHARED_OBJECT:
                return ".elf";
            default:
                return null;
        }
    }

    /** 登録名 name に、info の種類の拡張子を付けたもの (ELF でなければ name のまま) */
    static String apply(String name, ElfInfo info) {
        String ext = suffix(info);
        if (ext == null) return name;
        String base = name;
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            String lower = base.toLowerCase(Locale.ROOT);
            for (String s : STRIP) {
                if (lower.endsWith(s) && base.length() > s.length()) {
                    base = base.substring(0, base.length() - s.length());
                    stripped = true;
                    break;
                }
            }
        }
        if (base.length() + ext.length() > 64) base = base.substring(0, 64 - ext.length());
        return base + ext;
    }

    /**
     * 前の版で登録した ELF の名前に拡張子を付け直す (本体・起動スクリプト・アイコン・デスクトップの並び)。
     * 付け直した数を返す。同じ名前が既にあるものは、そのままにする
     */
    static synchronized int migrate(Context c) {
        File files = c.getFilesDir();
        File bin = new File(files, "bin");
        File[] list = bin.listFiles();
        if (list == null) return 0;
        int done = 0;
        for (File f : list) {
            if (!f.isFile()) continue;
            String name = f.getName();
            if (Archive.pkgDir(files, name).isDirectory()) continue; // 圧縮ファイルから展開したもの
            File glibc = new File(new File(files, "glibc-bin"), name);
            File emu = new File(Emu.binDir(c), name);
            File real = glibc.isFile() ? glibc : emu.isFile() ? emu : f;
            ElfInfo info;
            try {
                info = ElfInfo.read(real);
            } catch (IOException e) {
                continue;
            }
            String to = apply(name, info);
            if (to.equals(name) || new File(bin, to).exists()) continue;
            try {
                rename(c, name, to, real, info, real == glibc, real == emu);
                done++;
            } catch (IOException ignored) {
                // その 1 つは古い名前のまま
            }
        }
        return done;
    }

    private static void rename(Context c, String from, String to, File real, ElfInfo info,
                               boolean viaGlibc, boolean viaEmu) throws IOException {
        File bin = new File(c.getFilesDir(), "bin");
        File newReal = new File(real.getParentFile(), to);
        if (!real.renameTo(newReal)) throw new IOException("名前を変えられません");
        if (viaGlibc || viaEmu) {
            // 起動スクリプトには本体の場所が書いてあるので、新しい名前で作り直す
            File wrapper = new File(bin, to);
            Glibc.writeText(wrapper, viaEmu ? Emu.wrapperScript(c, info, newReal)
                    : Glibc.wrapperScript(c, info.abi(), newReal));
            wrapper.setReadable(true, false);
            wrapper.setExecutable(true, false);
            new File(bin, from).delete();
        }
        // アイコン
        File icon = Icons.file(c, from);
        if (icon.isFile()) {
            icon.renameTo(Icons.file(c, to));
            Icons.delete(c, from);
        }
        // デスクトップの並び (表示の切り替えごとに 4 つ)
        for (int filter = 0; filter < 4; filter++) {
            String layout = Prefs.desktopLayout(c, filter);
            if (layout == null) continue;
            String changed = replaceId(layout, "x:" + from, "x:" + to);
            if (!changed.equals(layout)) Prefs.setDesktopLayout(c, filter, changed);
        }
    }

    /** 並びの表 (タブと改行で区切った文字列) の中の項目 from を to に置き換える */
    static String replaceId(String layout, String from, String to) {
        StringBuilder out = new StringBuilder();
        String[] lines = layout.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String[] f = lines[i].split("\t", -1);
            for (int j = 0; j < f.length; j++) {
                if (f[j].equals(from)) f[j] = to;
            }
            out.append(String.join("\t", f));
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString();
    }
}
