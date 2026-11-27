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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * ARM64 の仮想マシンで動かせる、公式の ISO のカタログ (Linux・BSD)。アプリが公式の場所から取ってきて、
 * 下の表の大きさと SHA-256 で照合し、合わないものは使わない。ISO はどれも大きいので APK には入れない。
 *
 * 表の SHA-256 は、2026-11-26 に各ディストリビューションの公式の照合の値の一覧から写したもの。
 * 一覧の署名は、それぞれの公式の鍵で正しいことを確かめた (鍵の指紋):
 *   Debian      Debian CD signing key                       DF9B 9C49 EAA9 2984 3258  9D76 DA87 E80D 6294 BE9B
 *   Ubuntu      Ubuntu CD Image Automatic Signing Key (2012) 8439 38DF 228D 22F7 B374  2BC0 D94A A3F0 EFE2 1092
 *   Fedora      Fedora (45)                                  4F50 A611 4CD5 C697 6A7F  1179 655A 4B02 F577 861E
 *   Kali        Kali Linux Archive Automatic Signing Key (2025) 827C 8569 F251 8CC6 77FE  CA1A ED65 462E C8D5 E4C5
 *   AlmaLinux   AlmaLinux OS 10                              EE6D B7B9 8F5B F5ED D9DA  0DE5 DEE5 C11C C2A1 E572
 * Alpine は公式の latest-releases.yaml (HTTPS) の値、FreeBSD は公式の CHECKSUM.SHA256 (HTTPS) の値
 * (FreeBSD の一覧そのものには署名が付いていない。署名は発表のメールの側にある)。
 * openSUSE は、今の版の照合の値が SHA-512 だけだったので入れていない (照合は SHA-256 にそろえている)。
 *
 * 場所は版を固定したもの。古くなって公式の保管場所へ移ったときのために、移り先も順に試す。
 */
final class LinuxIsos {

    static final class Item {
        final String id, name, desc, file, sha256, verify;
        final long size;
        final int memoryMb;
        final boolean needsNetwork;
        final String[] urls;

        Item(String id, String name, String desc, String file, long size, String sha256, int memoryMb,
             boolean needsNetwork, String verify, String... urls) {
            this.id = id;
            this.name = name;
            this.desc = desc;
            this.file = file;
            this.size = size;
            this.sha256 = sha256;
            this.memoryMb = memoryMb;
            this.needsNetwork = needsNetwork;
            this.verify = verify;
            this.urls = urls;
        }
    }

