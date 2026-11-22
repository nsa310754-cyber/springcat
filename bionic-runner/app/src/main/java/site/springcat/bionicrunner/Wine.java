package site.springcat.bionicrunner;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Wine: Windows のプログラム (64bit の exe) を動かす。Box64 の上で、x86_64 用の Wine を動かす。
 *
 * Wine は大きい (ダウンロード約 110MB、入れたあと約 720MB) のでアプリには同梱せず、「追加機能」から
 * 入れる。取ってくるのは Debian 13 (trixie) の公式の配布物 (deb.debian.org) の、版を固定したパッケージで、
 * 1 つずつ下の表の SHA-256 で照合し、合わないものは使わない。表の SHA-256 は、Debian の
 * パッケージ一覧 (Packages。Debian の鍵で署名された Release ファイルから SHA-256 でたどれる) から写したもの。
 * 署名は 2026-11-22 に debian-archive-keyring 2025.1 の Debian 13 の鍵で正しいことを確かめた
 * (InRelease の署名 3 つがすべて正しい):
 *   Debian Archive Automatic Signing Key (13/trixie)  主鍵 04B5 4C3C DCA7 9751 B16B  C6B5 2256 29DF 75B1 88BD
 *     (署名に使われた副鍵 B8E5 F131 76D2 A7A7 5220  0280 78DB A3BC 47EF 2265)
 *   Debian Stable Release Key (13/trixie)             4158 7F7D B8C7 74BC CF13  1416 762F 67A0 B2C3 9DE4
 *   Debian Archive Automatic Signing Key (12/bookworm) 副鍵 4CB5 0190 207B 4758 A3F7  3A79 6ED0 E7B8 2643 E131
 * そこから main/binary-i386/Packages.xz (SHA-256 537ac0aa…de9bc95) → libwine_10.0~repack-6_i386.deb
 * (fef4802a…1527b) のようにたどった。
 *
 * 32bit の exe (試験中): 64bit 版の Wine は「新しい WoW64」の方式で、64bit のプロセスの中で 32bit の
 * Windows のプログラムを動かせる (32bit の Linux の部品は要らない)。必要な 32bit 用の Windows の DLL は、
 * Debian では別のパッケージ libwine:i386 に入っているので、使う人だけが「追加機能」で入れる
 * (その中の i386-windows だけを、64bit 版の Wine が探す場所に置く。中身は照合したまま変えない)。
 *
 * 場所 (files/wine/ の下):
 *   dist/     Wine 本体と、それが使う x86_64 用のライブラリ (パッケージの中身)
 *   prefix/   Windows の環境 (WINEPREFIX。C: ドライブ)。初めて動かしたときに wine.sh が作る
 *   shared/   D: ドライブ。Windows のプログラムとファイルを受け渡す場所
 *   run/      wineserver との連絡用のフォルダ
 *   wine.sh   wine コマンドの本体 (assets/wine/wine.sh から作る)
 *
 * Wine は LGPL 2.1 以降 (著作権は Wine の作者たち)。ソースは https://dl.winehq.org/wine/source/10.0/ と、
 * Debian のソースパッケージ wine 10.0~repack-6 (https://sources.debian.org/src/wine/10.0~repack-6/)。
 */
final class Wine {

    static final String VERSION = "10.0~repack-6";
    private static final String MIRROR = "https://deb.debian.org/debian/";

    /** 取ってくるパッケージ: 名前・版・配布元での場所・SHA-256・大きさ (バイト) */
    private static final String[][] PACKAGES = {
            {"libz-mingw-w64", "1.3.1+dfsg-2", "pool/main/libz/libz-mingw-w64/libz-mingw-w64_1.3.1+dfsg-2_all.deb",
                    "c4e6d6bfabe8b6d12c797736a3d762d83513b49f488d1aaf61d4526387a2dd3e", "158548"},
            {"fonts-wine", "10.0~repack-6", "pool/main/w/wine/fonts-wine_10.0~repack-6_all.deb",
                    "23ea26a992ed7b4577334441c14b2e4b0d95966fbcf87167adfb57f73b29d8d4", "194520"},
            {"libwine", "10.0~repack-6", "pool/main/w/wine/libwine_10.0~repack-6_amd64.deb",
                    "702c99d2fbaef2a4966667e0cdabd477ed1754c1b3b5d4476606eaf5fd6e1444", "111365028"},
            {"wine64", "10.0~repack-6", "pool/main/w/wine/wine64_10.0~repack-6_amd64.deb",
                    "4cdf8319d873c13210815c1aac18859a56ea21b0b2d35a3388dab881464ed488", "272800"},
            {"libx11-6", "2:1.8.12-1", "pool/main/libx/libx11/libx11-6_1.8.12-1_amd64.deb",
                    "b5a3fd3bf8c8fd0364bfb9bea00dcba7fc301229bd02dded084632d31f5b0fb3", "815292"},
            {"libxext6", "2:1.3.4-1+b3", "pool/main/libx/libxext/libxext6_1.3.4-1+b3_amd64.deb",
                    "fc618ec40465e5ce48622606299cb47833efc3fb235ba15543b81f850722f443", "50448"},
            {"libxrender1", "1:0.9.12-1", "pool/main/libx/libxrender/libxrender1_0.9.12-1_amd64.deb",
                    "9d042dfd5e613be1e02e6ddd0c5c4adef19c5eb08f6db838c2eba672c496dca4", "27896"},
            {"libxrandr2", "2:1.5.4-1+b3", "pool/main/libx/libxrandr/libxrandr2_1.5.4-1+b3_amd64.deb",
                    "11e3490de93a8bbee3daba719cb8e1325a26fb3c125525c34bdcb7deb05eb9b2", "36312"},
            {"libxi6", "2:1.8.2-1", "pool/main/libx/libxi/libxi6_1.8.2-1_amd64.deb",
                    "093d0903f35bb7a9f6815180ee040e6951fecf9b66c128cd72f064710210606e", "78896"},
            {"libxcursor1", "1:1.2.3-1", "pool/main/libx/libxcursor/libxcursor1_1.2.3-1_amd64.deb",
                    "bb0dbf58b804faaa74ed9efdae61c4b14bb0b4855680798f63e5ca6a4954976b", "39732"},
            {"libxfixes3", "1:6.0.0-2+b4", "pool/main/libx/libxfixes/libxfixes3_6.0.0-2+b4_amd64.deb",
                    "3fdd95d86d8e9d63e11f52070935c8f9f912c36aa3b17338d969f7685f3ed4fb", "20228"},
            {"libxinerama1", "2:1.1.4-3+b4", "pool/main/libx/libxinerama/libxinerama1_1.1.4-3+b4_amd64.deb",
                    "49d4e1628960407b8be2880a42428e7629801715a8a5697d42c0b0ca0a479911", "15992"},
            {"libxcomposite1", "1:0.4.6-1", "pool/main/libx/libxcomposite/libxcomposite1_0.4.6-1_amd64.deb",
                    "20e3c1d9b2135f0c8c4246a9fd26a51a57d5850c3b36ecc173c45d6be7328af3", "16348"},
            {"libx11-data", "2:1.8.12-1", "pool/main/libx/libx11/libx11-data_1.8.12-1_all.deb",
                    "c54f87069888f80ba4da586da6147d74c7598ccdd8b90906dbc4271fa414c738", "343072"},
            {"libfreetype6", "2.13.3+dfsg-1+deb13u1", "pool/main/f/freetype/libfreetype6_2.13.3+dfsg-1+deb13u1_amd64.deb",
                    "e4947f3291528f03d574f2b01d5c5fc45c58c47480f32888a5cdf0f231ab584e", "452308"},
            {"libunwind8", "1.8.1-0.1", "pool/main/libu/libunwind/libunwind8_1.8.1-0.1_amd64.deb",
                    "db21a86dd05c93413f0ef36282a9c64d32410d240f332273a53be1408bec1f62", "56252"},
            {"libxcb1", "1.17.0-2+b1", "pool/main/libx/libxcb/libxcb1_1.17.0-2+b1_amd64.deb",
                    "5c222a72d11b866447da31693254f738430726e3e065a384e82687b2fd2f978b", "144340"},
            {"libbrotli1", "1.1.0-2+b7", "pool/main/b/brotli/libbrotli1_1.1.0-2+b7_amd64.deb",
                    "0fb79f88db210afbd69282ab9649e525f393ec6950ca34da1a6b359250b8d7db", "306624"},
            {"libbz2-1.0", "1.0.8-6", "pool/main/b/bzip2/libbz2-1.0_1.0.8-6_amd64.deb",
                    "cba4cda04244b5e481bb15524bc3c983a7d1b6f330013b9b381706a2fcb65310", "37912"},
            {"libpng16-16t64", "1.6.48-1+deb13u5", "pool/main/libp/libpng1.6/libpng16-16t64_1.6.48-1+deb13u5_amd64.deb",
                    "2465b4e9fa85cff54dc10a6da3e64074d8d9292c86e4a8f989b809d6b158e97e", "283204"},
            {"zlib1g", "1:1.3.dfsg+really1.3.1-1+b1", "pool/main/z/zlib/zlib1g_1.3.dfsg+really1.3.1-1+b1_amd64.deb",
                    "015be740d6236ad114582dea500c1d907f29e16d6db00566ca32fb68d71ac90d", "88892"},
            {"liblzma5", "5.8.1-1+deb13u1", "pool/main/x/xz-utils/liblzma5_5.8.1-1+deb13u1_amd64.deb",
                    "1cfcc6e0dc36f438a79b6e2189facdb9d150b08f57d190a60e01c98075c7f896", "309492"},
            {"libxau6", "1:1.0.11-1", "pool/main/libx/libxau/libxau6_1.0.11-1_amd64.deb",
                    "689a9f0e0ba3e2c65431f864871e303ee904de69dd28abfc462663fae030227f", "20392"},
            {"libxdmcp6", "1:1.1.5-1", "pool/main/libx/libxdmcp/libxdmcp6_1.1.5-1_amd64.deb",
                    "0740dc760916b2008b45417a42a8fd7dd5de370fb57d31373f15034cda8acf0b", "27756"},
    };

    /**
     * あとから足したパッケージ: 名前・版・配布元での場所・SHA-256・大きさ・入っているかの目印のファイル。
     * fontconfig が無いと、Wine 10.0 は画面まわりの初期化の途中で落ちる (fontconfig を読み込めなかったのに、
     * その関数を呼ぶ箇所がある) ため、Wine の依存として入れる。入れ済みの Wine には、これだけを足す
     */
    private static final String[][] EXTRA_PACKAGES = {
            {"libfontconfig1", "2.15.0-2.3", "pool/main/f/fontconfig/libfontconfig1_2.15.0-2.3_amd64.deb",
                    "7ae91ec59857cc8e375eb2bdd371a4a69daea38f7787d06d474a847867d852b1", "391576",
                    "usr/lib/x86_64-linux-gnu/libfontconfig.so.1"},
            {"libexpat1", "2.8.3-1~deb13u1", "pool/main/e/expat/libexpat1_2.8.3-1~deb13u1_amd64.deb",
                    "38abe0e710a07688e9c149d74536e67cfee0364bdb64dd6d644c32a1cfad389f", "122672",
                    "usr/lib/x86_64-linux-gnu/libexpat.so.1"},
    };

    /**
     * 32bit の exe のための部品: Debian の libwine (i386) の中の 32bit 用の Windows の DLL。
     * 名前・版・配布元での場所・SHA-256・大きさ (バイト)。展開すると約 595MB
     */
    private static final String[] I386_PACKAGE = {"libwine:i386", "10.0~repack-6",
            "pool/main/w/wine/libwine_10.0~repack-6_i386.deb",
            "fef4802a6553a17eaa40003333a86fc697440d1d21c5824f2dbd1c444f01527b", "104122956"};
    /** パッケージの中での 32bit 用の DLL の場所と、置く場所 (64bit 版の Wine が探す場所) */
    private static final String I386_SRC = "usr/lib/i386-linux-gnu/wine/i386-windows";
    static final String I386_DIR = "usr/lib/x86_64-linux-gnu/wine/i386-windows";
    /** 入っているかの目印にする DLL (どれかが欠けていれば入っていないとみなす) */
    private static final String[] I386_NEED = {"ntdll.dll", "kernel32.dll", "kernelbase.dll", "user32.dll"};

    /**
     * .NET のプログラムのための部品: Wine Mono (Wine 10.0 が使う版 9.4.0)。WineHQ の公式の配布元から取り、
     * SHA-256 で照合する。名前・版・取ってくる場所・SHA-256・大きさ (バイト)。
     * 照合の値は、取ってきたファイルの SHA-512 が Arch Linux のパッケージ (wine-mono 9.4.0-1) の
     * sha512sums (2ea3fa22…12895f2) と一致することを確かめてから写した (WineHQ はハッシュ値を出していないため)。
     * Wine Mono は MIT などのオープンソースのライセンス (中身は Mono と .NET のオープンソース部分)
     */
    private static final String[] MONO_PACKAGE = {"wine-mono", "9.4.0",
            "https://dl.winehq.org/wine/wine-mono/9.4.0/wine-mono-9.4.0-x86.tar.xz",
            "fd772219aacf46b825fa891a647af4a9ddf8439320101c231918b2037bf13858", "43941584"};
    /** Wine Mono を置く場所 (Wine の data フォルダの中。Windows の環境からはリンクで参照する) */
    static final String MONO_DIR = "usr/share/wine/mono/wine-mono-9.4.0";

    private Wine() {
    }

    static File dir(Context c) {
        return new File(c.getFilesDir(), "wine");
    }

    static File dist(Context c) {
        return new File(dir(c), "dist");
    }

    static File shared(Context c) {
        return new File(dir(c), "shared");
    }

    /** 登録した Windows のプログラム (exe) の置き場所。Wine を消しても残す */
    static File programs(Context c) {
        return new File(dir(c), "programs");
    }

    /**
     * 登録した exe を Wine で動かすスクリプト (bin/<登録名> に置く)。root のシェルからは、wine コマンドと
     * 同じく、アプリへ合図を送ってアプリ自身 (普通の権限) が動かす
     */
    static String launcherScript(Context c, File exe) {
        String e = MainActivity.sq(exe.getAbsolutePath());
        return "#!/system/bin/sh\n"
                + "# Bionic Runner: Windows のプログラム (Wine で動かす)。本体: " + exe.getAbsolutePath() + "\n"
                + "E=" + e + "\n"
                + "if [ \"$(id -u)\" = 0 ]; then\n"
                + "  Q=\"'\"; R=\"'\\\\''\"\n"
                + "  c=\"'${E//$Q/$R}'\"\n"
                + "  for a in \"$@\"; do c=\"$c '${a//$Q/$R}'\"; done\n"
                + "  printf '\\036BRWINE|%s\\035%s\\037' \"$PWD\" \"$c\"\n"
                + "  echo 'Wine はアプリの普通の権限で動かします'\n"
                + "  exit 0\n"
                + "fi\n"
                + "exec /system/bin/sh " + MainActivity.sq(script(c).getAbsolutePath()) + " \"$E\" \"$@\"\n";
    }

    static File script(Context c) {
        return new File(dir(c), "wine.sh");
    }

    private static File versionFile(Context c) {
        return new File(dist(c), "BR-VERSION");
    }

    /** この端末で使えるか (Wine は Box64 の上で動く。Box64 は 64bit の ARM 端末用の APK だけに入っている) */
    static boolean usable(Context c) {
        return Box64.available(c);
    }

    /** 入っていて、あとから足したパッケージもそろっているか */
    static boolean installed(Context c) {
        return baseInstalled(c) && missingExtras(c).isEmpty();
    }

    /** 本体は入っているが、あとから足したパッケージが足りない (「更新」で足す) */
    static boolean needsUpdate(Context c) {
        return baseInstalled(c) && !missingExtras(c).isEmpty();
    }

    private static java.util.List<String[]> missingExtras(Context c) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (String[] p : EXTRA_PACKAGES) if (!new File(dist(c), p[5]).exists()) out.add(p);
        return out;
    }

    /** 本体 (最初の版からのパッケージ) が入っているか */
    private static boolean baseInstalled(Context c) {
        try {
            return new File(dist(c), "usr/lib/wine/wine64").isFile() && VERSION.equals(new String(
                    Icons.readAll(new FileInputStream(versionFile(c))), StandardCharsets.UTF_8).trim());
        } catch (IOException e) {
            return false;
        }
    }

    /** .NET のプログラムのための部品 (Wine Mono) が入っているか */
    static boolean monoInstalled(Context c) {
        return new File(dist(c), MONO_DIR + "/bin/libmono-2.0-x86_64.dll").isFile();
    }

    static int monoDownloadMegabytes() {
        return (int) ((Long.parseLong(MONO_PACKAGE[4]) + 1024 * 1024 - 1) / (1024 * 1024));
    }

    static long monoSize(Context c) {
        return Archive.treeSize(new File(dist(c), MONO_DIR));
    }

    /** Wine Mono を取ってきて確かめ、置く (Wine 本体が入っていることが前提。UI スレッド以外で呼ぶ) */
    static void installMono(Context c, Log log) throws IOException {
        if (!installed(c)) throw new IOException("先に Wine を入れてください");
        File d = dir(c);
        long free = d.getUsableSpace();
        if (free > 0 && free < 400L * 1024 * 1024) {
            throw new IOException("空き容量が足りません (.NET の部品を入れるには 400MB ほど必要です)");
        }
        File dl = new File(d, "download");
        File work = new File(d, "mono.new");
        Archive.deleteTree(dl);
        deleteTree(work);
        if (!dl.mkdirs() || !work.mkdirs()) throw new IOException("保存先を作れません");
        File target = new File(dist(c), MONO_DIR);
        File monoParent = target.getParentFile();
        File share = monoParent.getParentFile();
        try {
            long size = Long.parseLong(MONO_PACKAGE[4]);
            File tar = new File(dl, "wine-mono.tar.xz");
            final int[] last = {-1};
            log.line("Wine Mono " + MONO_PACKAGE[1] + " をダウンロードしています (" + (size / 1024 / 1024) + "MB)");
            Vm.download(MONO_PACKAGE[2], tar, size, MONO_PACKAGE[3], (m, pct) -> {
                if (pct / 10 != last[0]) {
                    last[0] = pct / 10;
                    log.line("  " + pct + "%");
                }
            });
            log.line("  SHA-256 が一致しました。展開しています (約 230MB)…");
            File tool = new File(c.getApplicationInfo().nativeLibraryDir, "lib7zz.so");
            if (!tool.isFile()) throw new IOException("展開に使う 7-Zip がありません");
            try (InputStream in = new BufferedInputStream(new FileInputStream(tar), 65536)) {
                unxzTar(tool, in, tar.length(), work);
            }
            tar.delete();
            File src = new File(work, "wine-mono-" + MONO_PACKAGE[1]);
            if (!new File(src, "bin/libmono-2.0-x86_64.dll").isFile()) {
                throw new IOException("展開した中に Wine Mono の本体がありません");
            }
            chmod(share, 0755);
            if (!monoParent.isDirectory() && !monoParent.mkdirs()) throw new IOException("保存先を作れません");
            chmod(monoParent, 0755);
            deleteTree(target);
            // フォルダを別の場所へ移すには、そのフォルダ自身に書き込めないといけないので、禁止は移したあと
            if (!src.renameTo(target)) throw new IOException("保存できません");
            setReadOnly(target);
        } finally {
            chmod(monoParent, 0555);
            chmod(share, 0555);
            deleteTree(work);
            Archive.deleteTree(dl);
        }
        // Windows の環境への登録 (リンクと設定) は、次に wine を動かしたときに wine.sh が行う
        new File(new File(d, "prefix"), ".br-mono").delete();
        log.line("Wine Mono を入れました (" + (monoSize(c) / 1024 / 1024) + "MB)");
    }

    /** Wine Mono を消して容量を戻す */
    static void removeMono(Context c) {
        File target = new File(dist(c), MONO_DIR);
        File monoParent = target.getParentFile();
        File share = monoParent.getParentFile();
        chmod(share, 0755);
        chmod(monoParent, 0755);
        try {
            deleteTree(target);
        } finally {
            chmod(monoParent, 0555);
            chmod(share, 0555);
        }
        deleteTree(new File(dir(c), "mono.new"));
        new File(new File(dir(c), "prefix"), ".br-mono").delete();
    }

    /** .NET のプログラム (CLR の見出しを持つ exe) か */
    static boolean isDotNet(File f) {
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
            if (r.length() < 64) return false;
            r.seek(60);
            long o = Integer.reverseBytes(r.readInt()) & 0xffffffffL;
            if (o + 24 + 2 > r.length()) return false;
            r.seek(o);
            if (Integer.reverseBytes(r.readInt()) != 0x4550) return false;
            r.seek(o + 24);
            int magic = Short.reverseBytes(r.readShort()) & 0xffff;
            // データの場所の表の 15 番目 (14) が .NET の見出し。PE32 は見出しの 96 バイト目から、PE32+ は 112 バイト目から
            long dirs = o + 24 + (magic == 0x20b ? 112 : 96);
            long at = dirs + 14 * 8;
            if (magic != 0x10b && magic != 0x20b || at + 8 > r.length()) return false;
            r.seek(at);
            return Integer.reverseBytes(r.readInt()) != 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** 32bit の exe のための部品 (i386 の DLL) が入っているか */
    static boolean wow64Installed(Context c) {
        File d = new File(dist(c), I386_DIR);
        for (String n : I386_NEED) if (!new File(d, n).isFile()) return false;
        return true;
    }

    /** 32bit の部品のダウンロードの大きさ (MB) */
    static int wow64DownloadMegabytes() {
        return (int) ((Long.parseLong(I386_PACKAGE[4]) + 1024 * 1024 - 1) / (1024 * 1024));
    }

    /** 32bit の部品の大きさ (バイト) */
    static long wow64Size(Context c) {
        return Archive.treeSize(new File(dist(c), I386_DIR));
    }

    /**
     * 32bit の exe のための部品 (Debian の libwine:i386 の中の i386 の DLL) を取ってきて確かめ、置く。
     * Wine 本体が入っていることが前提。時間がかかるので UI スレッド以外で呼ぶ
     */
    static void installWow64(Context c, Log log) throws IOException {
        if (!installed(c)) throw new IOException("先に Wine を入れてください");
        File d = dir(c);
        long free = d.getUsableSpace();
        // 部品 (約 600MB) とダウンロード (約 100MB) のほかに、最初に 32bit の部分を Windows の環境に作るとき、
        // DLL を一度コピーしてからリンクに置き換えるので、一時的に約 600MB 要る
        if (free > 0 && free < 1300L * 1024 * 1024) {
            throw new IOException("空き容量が足りません (32bit の部品を入れるには 1.3GB ほど必要です)");
        }
        File dl = new File(d, "download");
        File work = new File(d, "i386.new");
        Archive.deleteTree(dl);
        deleteTree(work);
        if (!dl.mkdirs() || !work.mkdirs()) throw new IOException("保存先を作れません");
        File target = new File(dist(c), I386_DIR);
        File parent = target.getParentFile();
        try {
            long size = Long.parseLong(I386_PACKAGE[4]);
            File deb = new File(dl, "libwine-i386.deb");
            final int[] last = {-1};
            log.line(I386_PACKAGE[0] + " " + I386_PACKAGE[1] + " をダウンロードしています (" + (size / 1024 / 1024)
                    + "MB)");
            Vm.download(MIRROR + I386_PACKAGE[2], deb, size, I386_PACKAGE[3], (m, pct) -> {
                if (pct / 10 != last[0]) {
                    last[0] = pct / 10;
                    log.line("  " + pct + "%");
                }
            });
            log.line("  SHA-256 が一致しました。展開しています (約 600MB。数分かかります)…");
            extractDeb(c, deb, work);
            deb.delete();
            File src = new File(work, I386_SRC);
            for (String n : I386_NEED) {
                if (!new File(src, n).isFile()) throw new IOException("展開した中に " + n + " がありません");
            }
            // 中身は照合したまま変えない (デバッグ情報も消さない)。置いてから書き込み禁止にする
            // (フォルダを別の場所へ移すには、そのフォルダ自身に書き込めないといけないため、禁止は移したあと)
            chmod(parent, 0755);
            deleteTree(target);
            if (!src.renameTo(target)) throw new IOException("保存できません");
            setReadOnly(target);
        } finally {
            if (!target.isDirectory()) target.mkdirs(); // 失敗しても、元の空のフォルダは残す
            chmod(parent, 0555);
            deleteTree(work);
            Archive.deleteTree(dl);
        }
        // Windows の環境に 32bit の部分 (syswow64) を作るのは、次に wine を動かしたときに wine.sh が行う
        new File(new File(d, "prefix"), ".br-wow64").delete();
        log.line("32bit の部品を入れました (" + (wow64Size(c) / 1024 / 1024) + "MB)。"
                + "最初に 32bit の exe を動かすときは、Windows の環境の更新で時間がかかります");
    }

    /** 32bit の部品を消して容量を戻す (Wine 本体と 64bit の exe はそのまま使える) */
    static void removeWow64(Context c) {
        File target = new File(dist(c), I386_DIR);
        File parent = target.getParentFile();
        chmod(parent, 0755);
        try {
            deleteTree(target);
            target.mkdirs();
        } finally {
            chmod(parent, 0555);
        }
        deleteTree(new File(dir(c), "i386.new"));
        new File(new File(dir(c), "prefix"), ".br-wow64").delete();
    }

    /** ダウンロードの合計 (MB) */
    static int downloadMegabytes() {
        long total = 0;
        for (String[] p : PACKAGES) total += Long.parseLong(p[4]);
        for (String[] p : EXTRA_PACKAGES) total += Long.parseLong(p[4]);
        return (int) ((total + 1024 * 1024 - 1) / (1024 * 1024));
    }

    /** files/wine 全体の大きさ (リンクの先は数えない) */
    static long size(Context c) {
        return Archive.treeSize(dir(c));
    }

    interface Log {
        void line(String s);
    }

    /** 取ってきて確かめ、展開する。時間がかかるので UI スレッド以外で呼ぶ */
    static void install(Context c, Log log) throws IOException {
        if (!usable(c)) throw new IOException("Wine は使えません (この端末では Box64 が使えないためです)");
        File d = dir(c);
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("保存先を作れません");
        long free = d.getUsableSpace();
        if (free > 0 && free < 1100L * 1024 * 1024) {
            throw new IOException("空き容量が足りません (Wine を入れるには 1.1GB ほど必要です)");
        }
        log.line("Box64 と glibc を用意しています…");
        Box64.ensureInstalled(c);
        if (needsUpdate(c)) {
            update(c, log);
            return;
        }

        File fresh = new File(d, "dist.new");
        File dl = new File(d, "download");
        deleteTree(fresh);
        Archive.deleteTree(dl);
        if (!fresh.mkdirs() || !dl.mkdirs()) throw new IOException("保存先を作れません");
        try {
            java.util.List<String[]> all = new java.util.ArrayList<>(java.util.Arrays.asList(PACKAGES));
            all.addAll(java.util.Arrays.asList(EXTRA_PACKAGES));
            int i = 0;
            for (String[] p : all) {
                i++;
                final String head = "(" + i + "/" + all.size() + ") " + p[0] + " " + p[1];
                long size = Long.parseLong(p[4]);
                File deb = new File(dl, p[0] + ".deb");
                final int[] last = {-1};
                log.line(head + " をダウンロードしています (" + (size / 1024) + "KB)");
                Vm.download(MIRROR + p[2], deb, size, p[3], (m, pct) -> {
                    if (size > 4 * 1024 * 1024 && pct / 10 != last[0]) {
                        last[0] = pct / 10;
                        log.line("  " + pct + "%");
                    }
                });
                log.line("  SHA-256 が一致しました。展開しています…");
                extractDeb(c, deb, fresh);
                deb.delete();
            }
            log.line("Android で動くように設定しています…");
            fixUp(c, fresh);
            Glibc.writeText(new File(fresh, "BR-VERSION"), VERSION + "\n");
            // 中身を書き込み禁止にする。Windows の環境 (prefix) の DLL は容量を減らすためにここへのリンクに
            // するので、Wine が環境を更新するときにリンクの先 (ここ) を書き換えて壊さないように
            setReadOnly(fresh);
            File dst = dist(c);
            deleteTree(dst);
            if (!fresh.renameTo(dst)) throw new IOException("保存できません");
        } finally {
            deleteTree(fresh);
            Archive.deleteTree(dl);
        }
        // 前の版で作った Windows の環境は、DLL のリンクの先が変わるので作り直す (D: の中身は残す)
        File prefix = new File(d, "prefix");
        File ready = new File(prefix, ".br-ready");
        if (prefix.isDirectory() && !ready.isFile()) deleteTree(prefix);
        shared(c).mkdirs();
        writeScript(c);
        log.line("Wine " + VERSION + " を入れました (" + (size(c) / 1024 / 1024) + "MB)");
    }

    /** 入れ済みの Wine に、足りないパッケージ (EXTRA_PACKAGES) だけを取ってきて足す */
    private static void update(Context c, Log log) throws IOException {
        File dl = new File(dir(c), "download");
        File dst = dist(c);
        Archive.deleteTree(dl);
        if (!dl.mkdirs()) throw new IOException("保存先を作れません");
        java.util.List<String[]> need = missingExtras(c);
        allowWrite(dst);
        try {
            int i = 0;
            for (String[] p : need) {
                i++;
                long size = Long.parseLong(p[4]);
                File deb = new File(dl, p[0] + ".deb");
                log.line("(" + i + "/" + need.size() + ") " + p[0] + " " + p[1] + " をダウンロードしています ("
                        + (size / 1024) + "KB)");
                Vm.download(MIRROR + p[2], deb, size, p[3], (m, pct) -> {
                });
                log.line("  SHA-256 が一致しました。展開しています…");
                extractDeb(c, deb, dst);
                deb.delete();
            }
        } finally {
            setReadOnly(dst);
            Archive.deleteTree(dl);
        }
        // 足りない部品のせいで作れなかった Windows の環境は、作り直す
        File prefix = new File(dir(c), "prefix");
        if (prefix.isDirectory() && !new File(prefix, ".br-ready").isFile()) deleteTree(prefix);
        writeScript(c);
        log.line("Wine を更新しました");
    }

    /** Wine を消す。D: ドライブ (共有フォルダ) の中身は残す */
    static void remove(Context c) {
        File d = dir(c);
        deleteTree(new File(d, "dist"));
        deleteTree(new File(d, "dist.new"));
        deleteTree(new File(d, "download"));
        deleteTree(new File(d, "prefix"));
        deleteTree(new File(d, "run"));
    }

    /** 書き込み禁止にしたフォルダも消せるように、フォルダの書き込みを許してから消す (リンクの先はたどらない) */
    private static void deleteTree(File f) {
        allowWrite(f);
        Archive.deleteTree(f);
    }

    private static void allowWrite(File f) {
        if (Archive.isSymlink(f) || !f.isDirectory()) return;
        chmod(f, 0700);
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) allowWrite(k);
    }

    /** ファイルもフォルダも書き込み禁止にする (実行できるかどうかはそのまま。リンクは触らない) */
    private static void setReadOnly(File f) {
        if (Archive.isSymlink(f)) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) setReadOnly(k);
            chmod(f, 0555);
        } else if (f.isFile()) {
            chmod(f, f.canExecute() ? 0555 : 0444);
        }
    }

    private static void chmod(File f, int mode) {
        try {
            android.system.Os.chmod(f.getAbsolutePath(), mode);
        } catch (android.system.ErrnoException ignored) {
            // 変えられなければそのまま
        }
    }

    /**
     * .deb (ar という形式のまとめファイル) の中の data.tar.xz を取り出し、7-Zip で xz を解いて展開する
     */
    private static void extractDeb(Context c, File deb, File dest) throws IOException {
        File tool = new File(c.getApplicationInfo().nativeLibraryDir, "lib7zz.so");
        if (!tool.isFile()) throw new IOException("展開に使う 7-Zip がありません");
        try (InputStream in = new BufferedInputStream(new FileInputStream(deb), 65536)) {
            byte[] magic = new byte[8];
            if (readFully(in, magic) != 8 || !new String(magic, StandardCharsets.US_ASCII).equals("!<arch>\n")) {
                throw new IOException("パッケージの形が違います: " + deb.getName());
            }
            byte[] h = new byte[60];
            while (readFully(in, h) == 60) {
                String name = new String(h, 0, 16, StandardCharsets.US_ASCII).trim();
                long size;
                try {
                    size = Long.parseLong(new String(h, 48, 10, StandardCharsets.US_ASCII).trim());
                } catch (NumberFormatException e) {
                    throw new IOException("パッケージの形が違います: " + deb.getName());
                }
                if (name.equals("data.tar.xz") || name.equals("data.tar.xz/")) {
                    unxzTar(tool, in, size, dest);
                    return;
                }
                skip(in, size + (size & 1));
            }
        }
        throw new IOException("パッケージの中に data.tar.xz がありません: " + deb.getName());
    }

    /** in から size バイトの xz を 7-Zip に渡し、出てきた tar を展開する */
    private static void unxzTar(File tool, InputStream in, long size, File dest) throws IOException {
        Process p = new ProcessBuilder(tool.getAbsolutePath(), "x", "-si", "-so", "-txz", "-bd", "-y")
                .redirectError(new File(dest.getParentFile(), "7zz.log")).start();
        final IOException[] feedError = {null};
        Thread feed = new Thread(() -> {
            byte[] buf = new byte[65536];
            long left = size;
            try (OutputStream os = p.getOutputStream()) {
                while (left > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n <= 0) throw new IOException("パッケージが途中で切れています");
                    os.write(buf, 0, n);
                    left -= n;
                }
            } catch (IOException e) {
                feedError[0] = e;
            }
        }, "wine-unxz");
        feed.start();
        try (InputStream tar = p.getInputStream()) {
            Archive.extractRootfsTar(tar, dest);
            // tar の終わりの印より後ろも読み切る (読まないと 7-Zip が書き込めずに止まる)
            byte[] buf = new byte[65536];
            while (tar.read(buf) > 0) {
                // 捨てる
            }
        } finally {
            try {
                feed.join();
                if (p.waitFor() != 0 && feedError[0] == null) {
                    throw new IOException("xz を展開できませんでした (7-Zip がエラーを返しました)");
                }
            } catch (InterruptedException e) {
                p.destroy();
                throw new IOException("中断しました");
            }
            new File(dest.getParentFile(), "7zz.log").delete();
        }
        if (feedError[0] != null) throw feedError[0];
    }

    /** Debian の配置のままでは動かないところを直す */
    private static void fixUp(Context c, File root) throws IOException {
        File lib = new File(root, "usr/lib/wine");
        // wineserver は Debian では「/usr/lib/wine/wineserver64 を起動するシェルスクリプト」なので、
        // 同じフォルダの本体へのリンクにする (wine64 は自分と同じフォルダの wineserver を起動する)
        link("wineserver64", new File(lib, "wineserver"));
        // wineserver は nls (文字の変換表) を share/wine/wine/nls で探すので、share/wine 自身を指すリンクを置く
        link(".", new File(root, "usr/share/wine/wine"));
        // wineserver との連絡用のフォルダの場所を、アプリのフォルダに書き換える (WinePatch の説明を参照)
        String run = new File(dir(c), "run").getAbsolutePath();
        WinePatch.patchFile(new File(root, "usr/lib/x86_64-linux-gnu/wine/x86_64-unix/ntdll.so"), run);
        WinePatch.patchFile(new File(lib, "wineserver64"), run);
        // 画面まわりの初期化を Box64 の pthread_once を通さずに必ず行う (WinePatch.patchInitUser の説明を参照)
        WinePatch.patchWin32uFile(new File(root, WIN32U));
        // 使わないもの (Debian 用の起動スクリプト) は消す
        new File(lib, "wineapploader").delete();
        Archive.deleteTree(new File(root, "usr/bin"));
        for (String need : new String[]{"usr/lib/wine/wine64", "usr/lib/wine/wineserver64",
                "usr/lib/x86_64-linux-gnu/wine/x86_64-unix/ntdll.so", "usr/share/wine/wine.inf",
                "usr/x86_64-w64-mingw32/lib/zlib1.dll"}) {
            if (!new File(root, need).isFile()) throw new IOException("展開した中に " + need + " がありません");
        }
    }

    private static final String WIN32U = "usr/lib/x86_64-linux-gnu/wine/x86_64-unix/win32u.so";

    /**
     * すでに入っている Wine に、あとから足した書き換えをする (ダウンロードし直さずに済むように)。
     * アプリの起動のたびに呼ぶ。書き換え済みなら何もしない
     */
    static void updatePatches(Context c) {
        if (!baseInstalled(c)) return;
        File f = new File(dist(c), WIN32U);
        File dir = f.getParentFile();
        try {
            byte[] d;
            try (InputStream in = new FileInputStream(f)) {
                d = Icons.readAll(in);
            }
            if (!WinePatch.patchInitUser(d)) return;
            // 書き込み禁止を一時的に外して書き換え、また禁止に戻す
            chmod(dir, 0755);
            chmod(f, 0644);
            try {
                WinePatch.patchWin32uFile(f);
            } finally {
                chmod(f, 0444);
                chmod(dir, 0555);
            }
        } catch (IOException ignored) {
            // 書き換えられなければ今のまま (wine --diag で分かる)
        }
    }

    private static void link(String target, File at) throws IOException {
        at.delete();
        try {
            android.system.Os.symlink(target, at.getAbsolutePath());
        } catch (android.system.ErrnoException e) {
            throw new IOException("リンクを作れません: " + at.getName());
        }
    }

    /**
     * wine コマンドの本体 (files/wine/wine.sh) を作る。中身は assets/wine/wine.sh で、場所だけを埋める。
     * アプリの起動のたびに呼ぶ (アプリを更新すると、同梱ファイルの場所が変わるため)
     */
    static void writeScript(Context c) {
        try (InputStream in = c.getAssets().open("wine/wine.sh")) {
            // 同梱の補助ライブラリの版 (assets/glibc/VERSIONS.txt の「arm64-v8a brshim 指紋」)。
            // 端末に展開したものがこれと違えば、wine.sh が「アプリを開き直して」と案内する
            String brshim = "?";
            try (InputStream v = c.getAssets().open("glibc/VERSIONS.txt")) {
                for (String line : new String(Icons.readAll(v), StandardCharsets.UTF_8).split("\n")) {
                    if (line.startsWith("arm64-v8a brshim ")) brshim = line.substring(17).trim();
                }
            } catch (IOException ignored) {
                // 無ければ照合できない (wine.sh は「準備がまだ」と出す)
            }
            String s = new String(Icons.readAll(in), StandardCharsets.UTF_8)
                    .replace("@BRSHIM@", brshim.replaceAll("[^0-9a-f]", ""))
                    .replace("@WINE_DIR@", MainActivity.sq(dir(c).getAbsolutePath()))
                    .replace("@NATIVE_DIR@", MainActivity.sq(c.getApplicationInfo().nativeLibraryDir))
                    .replace("@GLIBC_ARM64@", MainActivity.sq(Glibc.libDir(c, "arm64-v8a").getAbsolutePath()))
                    .replace("@GLIBC_X86_64@", MainActivity.sq(Glibc.libDir(c, "x86_64").getAbsolutePath()))
                    .replace("@BOX64_SUPPORT@", MainActivity.sq(Box64.support(c).getAbsolutePath()))
                    .replace("@BOX64_PROBE@", MainActivity.sq(Box64.probeFile(c).getAbsolutePath()))
                    .replace("@LINUX_ROOTFS@", MainActivity.sq(Linux.rootfs(c).getAbsolutePath()))
                    .replace("@WINE_VERSION@", MainActivity.sq(VERSION));
            dir(c).mkdirs();
            Glibc.writeText(script(c), s);
            // 日本語の字体の設定 (レジストリのファイル) と、日本語の環境のためのロケール
            WineFonts.writeFiles(c);
        } catch (IOException ignored) {
            // 書けなければ、wine コマンドは「入っていません」と同じ扱いになる
        }
    }

    /** 初めて Windows のプログラムを動かすときの確認を、もう済ませたか */
    static boolean agreed(Context c) {
        return new File(dir(c), "agreed").isFile();
    }

    static void setAgreed(Context c) {
        try {
            dir(c).mkdirs();
            Glibc.writeText(new File(dir(c), "agreed"), "");
        } catch (IOException ignored) {
            // 書けなければ、次にもう一度確かめるだけ
        }
    }

    /** exe の CPU の種類 (PE の Machine の値)。読めなければ -1 */
    static int peMachine(File f) {
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
            if (r.length() < 64) return -1;
            byte[] b = new byte[2];
            r.readFully(b);
            if (b[0] != 'M' || b[1] != 'Z') return -1;
            r.seek(60);
            long o = Integer.reverseBytes(r.readInt()) & 0xffffffffL;
            if (o + 6 > r.length()) return -1;
            r.seek(o);
            if (Integer.reverseBytes(r.readInt()) != 0x4550) return -1;
            return Short.reverseBytes(r.readShort()) & 0xffff;
        } catch (IOException e) {
            return -1;
        }
    }

    /** 32bit の exe (i386) か (.NET のプログラムは、どの CPU 向けでも Wine Mono が 64bit で動かすので除く) */
    static boolean is32bit(File f) {
        return peMachine(f) == 0x14c && !isDotNet(f);
    }

    /** 32bit の exe を動かすときの「試験中」の説明 */
    static final String WOW64_NOTICE = "32bit の exe の実行は試験中です。ときどき落ちることがあります。"
            + "落ちたときは、コマンドプロンプトに出る記録の場所を見るか、wine --diag で原因を調べられます。";

    /** .NET のプログラムを動かすときの説明 */
    static final String DOTNET_NOTICE = "これは .NET (C# など) のプログラムです。Wine Mono で動かします"
            + " (Box64 の上なので、起動に時間がかかります)。";

    /** exe の種類: 動かせるなら null、動かせないものはその理由 (32bit は部品が入っていれば動かせる) */
    static String checkPe(Context c, File f) {
        // .NET のプログラムは Wine Mono が要る (どの CPU 向けでも動く AnyCPU のものは、32bit の exe でも
        // 64bit で動くので、32bit の部品は要らない)
        if (isDotNet(f)) {
            return monoInstalled(c) ? null
                    : "これは .NET (C# など) のプログラムです。動かすには、X11 の画面の「追加機能」で"
                    + "「.NET のプログラムも動かす (Wine Mono)」を入れてください (ダウンロード約 "
                    + monoDownloadMegabytes() + "MB)";
        }
        String why = checkPe(f);
        if (why == null || !is32bit(f)) return why;
        return wow64Installed(c) ? null
                : "32bit の exe を動かすには、X11 の画面の「追加機能」で「32bit の exe も動かす (試験中)」を"
                + "入れてください (ダウンロード約 " + wow64DownloadMegabytes() + "MB)";
    }

    /** exe の種類: 64bit (x86_64) なら null、動かせないものはその理由 */
    static String checkPe(File f) {
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
            if (r.length() < 64) return "Windows のプログラム (exe) ではありません";
            byte[] b = new byte[2];
            r.readFully(b);
            if (b[0] != 'M' || b[1] != 'Z') return "Windows のプログラム (exe) ではありません";
            r.seek(60);
            long o = Integer.reverseBytes(r.readInt()) & 0xffffffffL;
            if (o + 6 > r.length()) return "Windows のプログラム (exe) ではありません";
            r.seek(o);
            if (Integer.reverseBytes(r.readInt()) != 0x4550) return "Windows のプログラム (exe) ではありません";
            int machine = Short.reverseBytes(r.readShort()) & 0xffff;
            if (machine == 0x8664) return null;
            if (machine == 0x14c) return "32bit の exe はまだ動きません (動くのは 64bit の exe だけです)";
            if (machine == 0xaa64) return "ARM64 用の exe は動きません (動くのは x86_64 用の 64bit の exe だけです)";
            return "この exe の種類 (CPU) には対応していません";
        } catch (IOException e) {
            return "読み込めませんでした";
        }
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int got = 0;
        while (got < buf.length) {
            int n = in.read(buf, got, buf.length - got);
            if (n < 0) break;
            got += n;
        }
        return got;
    }

    private static void skip(InputStream in, long n) throws IOException {
        byte[] buf = new byte[8192];
        while (n > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (r < 0) throw new IOException("パッケージが途中で切れています");
            n -= r;
        }
    }
}
