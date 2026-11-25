package site.springcat.bionicrunner;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Windows 11 (ARM64 版) を動かす仮想マシン (試験中)。
 *
 * 中身は今の仮想マシンと同じ QEMU (qemu-system-aarch64) で、CPU の仮想化の機能 (KVM) を使う。
 * Pixel などの Android では /dev/kvm はアプリの権限では SELinux に拒否され、root なら使えるので、
 * QEMU は root で動かす (KVM の診断で、root で使えると確かめてから)。
 *
 * 部品と取ってくる場所 (すべて SHA-256 で照合し、合わないものは使わない):
 *   - UEFI (起動の仕組み): Debian 13 の qemu-efi-aarch64 (AAVMF)。Debian の鍵で署名された一覧
 *     (InRelease → main/binary-all/Packages.xz → パッケージ) から SHA-256 をたどって、下の表に写した。
 *     2026-11-25 に、InRelease の署名が Debian Archive Automatic Signing Key (12/bookworm)
 *     (副鍵 4CB5 0190 207B 4758 A3F7  3A79 6ED0 E7B8 2643 E131) で正しいことを確かめた。
 *     Packages.xz の SHA-256 は bb4c324e…f20526 (InRelease に載っている値と一致)。
 *   - ドライバ: virtio-win の ISO (Fedora のプロジェクトの公式の配布 fedorapeople.org)。配布元には
 *     SHA-256 の一覧が無い (RPM の MD5 だけ) ので、公式の RPM (MD5 が配布元の CHECKSUM と一致) の中に
 *     書かれている ISO の SHA-256 と、直接取ってきた ISO の SHA-256 が一致することを確かめて、表に写した
 *     (RPM には署名が付いていないので、確かめられるのは「公式の場所の 2 つの配布物が同じ」ところまで)。
 *   - Windows 11 の ISO: 利用者が Microsoft の公式サイトから ARM64 版を自分でダウンロードして選ぶ。
 *     アプリには入れないし、代わりに取ってくることもしない。
 *
 * 保護モードの KVM (pKVM) の制限への対策は libbrkvmshim.so の「フラッシュの形」(BRKVM_SHIM_FLASH=1) を参照。
 * 画面は QEMU の VNC (127.0.0.1 だけで待ち受け、毎回作る 8 文字のパスワード付き) をアプリの VncView で映す。
 */
final class WinVm {

    /** UEFI: 名前・版・配布元での場所・SHA-256・大きさ (バイト) */
    static final String[] EFI_PACKAGE = {"qemu-efi-aarch64", "2025.02-8+deb13u1",
            "pool/main/e/edk2/qemu-efi-aarch64_2025.02-8+deb13u1_all.deb",
            "a00b2411a79c8aeafd95a7c868ac3cd1aab592f1af9fff965f02d81a625276ed", "5122856"};
    private static final String DEBIAN = "https://deb.debian.org/debian/";
    /** virtio-win: 版・場所・SHA-256・大きさ */
    static final String[] VIRTIO = {"0.1.302",
            "https://fedorapeople.org/groups/virt/virtio-win/direct-downloads/archive-virtio/"
                    + "virtio-win-0.1.302-1/virtio-win-0.1.302.iso",
            "303f7ae40dad495d6ae474fdc571df58958a4dbc5c37a522d80f9a203867949d", "877373440"};

    /** 仮想ディスクの大きさ (中身の分だけ増える qcow2) */
    static final long DISK_BYTES = 64L * 1024 * 1024 * 1024;
    /** VNC の番号 (5900 + この数)。使われていれば次を探す */
    private static final int VNC_BASE = 37;

    interface Log {
        void line(String s);
    }

    /** 設定 (files/winvm/config.txt に保存) */
    static final class Config {
        /** Windows 11 の ISO の場所 (端末の中の場所。空なら付けない) */
        String iso = "";
        boolean attachIso = true;
        boolean attachTools = true;
        int memoryMb = 4096;
        boolean network;
        /** 固定するコア・仮想 CPU の数・性能カウンタを切るか */
        String cpus = "4,5,6,7";
        int smp = 2;
        boolean pmuOff;
        /** 画面を virtio-gpu にする (Windows に virtio-win の viogpudo を入れたあと。大きい画面にできる) */
        boolean virtioGpu;
        /** キーボードの配列 (VNC がキーを PC のキーの番号に直すときの対応表): ja (日本語) か en-us (英語) */
        String keyboard = "ja";

        String encode() {
            return "iso=" + iso.replace('\n', ' ') + "\nattachIso=" + attachIso + "\nattachTools=" + attachTools
                    + "\nmemory=" + memoryMb + "\nnetwork=" + network + "\ncpus=" + cpus + "\nsmp=" + smp
                    + "\npmuoff=" + pmuOff + "\nvirtioGpu=" + virtioGpu + "\nkeyboard=" + keyboard + "\n";
        }