    static final Item[] ITEMS = {
            new Item("alpine-virt", "Alpine Linux 3.24.4 (virt・文字だけ)",
                    "とても軽い。root と入力してログイン。setup-alpine でディスクに入れられる",
                    "alpine-virt-3.24.4-aarch64.iso", 93464576L,
                    "cf88d8ba67389d20d9ed307366b52e487c15d7d3ee3233b62b95f709daa1d430", 1024, false,
                    "公式の latest-releases.yaml の SHA-256",
                    "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-virt-3.24.4-aarch64.iso"),
            new Item("alpine-standard", "Alpine Linux 3.24.4 (standard・文字だけ)",
                    "virt よりパッケージが多い版。ネットワーク無しでも入れられる",
                    "alpine-standard-3.24.4-aarch64.iso", 394668032L,
                    "c0e2e9a8cb2f4848c5dc56ba26f58fbe4fdabdd35780b25a6c36f217ef67359f", 1024, false,
                    "公式の latest-releases.yaml の SHA-256",
                    "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-standard-3.24.4-aarch64.iso"),
            new Item("debian-netinst", "Debian 13.8.0 (netinst)",
                    "安定した定番。インストールの途中でネットから取るので「ネットワークを付ける」が要る。デスクトップも選べる",
                    "debian-13.8.0-arm64-netinst.iso", 737456128L,
                    "f4e05f67b5c74e3ff7b24c2a8f889960f9eb31fe0c1ac8513d27d5656958b6b3", 2048, true,
                    "Debian CD signing key で署名された SHA256SUMS",
                    "https://cdimage.debian.org/debian-cd/13.8.0/arm64/iso-cd/debian-13.8.0-arm64-netinst.iso",
                    "https://cdimage.debian.org/cdimage/archive/13.8.0/arm64/iso-cd/debian-13.8.0-arm64-netinst.iso"),
            new Item("ubuntu-desktop", "Ubuntu 26.04.1 LTS デスクトップ",
                    "GNOME のデスクトップ。入れずに試すこともできる。重いのでメモリ 4GB 以上がおすすめ",
                    "ubuntu-26.04.1-desktop-arm64.iso", 4154798080L,
                    "c54d196489d3c867975fb3bbb72ca52ec2e137456e305481f81096304e4d2517", 4096, false,
                    "Ubuntu CD Image Automatic Signing Key で署名された SHA256SUMS",
                    "https://cdimage.ubuntu.com/releases/26.04.1/release/ubuntu-26.04.1-desktop-arm64.iso",
                    "https://cdimage.ubuntu.com/releases/26.04/release/ubuntu-26.04.1-desktop-arm64.iso"),
            new Item("ubuntu-server", "Ubuntu 26.04.1 LTS サーバー (文字だけ)",
                    "デスクトップ無しの Ubuntu。軽め",
                    "ubuntu-26.04.1-live-server-arm64.iso", 3076861952L,
                    "af78753c94b924c85c4964c670692a853f7c80299a4064e317edad95462e3168", 2048, false,
                    "Ubuntu CD Image Automatic Signing Key で署名された SHA256SUMS",
                    "https://cdimage.ubuntu.com/releases/26.04.1/release/ubuntu-26.04.1-live-server-arm64.iso",
                    "https://cdimage.ubuntu.com/releases/26.04/release/ubuntu-26.04.1-live-server-arm64.iso"),
            new Item("fedora-workstation", "Fedora 45 Workstation (Live)",
                    "新しい GNOME のデスクトップ。入れずに試すこともできる。メモリ 4GB 以上がおすすめ",
                    "Fedora-Workstation-Live-45-1.4.aarch64.iso", 2987100160L,
                    "ad73a977709d417f5b7dda4147fc071396b9d8d2bdcca2df615aa3aab7673710", 4096, false,
                    "Fedora (45) の鍵で署名された CHECKSUM",
                    "https://dl.fedoraproject.org/pub/fedora/linux/releases/45/Workstation/aarch64/iso/"
                            + "Fedora-Workstation-Live-45-1.4.aarch64.iso",
                    "https://archives.fedoraproject.org/pub/archive/fedora/linux/releases/45/Workstation/aarch64/iso/"
                            + "Fedora-Workstation-Live-45-1.4.aarch64.iso"),
            new Item("kali-installer", "Kali Linux 2026.3 (インストーラー)",
                    "セキュリティの調査・研究用のツールがそろったディストリビューション。デスクトップ付きで入れられる",
                    "kali-linux-2026.3-installer-arm64.iso", 4051513344L,
                    "c4a7f9bf3a0ff07c8263fe9e9b0edba0cc3d9b408f1d8f06c066df0afce23072", 4096, false,
                    "Kali Linux Archive Automatic Signing Key (2025) で署名された SHA256SUMS",
                    "https://cdimage.kali.org/kali-2026.3/kali-linux-2026.3-installer-arm64.iso",
                    "https://old.kali.org/kali-images/kali-2026.3/kali-linux-2026.3-installer-arm64.iso"),
            new Item("kali-netinst", "Kali Linux 2026.3 (netinst)",
                    "小さい版。インストールの途中でネットから取るので「ネットワークを付ける」が要る",
                    "kali-linux-2026.3-installer-netinst-arm64.iso", 603238400L,
                    "a986c7fe0f8e887a77e7ea9f868da88eae04e7ac780ae75e6b702f243b63bf8d", 2048, true,
                    "Kali Linux Archive Automatic Signing Key (2025) で署名された SHA256SUMS",
                    "https://cdimage.kali.org/kali-2026.3/kali-linux-2026.3-installer-netinst-arm64.iso",
                    "https://old.kali.org/kali-images/kali-2026.3/kali-linux-2026.3-installer-netinst-arm64.iso"),
            new Item("almalinux-minimal", "AlmaLinux 10.3 (minimal)",
                    "Red Hat Enterprise Linux と同じ系統。サーバー向け",
                    "AlmaLinux-10.3-aarch64-minimal.iso", 1618739200L,
                    "fa25507cac880a89906aceb1fd7c6449663f725437431d074a4716a97c0dd403", 2048, false,
                    "AlmaLinux OS 10 の鍵で署名された CHECKSUM",
                    "https://repo.almalinux.org/almalinux/10.3/isos/aarch64/AlmaLinux-10.3-aarch64-minimal.iso",
                    "https://vault.almalinux.org/10.3/isos/aarch64/AlmaLinux-10.3-aarch64-minimal.iso"),
            new Item("freebsd", "FreeBSD 15.1 (Linux ではない BSD)",
                    "UNIX の流れをくむ BSD。文字だけのインストーラー",
                    "FreeBSD-15.1-RELEASE-arm64-aarch64-disc1.iso", 1196779520L,
                    "e17805c6e5a1323d4a31919053b7b2b25876344f2fada3762a4d8009770f1d7f", 2048, false,
                    "公式の CHECKSUM.SHA256 の値",
                    "https://download.freebsd.org/releases/arm64/aarch64/ISO-IMAGES/15.1/"
                            + "FreeBSD-15.1-RELEASE-arm64-aarch64-disc1.iso",
                    "https://ftp-archive.freebsd.org/pub/FreeBSD-Archive/old-releases/arm64/aarch64/ISO-IMAGES/15.1/"
                            + "FreeBSD-15.1-RELEASE-arm64-aarch64-disc1.iso"),
    };

