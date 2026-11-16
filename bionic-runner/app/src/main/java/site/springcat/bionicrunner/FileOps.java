package site.springcat.bionicrunner;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * エクスプローラーのファイル操作。まず root のシェル (RootSession) で試し、それで出来なければ
 * アプリ自身の権限で行う。root が無い端末や、root のシェルが内部ストレージやアプリの作業領域に
 * 入れない端末でも、アプリが読み書きできる場所 (「全ファイルへのアクセス」を許可した内部ストレージ、
 * SD カード、アプリの作業領域、誰でも読めるシステムのファイル) は扱えるようにするため。
 * Android の API は使っていないので、PC 上の JVM でも単体で検証できる。
 */
final class FileOps {

    private interface Action {
        void run() throws IOException;
    }

    private final RootSession root;
    /** root のシェルを起動できたか (できていなければ最初からアプリの権限だけで行う) */
    private volatile boolean rootStarted;

    /** @param root 使わない (アプリの権限だけで行う) なら null */
    FileOps(RootSession root) {
        this.root = root;
    }

    void setRootStarted(boolean started) {
        rootStarted = started;
    }

    private boolean useRoot() {
        return root != null && rootStarted;
    }

    /** フォルダの中身。どちらの方法でも開けなければ null */
    RootSession.Listing list(String dir) {
        if (useRoot()) {
            try {
                RootSession.Listing l = root.list(dir);
                if (l != null) return l;
            } catch (IOException ignored) {
                // 下でアプリの権限で試す
            }
        }
        return localList(dir);
    }

    byte[] read(String file, int limit) throws IOException {
        IOException first = null;
        if (useRoot()) {
            try {
                return root.read(file, limit);
            } catch (IOException e) {
                first = e;
            }
        }
        try {
            return localRead(file, limit);
        } catch (IOException e) {
            throw first != null ? first : e;
        }
    }

    void write(final String file, final byte[] data) throws IOException {
        either(() -> root.write(file, data), () -> localWrite(file, data));
    }

    void makeDir(final String path) throws IOException {
        either(() -> root.makeDir(path), () -> {
            File f = new File(path);
            requireAbsent(f);
            if (!f.mkdir()) throw new IOException("フォルダを作成できませんでした");
        });
    }

    void makeFile(final String path) throws IOException {
        either(() -> root.makeFile(path), () -> {
            File f = new File(path);
            requireAbsent(f);
            if (!f.createNewFile()) throw new IOException("ファイルを作成できませんでした");
        });
    }

    void move(final String from, final String to) throws IOException {
        either(() -> root.move(from, to), () -> {
            File src = new File(from), dst = new File(to);
            requireAbsent(dst);
            if (src.renameTo(dst)) return;
            // 別の保存先へは名前の付け替えで移せないので、コピーしてから元を消す
            copyTree(src, dst);
            deleteTree(src);
        });
    }

    void copy(final String from, final String to) throws IOException {
        either(() -> root.copy(from, to), () -> {
            File dst = new File(to);
            requireAbsent(dst);
            copyTree(new File(from), dst);
        });
    }

    void delete(final String path) throws IOException {
        either(() -> root.delete(path), () -> deleteTree(new File(path)));
    }

    // ------------------------------------------------------------ 圧縮と解凍

    /** root のシェルを経由して圧縮・解凍するときに使う、アプリ自身の一時置き場 */
    private File stageDir;

    void setStageDir(File dir) {
        stageDir = dir;
    }

    /**
     * sources (同じフォルダにあるファイルやフォルダ) を 1 つの圧縮ファイル dest にまとめる。
     * まずアプリの権限で行い、読み書きできない場所なら root のシェルで一時置き場へ写してから行う。
     */
    void compress(List<String> sources, String dest, boolean tarGz) throws IOException {
        compress(sources, dest, tarGz, null);
    }

    /** password が空でなければ、パスワード付きの zip (AES-256) にする */
    void compress(List<String> sources, String dest, boolean tarGz, char[] password)
            throws IOException {
        File out = new File(dest);
        IOException first;
        try {
            requireAbsent(out);
            List<File> files = new ArrayList<>();
            for (String s : sources) files.add(new File(s));
            Archive.create(files, out, tarGz, password);
            return;
        } catch (IOException e) {
            first = e;
            if (out.isFile() && !"同じ名前のものが既にあります".equals(e.getMessage())) out.delete();
        }
        if (!useRoot() || stageDir == null) throw first;
        File stage = newStage();
        try {
            File in = new File(stage, "in");
            if (!in.mkdirs()) throw first;
            openToRoot(stage);
            List<File> files = new ArrayList<>();
            for (String s : sources) {
                root.run("cp -R -- " + RootSession.sq(s) + " " + RootSession.sq(in.getPath() + "/"));
                files.add(new File(in, new File(s).getName()));
            }
            root.run("chmod -R a+rX -- " + RootSession.sq(in.getPath()));
            File packed = new File(stage, "out");
            Archive.create(files, packed, tarGz, password);
            packed.setReadable(true, false);
            root.run(RootSession.notExists(dest) + " && cp -- " + RootSession.sq(packed.getPath())
                    + " " + RootSession.sq(dest));
        } finally {
            dropStage(stage);
        }
    }

    /**
     * 圧縮ファイル archive を解凍して dest に置く。tar や zip なら dest はフォルダになり、
     * 圧縮されただけの 1 個のファイル (.gz など) なら dest はそのファイルになる。
     */
    void extract(String archive, String dest) throws IOException {
        extract(archive, dest, null);
    }

