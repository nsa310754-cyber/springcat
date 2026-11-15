package site.springcat.bionicrunner;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 実行ファイルの簡易検査。ファイルに含まれる文字列 (スクリプトなら各行、バイナリなら埋め込まれた
 * 文字列) から、端末を壊したりデータを消したりする操作の記述を探す。
 *
 * あくまで「危険な記述が見当たるか」の検査で、ウイルス対策ソフトのような保証はできない。
 * 文字列を隠したプログラムは見逃すし、正当な管理ツールを危険と判定することもある。
 * Android の API は使っていないので、PC 上の JVM でも単体で検証できる。
 */
final class ExeScanner {

    static final class Finding {
        /** 何が危険かの説明 */
        final String title;
        /** 見つかった記述 (長ければ切り詰める) */
        final String evidence;

        Finding(String title, String evidence) {
            this.title = title;
            this.evidence = evidence;
        }
    }

    static final class Report {
        final List<Finding> findings = new ArrayList<>();
        /** 大きすぎて先頭だけ検査した */
        boolean truncated;

        boolean dangerous() {
            return !findings.isEmpty();
        }
    }

    private static final class Rule {
        final String title;
        final Pattern pattern;

        Rule(String title, String regex) {
            this.title = title;
            this.pattern = Pattern.compile(regex);
        }
    }

    /** 削除されると困る場所 (ルートそのもの、システム、利用者のデータ) */
    private static final String VITAL = "(?:\\*|(?:system|vendor|product|boot|efs|persist|data|data/data"
            + "|data/app|data/media(?:/0)?|sdcard|storage|storage/emulated(?:/0)?|mnt)(?:/\\*?)?)?";

    private static final Rule[] RULES = {
            new Rule("ストレージ (パーティション) への直接書き込み",
                    "/dev/block/|/dev/mmcblk|\\bof=/dev/"),
            new Rule("ストレージの初期化 (フォーマット)",
                    "\\b(?:mkfs(?:\\.\\w+)?|mke2fs|make_ext4fs|make_f2fs|newfs_msdos|sgdisk|parted)\\b"),
            new Rule("システムや保存データの一括削除",
                    "\\brm\\s+(?:-[\\w-]+\\s+)*-[\\w-]*[rRf][\\w-]*\\s+(?:-[\\w-]+\\s+)*[\"']?/" + VITAL
                            + "[\"']?(?=\\s|;|&|\\||$)"),
            new Rule("システム領域を書き込み可能に変更",
                    "\\bmount\\b.*\\bremount\\b.*\\brw\\b|\\bremount,rw\\b"),
            new Rule("起動に関わる領域の書き換え",
                    "\\b(?:fastboot|flash_image|flashcp)\\b|by-name/(?:boot|recovery|system|vendor|vbmeta"
                            + "|bootloader|aboot|sbl|tz|modem|efs|persist)"),
            new Rule("端末の初期化 (全データ消去)",
                    "--wipe[_-]data|\\bwipe\\s+(?:data|all)\\b|MASTER_CLEAR|FACTORY_RESET|\\bpm\\s+clear\\b"),
            new Rule("アプリの削除・無効化",
                    "\\bpm\\s+(?:uninstall|disable|disable-user|hide)\\b"),
            new Rule("フォーク爆弾 (端末を固まらせる)",
                    ":\\(\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:"),
            new Rule("保護機能の無効化",
                    "\\bsetenforce\\s+0\\b|disable-verity|disable-verification"
                            + "|\\bchmod\\s+(?:-R\\s+)?0*(?:000|777)\\s+/(?:system|vendor|data)?(?=\\s|;|$)"),
            new Rule("外部から命令を取得して実行・遠隔操作",
                    "\\b(?:curl|wget)\\b[^|;]*\\|\\s*(?:su\\s+(?:-c\\s+)?)?(?:sh|bash|mksh)\\b|/dev/tcp/"
                            + "|\\b(?:nc|ncat|netcat)\\b.*\\s-e\\s"),
            new Rule("個人情報・認証情報の読み取り",
                    "/data/system/(?:locksettings|gatekeeper|password|gesture)|accounts(?:_ce|_de)?\\.db"
                            + "|wpa_supplicant\\.conf|WifiConfigStore|/data/misc/keystore|mmssms\\.db|contacts2\\.db"),
            new Rule("メモリやカーネルへの直接書き込み",
                    "\\bof=/dev/(?:mem|kmem|port)\\b|>\\s*/dev/(?:mem|kmem)\\b|/proc/sysrq-trigger"),
            new Rule("ウイルス検査用のテスト文字列 (EICAR)",
                    "EICAR-STANDARD-ANTIVIRUS-TEST-FILE"),
    };

    /** これより大きいファイルは先頭だけ検査する */
    private static final long MAX_SCAN = 64L * 1024 * 1024;
    private static final int MIN_STRING = 4;
    private static final int MAX_STRING = 4096;
    private static final int MAX_EVIDENCE = 140;

    private ExeScanner() {
    }

    static Report scan(File file) throws IOException {
        Report report = new Report();
        boolean[] hit = new boolean[RULES.length];
        StringBuilder run = new StringBuilder();
        byte[] buf = new byte[65536];
        long total = 0;
        try (InputStream in = new FileInputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                for (int i = 0; i < n; i++) {
                    int b = buf[i] & 255;
                    // 印字できる文字 (とタブ) が続く間を 1 つの文字列として集める
                    if ((b >= 0x20 && b < 0x7f) || b == '\t') {
                        run.append((char) b);
                        if (run.length() >= MAX_STRING) check(run, report, hit);
                    } else if (run.length() > 0) {
                        check(run, report, hit);
                    }
                }
                total += n;
                if (total >= MAX_SCAN) {
                    report.truncated = true;
                    break;
                }
            }
        }
        check(run, report, hit);
        return report;
    }

    /** 文字列 1 本を全ルールに当て、終わったら次に備えて空にする */
    private static void check(StringBuilder run, Report report, boolean[] hit) {
        if (run.length() >= MIN_STRING) {
            for (int r = 0; r < RULES.length; r++) {
                if (hit[r]) continue; // 同じ種類は最初の 1 件だけ報告する
                Matcher m = RULES[r].pattern.matcher(run);
                if (m.find()) {
                    hit[r] = true;
                    // 見つかった箇所の前後を少し含めて見せる
                    int from = Math.max(0, m.start() - 30);
                    int to = Math.min(run.length(), Math.max(m.end() + 30, from + 60));
                    String ev = run.substring(from, Math.min(to, from + MAX_EVIDENCE)).trim();
                    report.findings.add(new Finding(RULES[r].title, ev));
                }
            }
        }
        run.setLength(0);
    }
}