    private LinuxIsos() {
    }

    static Item find(String id) {
        for (Item i : ITEMS) if (i.id.equals(id)) return i;
        return null;
    }

    /** ダウンロードした ISO の置き場所 (すべてのマシンで共用) */
    static File dir(Context c) {
        return new File(WinVm.base(c), "isos");
    }

    static File file(Context c, Item item) {
        return new File(dir(c), item.file);
    }

    static boolean ready(Context c, Item item) {
        return file(c, item).length() == item.size;
    }

    /**
     * 取ってきて照合する。途中で切れたら、次は続きから取る (.part を残す)。場所を順に試し、
     * 最後に大きさと SHA-256 が表と合えば置く。合わなければ消して IOException
     */
    static File download(Context c, Item item, Vm.Progress progress) throws IOException {
        File d = dir(c);
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("保存先を作れません");
        File out = file(c, item);
        if (ready(c, item)) return out;
        File part = new File(d, item.file + ".part");
        long have = part.isFile() ? part.length() : 0;
        if (have > item.size) {
            part.delete();
            have = 0;
        }
        long free = d.getUsableSpace();
        if (free > 0 && free < item.size - have + 512L * 1024 * 1024) {
            throw new IOException("空き容量が足りません (" + ((item.size - have) / 1024 / 1024 + 512) + "MB ほど必要です)");
        }
        IOException last = null;
        for (String url : item.urls) {
            try {
                fetch(url, part, item.size, progress);
                last = null;
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (last != null) throw last;
        progress.update("照合しています (SHA-256)…", 100);
        String got = sha256(part);
        if (part.length() != item.size || !got.equals(item.sha256)) {
            part.delete();
            throw new IOException("ダウンロードした ISO が壊れているか、すり替えられています (SHA-256 が一致しません)");
        }
        out.delete();
        if (!part.renameTo(out)) throw new IOException("保存できません");
        return out;
    }

    /** part の続きから取る (相手が続きに対応していなければ初めから) */
    private static void fetch(String url, File part, long size, Vm.Progress progress) throws IOException {
        long have = part.isFile() ? part.length() : 0;
        if (have == size) return;
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(20_000);
        con.setReadTimeout(60_000);
        con.setInstanceFollowRedirects(true);
        if (have > 0) con.setRequestProperty("Range", "bytes=" + have + "-");
        int code = con.getResponseCode();
        boolean append = code == 206 && have > 0;
        if (code != 200 && code != 206) {
            con.disconnect();
            throw new IOException("ダウンロードできませんでした (HTTP " + code + ")");
        }
        if (!append) have = 0;
        long got = have;
        int lastPct = -1;
        try (InputStream in = con.getInputStream(); OutputStream os = new FileOutputStream(part, append)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                got += n;
                if (got > size) throw new IOException("大きさが表と違います");
                int pct = (int) (got * 100 / size);
                if (pct != lastPct) {
                    lastPct = pct;
                    progress.update(null, pct);
                }
            }
        } finally {
            con.disconnect();
        }
        if (got != size) throw new IOException("途中で切れました (" + (got / 1024 / 1024) + "MB まで。もう一度押すと続きから取ります)");
    }

    private static String sha256(File f) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e.getMessage());
        }
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest()) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    /** ダウンロードした ISO (途中のものも) の一覧 */
    static List<File> downloaded(Context c) {
        List<File> out = new ArrayList<>();
        File[] fs = dir(c).listFiles();
        if (fs != null) for (File f : fs) if (f.isFile()) out.add(f);
        return out;
    }
}