        static Config decode(String s) {
            Config c = new Config();
            for (String line : s.split("\n")) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String k = line.substring(0, eq), v = line.substring(eq + 1).trim();
                try {
                    switch (k) {
                        case "iso": c.iso = v; break;
                        case "attachIso": c.attachIso = v.equals("true"); break;
                        case "attachTools": c.attachTools = v.equals("true"); break;
                        case "memory": c.memoryMb = Math.max(1024, Math.min(16384, Integer.parseInt(v))); break;
                        case "network": c.network = v.equals("true"); break;
                        case "cpus": c.cpus = v.matches("[0-9,\\-]{1,64}") ? v : c.cpus; break;
                        case "smp": c.smp = Math.max(1, Math.min(8, Integer.parseInt(v))); break;
                        case "pmuoff": c.pmuOff = v.equals("true"); break;
                        case "virtioGpu": c.virtioGpu = v.equals("true"); break;
                        case "keyboard": c.keyboard = v.equals("en-us") ? "en-us" : "ja"; break;
                        default: break;
                    }
                } catch (NumberFormatException ignored) {
                    // 読めない値は初めの値のまま
                }
            }
            return c;
        }
    }

    private WinVm() {
    }

    // ------------------------------------------------------------------ 場所

    static File dir(Context c) {
        return new File(c.getFilesDir(), "winvm");
    }

    static File efiCode(Context c) {
        return new File(dir(c), "AAVMF_CODE.fd");
    }

    /** UEFI の設定の保存場所のひな形 (変えない) */
    private static File efiVarsTemplate(Context c) {
        return new File(dir(c), "AAVMF_VARS.fd");
    }

    /** UEFI の設定の保存場所 (この仮想マシン用。起動の順番などが書き込まれる) */
    static File efiVars(Context c) {
        return new File(dir(c), "vars.fd");
    }

    static File virtioIso(Context c) {
        return new File(dir(c), "virtio-win.iso");
    }

    static File disk(Context c) {
        return new File(dir(c), "disk.qcow2");
    }

    static File toolsDir(Context c) {
        return new File(dir(c), "tools");
    }

    /**
     * Windows へ送るファイルを置くフォルダ。Windows からは読み取り専用の USB メモリ (BR-SHARE) に見える。
     * エクスプローラーの「アプリの作業領域」→ winvm-share からも出し入れできる
     */
    static File shareDir(Context c) {
        return new File(new File(c.getFilesDir(), "home"), "winvm-share");
    }

    /** 送るファイルの合計の上限 (USB メモリは FAT16 の形で作られるので、500MB ほどまでしか入らない) */
    static final long SHARE_MAX = 480L * 1024 * 1024;

    static long shareSize(Context c) {
        return Archive.treeSize(shareDir(c));
    }

    /**
     * ファイルの選択の画面で選んだものを、送るフォルダへ写す。同じ名前があれば上書きする。
     * 合計が上限を超えるときは写さずに IOException
     */
    static String copyIn(Context c, android.net.Uri uri) throws IOException {
        String name = null;
        long size = -1;
        try (android.database.Cursor cur = c.getContentResolver().query(uri, null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int n = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                int z = cur.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (n >= 0) name = cur.getString(n);
                if (z >= 0 && !cur.isNull(z)) size = cur.getLong(z);
            }
        }
        if (name == null || name.isEmpty()) name = "file";
        // Windows のファイル名に使えない文字と、場所を表す文字を置き換える
        name = name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_");
        if (name.equals(".") || name.equals("..")) name = "file";
        File dir = shareDir(c);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("保存先を作れません");
        File out = new File(dir, name);
        long now = shareSize(c) - (out.isFile() ? out.length() : 0);
        if (size > 0 && now + size > SHARE_MAX) {
            throw new IOException(name + " を入れると、合計が上限 (" + (SHARE_MAX / 1024 / 1024) + "MB) を超えます");
        }
        File part = new File(dir, "." + name + ".part");
        long total = 0;
        try (InputStream in = c.getContentResolver().openInputStream(uri);
             FileOutputStream os = new FileOutputStream(part)) {
            if (in == null) throw new IOException("読めません: " + name);
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) {
                total += r;
                if (now + total > SHARE_MAX) {
                    throw new IOException(name + " を入れると、合計が上限 (" + (SHARE_MAX / 1024 / 1024) + "MB) を超えます");
                }
                os.write(buf, 0, r);
            }
        } catch (IOException e) {
            part.delete();
            throw e;
        }
        out.delete();
        if (!part.renameTo(out)) {
            part.delete();
            throw new IOException("保存できません: " + name);
        }
        return name;
    }

    /** 選んだファイル (1 つか複数) を写して、動いていれば Windows に見せ直す。結果の文を返す */
    static String sendFiles(Context c, android.content.Intent data) {
        List<android.net.Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        StringBuilder ok = new StringBuilder(), ng = new StringBuilder();
        for (android.net.Uri u : uris) {
            try {
                String n = copyIn(c, u);
                ok.append(ok.length() > 0 ? "、" : "").append(n);
            } catch (IOException e) {
                ng.append("\n✖ ").append(e.getMessage());
            }
        }
        String msg = ok.length() > 0 ? "送るフォルダに入れました: " + ok : "入れられたファイルはありません";
        if (ok.length() > 0) {
            if (runningPort(c) > 0) {
                String r = refreshShare(c);
                msg += "\nWindows のエクスプローラーの USB ドライブ (QEMU VVFAT) に見えます。"
                        + "読み取り専用なので、使う前にデスクトップなどへコピーしてください"
                        + (r.isEmpty() ? "" : " (" + r + ")");
            } else {
                msg += "\n次に起動したとき、Windows の USB ドライブ (BR-SHARE) に見えます";
            }
        }
        return msg + ng;
    }

    /** 送るフォルダを空にする */
    static void clearShare(Context c) {
        File[] files = shareDir(c).listFiles();
        if (files != null) for (File f : files) Archive.deleteTree(f);
    }

    /**
     * 動いている Windows に、送るフォルダの今の中身を見せ直す (USB メモリを挿したまま、中身を入れ替える。
     * 抜いて挿し直す形は QEMU が固まることがあったので使わない)。動いていなければ何もしない
     */
    static String refreshShare(Context c) {
        if (runningPort(c) <= 0) return "";
        return monitorCommand(c, "change share fat:ro:" + shareDir(c).getAbsolutePath() + " raw");
    }

    private static File configFile(Context c) {
        return new File(dir(c), "config.txt");
    }

    private static File pidFile(Context c) {
        return new File(dir(c), "qemu.pid");
    }

    static File monitor(Context c) {
        return new File(dir(c), "monitor.sock");
    }

    private static File secretFile(Context c) {
        return new File(dir(c), "vnc.secret");
    }

    static File logFile(Context c) {
        return new File(dir(c), "qemu.log");
    }

    private static File portFile(Context c) {
        return new File(dir(c), "vnc.port");
    }

    static boolean efiReady(Context c) {
        return efiCode(c).length() == 64L * 1024 * 1024 && efiVarsTemplate(c).length() == 64L * 1024 * 1024;
    }

    static boolean virtioReady(Context c) {
        return virtioIso(c).length() == Long.parseLong(VIRTIO[3]);
    }

    static Config config(Context c) {
        try (InputStream in = new FileInputStream(configFile(c))) {
            return Config.decode(new String(Icons.readAll(in), StandardCharsets.UTF_8));
        } catch (IOException e) {
            Config cfg = new Config();
            KvmCheck.Plan p = KvmCheck.savedAny(c);
            if (p != null && p.cpus != null) {
                cfg.cpus = p.cpus;
                cfg.smp = p.smp;
                cfg.pmuOff = p.pmuOff;
            }
            return cfg;
        }
    }

    static void save(Context c, Config cfg) throws IOException {
        dir(c).mkdirs();
        Glibc.writeText(configFile(c), cfg.encode());
    }

    // ------------------------------------------------------------------ 準備

    /** UEFI を Debian から取ってきて照合し、2 つのファイル (本体・設定のひな形) を取り出す */
    static void prepareEfi(Context c, Log log) throws IOException {
        File d = dir(c);
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("保存先を作れません");
        File deb = new File(d, "efi.deb");
        File work = new File(d, "efi.new");
        Archive.deleteTree(work);
        if (!work.mkdirs()) throw new IOException("保存先を作れません");
        try {
            long size = Long.parseLong(EFI_PACKAGE[4]);
            log.line(EFI_PACKAGE[0] + " " + EFI_PACKAGE[1] + " (Debian) をダウンロードしています ("
                    + (size / 1024 / 1024) + "MB)…");
            Vm.download(DEBIAN + EFI_PACKAGE[2], deb, size, EFI_PACKAGE[3], (m, p) -> {
            });
            log.line("SHA-256 が一致しました: " + EFI_PACKAGE[3]);
            Wine.extractDeb(c, deb, work);
            File code = new File(work, "usr/share/AAVMF/AAVMF_CODE.no-secboot.fd");
            File vars = new File(work, "usr/share/AAVMF/AAVMF_VARS.fd");
            if (code.length() != 64L * 1024 * 1024 || vars.length() != 64L * 1024 * 1024) {
                throw new IOException("パッケージの中に UEFI のファイルが見つかりません");
            }
            efiCode(c).delete();
            efiVarsTemplate(c).delete();
            if (!code.renameTo(efiCode(c)) || !vars.renameTo(efiVarsTemplate(c))) throw new IOException("保存できません");
            log.line("UEFI を用意しました (セキュアブート無しの版。Windows の要件の確認は外して入れます)");
        } finally {
            deb.delete();
            Archive.deleteTree(work);
        }
    }

    static void prepareVirtio(Context c, Log log, Vm.Progress progress) throws IOException {
        File d = dir(c);
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("保存先を作れません");
        long size = Long.parseLong(VIRTIO[3]);
        long free = d.getUsableSpace();
        if (free > 0 && free < size + 200L * 1024 * 1024) {
            throw new IOException("空き容量が足りません (" + (size / 1024 / 1024 + 200) + "MB ほど必要です)");
        }
        File part = new File(d, "virtio-win.iso.part");
        log.line("virtio-win " + VIRTIO[0] + " (Windows 用のドライバ、" + (size / 1024 / 1024) + "MB) をダウンロードしています…");
        Vm.download(VIRTIO[1], part, size, VIRTIO[2], progress);
        virtioIso(c).delete();
        if (!part.renameTo(virtioIso(c))) throw new IOException("保存できません");
        log.line("SHA-256 が一致しました: " + VIRTIO[2]);
    }

    /** 仮想ディスクを作る前に要る空き (Windows を入れると 20〜30GB ほどになる) */
    static final long DISK_MIN_FREE = 32L * 1024 * 1024 * 1024;

    /**
     * 64GB の qcow2 を作る。中身はまだ無いので、ファイルは 256KB だけ。Windows を入れると、
     * 書いた分だけ大きくなる (最大 64GB と少し)
     */
    static void createDisk(Context c) throws IOException {
        dir(c).mkdirs();
        File f = disk(c);
        if (f.exists()) throw new IOException("仮想ディスクはもうあります");
        writeQcow2(f, DISK_BYTES);
        // UEFI の設定の保存場所も、まっさらなものから始める
        copy(efiVarsTemplate(c), efiVars(c));
    }

    /**
     * 空の qcow2 (第 3 版) を書く。クラスタ (割り当ての単位) は 64KB。
     * 0: 見出し、1: L1 表、2: 参照数の表、3: 参照数のブロック (0〜3 番のクラスタが使用中)
     */
    static void writeQcow2(File f, long virtualSize) throws IOException {
        final int cluster = 65536;
        long l2Covers = (long) cluster * (cluster / 8);
        int l1Size = (int) ((virtualSize + l2Covers - 1) / l2Covers);
        if (l1Size * 8 > cluster) throw new IOException("大きすぎます");
        byte[] img = new byte[cluster * 4];
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(img);
        b.putInt(0, 0x514649fb); // QFI\xfb
        b.putInt(4, 3); // 版
        b.putLong(8, 0); // 元のイメージ (無し)
        b.putInt(16, 0);
        b.putInt(20, 16); // クラスタは 2^16 バイト
        b.putLong(24, virtualSize);
        b.putInt(32, 0); // 暗号化なし
        b.putInt(36, l1Size);
        b.putLong(40, cluster); // L1 表の場所
        b.putLong(48, 2L * cluster); // 参照数の表の場所
        b.putInt(56, 1); // 参照数の表のクラスタの数
        b.putInt(60, 0); // スナップショット無し
        b.putLong(64, 0);
        b.putLong(72, 0); // 互換性の印 (なし)
        b.putLong(80, 0);
        b.putLong(88, 0);
        b.putInt(96, 4); // 参照数は 16 ビット
        b.putInt(100, 104); // 見出しの長さ
        // 104〜111: 拡張の終わりの印 (0) — 配列は 0 で埋まっている
        b.putLong(2 * cluster, 3L * cluster); // 参照数の表 → ブロック
        for (int i = 0; i < 4; i++) b.putShort(3 * cluster + i * 2, (short) 1);
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(img);
            os.getFD().sync();
        }
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); FileOutputStream os = new FileOutputStream(to)) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    /** 仮想ディスクと UEFI の設定を消す (Windows ごと消える) */
    static void removeDisk(Context c) {
        disk(c).delete();
        efiVars(c).delete();
    }

    /** ダウンロードした部品も含めて全部消す */
    static void removeAll(Context c) {
        Archive.deleteTree(dir(c));
    }

    // ------------------------------------------------------------------ 確かめること

    /** 端末のメモリ (全体・今の空き) と、選んだ量への注意 (問題なければ null) */
    static String memoryWarning(Context c, int memoryMb) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return null;
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long total = mi.totalMem / 1024 / 1024, avail = mi.availMem / 1024 / 1024;
        if (memoryMb + 2500 > total) {
            return "端末のメモリ (" + total + "MB) に対して多すぎます。Android やほかのアプリが止められることがあります。"
                    + "少ない量を選んでください";
        }
        if (memoryMb > avail) {
            return "今の空きメモリ (" + avail + "MB) より多い量です。ほかのアプリを閉じてから起動してください"
                    + " (足りないと、仮想マシンかほかのアプリが止められることがあります)";
        }
        return null;
    }

    static String memoryInfo(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return "";
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return "端末のメモリ: 全体 " + (mi.totalMem / 1024 / 1024) + "MB・今の空き " + (mi.availMem / 1024 / 1024) + "MB";
    }

    /** ダウンロードのフォルダにある ISO (virtio-win は除く) */
    static List<File> isoCandidates() {
        List<File> out = new ArrayList<>();
        File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File[] files = dl == null ? null : dl.listFiles();
        if (files == null) return out;
        for (File f : files) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (f.isFile() && n.endsWith(".iso") && !n.startsWith("virtio-win")) out.add(f);
        }
        out.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    /** 名前から、ARM64 版でなさそうなら注意を返す (分からなければ null) */
    static String isoNameWarning(String path) {
        String n = new File(path).getName().toLowerCase(Locale.ROOT);
        if (n.contains("arm64") || n.contains("aarch64")) return null;
        if (n.contains("x64") || n.contains("x86") || n.contains("amd64")) {
            return "名前に x64 / x86 とあります。この仮想マシンで動くのは ARM64 版だけです";
        }
        return "名前に ARM64 とありません。ARM64 版か確かめてください (x64 版は動きません)";
    }

    /**
     * root の QEMU から読む場所。/storage/emulated/0/… (アプリから見える場所) は、root の側では
     * 見えない (場所の見せ方が違う) ことがあるので、実際の場所 /data/media/0/… に直す
     */
    static String rootPath(String path) {
        if (path.startsWith("/storage/emulated/0/")) return "/data/media/0/" + path.substring(20);
        if (path.startsWith("/sdcard/")) return "/data/media/0/" + path.substring(8);
        return path;
    }

    // ------------------------------------------------------------------ 要件の確認を外す設定ファイル

    /** Windows の要件の確認 (TPM・セキュアブートなど) を外す設定ファイルと、手で使う同じ中身のコマンド */
    static void writeTools(Context c) throws IOException {
        File t = toolsDir(c);
        Archive.deleteTree(t);
        if (!t.mkdirs()) throw new IOException("保存先を作れません");
        String[] keys = {"BypassTPMCheck", "BypassSecureBootCheck", "BypassRAMCheck", "BypassCPUCheck",
                "BypassStorageCheck"};
        StringBuilder pe = new StringBuilder();
        int order = 1;
        for (String k : keys) {
            pe.append("        <RunSynchronousCommand wcm:action=\"add\">\r\n")
                    .append("          <Order>").append(order++).append("</Order>\r\n")
                    .append("          <Path>reg add HKLM\\SYSTEM\\Setup\\LabConfig /v ").append(k)
                    .append(" /t REG_DWORD /d 1 /f</Path>\r\n")
                    .append("        </RunSynchronousCommand>\r\n");
        }
        String ns = "processorArchitecture=\"arm64\" publicKeyToken=\"31bf3856ad364e35\" language=\"neutral\" "
                + "versionScope=\"nonSxS\" xmlns:wcm=\"http://schemas.microsoft.com/WMIConfig/2002/State\" "
                + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"";
        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n"
                + "<!-- Bionic Runner: Windows 11 の要件の確認 (TPM・セキュアブートなど) を外すだけの設定。\r\n"
                + "     ほかは何も自動にしない (言語・ディスク・アカウントなどはいつも通り画面で選ぶ) -->\r\n"
                + "<unattend xmlns=\"urn:schemas-microsoft-com:unattend\">\r\n"
                + "  <settings pass=\"windowsPE\">\r\n"
                + "    <component name=\"Microsoft-Windows-Setup\" " + ns + ">\r\n"
                + "      <RunSynchronous>\r\n" + pe
                + "      </RunSynchronous>\r\n"
                + "    </component>\r\n"
                + "  </settings>\r\n"
                + "  <settings pass=\"specialize\">\r\n"
                + "    <component name=\"Microsoft-Windows-Deployment\" " + ns + ">\r\n"
                + "      <RunSynchronous>\r\n"
                + "        <RunSynchronousCommand wcm:action=\"add\">\r\n"
                + "          <Order>1</Order>\r\n"
                + "          <Path>reg add HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\OOBE /v BypassNRO /t REG_DWORD /d 1 /f</Path>\r\n"
                + "        </RunSynchronousCommand>\r\n"
                + "      </RunSynchronous>\r\n"
                + "    </component>\r\n"
                + "  </settings>\r\n"
                + "</unattend>\r\n";
        Glibc.writeText(new File(t, "autounattend.xml"), xml);
        StringBuilder cmd = new StringBuilder("@echo off\r\nrem Bionic Runner: Windows 11 の要件の確認を外す (手で使うとき)\r\n");
        for (String k : keys) {
            cmd.append("reg add HKLM\\SYSTEM\\Setup\\LabConfig /v ").append(k).append(" /t REG_DWORD /d 1 /f\r\n");
        }
        cmd.append("echo 済みました。インストールの画面に戻って、「戻る」を押してからもう一度進めてください\r\n");
        // コマンドプロンプトは日本語の文字を Shift_JIS で読むので、説明は英数字だけにした版も付ける
        Glibc.writeText(new File(t, "bypass.cmd"), cmd.toString().replaceAll("rem [^\r]*\r\n", "")
                .replaceAll("echo [^\r]*\r\n", "echo Done. Go back and try again.\r\n"));
        Glibc.writeText(new File(t, "README.txt"), guideText().replace("\n", "\r\n"));
    }

    /** 要件の確認を外す手順 (画面と README.txt に出す) */
    static String guideText() {
        return "Windows 11 の要件の確認 (TPM・セキュアブートなど) を外す手順\n\n"
                + "この仮想マシンには TPM がなく、UEFI もセキュアブート無しの版なので、そのままだとインストールの途中で\n"
                + "「この PC では Windows 11 を実行できません」と出て進めません。次のどちらかで外します。\n\n"
                + "1. 自動 (おすすめ): 起動の画面で「要件の確認を外す設定ファイルを付ける」を入れたまま起動します。\n"
                + "   付けたドライブに autounattend.xml があり、Windows のインストールが始まるときに読み込まれて、\n"
                + "   LabConfig のレジストリ (BypassTPMCheck・BypassSecureBootCheck・BypassRAMCheck・BypassCPUCheck・\n"
                + "   BypassStorageCheck) を 1 にします。ほかの設定 (言語・ディスク・アカウント) はいつも通り画面で選びます。\n\n"
                + "2. 手で: 「この PC では Windows 11 を実行できません」と出たら、Shift + F10 でコマンドプロンプトを開き、\n"
                + "   for %i in (D E F G H I J) do @if exist %i:\\bypass.cmd echo %i:\n"
                + "   と打つと、bypass.cmd のあるドライブ (例 E:) が出ます。続けて E:\\bypass.cmd と打ち、\n"
                + "   閉じてから、インストールの画面で「戻る」を押してもう一度進めます。\n"
                + "   (bypass.cmd が無いときは: reg add HKLM\\SYSTEM\\Setup\\LabConfig /v BypassTPMCheck /t REG_DWORD /d 1 /f\n"
                + "    を、BypassSecureBootCheck・BypassRAMCheck・BypassCPUCheck についても打ちます)\n\n"
                + "インターネットにつながずに初期設定を進めるには:\n"
                + "   設定ファイルを付けていれば、ネットワークの画面に「インターネットに接続していません」が出ます。\n"
                + "   出ないときは、その画面で Shift + F10 を押し、start ms-cxh:localonly と打つと、\n"
                + "   ローカルのアカウントを作る画面になります (Windows の版によっては使えないことがあります)。\n\n"
                + "ドライバ (virtio-win の CD):\n"
                + "   ネットワーク (NetKVM) や画面 (viogpudo) のドライバは、もう 1 つの CD の各フォルダの w11\\ARM64 にあります。\n"
                + "   インストールのあと、デバイスマネージャーから「ドライバーの更新」でその CD を選びます。\n\n"
                + "ライセンス: Windows は試すことはできますが、使い続けるにはライセンス (プロダクトキー) が必要です。\n";
    }

    // ------------------------------------------------------------------ 起動と停止

    /** 使っていない VNC の番号を探す (5900 + 番号。127.0.0.1 で待ち受けられるか試す) */
    private static int freeVncDisplay() {
        for (int d = VNC_BASE; d < VNC_BASE + 20; d++) {
            try (java.net.ServerSocket s = new java.net.ServerSocket()) {
                s.bind(new java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), 5900 + d));
                return d;
            } catch (IOException ignored) {
                // 使われている
            }
        }
        return VNC_BASE;
    }

    /** 8 文字のパスワード (VNC の決まりで 8 文字まで) */
    private static String newPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    /** 動いている仮想マシンの VNC の番号 (5900 + 番号 = ポート)。動いていなければ -1 */
    static int runningPort(Context c) {
        int port;
        try (InputStream in = new FileInputStream(portFile(c))) {
            port = Integer.parseInt(new String(Icons.readAll(in), StandardCharsets.UTF_8).trim());
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), port), 500);
            return port;
        } catch (IOException e) {
            return -1;
        }
    }

    static String password(Context c) {
        try (InputStream in = new FileInputStream(secretFile(c))) {
            return new String(Icons.readAll(in), StandardCharsets.US_ASCII).trim();
        } catch (IOException e) {
            return null;
        }
    }

    /** QEMU のコマンド (root で動かす前のもの) */
    static List<String> argv(Context c, Config cfg, int vncDisplay) {
        String lib = c.getApplicationInfo().nativeLibraryDir;
        List<String> a = new ArrayList<>();
        if (cfg.cpus != null && !cfg.cpus.isEmpty()) {
            a.addAll(Arrays.asList(new File(lib, "libbrkvm.so").getAbsolutePath(), "pin", cfg.cpus, "--"));
        }
        a.add(new File(lib, Vm.Arch.AARCH64.tool).getAbsolutePath());
        a.addAll(Arrays.asList(
                "-M", "virt,gic-version=host",
                "-accel", "kvm",
                "-cpu", "host" + (cfg.pmuOff ? ",pmu=off" : ""),
                "-smp", String.valueOf(cfg.smp),
                "-m", String.valueOf(cfg.memoryMb),
                // 付属ファイルの場所 (VNC のキーボードの対応表 keymaps/ がある)
                "-L", Vm.firmwareDir(c).getAbsolutePath(),
                // UEFI: 本体 (読み取り専用) と、設定の保存場所
                "-drive", "if=pflash,format=raw,readonly=on,file=" + efiCode(c).getAbsolutePath(),
                "-drive", "if=pflash,format=raw,file=" + efiVars(c).getAbsolutePath(),
                // 画面: ramfb (UEFI と、インストール中の Windows がそのまま使える簡単な画面)。
                // Windows に virtio-win の viogpudo を入れたあとは、設定で virtio-gpu (大きい画面にできる) に切り替えられる
                "-device", cfg.virtioGpu ? "virtio-gpu-pci,romfile=" : "ramfb",
                // 入力: USB のキーボードと、タッチで指した場所にそのまま動くポインタ (タブレット)
                "-device", "qemu-xhci,id=xhci,p2=8,p3=8",
                "-device", "usb-kbd,bus=xhci.0",
                "-device", "usb-tablet,bus=xhci.0",
                // ディスク: NVMe (Windows に最初からドライバがある)
                "-drive", "if=none,id=disk0,format=qcow2,discard=unmap,detect-zeroes=unmap,file="
                        + disk(c).getAbsolutePath(),
                "-device", "nvme,drive=disk0,serial=BRWIN0001,bootindex=1",
                "-rtc", "base=localtime",
                "-boot", "menu=off"));
        int usb = 0;
        if (cfg.attachIso && !cfg.iso.isEmpty()) {
            a.addAll(Arrays.asList(
                    "-drive", "if=none,id=cd0,media=cdrom,readonly=on,format=raw,file=" + rootPath(cfg.iso),
                    "-device", "usb-storage,bus=xhci.0,drive=cd0,removable=on,bootindex=0"));
            usb++;
        }
        if (virtioReady(c)) {
            a.addAll(Arrays.asList(
                    "-drive", "if=none,id=cd1,media=cdrom,readonly=on,format=raw,file=" + virtioIso(c).getAbsolutePath(),
                    "-device", "usb-storage,bus=xhci.0,drive=cd1,removable=on"));
            usb++;
        }
        if (cfg.attachTools && toolsDir(c).isDirectory()) {
            // 要件の確認を外す設定ファイルのドライブ (読み取り専用の FAT)
            a.addAll(Arrays.asList(
                    "-drive", "if=none,id=tools,format=raw,readonly=on,file=fat:ro:" + toolsDir(c).getAbsolutePath(),
                    "-device", "usb-storage,bus=xhci.0,drive=tools,removable=on"));
            usb++;
        }
        // Windows へ送るファイルのドライブ (読み取り専用の USB メモリ。動いている間も中身を入れ替えられる)
        shareDir(c).mkdirs();
        a.addAll(Arrays.asList(
                "-drive", "if=none,id=share,driver=vvfat,label=BR-SHARE,readonly=on,dir=" + shareDir(c).getAbsolutePath(),
                "-device", "usb-storage,bus=xhci.0,drive=share,id=sharedev,removable=on"));
        if (cfg.network) {
            // NAT。Windows には virtio-win の NetKVM のドライバを入れる。端末から中へ入る口は作らない
            a.addAll(Arrays.asList("-netdev", "user,id=n0", "-device", "virtio-net-pci,netdev=n0,romfile="));
        } else {
            a.addAll(Arrays.asList("-nic", "none"));
        }
        a.addAll(Arrays.asList(
                "-object", "secret,id=vncpw,format=raw,file=" + secretFile(c).getAbsolutePath(),
                "-vnc", "127.0.0.1:" + vncDisplay + ",password-secret=vncpw",
                // Windows のキーボードの配列に合わせる (合っていないと記号がずれる)
                "-k", "en-us".equals(cfg.keyboard) ? "en-us" : "ja",
                "-monitor", "unix:" + monitor(c).getAbsolutePath() + ",server=on,wait=off",
                "-pidfile", pidFile(c).getAbsolutePath(),
                "-daemonize"));
        return a;
    }

    static String[] env(Context c) {
        List<String> e = new ArrayList<>(Arrays.asList(Vm.env(c, true, false)));
        e.add("BRKVM_SHIM_FLASH=1");
        return e.toArray(new String[0]);
    }

    /**
     * 仮想マシンを root で起動する。QEMU は準備ができたら裏に回る (-daemonize) ので、ここは数秒で戻る。
     * 失敗したら IOException (QEMU のエラーの文) 。成功したら VNC のポートを返す
     */
    static int start(Context c, Config cfg, Log log) throws IOException {
        if (!efiReady(c)) throw new IOException("先に UEFI を用意してください");
        if (!disk(c).isFile()) throw new IOException("先に仮想ディスクを作ってください");
        if (!efiVars(c).isFile()) copy(efiVarsTemplate(c), efiVars(c));
        if (runningPort(c) > 0) throw new IOException("もう動いています");
        if (cfg.attachTools) writeTools(c);
        // QEMU が読むライブラリと付属ファイル (キーボードの対応表など) を展開する (済んでいれば何もしない)
        Glibc.installAsset(c, "qemu", "arm64-v8a", new File(Vm.firmwareDir(c), "keymaps/en-us"));
        String pw = newPassword();
        Glibc.writeText(secretFile(c), pw);
        int display = freeVncDisplay();
        Glibc.writeText(portFile(c), String.valueOf(5900 + display));
        pidFile(c).delete();
        monitor(c).delete();
        List<String> argv = argv(c, cfg, display);
        String[] env = env(c);
        StringBuilder sb = new StringBuilder();
        // ISO が root から読めるか先に確かめる (読めなければ、分かりやすい文で止める)
        if (cfg.attachIso && !cfg.iso.isEmpty()) {
            sb.append("[ -r ").append(KvmCheck.q(rootPath(cfg.iso))).append(" ] || { echo 'Windows の ISO が読めません: ")
                    .append(rootPath(cfg.iso).replace("'", "")).append("'; exit 3; }; ");
        }
        sb.append(KvmCheck.exports(env)).append("exec");
        for (String s : argv) sb.append(' ').append(KvmCheck.q(s));
        sb.append(" > ").append(KvmCheck.q(logFile(c).getAbsolutePath())).append(" 2>&1");
        log.line("root で QEMU を起動しています (メモリ " + cfg.memoryMb + "MB・仮想 CPU " + cfg.smp + " 個・コア "
                + cfg.cpus + ")…");
        int[] code = new int[1];
        String out = KvmCheck.run(Arrays.asList(RootCheck.su(), "-c", sb.toString()), null, 120, code);
        String qlog = readLog(c);
        if (code[0] != 0) {
            throw new IOException("起動できませんでした (終了コード " + code[0] + ")\n" + out.trim() + "\n" + qlog);
        }
        if (!qlog.isEmpty()) log.line(qlog);
        if (cfg.attachIso && !cfg.iso.isEmpty() && diskUsed(c) < 2L * 1024 * 1024 * 1024) {
            pressKeysForCdBoot(c, log);
        }
        return 5900 + display;
    }

    static String readLog(Context c) {
        // QEMU は root で動くので、記録のファイルは root が作る。アプリから読めないときは root で読む
        File f = logFile(c);
        try (InputStream in = new FileInputStream(f)) {
            return new String(Icons.readAll(in), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            String out = KvmCheck.run(Arrays.asList(RootCheck.su(), "-c", "cat " + KvmCheck.q(f.getAbsolutePath())),
                    null, 20, null);
            return out.trim();
        }
    }

    /**
     * Windows の CD は、起動のときに「Press any key to boot from CD or DVD…」と出し、5 秒以内にキーが
     * 押されないと CD から起動しない (UEFI のシェルに戻る)。まだ Windows が入っていないときは、起動から
     * 25 秒の間、1 秒ごとに空白のキーを送って、それに間に合わせる (QEMU の監視の画面の sendkey。裏で動かす)
     */
    private static void pressKeysForCdBoot(Context c, Log log) {
        log.line("Windows の CD から起動するため、はじめの 25 秒だけ自動でキーを押します");
        String b = KvmCheck.q(KvmCheck.tool(c).getAbsolutePath()), m = KvmCheck.q(monitor(c).getAbsolutePath());
        String script = "i=0; while [ $i -lt 25 ]; do " + b + " hmp " + m + " 'sendkey spc' >/dev/null 2>&1; "
                + "sleep 1; i=$((i+1)); done";
        new Thread(() -> KvmCheck.run(Arrays.asList(RootCheck.su(), "-c", script), null, 60, null),
                "winvm-cdkey").start();
    }

    /** Windows に「電源ボタンが押された」と伝える (Windows がシャットダウンする) */
    static String powerdown(Context c) {
        return monitorCommand(c, "system_powerdown");
    }

    static String monitorCommand(Context c, String command) {
        String b = KvmCheck.q(KvmCheck.tool(c).getAbsolutePath());
        return KvmCheck.run(Arrays.asList(RootCheck.su(), "-c", b + " hmp " + KvmCheck.q(monitor(c).getAbsolutePath())
                + " " + KvmCheck.q(command)), null, 30, null).trim();
    }

    /** すぐに止める (保存していない内容は消える。Windows のディスクが壊れることもある) */
    static void forceStop(Context c) {
        String pf = KvmCheck.q(pidFile(c).getAbsolutePath());
        KvmCheck.run(Arrays.asList(RootCheck.su(), "-c",
                "p=$(cat " + pf + " 2>/dev/null); [ -n \"$p\" ] && kill -9 \"$p\"; rm -f " + pf), null, 30, null);
    }

    /** 使っている容量の内訳 */
    static long size(Context c) {
        return Archive.treeSize(dir(c));
    }

    /** qcow2 の実際の大きさ (ファイルの大きさ) */
    static long diskUsed(Context c) {
        return disk(c).length();
    }
}
