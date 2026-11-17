package site.springcat.bionicrunner;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * セキュリティ研究用の仮想マシン。怪しいプログラムを、端末から切り離された使い捨ての Linux の中で動かす。
 *
 * 中身は CPU エミュレータ QEMU (qemu-system-aarch64。Termux の Android 用ビルドを
 * lib/arm64-v8a/libqemusys.so として同梱) で、その上で Alpine Linux (公式の virt 版) を動かす。
 * Alpine は 90MB ほどあるので APK には入れず、初めて使うときに公式サイトから取ってきて、
 * 公式の SHA-256 で確かめる。
 *
 * 端末からの切り離し方:
 *  - 別の OS (自分専用のカーネル) で動く。仮想マシンの中で root になっても、端末には届かない
 *  - ネットワークは既定で無し。有りにすると外 (インターネット) へ出られるようになり、
 *    あわせて端末の中で待ち受けているもの (127.0.0.1) にも 10.0.2.2 経由で届いてしまう
 *    (QEMU の NAT の仕組み上、塞げない)。怪しいものを動かすときは無しで使う
 *  - 端末と共有するフォルダは読み取り専用。中から書き換えたり、ファイルを置いたりはできない
 *  - ディスクを持たない (メモリの上だけで動く)。電源を切ると中で起きたことはすべて消え、
 *    次に起動すると、いつも同じまっさらな状態から始まる
 *  - QEMU 自体はアプリの権限 (root ではない) で動くので、万一 QEMU の穴を突かれても、
 *    Android のアプリとしての制限の中にとどまる
 */
final class Vm {

    /**
     * 仮想マシンの CPU の種類。ARM64 は端末と同じ CPU なので速め。x86_64 は PC 用のプログラム
     * (x86_64 用のマルウェアの検体など) を、その CPU のまま中で動かすためのもの。
     */
    enum Arch {
        AARCH64("aarch64", "ARM64", "libqemusys.so", ""),
        X86_64("x86_64", "x86_64 (PC 用)", "libqemusysx86.so", "-x86_64");

        /** Alpine の配布物の場所の名前 */
        final String alpine;
        final String label;
        /** エンジンの名前 (lib/ の中) */
        final String tool;
        /** 保存先のフォルダ名の後ろに付ける文字 (ARM64 は前の版と同じ files/vm のまま) */
        final String suffix;

        Arch(String alpine, String label, String tool, String suffix) {
            this.alpine = alpine;
            this.label = label;
            this.tool = tool;
            this.suffix = suffix;
        }

        /** Alpine の配布物の一覧 (いちばん新しい安定版)。ここに各ファイルの名前と SHA-256 が載っている */
        String releases() {
            return "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/" + alpine + "/";
        }
    }

    /** ダウンロードの途中経過 */
    interface Progress {
        void update(String message, int percent);
    }

    private Vm() {
    }

    /** この端末で仮想マシンを使えるか (エンジンは 64bit の ARM 端末用だけを同梱している) */
    static boolean supported(Context c) {
        return supported(c, Arch.AARCH64) || supported(c, Arch.X86_64);
    }

    static boolean supported(Context c, Arch a) {
        return new File(c.getApplicationInfo().nativeLibraryDir, a.tool).isFile();
    }

    static File dir(Context c, Arch a) {
        return new File(c.getFilesDir(), "vm" + a.suffix);
    }

    /** 仮想マシンと共有するフォルダ。エクスプローラーの「アプリの作業領域」から入れる */
    static File shareDir(Context c) {
        return new File(new File(c.getFilesDir(), "home"), "vm-share");
    }

    private static File iso(Context c, Arch a) {
        return new File(dir(c, a), "alpine.iso");
    }

    private static File kernel(Context c, Arch a) {
        return new File(dir(c, a), "vmlinuz-virt");
    }

    private static File initrd(Context c, Arch a) {
        return new File(dir(c, a), "initramfs-virt");
    }

    private static File versionFile(Context c, Arch a) {
        return new File(dir(c, a), "VERSION.txt");
    }

    /** 準備が済んでいるか */
    static boolean ready(Context c, Arch a) {
        return iso(c, a).isFile() && kernel(c, a).isFile() && initrd(c, a).isFile()
                && versionFile(c, a).isFile();
    }