    /**
     * password はパスワード付きの zip 用。要るのに無い、または違うときは
     * {@link ZipCrypt.PasswordException} (呼び出し側で尋ね直す)。
     */
    void extract(String archive, String dest, char[] password) throws IOException {
        File src = new File(archive), out = new File(dest);
        IOException first;
        // 途中のものは隣の隠しフォルダに作り、出来上がってから目的の名前にする
        File tmp = new File(out.getParentFile(), ".br-extract-" + System.nanoTime());
        try {
            requireAbsent(out);
            File made = unpack(src, tmp, out.getName(), password);
            if (!made.renameTo(out)) throw new IOException("作成できません: " + out.getName());
            return;
        } catch (ZipCrypt.PasswordException e) {
            throw e; // 読めてはいる。root 経由でやり直しても同じなので、そのまま伝える
        } catch (IOException e) {
            first = e;
        } finally {
            Archive.deleteTree(tmp);
        }
        if (!useRoot() || stageDir == null) throw first;
        File stage = newStage();
        try {
            if (!stage.mkdirs()) throw first;
            openToRoot(stage);
            File copy = new File(stage, "src");
            root.run("cp -- " + RootSession.sq(archive) + " " + RootSession.sq(copy.getPath())
                    + " && chmod a+r -- " + RootSession.sq(copy.getPath()));
            File made = unpack(copy, new File(stage, "out"), out.getName(), password);
            root.run("chmod -R a+rX -- " + RootSession.sq(made.getPath()) + "; "
                    + RootSession.notExists(dest) + " && cp -R -- " + RootSession.sq(made.getPath())
                    + " " + RootSession.sq(dest));
        } finally {
            dropStage(stage);
        }
    }

    /** src を tmp の中へ展開し、出来たもの (フォルダか、1 個のファイル) を返す */
    private static File unpack(File src, File tmp, String name, char[] password)
            throws IOException {
        Archive.Format format;
        try {
            format = Archive.detect(src);
        } catch (IOException e) {
            throw new IOException("読み込めませんでした (権限がありません)");
        }
        if (format == Archive.Format.NONE) throw new IOException("圧縮ファイルではありません");
        if (format == Archive.Format.UNSUPPORTED) {
            throw new IOException("この圧縮形式 (7z / rar / zstd) は解凍できません");
        }
        File root = new File(tmp, "x");
        Archive.Result r;
        try {
            r = Archive.extract(src, format, root, name, password);
        } catch (java.io.EOFException | java.util.zip.ZipException e) {
            throw new IOException("圧縮ファイルが壊れているか、途中で切れています");
        }
        return r.tree ? root : r.files.get(0).file;
    }

    private File newStage() {
        return new File(stageDir, "br-stage-" + System.nanoTime());
    }

    /** root のシェル (別の利用者) が一時置き場に出入りできるようにする */
    private static void openToRoot(File stage) {
        stage.setReadable(true, false);
        stage.setWritable(true, false);
        stage.setExecutable(true, false);
    }

    /** 一時置き場を消す。root が作ったものはアプリからは消せないので、root のシェルにも消させる */
    private void dropStage(File stage) {
        try {
            root.run("rm -rf -- " + RootSession.sq(stage.getPath()));
        } catch (IOException ignored) {
            // 下でアプリの権限でも消す
        }
        Archive.deleteTree(stage);
    }

    /** root で試し、失敗したらアプリの権限で試す。両方失敗したら、後者の理由を伝える */
    private void either(Action viaRoot, Action local) throws IOException {
        if (useRoot()) {
            try {
                viaRoot.run();
                return;
            } catch (IOException ignored) {
                // 下でアプリの権限で試す
            }
        }
        local.run();
    }

    // -------------------------------------------------------- アプリの権限での操作

    private static void requireAbsent(File f) throws IOException {
        if (f.exists()) throw new IOException("同じ名前のものが既にあります");
    }

    private static RootSession.Listing localList(String dir) {
        File d = new File(dir);
        File[] files = d.listFiles();
        if (files == null) return null; // フォルダでない、または読む権限が無い
        RootSession.Listing l = new RootSession.Listing();
        try {
            l.path = d.getCanonicalPath();
        } catch (IOException e) {
            l.path = d.getAbsolutePath();
        }
        Arrays.sort(files);
        for (File f : files) {
            RootSession.Entry e = new RootSession.Entry();
            e.name = f.getName();
            e.type = f.isDirectory() ? "directory" : f.isFile() ? "regular file" : "other";
            e.size = f.length();
            e.mtime = f.lastModified() / 1000;
            l.entries.add(e);
        }
        return l;
    }

    private static byte[] localRead(String file, int limit) throws IOException {
        File f = new File(file);
        if (f.isDirectory()) throw new IOException("フォルダは開けません");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[limit];
            int total = 0;
            while (total < limit) {
                int n = in.read(buf, total, limit - total);
                if (n < 0) break;
                total += n;
            }
            return Arrays.copyOf(buf, total);
        } catch (IOException e) {
            throw new IOException("読み込めませんでした (権限がありません)");
        }
    }

    private static void localWrite(String file, byte[] data) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(data);
        } catch (IOException e) {
            throw new IOException("書き込めませんでした (権限がありません)");
        }
    }

    private static void copyTree(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.mkdir()) throw new IOException("フォルダを作成できませんでした: " + dst.getName());
            File[] kids = src.listFiles();
            if (kids == null) throw new IOException("読み込めませんでした: " + src.getName());
            for (File k : kids) copyTree(k, new File(dst, k.getName()));
            return;
        }
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (IOException e) {
            throw new IOException("コピーできませんでした: " + src.getName());
        }
    }

    private static void deleteTree(File f) throws IOException {
        File[] kids = f.isDirectory() ? f.listFiles() : null;
        if (kids != null) for (File k : kids) deleteTree(k);
        if (!f.delete() && f.exists()) throw new IOException("削除できませんでした: " + f.getName());
    }
}
