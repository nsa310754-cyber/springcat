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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;

/**
 * 画面:ファイルを選ぶ → 中身で種別/アーキ/libc を判定 → 直接実行 or 変換実行を振り分け。
 *
 * ユーザーの要望「拡張子が分からなくてもアップロードして自動判定」を満たすため、
 * 判定は拡張子を一切見ず、ファイル先頭のマジックナンバーと ELF ヘッダだけで行う。
 */
public class MainActivity extends Activity {

    private static final int PICK = 1;

    private TextView log;
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
                + "\nroot: " + (Executor.hasRoot() ? "あり" : "なし"));
        sub.setTextColor(Color.parseColor("#9AA7B2"));
        sub.setTextSize(12);
        sub.setPadding(0, dp(4), 0, dp(12));
        root.addView(sub);

        Button pick = mkBtn("① ファイルを選ぶ(.exe / .tar.gz / 何でも)");
        pick.setOnClickListener(v -> pickFile());
        root.addView(pick);

        btnExtract = mkBtn("②-A 展開して再判定(gzip)");
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

            btnExtract.setEnabled(plan.action == DecisionEngine.Action.EXTRACT
                    && result.kind == BinaryAnalyzer.Kind.GZIP);
            boolean canRun = plan.action != DecisionEngine.Action.UNSUPPORTED
                    && plan.action != DecisionEngine.Action.EXTRACT;
            btnRun.setEnabled(canRun);
            btnRoot.setEnabled(canRun && result.kind == BinaryAnalyzer.Kind.ELF);
            setBusy(false);
        });
    }

    /** gzip を展開して中の 1 ファイルを再判定する。 */
    private void extractAndReanalyze() {
        setBusy(true);
        println("\ngzip を展開中…");
        new Thread(() -> {
            try {
                File dst = new File(getCodeCacheDir(), "payload.bin");
                File tmp = new File(getCodeCacheDir(), "inner.bin");
                try (InputStream in = new GZIPInputStream(new java.io.FileInputStream(dst));
                     OutputStream outS = new FileOutputStream(tmp)) {
                    copy(in, outS);
                }
                // 中身が tar(複数ファイル)なら 1 本には絞れない旨を案内する
                BinaryAnalyzer.Result inner = BinaryAnalyzer.analyze(tmp);
                if (inner.kind == BinaryAnalyzer.Kind.TAR) {
                    ui.post(() -> {
                        println("\n展開結果は tar(複数ファイルの書庫)でした。\n"
                                + "tar の中から実行したい 1 ファイルを取り出して選び直してください\n"
                                + "(例: Files アプリや Termux の `tar xf` で展開)。");
                        setBusy(false);
                    });
                    return;
                }
                tmp.renameTo(dst);
                payload = dst;
                analyze();
            } catch (Exception e) {
                ui.post(() -> { println("展開失敗: " + e.getMessage()); setBusy(false); });
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
