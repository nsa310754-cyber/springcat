package site.springcat.bionicrunner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * セキュリティ (PC の「Windows セキュリティ」のような画面)。
 * 保護の状態・スキャン (クイック / フル / カスタム)・リアルタイム保護・ウイルスの定義の更新・
 * 隔離したもの・保護の履歴をまとめて扱う。
 */
public class SecurityActivity extends Activity {

    // ----------------------------------------------- 定義の更新 (画面を閉じても続ける。1 つだけ)

    private static volatile boolean updating;
    private static volatile String updateStatus = "";
    private static volatile int updatePercent;

    private static synchronized boolean startUpdate(final Context app) {
        if (updating) return false;
        updating = true;
        updateStatus = "定義を確認しています…";
        updatePercent = 0;
        new Thread(() -> {
            try {
                String r = AvDefs.update(app, (m, p) -> {
                    if (m != null) updateStatus = m;
                    updatePercent = p;
                });
                updateStatus = "定義を更新しました。" + r.replace("\n", " / ");
            } catch (Exception e) {
                updateStatus = "更新できませんでした: " + (e.getMessage() == null ? e.toString() : e.getMessage());
            }
            updating = false;
        }, "av-update").start();
        return true;
    }

    // ------------------------------------------------------------------ 画面

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout body;
    private TextView scanStatus, scanDetail, updateText;
    private ProgressBar scanBar, updateBar;
    private LinearLayout resultsBox;
    private boolean wasScanning, wasUpdating;
    private int shownResults = -1;
    private boolean destroyed;
    private final SimpleDateFormat fmt = new SimpleDateFormat("M/d HH:mm", Locale.JAPAN);

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            boolean scanning = AvScan.running;
            if (scanning != wasScanning || updating != wasUpdating) {
                wasScanning = scanning;
                wasUpdating = updating;
                rebuild();
            } else {
                refreshProgress();
            }
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("セキュリティ");
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF0F172A);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int p = dp(12);
        body.setPadding(p, p, p, p);
        sv.addView(body);
        setContentView(sv);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 5);
        }
        RealtimeService.sync(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        wasScanning = AvScan.running;
        wasUpdating = updating;
        rebuild();
        ui.removeCallbacks(poll);
        ui.post(poll);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(poll);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacks(poll);
        super.onDestroy();
    }

    private int dp(float v) {
        return GameKeys.dp(this, v);
    }

    private boolean storageGranted() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** 画面を作り直す */
    private void rebuild() {
        body.removeAllViews();
        shownResults = -1;
        statusHeader();
        if (!storageGranted()) storageCard();
        scanCard();
        realtimeCard();
        defsCard();
        quarantineCard();
        historyCard();
        refreshProgress();
    }

    // ------------------------------------------------------------------ 部品

    private LinearLayout card(String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(12), dp(10), dp(12), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1E293B);
        bg.setCornerRadius(dp(10));
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        c.setLayoutParams(lp);
        if (title != null) {
            TextView t = text(title, 16, 0xFFFFFFFF);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            c.addView(t);
        }
        body.addView(c);
        return c;
    }

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    private Button button(String label, int color, View.OnClickListener l) {
        Button b = GameKeys.button(this, label);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(8));
        b.setBackground(bg);
        b.setTextSize(14);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(6), 0, 0);
        return r;
    }

    private String when(long t) {
        return fmt.format(new Date(t));
    }

    // ------------------------------------------------------------------ 状態

    private void statusHeader() {
        int threats = AvScan.unhandledThreats();
        List<String> warn = new ArrayList<>();
        if (!Prefs.avRealtime(this)) warn.add("リアルタイム保護がオフです");
        if (!AvDefs.ready(this)) warn.add("ウイルスの定義がありません (「定義の更新」を押してください)");
        else if (System.currentTimeMillis() - Prefs.avUpdated(this) > 7L * 24 * 3600 * 1000) warn.add("定義が古くなっています");
        if (!storageGranted()) warn.add("内部ストレージを調べる許可がありません");
        if (Prefs.avLastScan(this).isEmpty()) warn.add("まだスキャンしたことがありません");
        String title;
        int color;
        if (threats > 0) {
            title = "対処が必要です: 脅威 " + threats + " 件";
            color = 0xFF991B1B;
        } else if (!warn.isEmpty()) {
            title = "確認が必要な項目があります";
            color = 0xFF92400E;
        } else {
            title = "端末は保護されています";
            color = 0xFF166534;
        }
        LinearLayout c = card(null);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(10));
        c.setBackground(bg);
        TextView t = text("🛡 " + title, 20, 0xFFFFFFFF);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(t);
        for (String w : warn) c.addView(text("・" + w, 13, 0xFFFDE68A));
        c.addView(text("ClamAV のウイルスの定義と、YARA ルール・危険な操作の記述の検査で調べます。"
                + "ウイルス対策アプリと同じく、すべての脅威を見つけられるわけではありません。", 12, 0xFFE2E8F0));
    }

    private void storageCard() {
        LinearLayout c = card("ファイルへのアクセス");
        c.addView(text("内部ストレージ (ダウンロードなど) を調べるには、「全ファイルへのアクセス」の許可が要ります。",
                13, 0xFFCBD5E1));
        LinearLayout r = row();
        r.addView(button("許可する", 0xFF2563EB, v -> askStorage()));
        c.addView(r);
    }

    private void askStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
            } catch (RuntimeException e) {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 6);
        }
    }

    // ------------------------------------------------------------------ スキャン

    private void scanCard() {
        LinearLayout c = card("ウイルスと脅威の防止");
        String last = Prefs.avLastScan(this);
        if (!last.isEmpty()) {
            String[] f = last.split("\t");
            if (f.length == 4) {
                c.addView(text("前回: " + f[0] + " (" + when(Long.parseLong(f[1])) + ") — " + f[2] + " 個を調べ、"
                        + (f[3].equals("0") ? "脅威なし" : f[3] + " 件を検出"), 13, 0xFFCBD5E1));
            }
        }
        scanStatus = text("", 14, 0xFFFDE68A);
        scanDetail = text("", 11, 0xFF94A3B8);
        scanDetail.setSingleLine(true);
        scanDetail.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        scanBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        c.addView(scanStatus);
        c.addView(scanBar);
        c.addView(scanDetail);
        if (AvScan.running) {
            LinearLayout r = row();
            r.addView(button("中止", 0xFF7F1D1D, v -> AvScan.stop()));
            c.addView(r);
        } else {
            LinearLayout r = row();
            r.addView(button("クイックスキャン", 0xFF2563EB, v -> startScan(AvScan.Type.QUICK, null)));
            r.addView(button("フルスキャン", 0xFF1D4ED8, v -> confirmFull()));
            c.addView(r);
            LinearLayout r2 = row();
            r2.addView(button("カスタムスキャン", 0xFF374151, v -> chooseCustom()));
            c.addView(r2);
            c.addView(text("クイック: ダウンロード・ドキュメント・Bluetooth・メッセージアプリの受信ファイル・登録した"
                    + "プログラム。フル: 内部ストレージ全体・アプリの作業領域・インストール済みのアプリ。", 12, 0xFF94A3B8));
        }
        resultsBox = new LinearLayout(this);
        resultsBox.setOrientation(LinearLayout.VERTICAL);
        c.addView(resultsBox);
    }

    private void confirmFull() {
        new AlertDialog.Builder(this)
                .setTitle("フルスキャン")
                .setMessage("内部ストレージ全体と、インストール済みのアプリを調べます。ファイルの数によっては"
                        + "数十分かかります。ClamAV のエンジンは 1GB ほどメモリを使います。")
                .setPositiveButton("開始", (d, w) -> startScan(AvScan.Type.FULL, null))
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void chooseCustom() {
        final File sd = Environment.getExternalStorageDirectory();
        final String[] labels = {"内部ストレージ全体", "Download", "DCIM (写真)", "Documents",
                "アプリの作業領域 (登録したプログラムなど)", "場所を入力…"};
        new AlertDialog.Builder(this)
                .setTitle("調べる場所")
                .setItems(labels, (d, which) -> {
                    switch (which) {
                        case 0: startScan(AvScan.Type.CUSTOM, sd); break;
                        case 1: startScan(AvScan.Type.CUSTOM, new File(sd, "Download")); break;
                        case 2: startScan(AvScan.Type.CUSTOM, new File(sd, "DCIM")); break;
                        case 3: startScan(AvScan.Type.CUSTOM, new File(sd, "Documents")); break;
                        case 4: startScan(AvScan.Type.CUSTOM, getFilesDir()); break;
                        default: askPath();
                    }
                })
                .show();
    }

    private void askPath() {
        final EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setText(Environment.getExternalStorageDirectory().getAbsolutePath() + "/");
        new AlertDialog.Builder(this)
                .setTitle("調べるフォルダかファイルの場所")
                .setView(e)
                .setPositiveButton("スキャン", (d, w) -> {
                    File f = new File(e.getText().toString().trim());
                    if (!f.exists()) {
                        Toast.makeText(this, "見つかりません: " + f, Toast.LENGTH_LONG).show();
                        return;
                    }
                    startScan(AvScan.Type.CUSTOM, f);
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void startScan(AvScan.Type t, File custom) {
        if (!AvDefs.ready(this) && !updating) {
            Toast.makeText(this, "ウイルスの定義がまだありません。定義なしで調べます (先に「定義の更新」が"
                    + "おすすめです)", Toast.LENGTH_LONG).show();
        }
        if (!AvScan.start(this, t, custom == null ? null : Collections.singletonList(custom))) {
            Toast.makeText(this, "スキャンはすでに動いています", Toast.LENGTH_SHORT).show();
            return;
        }
        KeepAliveService.ensureStarted(getApplicationContext());
        rebuild();
    }

    private void refreshProgress() {
        if (scanStatus != null) {
            if (AvScan.running) {
                scanStatus.setText((AvScan.type == null ? "" : AvScan.type.label + ": ") + AvScan.phase);
                int total = Math.max(1, AvScan.total);
                scanBar.setVisibility(View.VISIBLE);
                scanBar.setMax(total);
                scanBar.setProgress(Math.min(total, AvScan.done));
                scanDetail.setText(AvScan.done + " / " + AvScan.total + "  " + AvScan.current);
                scanDetail.setVisibility(View.VISIBLE);
            } else {
                scanStatus.setText(AvScan.summary);
                scanStatus.setVisibility(AvScan.summary.isEmpty() ? View.GONE : View.VISIBLE);
                scanBar.setVisibility(View.GONE);
                scanDetail.setVisibility(View.GONE);
            }
            List<AvScan.Result> rs = AvScan.results();
            if (rs.size() != shownResults) {
                shownResults = rs.size();
                showResults(rs);
            }
        }
        if (updateText != null) {
            updateText.setText(updateStatus);
            updateText.setVisibility(updateStatus.isEmpty() ? View.GONE : View.VISIBLE);
            updateBar.setVisibility(updating ? View.VISIBLE : View.GONE);
            updateBar.setProgress(updatePercent);
        }
    }

    private void showResults(List<AvScan.Result> rs) {
        resultsBox.removeAllViews();
        if (rs.isEmpty()) return;
        resultsBox.addView(text("見つかったもの", 15, 0xFFFFFFFF));
        boolean anyThreat = false;
        for (final AvScan.Result r : rs) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(8), dp(6), dp(8), dp(6));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(r.level == AvScanner.THREAT ? 0xFF450A0A : 0xFF422006);
            bg.setCornerRadius(dp(8));
            item.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            item.setLayoutParams(lp);
            item.addView(text((r.level == AvScanner.THREAT ? "⚠ 脅威: " : "△ 疑わしい: ") + r.name, 14, 0xFFFFFFFF));
            item.addView(text((r.packageName != null ? "アプリ " + r.packageName + "\n" : "") + r.path + "\n検出: " + r.engine,
                    11, 0xFFCBD5E1));
            if (r.handled != null) {
                item.addView(text("✓ " + r.handled, 13, 0xFF86EFAC));
            } else {
                LinearLayout b = row();
                if (r.packageName != null) {
                    b.addView(button("アンインストール", 0xFF991B1B, v -> {
                        startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + r.packageName)));
                        AvScan.markHandled(r, "アンインストールを開きました");
                        shownResults = -1;
                    }));
                } else {
                    if (r.level == AvScanner.THREAT) anyThreat = true;
                    b.addView(button("隔離", 0xFF991B1B, v -> handle(r, "隔離")));
                    b.addView(button("削除", 0xFF7F1D1D, v -> handle(r, "削除")));
                }
                b.addView(button("許可", 0xFF374151, v -> handle(r, "許可")));
                item.addView(b);
            }
            resultsBox.addView(item);
        }
        if (anyThreat) {
            LinearLayout r = row();
            r.addView(button("脅威をすべて隔離", 0xFF991B1B, v -> {
                for (AvScan.Result x : AvScan.results()) {
                    if (x.handled == null && x.packageName == null && x.level == AvScanner.THREAT) handle(x, "隔離");
                }
            }));
            resultsBox.addView(r);
        }
    }

    private void handle(final AvScan.Result r, final String how) {
        final Context app = getApplicationContext();
        new Thread(() -> {
            String msg;
            File f = new File(r.path);
            try {
                switch (how) {
                    case "隔離":
                        AvScanner.quarantine(app, f, r.name);
                        msg = "隔離しました";
                        break;
                    case "削除":
                        if (!f.delete()) throw new java.io.IOException("消せません");
                        AvScanner.history(app, "削除", r.path, r.name);
                        msg = "削除しました";
                        break;
                    default:
                        Prefs.avAllow(app, AvDefs.hex(AvDefs.hashes(f)[2]));
                        AvScanner.history(app, "許可", r.path, r.name);
                        msg = "許可しました (今後は検出しません)";
                }
                AvScan.markHandled(r, msg);
            } catch (java.io.IOException e) {
                msg = how + "できませんでした: " + e.getMessage();
            }
            final String m = msg;
            ui.post(() -> {
                if (destroyed) return;
                Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
                rebuild();
            });
        }, "av-handle").start();
    }

    // ------------------------------------------------------------------ リアルタイム保護

    private void realtimeCard() {
        LinearLayout c = card("リアルタイム保護");
        c.addView(text("新しく置かれたり書き換えられたりしたファイルを、すぐに調べます。脅威は自動で隔離して"
                + "通知します。オンのあいだは通知が出たままになります (Android の決まり)。", 12, 0xFF94A3B8));
        Switch on = new Switch(this);
        on.setText("リアルタイム保護");
        on.setTextColor(0xFFFFFFFF);
        on.setChecked(Prefs.avRealtime(this));
        on.setOnCheckedChangeListener((v, checked) -> {
            Prefs.setAvRealtime(this, checked);
            RealtimeService.sync(this);
            ui.postDelayed(this::rebuild, 300);
        });
        c.addView(on);
        Switch auto = new Switch(this);
        auto.setText("脅威を自動で隔離する");
        auto.setTextColor(0xFFFFFFFF);
        auto.setChecked(Prefs.avAutoQuarantine(this));
        auto.setOnCheckedChangeListener((v, checked) -> Prefs.setAvAutoQuarantine(this, checked));
        c.addView(auto);
        StringBuilder sb = new StringBuilder("見張る場所: ");
        for (File f : RealtimeService.roots(this)) {
            sb.append(f.getAbsolutePath().replace(Environment.getExternalStorageDirectory().getAbsolutePath(), "内部ストレージ"))
                    .append("  ");
        }
        c.addView(text(sb.toString().trim(), 11, 0xFF94A3B8));
        c.addView(text("状態: " + (RealtimeService.isRunning() ? "動いています" : "止まっています"), 13,
                RealtimeService.isRunning() ? 0xFF86EFAC : 0xFFFCA5A5));
    }

    // ------------------------------------------------------------------ 定義

    private void defsCard() {
        LinearLayout c = card("ウイルスの定義");
        c.addView(text(AvEngine.available(this)
                ? "検査エンジン: ClamAV 1.5.4 (スキャンで使用) + 内蔵の検査 (リアルタイム保護で使用)"
                : "検査エンジン: 内蔵の検査 (この端末の CPU 用の ClamAV のエンジンは入っていません。"
                + "定義のハッシュ値・YARA ルール・危険な操作の記述で調べます)", 12, 0xFFCBD5E1));
        if (AvDefs.ready(this)) {
            StringBuilder sb = new StringBuilder();
            for (String n : AvDefs.DATABASES) {
                AvDefs.Header h = AvDefs.localHeader(this, n);
                if (h != null) sb.append(n).append(": 版 ").append(h.version).append(" (").append(h.date).append(")\n");
            }
            sb.append("定義の数: ").append(String.format(Locale.ROOT, "%,d", AvDefs.signatureCount(this)));
            long t = Prefs.avUpdated(this);
            if (t > 0) sb.append("\n最後に確認: ").append(when(t));
            c.addView(text(sb.toString(), 13, 0xFFE2E8F0));
        } else {
            c.addView(text("まだありません。「定義の更新」で ClamAV の公式の配布元から取ってきます (初回は約 110MB。"
                    + "Wi-Fi がおすすめです)。", 13, 0xFFFDE68A));
        }
        File yaraDir = Yara.rulesDir(this);
        c.addView(text("YARA ルール: " + Yara.rules(this).length + " 個 (" + yaraDir.getAbsolutePath() + ")", 11, 0xFF94A3B8));
        updateText = text("", 12, 0xFFFDE68A);
        updateBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        updateBar.setMax(100);
        c.addView(updateText);
        c.addView(updateBar);
        if (!updating) {
            LinearLayout r = row();
            r.addView(button("定義の更新", 0xFF2563EB, v -> {
                if (startUpdate(getApplicationContext())) rebuild();
            }));
            if (Prefs.avAllowedCount(this) > 0) {
                r.addView(button("許可の取り消し (" + Prefs.avAllowedCount(this) + ")", 0xFF374151, v -> {
                    Prefs.avClearAllowed(this);
                    rebuild();
                }));
            }
            c.addView(r);
        }
    }

    // ------------------------------------------------------------------ 隔離・履歴

    private void quarantineCard() {
        List<AvScanner.Quarantined> qs = AvScanner.quarantined(this);
        LinearLayout c = card("隔離したもの (" + qs.size() + ")");
        if (qs.isEmpty()) {
            c.addView(text("ありません", 13, 0xFF94A3B8));
            return;
        }
        c.addView(text("隔離したファイルは、中身を変換してアプリの中にしまってあります (実行も開くこともできません)。",
                12, 0xFF94A3B8));
        for (final AvScanner.Quarantined q : qs) {
            c.addView(text(q.threat + "\n" + q.path + " (" + when(q.time) + ")", 12, 0xFFE2E8F0));
            LinearLayout r = row();
            r.addView(button("完全に削除", 0xFF7F1D1D, v -> {
                AvScanner.deleteQuarantined(this, q, true);
                rebuild();
            }));
            r.addView(button("復元", 0xFF374151, v -> new AlertDialog.Builder(this)
                    .setTitle("復元")
                    .setMessage(q.path + " に戻します。脅威と判定されたファイルです。今後は検出しなくなります。")
                    .setPositiveButton("復元", (d, w) -> {
                        try {
                            File f = AvScanner.restore(this, q);
                            Toast.makeText(this, "復元しました: " + f, Toast.LENGTH_LONG).show();
                        } catch (java.io.IOException e) {
                            Toast.makeText(this, "復元できませんでした: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                        rebuild();
                    })
                    .setNegativeButton("キャンセル", null)
                    .show()));
            c.addView(r);
        }
    }

    private void historyCard() {
        List<String[]> hs = AvScanner.history(this, 40);
        LinearLayout c = card("保護の履歴");
        if (hs.isEmpty()) {
            c.addView(text("まだありません", 13, 0xFF94A3B8));
            return;
        }
        for (String[] h : hs) {
            long t;
            try {
                t = Long.parseLong(h[0]);
            } catch (NumberFormatException e) {
                continue;
            }
            c.addView(text(when(t) + "  " + h[1] + ": " + h[3] + "\n" + h[2], 11, 0xFFCBD5E1));
        }
    }
}