    /** 準備済みの Alpine の版 (無ければ null) */
    static String version(Context c, Arch a) {
        try (InputStream in = new FileInputStream(versionFile(c, a))) {
            return new String(Icons.readAll(in), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    /** ダウンロードしたものを消す (空き容量を戻す) */
    static void remove(Context c, Arch a) {
        if (a == Arch.AARCH64) {
            // ARM64 の保存先 (files/vm) は、ほかの CPU 用の保存先と別なので、中身だけを消す
            File[] kids = dir(c, a).listFiles();
            if (kids != null) for (File k : kids) Archive.deleteTree(k);
        } else {
            Archive.deleteTree(dir(c, a));
        }
    }

    /** 使っている容量 (バイト) */
    static long size(Context c, Arch a) {
        return Archive.treeSize(dir(c, a));
    }

    // ------------------------------------------------------------------ 準備

    /** Alpine を取ってきて確かめ、起動に使うカーネルを取り出す。時間がかかるので UI スレッド以外で呼ぶ */
    static void prepare(Context c, Arch a, Progress progress) throws IOException {
        File d = dir(c, a);
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("保存先を作れません");
        progress.update("最新版を確認しています…", 0);
        String[] release = latestVirt(new String(fetchSmall(a.releases() + "latest-releases.yaml"),
                StandardCharsets.UTF_8));
        String file = release[0], sha256 = release[1], ver = release[2];
        long size = Long.parseLong(release[3]);
        long free = d.getUsableSpace();
        if (free > 0 && free < size + 150L * 1024 * 1024) {
            throw new IOException("空き容量が足りません (" + (size / 1024 / 1024 + 150) + "MB ほど必要です)");
        }
        File part = new File(d, "alpine.iso.part");
        download(a.releases() + file, part, size, sha256, (m, p) -> progress.update(
                "Alpine Linux " + ver + " (" + a.label + ") をダウンロード中… " + p + "%", p));
        progress.update("起動の準備をしています…", 100);
        File isoFile = iso(c, a);
        isoFile.delete();
        if (!part.renameTo(isoFile)) throw new IOException("保存できません");
        // ISO の中から、起動に使うカーネルと初期化用のファイルを取り出す (同梱の 7-Zip で)
        SevenZip.init(c.getApplicationInfo().nativeLibraryDir);
        File boot = new File(d, "boot-tmp");
        Archive.deleteTree(boot);
        SevenZip.extract(isoFile, boot, null);
        File k = new File(boot, "boot/vmlinuz-virt"), i = new File(boot, "boot/initramfs-virt");
        if (!k.isFile() || !i.isFile()) {
            Archive.deleteTree(boot);
            throw new IOException("ISO の中に起動用のファイルが見つかりません");
        }
        kernel(c, a).delete();
        initrd(c, a).delete();
        boolean ok = k.renameTo(kernel(c, a)) && i.renameTo(initrd(c, a));
        Archive.deleteTree(boot);
        if (!ok) throw new IOException("保存できません");
        Glibc.writeText(versionFile(c, a), ver + "\n");
        // QEMU が読み込むライブラリ (32bit 用のエミュレータと共有) を展開する
        Glibc.installAsset(c, "qemu", "arm64-v8a",
                new File(new File(new File(c.getFilesDir(), "qemu"), "arm64-v8a/lib"), "libglib-2.0.so.0"));
    }

    /**
     * latest-releases.yaml から、virt 版のファイル名・SHA-256・版・大きさを取り出す。
     * YAML の読み込み部品は使わず、「flavor: alpine-virt」の項目の行を拾う。
     */
    static String[] latestVirt(String yaml) throws IOException {
        String[] items = yaml.split("\n-");
        for (String item : items) {
            if (!item.contains("flavor: alpine-virt")) continue;
            String file = field(item, "file"), sha = field(item, "sha256");
            String ver = field(item, "version"), size = field(item, "size");
            if (file == null || sha == null || !file.endsWith(".iso") || !file.matches("[A-Za-z0-9._-]+")
                    || !sha.matches("[0-9a-f]{64}") || size == null || !size.matches("\\d{1,12}")) {
                continue;
            }
            return new String[]{file, sha, ver == null ? "" : ver, size};
        }
        throw new IOException("配布物の一覧に virt 版が見つかりません");
    }

    private static String field(String item, String key) {
        for (String line : item.split("\n")) {
            String t = line.trim();
            if (t.startsWith(key + ":")) return t.substring(key.length() + 1).trim();
        }
        return null;
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(20_000);
        con.setReadTimeout(60_000);
        con.setInstanceFollowRedirects(true);
        if (con.getResponseCode() != 200) {
            int code = con.getResponseCode();
            con.disconnect();
            throw new IOException("ダウンロードできませんでした (HTTP " + code + ")");
        }
        return con;
    }

    private static byte[] fetchSmall(String url) throws IOException {
        HttpURLConnection con = open(url);
        try (InputStream in = con.getInputStream()) {
            return Icons.readAll(in);
        } finally {
            con.disconnect();
        }
    }

    /** url を out へ保存し、大きさと SHA-256 を確かめる。合わなければ消して IOException */
    private static void download(String url, File out, long size, String sha256, Progress progress)
            throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e.getMessage());
        }
        HttpURLConnection con = open(url);
        long got = 0;
        int last = -1;
        try (InputStream in = con.getInputStream(); OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                md.update(buf, 0, n);
                got += n;
                if (got > size) throw new IOException("大きさが一覧と違います");
                int p = (int) (got * 100 / size);
                if (p != last) {
                    last = p;
                    progress.update(null, p);
                }
            }
        } catch (IOException e) {
            out.delete();
            throw e;
        } finally {
            con.disconnect();
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest()) hex.append(String.format("%02x", b));
        if (got != size || !hex.toString().equals(sha256)) {
            out.delete();
            throw new IOException("ダウンロードしたファイルが壊れているか、すり替えられています (SHA-256 が一致しません)");
        }
    }

    // ------------------------------------------------------------------ 起動

    /**
     * 仮想マシンを起動するコマンド。画面は文字だけ (シリアル端末) で、出入力は呼び出し側の端末画面につなぐ。
     * network が真なら、外へのネットワーク (NAT) を付ける。そのときは端末の中で待ち受けているもの
     * (127.0.0.1) にも 10.0.2.2 経由で届いてしまうので、画面で利用者に知らせる。
     */
    static List<String> argv(Context c, Arch arch, boolean network, int memoryMb) {
        String lib = c.getApplicationInfo().nativeLibraryDir;
        boolean x86 = arch == Arch.X86_64;
        List<String> a = new ArrayList<>(Arrays.asList(
                new File(lib, arch.tool).getAbsolutePath(),
                "-M", x86 ? "pc" : "virt"));
        // 端末が仮想化の機能 (KVM) をアプリに開放していれば使う (ほぼ無い。使えるのは端末と同じ ARM64 だけ)。
        // 無ければ CPU を真似て動かす
        if (!x86 && new File("/dev/kvm").canWrite()) {
            a.addAll(Arrays.asList("-accel", "kvm", "-cpu", "host"));
        } else {
            // x86_64 は、ARM の CPU の上では複数の CPU を並列に真似られない (メモリの読み書きの順序の
            // 決まりが x86 の方が厳しく、ARM の上で並列に動かすと守れないため)。QEMU の既定の方式にする
            a.addAll(Arrays.asList("-accel", x86 ? "tcg" : "tcg,thread=multi",
                    "-cpu", x86 ? "max" : "max,pauth-impdef=on"));
        }
        a.addAll(Arrays.asList(
                "-m", String.valueOf(memoryMb),
                "-smp", String.valueOf(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2))),
                "-nographic", "-no-reboot",
                // 付属ファイル (BIOS など) の置き場所。x86_64 は起動に BIOS が要るので同梱のものを使う。
                // ARM64 はカーネルを直接起動して何も読まないので、空の場所を指す
                "-L", x86 ? firmwareDir(c).getAbsolutePath() : dir(c, arch).getAbsolutePath(),
                "-kernel", kernel(c, arch).getAbsolutePath(),
                "-initrd", initrd(c, arch).getAbsolutePath(),
                "-append", "console=" + (x86 ? "ttyS0" : "ttyAMA0")
                        + " modules=loop,squashfs,sd-mod,virtio_blk quiet",
                // Alpine 本体 (読み取り専用)
                "-drive", "if=virtio,file=" + iso(c, arch).getAbsolutePath() + ",format=raw,readonly=on",
                // 共有フォルダ: 中からは読み取り専用の FAT のディスクに見える
                "-drive", "if=virtio,file=fat:ro:" + shareDir(c).getAbsolutePath() + ",format=raw",
                // ディスクへの書き込みは捨てる (電源を切ると元どおり)
                "-snapshot"));
        if (network) {
            // NAT のネットワーク。端末から仮想マシンの中へ入る口 (hostfwd) は作らない
            a.addAll(Arrays.asList("-netdev", "user,id=n0",
                    "-device", "virtio-net-pci,netdev=n0,romfile="));
        } else {
            a.addAll(Arrays.asList("-nic", "none"));
        }
        return a;
    }

    /** x86_64 の仮想マシンが使う BIOS などの置き場所 (同梱のライブラリと一緒に展開される) */
    private static File firmwareDir(Context c) {
        return new File(new File(c.getFilesDir(), "qemu"), "arm64-v8a/fw");
    }

    /** 起動するときの環境変数。QEMU が同梱のライブラリと一時ファイルの置き場所を見つけられるように */
    static String[] env(Context c) {
        return new String[]{
                "LD_LIBRARY_PATH=" + new File(new File(c.getFilesDir(), "qemu"), "arm64-v8a/lib")
                        .getAbsolutePath(),
                "TMPDIR=" + c.getCacheDir().getAbsolutePath(),
        };
    }
}
