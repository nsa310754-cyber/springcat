package site.ragdollp.binrunner;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 画面:ファイルを選ぶ → 中身で種別/アーキ/libc を判定 → 直接実行 or 変換実行を振り分け。
 *
 * ユーザーの要望「拡張子が分からなくてもアップロードして自動判定」を満たすため、
 * 判定は拡張子を一切見ず、ファイル先頭のマジックナンバーと ELF ヘッダだけで行う。
 */
public class MainActivity extends Activity {

    private static final int PICK = 1;

    private TextView log;
    private EditText genText;
    private Button btnRun, btnRoot, btnExtract;
    private File payload;                 // 実行可能領域へコピーした解析対象
    private BinaryAnalyzer.Result result;
    private DecisionEngine.Plan plan;

    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.parseColor("#0F1419"));

        TextView title = new TextView(this);
        title.setText("BinRunner — 実行ファイル自動判定");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("端末 ABI: " + join(Build.SUPPORTED_ABIS)
                + "\ntargetSdk 28 のため root 無しでも直接実行を試みます"
                + "\nsu 検出: " + (Executor.hasRoot() ? "あり" : "標準パスでは未検出"));
        sub.setTextColor(Color.parseColor("#9AA7B2"));
        sub.setTextSize(12);
        sub.setPadding(0, dp(4), 0, dp(12));
        root.addView(sub);

        Button pick = mkBtn("① ファイルを選ぶ(.exe / .tar.gz / 何でも)");
        pick.setOnClickListener(v -> pickFile());
        root.addView(pick);

        btnExtract = mkBtn("②-A 展開して実行ファイルを取り出す(deb/zip/tar/gz/ar)");
        btnExtract.setEnabled(false);
        btnExtract.setOnClickListener(v -> extractAndReanalyze());
        root.addView(btnExtract);

        btnRun = mkBtn("② 実行 / 変換実行");
        btnRun.setEnabled(false);
        btnRun.setOnClickListener(v -> run(false));
        root.addView(btnRun);

        btnRoot = mkBtn("②-B root(su)で実行");
        btnRoot.setEnabled(false);
        btnRoot.setOnClickListener(v -> run(true));
        root.addView(btnRoot);

        Button rootCheck = mkBtn("🔑 root(su)を確認する");
        rootCheck.setOnClickListener(v -> {
            println("\nroot を確認中…(許可ダイアログが出たら許可してください)");
            new Thread(() -> {
                String s = Executor.probeRoot();
                ui.post(() -> println("結果: " + s));
            }).start();
        });
        root.addView(rootCheck);

        // --- Bionic ネイティブ実行ファイル生成 ---
        TextView genLabel = new TextView(this);
        genLabel.setText("③ Bionic ネイティブ実行ファイルを作る(入力文を表示して終了する静的 ELF)");
        genLabel.setTextColor(Color.parseColor("#9AA7B2"));
        genLabel.setTextSize(12);
        genLabel.setPadding(0, dp(10), 0, dp(2));
        root.addView(genLabel);

        genText = new EditText(this);
        genText.setHint("出力する文字列(例: Hello from BinRunner)");
        genText.setText("Hello from BinRunner");
        genText.setTextColor(Color.WHITE);
        genText.setHintTextColor(Color.parseColor("#6B7785"));
        genText.setSingleLine(true);
        root.addView(genText);

        Button gen = mkBtn("③ 生成する → そのまま②で実行できます");
        gen.setOnClickListener(v -> generate());
        root.addView(gen);

        log = new TextView(this);
        log.setTextColor(Color.parseColor("#D6E2EC"));
        log.setTextSize(13);
        log.setTypeface(Typeface.MONOSPACE);
        log.setMovementMethod(new ScrollingMovementMethod());
        log.setText("ファイルを選ぶと、拡張子ではなく中身を見て種類を判定します。\n");
        ScrollView sv = new ScrollView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(12);
        sv.setLayoutParams(lp);
        sv.setBackgroundColor(Color.parseColor("#161C24"));
        sv.setPadding(dp(10), dp(10), dp(10), dp(10));
        sv.addView(log);
        root.addView(sv);

        setContentView(root);
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == PICK && res == RESULT_OK && data != null && data.getData() != null) {
            copyAndAnalyze(data.getData());
        }
    }

    /** 選んだファイルを実行可能領域へコピーしてから判定する。 */
    private void copyAndAnalyze(Uri uri) {
        setBusy(true);
        println("\n読み込み中…");
        new Thread(() -> {
            try {
                File dst = new File(getCodeCacheDir(), "payload.bin");
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream outS = new FileOutputStream(dst)) {
                    copy(in, outS);
                }
                payload = dst;
                analyze();
            } catch (Exception e) {
                ui.post(() -> { println("読み込み失敗: " + e.getMessage()); setBusy(false); });
            }
        }).start();
    }

    private void analyze() {
        result = BinaryAnalyzer.analyze(payload);
        plan = DecisionEngine.decide(result);
        ui.post(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("\n── 判定 ──\n");
            sb.append("サイズ : ").append(result.size).append(" バイト\n");
            sb.append("種別   : ").append(result.oneLine()).append('\n');
            if (result.kind == BinaryAnalyzer.Kind.ELF && result.interpreter != null)
                sb.append("リンカ : ").append(result.interpreter).append('\n');
            sb.append('\n').append(plan.title).append('\n').append(plan.detail).append('\n');
            println(sb.toString());

            btnExtract.setEnabled(plan.action == DecisionEngine.Action.EXTRACT);
            boolean canRun = plan.action != DecisionEngine.Action.UNSUPPORTED
                    && plan.action != DecisionEngine.Action.EXTRACT;
            btnRun.setEnabled(canRun);
            btnRoot.setEnabled(canRun && result.kind == BinaryAnalyzer.Kind.ELF);
            setBusy(false);
        });
    }

    /** deb / zip / tar / gzip / ar を展開し、中の ELF 実行ファイルを取り出して再判定する。 */
    private void extractAndReanalyze() {
        if (result == null) return;
        setBusy(true);
        println("\n展開中…");
        final BinaryAnalyzer.Kind kind = result.kind;
        new Thread(() -> {
            try {
                File dir = getCodeCacheDir();
                File src = payload;
                Archive.Found fnd;
                switch (kind) {
                    case DEB: fnd = Archive.extractDeb(src, dir);  break;
                    case ZIP: fnd = Archive.extractZip(src, dir);  break;
                    case TAR: fnd = Archive.extractTar(src, dir);  break;
                    case AR:  fnd = Archive.extractDeb(src, dir);  break; // ar も data.tar を探す
                    case GZIP:
                    default:  fnd = Archive.extractGzip(src, dir); break;
                }
                final Archive.Found f = fnd;
                if (f.file == null) {
                    markForbidden(f.note);   // 実行ファイルを含まない=ただのデータ → 🚫
                    return;
                }
                payload = f.file;
                ui.post(() -> println("\n" + f.note));
                analyze();   // 取り出したファイルを判定し直す(実行ボタンが有効化される)
            } catch (Exception e) {
                ui.post(() -> { println("展開失敗: " + e.getMessage()); setBusy(false); });
            }
        }).start();
    }

    /** 入力文字列を表示して終了する静的 ELF(Bionic ネイティブ実行ファイル)を生成する。 */
    private void generate() {
        setBusy(true);
        final String text = genText.getText().toString();
        final String abi = DecisionEngine.primaryAbi();
        println("\n── 生成 ──\n端末 ABI 向けに生成します: " + abi);
        new Thread(() -> {
            try {
                ElfGenerator.Gen g = ElfGenerator.forAbi(abi, text);
                File dst = new File(getCodeCacheDir(), "payload.bin");
                try (OutputStream os = new FileOutputStream(dst)) { os.write(g.bytes); }
                // 取り出し用に外部領域へもコピー(ファイルマネージャから共有できる)
                File shareDir = getExternalFilesDir(null);
                String sharePath = "(外部領域なし)";
                if (shareDir != null) {
                    File share = new File(shareDir, "bionic-" + g.arch.replaceAll("[^A-Za-z0-9]", "") + ".elf");
                    try (OutputStream os = new FileOutputStream(share)) { os.write(g.bytes); }
                    sharePath = share.getAbsolutePath();
                }
                payload = dst;
                final String sp = sharePath;
                final int bytes = g.bytes.length;
                final String arch = g.arch;
                ui.post(() -> {
                    println("生成完了: " + arch + " 静的 ELF / " + bytes + " バイト");
                    println("保存先(取り出し用): " + sp);
                    println("※ Windows の .exe ではなく Android で直接動くネイティブ実行ファイルです。");
                });
                analyze();   // 生成物を判定(静的 ELF と表示され、②で実行可能に)
            } catch (Exception e) {
                ui.post(() -> { println("生成失敗: " + e.getMessage()); setBusy(false); });
            }
        }).start();
    }

    private void run(boolean preferRoot) {
        if (payload == null || plan == null) return;
        setBusy(true);
        println("\n── 実行 ──");
        new Thread(() -> {
            Executor.Out o;
            switch (plan.action) {
                case RUN_DIRECT:
                    o = Executor.runDirect(payload, preferRoot);
                    break;
                case CONVERT_PROOT:
                    o = Executor.runVia("proot", payload);
                    break;
                case CONVERT_TRANSLATE:
                    o = Executor.runVia(result.eMachine == 62 ? "box64"
                            : result.eMachine == 3 ? "box86" : "qemu-aarch64", payload);
                    break;
                case CONVERT_WINE:
                    o = Executor.runVia("wine", payload);
                    break;
                default:
                    o = new Executor.Out();
                    o.log = "この種別は実行できません。";
            }
            final Executor.Out fo = o;
            ui.post(() -> { println(fo.log); setBusy(false); });
        }).start();
    }

    // --- helpers ---
    private Button mkBtn(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    /** 実行対象を含まない「ただのデータ/圧縮ファイル」に 🚫 を付け、実行系ボタンを全て塞ぐ。 */
    private void markForbidden(String reason) {
        ui.post(() -> {
            println("\n🚫 実行できません — 実行ファイルを含まない、ただのデータ/圧縮ファイルです。");
            if (reason != null && !reason.isEmpty()) println(reason);
            btnExtract.setEnabled(false);
            btnRun.setEnabled(false);
            btnRoot.setEnabled(false);
        });
    }

    private void setBusy(boolean busy) {
        // コピー/解析中はボタンを一時的に無効化(選択ボタン以外)
        if (busy) { btnRun.setEnabled(false); btnRoot.setEnabled(false); btnExtract.setEnabled(false); }
    }

    private void println(String s) {
        log.append(s + "\n");
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String join(String[] a) {
        if (a == null) return "?";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(", "); sb.append(a[i]); }
        return sb.toString();
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
    }
}
