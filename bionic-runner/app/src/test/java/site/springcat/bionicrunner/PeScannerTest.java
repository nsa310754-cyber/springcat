package site.springcat.bionicrunner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Windows の exe の構造の検査 (PeInfo・ExeScanner.scanPe) の確かめ。PC の Java で動く (外部のライブラリは使わない)。
 *
 *   tools/run-pe-tests.sh [Wine の x86_64-windows フォルダ]
 *
 * 確かめること:
 *   1. 見逃し: samples/windows/scanner-test の確認用の exe (ローダー型の特徴だけを持つ、害の無いもの) が「危険」になる
 *   2. 誤判定: samples/windows のふつうのサンプルが「問題なし」になる。Wine のフォルダを渡すと、その中の
 *      exe と dll すべてが「問題なし」になるかも確かめ、点数の分かれ方を出す
 *   3. 壊れたファイル: 空・途中で切れた・ありえない数を書いた・表が自分を指す、などで例外にならない
 */
public final class PeScannerTest {

    private static int failures;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ○ " : "  × ") + what);
        if (!ok) failures++;
    }

    public static void main(String[] args) throws IOException {
        File samples = new File(args.length > 1 ? args[1] : "samples/windows");

        System.out.println("== 1. 見逃しの確認 (確認用の exe)");
        for (String n : new String[]{"scanner-test/scanner-test.exe", "scanner-test/scanner-test-32.exe"}) {
            ExeScanner.PeReport r = ExeScanner.scanPe(new File(samples, n));
            print(n, r);
            check(r.level == ExeScanner.PeLevel.DANGER, n + " が「危険」");
        }

        System.out.println("== 2. 誤判定の確認 (ふつうのサンプル)");
        for (String n : new String[]{"hello-console.exe", "hello-window.exe"}) {
            ExeScanner.PeReport r = ExeScanner.scanPe(new File(samples, n));
            print(n, r);
            check(r.level == ExeScanner.PeLevel.OK, n + " が「問題なし」");
        }
        if (args.length > 0 && !args[0].isEmpty()) wineFolder(new File(args[0]));

        System.out.println("== 3. 壊れたファイル");
        File tmp = Files.createTempDirectory("pe-test").toFile();
        byte[] good = Files.readAllBytes(new File(samples, "scanner-test/scanner-test.exe").toPath());
        broken(tmp, "空のファイル", new byte[0]);
        broken(tmp, "MZ だけ", new byte[]{'M', 'Z'});
        // 途中で切れたもの (いろいろな長さ)
        for (int len : new int[]{64, 0x80, 0x100, 0x200, 0x400, 0x1000, good.length / 2}) {
            byte[] b = new byte[Math.min(len, good.length)];
            System.arraycopy(good, 0, b, 0, b.length);
            broken(tmp, "途中で切れた (" + len + " バイト)", b);
        }
        int pe = (good[0x3c] & 0xff) | (good[0x3d] & 0xff) << 8;
        // PE ヘッダーの場所がファイルの外
        byte[] b1 = good.clone();
        b1[0x3c] = (byte) 0xff;
        b1[0x3d] = (byte) 0xff;
        b1[0x3e] = (byte) 0xff;
        b1[0x3f] = (byte) 0x7f;
        broken(tmp, "PE の場所がファイルの外", b1);
        // セクションの数がありえない
        byte[] b2 = good.clone();
        b2[pe + 6] = (byte) 0xff;
        b2[pe + 7] = (byte) 0xff;
        broken(tmp, "セクションが 65535 個", b2);
        // セクションの大きさ・場所がありえない
        byte[] b3 = good.clone();
        int opt = pe + 24, optSize = (good[pe + 20] & 0xff) | (good[pe + 21] & 0xff) << 8;
        int sec = opt + optSize;
        for (int i = 0; i < 4; i++) {
            b3[sec + 16 + i] = (byte) 0xff; // 大きさ
            b3[sec + 20 + i] = (byte) 0xff; // 場所
        }
        broken(tmp, "セクションの場所と大きさが 4GB", b3);
        // インポートの表を、セクションの表 (自分のヘッダー) に向ける → 終わりのない表のように見える
        byte[] b4 = good.clone();
        long dirs = opt + 112; // 64bit
        put32(b4, (int) dirs + 8, sec);
        put32(b4, (int) dirs + 12, 0x7fffffff);
        broken(tmp, "インポートの表が自分のヘッダーを指す", b4);
        // 32bit でも 64bit でもない
        byte[] b5 = good.clone();
        b5[opt] = 0x07;
        b5[opt + 1] = 0x01;
        broken(tmp, "32bit でも 64bit でもない", b5);
        // ランダムなバイト列 (先頭だけ PE に見せる)
        Random rnd = new Random(1);
        for (int i = 0; i < 200; i++) {
            byte[] b = good.clone();
            for (int k = 0; k < 64; k++) b[0x40 + rnd.nextInt(Math.min(0x600, b.length - 0x40))] = (byte) rnd.nextInt();
            broken(tmp, null, b);
        }
        check(true, "ランダムに壊した 200 個も、例外にならない");
        for (File f : tmp.listFiles()) f.delete();
        tmp.delete();

        System.out.println(failures == 0 ? "すべて通りました" : "失敗: " + failures + " 件");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void broken(File dir, String name, byte[] data) throws IOException {
        File f = new File(dir, "t.exe");
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(data);
        }
        try {
            ExeScanner.PeReport r = ExeScanner.scanPe(f);
            if (name != null) check(true, name + ": " + r.summary());
        } catch (RuntimeException e) {
            check(false, (name == null ? "ランダムに壊したもの" : name) + ": 例外 " + e);
        }
    }

    private static void put32(byte[] b, int at, long v) {
        for (int i = 0; i < 4; i++) b[at + i] = (byte) (v >> (8 * i));
    }

    private static void print(String name, ExeScanner.PeReport r) {
        System.out.println("  " + name + ": " + r.summary());
        for (ExeScanner.PeFinding x : r.findings) System.out.println("      +" + x.points + " " + x.title);
    }

    /** Wine の exe と dll すべて: 点数の分かれ方と、「問題なし」でないもの */
    private static void wineFolder(File dir) {
        System.out.println("== 2b. 誤判定の確認 (" + dir + ")");
        File[] files = dir.listFiles();
        if (files == null) {
            check(false, "フォルダを読めません");
            return;
        }
        Map<Integer, Integer> hist = new TreeMap<>();
        Map<String, Integer> causes = new TreeMap<>();
        int n = 0, notOk = 0, broken = 0;
        for (File f : files) {
            if (!f.isFile()) continue;
            ExeScanner.PeReport r = ExeScanner.scanPe(f);
            if (r.error != null && r.info != null && !r.info.looksLikePe) continue; // PE でないもの (データ)
            n++;
            if (r.error != null) broken++;
            hist.merge(r.score, 1, Integer::sum);
            for (ExeScanner.PeFinding x : r.findings) causes.merge(x.title.replaceAll(" \\(.*", ""), 1, Integer::sum);
            if (r.level != ExeScanner.PeLevel.OK) {
                notOk++;
                print(f.getName(), r);
            }
        }
        System.out.println("  調べた数: " + n + " (壊れた PE: " + broken + ")");
        System.out.println("  点数の分かれ方 (点数: 個数): " + hist);
        System.out.println("  見つかった特徴 (特徴: 個数):");
        for (Map.Entry<String, Integer> e : causes.entrySet()) System.out.println("      " + e.getValue() + "  " + e.getKey());
        check(notOk == 0, "すべて「問題なし」 (要注意・危険: " + notOk + " 個)");
    }
}
