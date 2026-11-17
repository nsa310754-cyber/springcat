package site.springcat.bionicrunner;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * アプリに同梱した 7-Zip (コマンド版 7zz。lib/&lt;ABI&gt;/lib7zz.so として入れてある) の呼び出し。
 *
 * zip・tar・gzip は Java だけで扱う ({@link Archive})。それ以外の形式 (7z・rar・cab・iso・msi・
 * インストーラーや自己解凍形式の exe・deb・rpm・zstd・lzh など) の展開と、7z・tar.xz・tar.bz2 の
 * 作成を 7zz に任せる。Android の API は使っていないので、PC 上の JVM でも単体で検証できる
 * (その場合は {@link #tool} に PC 用の 7zz の場所を入れる)。
 */
final class SevenZip {

    /** 7zz の場所。使えなければ null */
    static volatile String tool;

    /** 圧縮ファイルの概要 */
    static final class Info {
        /** 7-Zip が判定した形式の名前 ("7z"、"Rar5"、"Nsis"、"Cab" など) */
        String type = "";
        /** 中身が暗号化された項目がある */
        boolean encrypted;
        int files;
        long totalSize;
        /** 画面に出すための一覧 (多すぎるときは先頭だけ) */
        final List<Item> items = new ArrayList<>();
    }

    static final class Item {
        String path = "";
        long size;
        boolean dir;
        boolean encrypted;
    }

    /**
     * 7-Zip は実行ファイルや文書の「構造」も圧縮ファイルのように開けるが (exe のセクションなど)、
     * それを取り出しても役に立たないので、圧縮ファイルとしては扱わない形式。
     */
    private static final Set<String> NOT_ARCHIVES = new HashSet<>(Arrays.asList(
            "pe", "elf", "macho", "mub", "coff", "te", "uefic", "uefif", "base64", "ihex", "hash",
            "split", "swf", "swfc", "flv", "mbr", "gpt", "apm", "lp", "sparse"));

    /** 1 本の流れを圧縮しただけの形式 (中のファイル名を持たない) */
    private static final Set<String> STREAMS = new HashSet<>(Arrays.asList(
            "zstd", "lzma", "lzma86", "z", "ppmd", "xz", "bzip2", "gzip", "mslz"));

    private static final int MAX_ITEMS = 300;

    private SevenZip() {
    }

    static void init(String nativeLibraryDir) {
        File f = new File(nativeLibraryDir, "lib7zz.so");
        tool = f.isFile() ? f.getAbsolutePath() : null;
    }

    static boolean available() {
        return tool != null;
    }

    static boolean isStream(String type) {
        return STREAMS.contains(type.toLowerCase(Locale.ROOT));
    }

    /**
     * 7-Zip で開ける圧縮ファイルなら、その概要を返す。圧縮ファイルでなければ null。
     * 名前まで暗号化されていてパスワードが無い (または違う) ときは {@link ZipCrypt.PasswordException}。
     */
    static Info probe(File f, char[] password) throws IOException {
        if (!available()) return null;
        // -p は必ず付ける。付けないと、パスワードが要るときに入力待ちで止まってしまう
        Process p = new ProcessBuilder(tool, "l", "-slt", "-sccUTF-8", pw(password), "--",
                f.getAbsolutePath()).redirectErrorStream(true).start();
        Info info = new Info();
        boolean inItems = false, wrong = false, notArchive = false, sawType = false;
        Item item = null;
        try {
            p.getOutputStream().close();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) {
                if (line.contains("Wrong password")) wrong = true;
                if (line.contains("Cannot open the file as archive")) notArchive = true;
                if (line.startsWith("----------")) {
                    inItems = true;
                    continue;
                }
                int eq = line.indexOf(" = ");
                String key = eq < 0 ? "" : line.substring(0, eq);
                String value = eq < 0 ? "" : line.substring(eq + 3);
                if (!inItems) {
                    if (key.equals("Type") && !sawType) {
                        sawType = true;
                        info.type = value.trim();
                    }
                    continue;
                }
                if (key.equals("Path")) {
                    item = new Item();
                    item.path = value;
                    if (info.items.size() < MAX_ITEMS) info.items.add(item);
                    info.files++;
                } else if (item == null) {
                    continue;
                } else if (key.equals("Size")) {
                    try {
                        item.size = Long.parseLong(value.trim());
                        info.totalSize += item.size;
                    } catch (NumberFormatException ignored) {
                        // 大きさの分からない項目もある
                    }
                } else if (key.equals("Folder") && value.trim().equals("+")) {
                    markDir(info, item);
                } else if (key.equals("Attributes") && value.trim().startsWith("D")) {
                    markDir(info, item);
                } else if (key.equals("Encrypted") && value.trim().equals("+")) {
                    item.encrypted = true;
                    info.encrypted = true;
                }
            }
            waitFor(p);
        } finally {
            p.destroy();
        }
        if (wrong) throw new ZipCrypt.PasswordException(password != null && password.length > 0);
        if (notArchive || !sawType) return null;
        if (NOT_ARCHIVES.contains(info.type.toLowerCase(Locale.ROOT))) return null;
        return info;
    }

    private static void markDir(Info info, Item item) {
        if (!item.dir) {
            item.dir = true;
            info.files--;
        }
    }

    /** src を dest (フォルダ) へ展開する */
    static void extract(File src, File dest, char[] password) throws IOException {
        if (!dest.isDirectory() && !dest.mkdirs()) throw new IOException("展開先を作れません");
        run(null, password, Arrays.asList(tool, "x", "-y", "-aoa", "-bd", "-bso0", "-bsp0",
                "-sccUTF-8", pw(password), "-o" + dest.getAbsolutePath(), "--",
                src.getAbsolutePath()));
    }

    /**
     * src (xz や bzip2 など、1 本の流れを圧縮したもの) を伸長しながら読む流れを開く。
     * 読み終えたら {@link Process#destroy} すること。
     */
    static Process stream(File src) throws IOException {
        return new ProcessBuilder(tool, "x", "-so", "-bd", "-p", "--", src.getAbsolutePath())
                .start();
    }

    /**
     * sources (同じフォルダにあるファイルやフォルダ) を out にまとめる。
     * kind は "7z"・"tar"・"tar.xz"・"tar.bz2"。パスワードを付けられるのは 7z だけで、
     * そのときはファイルの名前の一覧も暗号化する。
     */
    static void create(List<File> sources, File out, String kind, char[] password)
            throws IOException {
        if (!available()) throw new IOException("7-Zip がこのアプリに入っていません");
        if (sources.isEmpty()) throw new IOException("圧縮するものがありません");
        boolean locked = password != null && password.length > 0;
        if (locked && !kind.equals("7z")) {
            throw new IOException("パスワードを付けられるのは zip と 7z だけです");
        }
        File dir = sources.get(0).getAbsoluteFile().getParentFile();
        List<String> names = new ArrayList<>();
        for (File f : sources) names.add(f.getName());
        // 7zz は、拡張子の無い名前を渡すと勝手に拡張子を足す。必ず拡張子付きの仮の名前で作ってから直す
        long id = System.nanoTime();
        File parent = out.getAbsoluteFile().getParentFile();
        File tar = new File(parent, ".br-pack-" + id + ".tar");
        try {
            if (kind.equals("7z")) {
                File tmp = new File(parent, ".br-pack-" + id + ".7z");
                try {
                    List<String> cmd = new ArrayList<>(Arrays.asList(tool, "a", "-t7z", "-snl",
                            "-bd", "-bso0", "-bsp0", "-sccUTF-8"));
                    if (locked) {
                        cmd.add(pw(password));
                        cmd.add("-mhe=on");
                    }
                    cmd.add(tmp.getAbsolutePath());
                    cmd.add("--");
                    cmd.addAll(names);
                    run(dir, null, cmd);
                    if (!tmp.renameTo(out)) throw new IOException("作成できません: " + out.getName());
                } finally {
                    tmp.delete();
                }
                return;
            }
            List<String> cmd = new ArrayList<>(Arrays.asList(tool, "a", "-ttar", "-snl", "-bd",
                    "-bso0", "-bsp0", "-sccUTF-8", tar.getAbsolutePath(), "--"));
            cmd.addAll(names);
            run(dir, null, cmd);
            if (kind.equals("tar")) {
                if (!tar.renameTo(out)) throw new IOException("作成できません: " + out.getName());
                return;
            }
            boolean xz = kind.equals("tar.xz");
            if (!xz && !kind.equals("tar.bz2")) throw new IOException("対応していない形式です: " + kind);
            File tmp = new File(parent, ".br-pack-" + id + (xz ? ".xz" : ".bz2"));
            try {
                run(parent, null, Arrays.asList(tool, "a", xz ? "-txz" : "-tbzip2", "-bd", "-bso0",
                        "-bsp0", tmp.getAbsolutePath(), "--", tar.getName()));
                if (!tmp.renameTo(out)) throw new IOException("作成できません: " + out.getName());
            } finally {
                tmp.delete();
            }
        } finally {
            tar.delete();
        }
    }

    /**
     * Windows の exe (PE 形式) に埋め込まれたアイコン (.rsrc/ICON/*.ico) を dest へ取り出す。
     * exe を「実行ファイルの構造」として開く指定 (-tPE) を付ける。取り出せたら真。
     */
    static boolean extractPeIcons(File exe, File dest) throws IOException {
        if (!available()) return false;
        if (!dest.isDirectory() && !dest.mkdirs()) return false;
        Process p = new ProcessBuilder(tool, "x", "-y", "-aoa", "-bd", "-bso0", "-bsp0", "-tPE",
                "-o" + dest.getAbsolutePath(), exe.getAbsolutePath(), ".rsrc/ICON/*",
                ".rsrc/*/ICON/*", "-r").redirectErrorStream(true).start();
        try {
            p.getOutputStream().close();
            byte[] buf = new byte[4096];
            while (p.getInputStream().read(buf) > 0) {
                // 出力は使わない
            }
            return waitFor(p) <= 1;
        } finally {
            p.destroy();
        }
    }

    private static String pw(char[] password) {
        return "-p" + (password == null ? "" : new String(password));
    }

    /** 7zz を実行する。失敗したら、7zz の出したエラーの 1 行目を理由にして IOException にする */
    private static void run(File dir, char[] password, List<String> cmd) throws IOException {
        if (!available()) throw new IOException("7-Zip がこのアプリに入っていません");
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (dir != null) pb.directory(dir);
        Process p = pb.start();
        String first = null;
        boolean wrong = false, danger = false;
        try {
            p.getOutputStream().close();
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null) {
                if (line.contains("Wrong password")) wrong = true;
                if (line.contains("Dangerous link")) danger = true;
                if (first == null && line.startsWith("ERROR")) first = line;
            }
            int code = waitFor(p);
            if (code == 0 || code == 1) return; // 1 は「警告あり」(一部のファイルを読めなかった等)
            if (wrong) throw new ZipCrypt.PasswordException(password != null && password.length > 0);
            if (danger) {
                throw new IOException("展開先の外を指すリンクが入っているため、解凍を中止しました");
            }
            throw new IOException(first != null ? translate(first)
                    : "7-Zip が失敗しました (終了コード " + code + ")");
        } finally {
            p.destroy();
        }
    }

    /** よく出るエラーを日本語にする。それ以外は 7-Zip の文のまま出す */
    private static String translate(String error) {
        if (error.contains("Cannot open the file as archive")) return "圧縮ファイルではありません";
        if (error.contains("Unsupported Method")) return "対応していない圧縮方式が使われています";
        if (error.contains("CRC Failed") || error.contains("Data Error")
                || error.contains("Unexpected end")) {
            return "圧縮ファイルが壊れているか、途中で切れています";
        }
        if (error.contains("No space left")) return "端末の空き容量が足りません";
        return error.length() > 200 ? error.substring(0, 200) : error;
    }

    private static int waitFor(Process p) throws IOException {
        try {
            return p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("中断されました");
        }
    }
}
