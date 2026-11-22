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
                    // 道具の名前だけ (ツール集の中のファイル名 bin/mkfs.cramfs など) では当てはめず、
                    // 装置 (/dev/…) を相手に使っている記述だけにする
                    "\\b(?:mkfs(?:\\.\\w+)?|mke2fs|make_ext4fs|make_f2fs|newfs_msdos|sgdisk|parted)\\b[^\\n]{0,120}?\\s/dev/"),
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
            // ---- Windows のプログラム (Wine で動かす exe) 向け。Windows のコマンドは大文字小文字を区別しない
            new Rule("Windows: 復元用の記録 (シャドウコピー・バックアップ) の削除 (身代金を要求するウイルスの典型)",
                    "(?i)\\bvssadmin(?:\\.exe)?\\s+delete\\s+shadows|\\bwmic(?:\\.exe)?\\s+shadowcopy\\s+delete"
                            + "|\\bwbadmin(?:\\.exe)?\\s+delete\\s+(?:catalog|systemstatebackup)"),
            new Rule("Windows: 起動・回復の設定の書き換え",
                    "(?i)\\bbcdedit(?:\\.exe)?\\s+/set\\b.*\\b(?:recoveryenabled\\s+no|bootstatuspolicy\\s+ignoreallfailures)"),
            new Rule("Windows: ドライブの初期化・完全消去",
                    "(?i)\\bformat(?:\\.com)?\\s+[a-z]:|\\bcipher(?:\\.exe)?\\s+/w:"),
            new Rule("Windows: ウイルス対策 (Windows Defender) の無効化",
                    "(?i)DisableAntiSpyware|DisableRealtimeMonitoring|Set-MpPreference\\s+-Disable"),
            new Rule("Windows: 外部から命令やファイルを取ってきて実行",
                    "(?i)\\bpowershell(?:\\.exe)?\\b.*(?:\\s-e(?:nc(?:odedcommand)?)?\\s+[A-Za-z0-9+/=]{20,}"
                            + "|\\biex\\s*\\(|Invoke-Expression|DownloadString\\()"
                            + "|\\bcertutil(?:\\.exe)?\\s+-urlcache|\\bbitsadmin(?:\\.exe)?\\s+/transfer"),
            new Rule("Windows: 記録 (イベントログ) の消去",
                    "(?i)\\bwevtutil(?:\\.exe)?\\s+cl\\b"),
            new Rule("Windows: ブラウザのパスワードや暗号資産のウォレットの読み取り",
                    "(?i)\\\\User Data\\\\Default\\\\Login Data|\\bwallet\\.dat\\b|\\\\Microsoft\\\\Protect\\\\"),
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
        // Windows のプログラムは文字列を UTF-16 (1 文字 2 バイト: 英字なら「文字, 0」) で持つことが多いので、
        // その形の文字列も集めて調べる
        StringBuilder wide = new StringBuilder();
        int pending = -1;
        byte[] buf = new byte[65536];
        long total = 0;
        try (InputStream in = new FileInputStream(file)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                for (int i = 0; i < n; i++) {
                    int b = buf[i] & 255;
                    if (pending >= 0) {
                        if (b == 0) {
                            wide.append((char) pending);
                            if (wide.length() >= MAX_STRING) check(wide, report, hit);
                            pending = -1;
                        } else {
                            if (wide.length() > 0) check(wide, report, hit);
                            pending = ((b >= 0x20 && b < 0x7f) || b == '\t') ? b : -1;
                        }
                    } else if ((b >= 0x20 && b < 0x7f) || b == '\t') {
                        pending = b;
                    } else if (b != 0 && wide.length() > 0) {
                        check(wide, report, hit);
                    } else if (b == 0 && wide.length() > 0) {
                        check(wide, report, hit); // 0, 0 (文字列の終わり)
                    }
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
        check(wide, report, hit);
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

    // ======================================================== Windows の exe の構造の検査

    /** 構造の検査の判定 */
    enum PeLevel {
        OK("問題なし"), CAUTION("要注意"), DANGER("危険");

        final String label;

        PeLevel(String label) {
            this.label = label;
        }
    }

    /** 点数の合計がこれ以上なら「要注意」「危険」。決め方は README と PeScannerTest を参照 */
    static final int PE_CAUTION = 5, PE_DANGER = 8;

    static final class PeFinding {
        final int points;
        final String title;
        /** セキュリティに詳しくない人向けの説明 */
        final String reason;

        PeFinding(int points, String title, String reason) {
            this.points = points;
            this.title = title;
            this.reason = reason;
        }
    }

    static final class PeReport {
        final List<PeFinding> findings = new ArrayList<>();
        int score;
        PeLevel level = PeLevel.OK;
        /** PE として読めなかったときの理由 (読めたら null) */
        String error;
        PeInfo info;

        /** 1 行のまとめ (例: 「構造の検査: 要注意 (6 点)」) */
        String summary() {
            if (error != null) return "構造の検査: " + error;
            return "構造の検査: " + level.label + " (" + score + " 点)";
        }
    }

    private static void add(PeReport r, int points, String title, String reason) {
        r.findings.add(new PeFinding(points, title, reason));
        r.score += points;
    }

    /**
     * Windows の exe・dll の構造 (取り込む関数・セクション・付け足されたデータなど) を見て、危険なプログラムに
     * よくある特徴に点数を付ける。1 つの特徴だけでは危険とは言えないので、合計点で判定する。
     * 暗号化して中身を隠すプログラムは、文字列の検査 ({@link #scan}) では見つけにくいため、この検査で補う。
     */
    static PeReport scanPe(File file) {
        PeReport r = new PeReport();
        PeInfo p = PeInfo.read(file);
        r.info = p;
        if (!p.valid) {
            r.error = p.error;
            // PE の印があるのに読めない (壊れた PE) のは、解析を邪魔するために壊していることもある
            if (p.looksLikePe) {
                add(r, 3, "壊れた PE", "Windows のプログラムの形が壊れています。調べられないようにわざと壊していることがあります");
            }
            finish(r);
            return r;
        }
        // 1. 実行するときにメモリを「実行できる」ようにする
        boolean alloc = p.importsAny("VirtualAlloc", "VirtualProtect");
        boolean thread = p.importsAny("CreateThread", "CreateFiber", "ConvertThreadToFiber", "CreateTimerQueueTimer");
        if (p.imports("VirtualAlloc") && (p.rwxAllocPattern || thread) || alloc && p.rwxAllocPattern) {
            add(r, 3, "実行するときに、書き込みも実行もできるメモリを用意する",
                    "あとから作った命令 (暗号を解いた命令など) を動かすための準備です。中身を隠して動くプログラムによくあります"
                            + (p.rwxAllocPattern ? " (「書き込みも実行もできる」と指定している箇所があります)" : ""));
        }
        // 2. ほかのプログラムへの書き込み (注入)
        if (p.imports("OpenProcess") && p.imports("VirtualAllocEx") && p.imports("WriteProcessMemory")
                && p.importsAny("CreateRemoteThread", "CreateRemoteThreadEx", "QueueUserAPC", "SetThreadContext",
                "NtQueueApcThread", "RtlCreateUserThread")) {
            add(r, 6, "ほかのプログラムに命令を書き込んで動かす",
                    "動いているほかのプログラムの中に命令を入れて動かす仕組みです。正体を隠すためによく使われます");
        }
        // 3. 書き込みも実行もできるセクション
        for (PeInfo.Section sec : p.sections) {
            if (sec.executable() && sec.writable()) {
                add(r, 3, "書き込みも実行もできる部分がある (" + sec.name + ")",
                        "動きながら自分の命令を書き換えられる作りです。暗号化した命令を隠すときによく使われます");
                break;
            }
        }
        // 4. 中身がランダムに近いセクション (リソース = 絵や音の部分は除く)
        for (PeInfo.Section sec : p.sections) {
            boolean resource = p.resourceRva != 0 && p.resourceRva >= sec.virtualAddress
                    && p.resourceRva < sec.virtualAddress + Math.max(sec.virtualSize, sec.rawSize);
            if (!resource && sec.entropy >= 7.2 && sec.rawSize >= 4096) {
                add(r, 3, String.format(java.util.Locale.ROOT, "中身がランダムに近い部分がある (%s、%.1f)", sec.name, sec.entropy),
                        "暗号化や圧縮で中身を隠していると、このようになります");
                break;
            }
        }
        // 5. 末尾に付け足されたデータ (オーバーレイ)
        if (p.overlaySize >= 1024 * 1024 || p.overlaySize >= 1024 && p.overlayEntropy >= 7.2) {
            add(r, 3, "末尾に大きい、または暗号化されたようなデータが付いている (" + (p.overlaySize / 1024) + "KB)",
                    "プログラムの本体の後ろにデータが付け足されています。設定や別のプログラムを隠しておく場所として使われます"
                            + " (インストーラーにもよくあります)");
        }
        // 6. 取り込む関数が極端に少ない
        int total = p.importFunctionCount + p.importOrdinalCount;
        if (total <= 6 && p.imports("GetProcAddress") && p.importsAny("LoadLibrary", "LoadLibraryEx", "GetModuleHandle")) {
            add(r, 3, "使う関数をほとんど書いていない (" + total + " 個)",
                    "使う関数を実行中に探す作りです。何をするプログラムかを分かりにくくするためによく使われます");
        } else if (total == 0 && !p.isDll) {
            add(r, 2, "使う関数がひとつも書かれていない",
                    "ふつうのプログラムは、使う関数の一覧を持っています。中身を圧縮・暗号化していると無くなります");
        }
        // 7. よく知られた圧縮・難読化の道具 (パッカー) の印
        for (PeInfo.Section sec : p.sections) {
            String n = sec.name.toLowerCase(java.util.Locale.ROOT);
            if (n.matches("\\.?upx[0-9!]?|\\.aspack|\\.adata|\\.mpress[12]|\\.petite|\\.nsp[012]")) {
                add(r, 2, "圧縮の道具の印がある (" + sec.name + ")",
                        "中身を圧縮して小さくする道具が使われています。それ自体は悪くありませんが、中身を隠すためにも使われます");
                break;
            }
            if (n.matches("\\.themida|\\.winlice|\\.vmp[0-9]|\\.enigma[12]|\\.boom|\\.ccg|\\.packed")) {
                add(r, 3, "解析を難しくする道具の印がある (" + sec.name + ")",
                        "中身を読まれないようにする道具が使われています。ウイルスにもよく使われます");
                break;
            }
        }
        // 8. 最初に動く場所 (エントリーポイント) がコードの部分の外
        if (p.entryRva != 0) {
            PeInfo.Section es = p.entrySection;
            boolean last = es != null && p.sections.size() > 1 && es == p.sections.get(p.sections.size() - 1);
            if (es == null || !es.executable() || last && !es.name.equalsIgnoreCase(".text")) {
                add(r, 3, "最初に動く場所が、ふつうと違う所にある (" + (es == null ? "どの部分にも無い" : es.name) + ")",
                        "ふつうはプログラムの命令の部分から動き始めます。後から付け足した部分から動くのは、改造や圧縮の跡です");
            }
        }
        // 9. TLS コールバック (メインの処理より先に動く仕組み)
        if (p.tlsCallbacks > 0) {
            add(r, 1, "最初の処理より前に動く仕組みがある (TLS コールバック " + p.tlsCallbacks + " 個)",
                    "ふつうのプログラムでも使いますが、調べられる前に動くために使われることもあります");
        }
        // 10. キー入力の記録・画面の取り込み
        // (GetKeyState や MapVirtualKey は、ふつうのプログラムもキーの状態を見るのに使うので数えない)
        boolean keyHook = p.imports("SetWindowsHookEx") && p.importsAny("GetKeyboardState", "ToUnicode", "ToAscii",
                "ToUnicodeEx", "ToAsciiEx", "GetKeyNameText");
        boolean keyPoll = p.imports("GetAsyncKeyState") && p.importsAny("GetForegroundWindow", "GetWindowText");
        boolean screen = p.imports("BitBlt") && p.importsAny("GetDC", "GetWindowDC") && p.imports("GetDesktopWindow")
                && p.imports("CreateCompatibleBitmap") && p.importsAny("GetDIBits", "GdipSaveImageToFile");
        if (keyHook || keyPoll || screen) {
            add(r, 3, keyHook || keyPoll ? "キーの入力を記録できる" : "画面を取り込んで保存できる",
                    keyHook || keyPoll ? "打ったキーを読み取る仕組みがあります。パスワードを盗むプログラムによくあります"
                            : "画面全体を写して保存する仕組みがあります。画面を盗み見るプログラムによくあります");
        }
        // 11. 自分のファイルを読み、末尾のデータを取り出す
        if (p.overlaySize >= 256 && p.imports("GetModuleFileName") && p.imports("CreateFile") && p.imports("ReadFile")) {
            add(r, 3, "自分自身のファイルを読み、末尾に付けたデータを使う",
                    "自分の後ろに隠した設定やプログラムを取り出す作りです。中身を隠して運ぶプログラムによくあります");
        }
        finish(r);
        return r;
    }

    private static void finish(PeReport r) {
        r.level = r.score >= PE_DANGER ? PeLevel.DANGER : r.score >= PE_CAUTION ? PeLevel.CAUTION : PeLevel.OK;
    }
}
