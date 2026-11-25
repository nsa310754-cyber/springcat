package site.springcat.bionicrunner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Windows 11 (ARM64 版) の仮想マシンの準備と起動の画面 (試験中)。
 * 部品のダウンロード・Windows の ISO の選択・仮想ディスクの作成・設定・起動と停止をする。
 */
public class WinVmActivity extends Activity {

    private static final int REQ_ISO = 51;
    private static final int[] MEMORY = {3072, 4096, 6144};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView requirement, efiStatus, virtioStatus, isoStatus, diskStatus, memWarning, runStatus, log;
    private Button efiButton, virtioButton, diskButton, startButton, screenButton, stopButton;
    private ProgressBar bar;
    private RadioGroup memory, cpuGroup;
    private CheckBox attachIso, attachTools, network, virtioGpu;
    private final List<String[]> cpuChoices = new ArrayList<>();
    private boolean busy;
    private WinVm.Config cfg;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Windows 11 の仮想マシン");
        cfg = WinVm.config(this);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ 画面

    private View buildUi() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(14), dp(16), dp(24));

        TextView title = new TextView(this);
        title.setText("Windows 11 (ARM64) の仮想マシン (試験中)");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);
        col.addView(note("端末の CPU の仮想化の機能 (KVM) を使って、ARM64 版の Windows 11 を仮想マシンの中で動かします。\n"
                + "・KVM はアプリの権限では使えないので、仮想マシン (QEMU) は root で動きます\n"
                + "・Windows の画面は、この端末の中だけ (127.0.0.1) で待ち受ける VNC に、毎回作るパスワードを付けて映します\n"
                + "・ネットワークは初めは無しです (設定で付けられます)"));
        TextView license = note("ライセンスについて: Windows は試すことはできますが、使い続けるにはライセンス (プロダクトキー) が"
                + "必要です。Windows の ISO はアプリに入っていません。Microsoft の公式サイトから ARM64 版をご自分でダウンロードしてください。");
        license.setTextColor(0xFFFDE68A);
        col.addView(license);

        col.addView(heading("必要なもの"));
        requirement = note("");
        requirement.setTextColor(0xFFE5E7EB);
        col.addView(requirement);
        col.addView(button("KVM の診断を開く (仮想マシンの画面)", 0xFF475569,
                v -> startActivity(new Intent(this, VmActivity.class))));

        col.addView(heading("1. 起動の仕組み (UEFI)"));
        efiStatus = status();
        col.addView(efiStatus);
        efiButton = button("UEFI をダウンロード (Debian の qemu-efi-aarch64、約 5MB)", 0xFF2563EB, v -> prepareEfi());
        col.addView(efiButton);

        col.addView(heading("2. ドライバ (virtio-win)"));
        col.addView(note("ネットワークや画面のドライバの CD です (Fedora のプロジェクトの公式の配布)。Windows のインストールには"
                + "無くても進めますが、ネットワークを使うには要ります。"));
        virtioStatus = status();
        col.addView(virtioStatus);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        col.addView(bar);
        virtioButton = button("virtio-win " + WinVm.VIRTIO[0] + " をダウンロード (約 840MB)", 0xFF2563EB, v -> prepareVirtio());
        col.addView(virtioButton);

        col.addView(heading("3. Windows 11 の ISO (ARM64 版)"));
        col.addView(note("Microsoft の公式サイトの「Windows 11 (Arm64) のディスク イメージ (ISO) をダウンロードする」から、"
                + "ご自分でダウンロードして、端末の「ダウンロード」に置いてください。アプリが代わりに取ってくることはしません。"));
        isoStatus = status();
        col.addView(isoStatus);
        col.addView(button("ISO を選ぶ…", 0xFF2563EB, v -> chooseIso()));

        col.addView(heading("4. 仮想ディスク"));
        col.addView(note("64GB の仮想ディスク (qcow2) を内部ストレージに作ります。中身の分だけ大きくなる形なので、作った直後は"
                + "256KB です。Windows を入れると 20〜30GB ほどになります。"));
        diskStatus = status();
        col.addView(diskStatus);
        diskButton = button("仮想ディスクを作る (64GB)", 0xFF2563EB, v -> createDisk());
        col.addView(diskButton);

        col.addView(heading("5. 設定"));
        col.addView(note(WinVm.memoryInfo(this)));
        memory = new RadioGroup(this);
        memory.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < MEMORY.length; i++) {
            RadioButton r = radio("メモリ " + (MEMORY[i] / 1024.0 == Math.floor(MEMORY[i] / 1024.0)
                    ? (MEMORY[i] / 1024) + "GB" : MEMORY[i] + "MB"));
            r.setId(i + 1);
            memory.addView(r);
            if (MEMORY[i] == cfg.memoryMb) memory.check(i + 1);
        }
        if (memory.getCheckedRadioButtonId() < 0) memory.check(2);
        memory.setOnCheckedChangeListener((g, id) -> {
            cfg.memoryMb = MEMORY[id - 1];
            saveCfg();
            refreshMemory();
        });
        col.addView(memory);
        memWarning = note("");
        memWarning.setTextColor(0xFFFCA5A5);
        col.addView(memWarning);

        col.addView(note("キーボードの配列 (Windows の側の設定に合わせます。合っていないと記号がずれます):"));
        RadioGroup kb = new RadioGroup(this);
        kb.setOrientation(LinearLayout.HORIZONTAL);
        RadioButton ja = radio("日本語 (JIS)"), us = radio("英語 (US)");
        ja.setId(301);
        us.setId(302);
        kb.addView(ja);
        kb.addView(us);
        kb.check("en-us".equals(cfg.keyboard) ? 302 : 301);
        kb.setOnCheckedChangeListener((g, id) -> {
            cfg.keyboard = id == 302 ? "en-us" : "ja";
            saveCfg();
        });
        col.addView(kb);

        col.addView(note("仮想 CPU (KVM の診断で安定していた組み合わせから選びます):"));
        cpuGroup = new RadioGroup(this);
        col.addView(cpuGroup);

        attachIso = check("Windows の ISO を付ける (インストールが終わったら外す)", cfg.attachIso, b -> {
            cfg.attachIso = b;
            saveCfg();
        });
        col.addView(attachIso);
        attachTools = check("要件の確認 (TPM・セキュアブートなど) を外す設定ファイルを付ける", cfg.attachTools, b -> {
            cfg.attachTools = b;
            saveCfg();
        });
        col.addView(attachTools);
        network = check("ネットワークを付ける (NAT。virtio-win の NetKVM のドライバが要ります)", cfg.network, b -> {
            if (b) {
                new AlertDialog.Builder(this)
                        .setTitle("ネットワークを付ける")
                        .setMessage("Windows からインターネットへ出られるようになります。\n\n注意: QEMU の仕組み上、中から 10.0.2.2 へ"
                                + "つなぐと、この端末の中で待ち受けているもの (127.0.0.1 のサービス) にも届いてしまいます。")
                        .setPositiveButton("付ける", (d, w) -> {
                            cfg.network = true;
                            saveCfg();
                        })
                        .setNegativeButton("やめる", (d, w) -> network.setChecked(false))
                        .setOnCancelListener(d -> network.setChecked(false))
                        .show();
            } else {
                cfg.network = false;
                saveCfg();
            }
        });
        col.addView(network);
        virtioGpu = check("画面を virtio-gpu にする (Windows に viogpudo のドライバを入れたあと。大きい画面にできる)",
                cfg.virtioGpu, b -> {
                    cfg.virtioGpu = b;
                    saveCfg();
                });
        col.addView(virtioGpu);

        col.addView(heading("6. 起動"));
        runStatus = status();
        col.addView(runStatus);
        startButton = button("起動する", 0xFF16A34A, v -> start());
        col.addView(startButton);
        screenButton = button("画面を開く", 0xFF2563EB, v -> startActivity(new Intent(this, WinVmScreenActivity.class)));
        col.addView(screenButton);
        stopButton = button("シャットダウン (Windows に電源ボタンを押したと伝える)", 0xFFB45309, v -> powerdown());
        col.addView(stopButton);
        col.addView(button("強制終了 (保存していない内容は消えます)", 0xFF7F1D1D, v -> confirmForceStop()));
        log = note("");
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextColor(0xFFD1D5DB);
        col.addView(log);

        col.addView(heading("Windows 11 の要件の確認を外す手順"));
        col.addView(note(WinVm.guideText()));
        col.addView(note("インストールの始めに「Press any key to boot from CD or DVD…」と出たら、5 秒以内に画面を触るか、"
                + "Enter を押してください (押さないと CD から起動しません)。"));

        col.addView(heading("後片付け"));
        col.addView(button("仮想ディスクを削除 (Windows ごと消えます)", 0xFF7F1D1D, v -> confirmRemoveDisk()));
        col.addView(button("Windows の仮想マシンの部品をすべて削除", 0xFF7F1D1D, v -> confirmRemoveAll()));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF111827);
        sv.addView(col);
        return sv;
    }

    private TextView heading(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFF93C5FD);
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(20), 0, dp(6));
        return t;
    }

    private TextView note(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0x99FFFFFF);
        t.setTextSize(12);
        t.setPadding(0, dp(4), 0, dp(4));
        t.setTextIsSelectable(true);
        return t;
    }

    private TextView status() {
        TextView t = note("");
        t.setTextColor(0xFFE5E7EB);
        return t;
    }

    private RadioButton radio(String label) {
        RadioButton r = new RadioButton(this);
        r.setText(label);
        r.setTextColor(0xFFE5E7EB);
        r.setTextSize(13);
        return r;
    }

    private interface OnCheck {
        void changed(boolean on);
    }

    private CheckBox check(String label, boolean on, OnCheck l) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextColor(0xFFE5E7EB);
        c.setTextSize(13);
        c.setChecked(on);
        c.setOnCheckedChangeListener((b, isChecked) -> l.changed(isChecked));
        return c;
    }

    private Button button(String label, int color, View.OnClickListener l) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(8));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        b.setMinHeight(dp(48));
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private void saveCfg() {
        try {
            WinVm.save(this, cfg);
        } catch (IOException e) {
            toast("設定を保存できません: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 状態

    private void refresh() {
        KvmCheck.Plan plan = KvmCheck.savedAny(this);
        if (plan == null) {
            requirement.setText("✖ KVM の診断がまだです (または KVM が使えませんでした)。「仮想マシン」の画面で"
                    + "「KVM の診断」をしてください。root が必要です");
        } else {
            requirement.setText("✔ KVM: " + (plan.root ? "root で使えます" : "使えます") + " (診断で一番よかった設定: "
                    + plan.label + ")");
        }
        efiStatus.setText(WinVm.efiReady(this) ? "✔ 用意できています (" + WinVm.EFI_PACKAGE[0] + " "
                + WinVm.EFI_PACKAGE[1] + "、SHA-256 照合済み)" : "まだです");
        efiButton.setEnabled(!busy);
        virtioStatus.setText(WinVm.virtioReady(this) ? "✔ 用意できています (virtio-win " + WinVm.VIRTIO[0]
                + "、SHA-256 照合済み)" : "まだです (無くても起動できます)");
        virtioButton.setEnabled(!busy && !WinVm.virtioReady(this));
        if (cfg.iso.isEmpty()) {
            isoStatus.setText("まだ選んでいません");
        } else {
            File f = new File(cfg.iso);
            String w = WinVm.isoNameWarning(cfg.iso);
            isoStatus.setText(cfg.iso + (f.isFile() ? " (" + Formatter.formatShortFileSize(this, f.length()) + ")" : "")
                    + (w != null ? "\n⚠ " + w : ""));
        }
        if (WinVm.disk(this).isFile()) {
            diskStatus.setText("✔ あります (使っている大きさ: " + Formatter.formatShortFileSize(this, WinVm.diskUsed(this))
                    + " / 64GB)");
            diskButton.setEnabled(false);
        } else {
            diskStatus.setText("まだです (端末の空き: " + Formatter.formatShortFileSize(this,
                    getFilesDir().getUsableSpace()) + ")");
            diskButton.setEnabled(!busy);
        }
        refreshMemory();
        refreshCpus(plan);
        runStatus.setText("確かめています…");
        new Thread(() -> {
            int port = WinVm.runningPort(this);
            ui.post(() -> {
                boolean running = port > 0;
                runStatus.setText(running ? "▶ 動いています (VNC 127.0.0.1:" + port + ")" : "止まっています");
                startButton.setEnabled(!busy && !running);
                screenButton.setEnabled(running);
                stopButton.setEnabled(running);
            });
        }, "winvm-status").start();
    }

    private void refreshMemory() {
        String w = WinVm.memoryWarning(this, cfg.memoryMb);
        memWarning.setText(w == null ? "" : "⚠ " + w);
        memWarning.setVisibility(w == null ? View.GONE : View.VISIBLE);
    }

    /** 仮想 CPU の選択肢: CPU のコアの種類ごとのまとまりから (A510 のような遅いコアは、診断で止まったので除く) */
    private void refreshCpus(KvmCheck.Plan plan) {
        cpuChoices.clear();
        cpuGroup.setOnCheckedChangeListener(null);
        cpuGroup.removeAllViews();
        int[] code = new int[1];
        List<KvmCheck.Group> gs = KvmCheck.groups(KvmCheck.run(java.util.Arrays.asList(
                KvmCheck.tool(this).getAbsolutePath(), "cores"), null, 10, code));
        long maxFreq = 0;
        for (KvmCheck.Group g : gs) maxFreq = Math.max(maxFreq, g.maxFreqKhz);
        for (KvmCheck.Group g : gs) {
            if (gs.size() > 1 && g.maxFreqKhz * 10 < maxFreq * 7) continue; // 遅いまとまり (A510 など)
            int n = Math.min(2, g.count);
            cpuChoices.add(new String[]{g.cpus, String.valueOf(n), g.name + " (" + g.cpus + ") に固定・仮想 CPU " + n + " 個"});
        }
        if (plan != null && plan.cpus != null) {
            boolean seen = false;
            for (String[] ch : cpuChoices) if (ch[0].equals(plan.cpus) && ch[1].equals(String.valueOf(plan.smp))) seen = true;
            if (!seen) cpuChoices.add(new String[]{plan.cpus, String.valueOf(plan.smp), "診断の設定: " + plan.label});
        }
        int checked = -1;
        for (int i = 0; i < cpuChoices.size(); i++) {
            String[] ch = cpuChoices.get(i);
            RadioButton r = radio(ch[2] + (i == 0 ? " (おすすめ)" : ""));
            r.setId(200 + i);
            cpuGroup.addView(r);
            if (ch[0].equals(cfg.cpus) && ch[1].equals(String.valueOf(cfg.smp))) checked = 200 + i;
        }
        if (checked < 0 && !cpuChoices.isEmpty()) {
            checked = 200;
            cfg.cpus = cpuChoices.get(0)[0];
            cfg.smp = Integer.parseInt(cpuChoices.get(0)[1]);
        }
        if (checked >= 0) cpuGroup.check(checked);
        cpuGroup.setOnCheckedChangeListener((g, id) -> {
            int i = id - 200;
            if (i < 0 || i >= cpuChoices.size()) return;
            cfg.cpus = cpuChoices.get(i)[0];
            cfg.smp = Integer.parseInt(cpuChoices.get(i)[1]);
            saveCfg();
        });
    }

    private void logLine(String s) {
        ui.post(() -> log.append(s + "\n"));
    }

    // ------------------------------------------------------------------ 準備

    private void prepareEfi() {
        if (busy) return;
        busy = true;
        refresh();
        new Thread(() -> {
            String err = null;
            try {
                WinVm.prepareEfi(this, this::logLine);
            } catch (IOException e) {
                err = e.getMessage();
            }
            final String e2 = err;
            ui.post(() -> {
                busy = false;
                if (e2 != null) toast("UEFI を用意できませんでした: " + e2);
                refresh();
            });
        }, "winvm-efi").start();
    }

    private void prepareVirtio() {
        if (busy) return;
        busy = true;
        bar.setVisibility(View.VISIBLE);
        bar.setProgress(0);
        refresh();
        new Thread(() -> {
            String err = null;
            try {
                WinVm.prepareVirtio(this, this::logLine, (m, p) -> ui.post(() -> bar.setProgress(p)));
            } catch (IOException e) {
                err = e.getMessage();
            }
            final String e2 = err;
            ui.post(() -> {
                busy = false;
                bar.setVisibility(View.GONE);
                if (e2 != null) toast("virtio-win を用意できませんでした: " + e2);
                refresh();
            });
        }, "winvm-virtio").start();
    }

    private void createDisk() {
        long free = getFilesDir().getUsableSpace();
        String msg = "64GB の仮想ディスクを作ります (作った直後は 256KB。Windows を入れると 20〜30GB ほどになります)。";
        if (free < WinVm.DISK_MIN_FREE) {
            msg += "\n\n⚠ 端末の空き (" + Formatter.formatShortFileSize(this, free) + ") が少ないです。Windows を入れる途中で"
                    + "空きが無くなると、インストールが失敗します。32GB 以上空けてからをおすすめします。";
        }
        new AlertDialog.Builder(this)
                .setTitle("仮想ディスクを作る")
                .setMessage(msg)
                .setPositiveButton("作る", (d, w) -> {
                    try {
                        if (!WinVm.efiReady(this)) throw new IOException("先に UEFI を用意してください");
                        WinVm.createDisk(this);
                        toast("仮想ディスクを作りました");
                    } catch (IOException e) {
                        toast("作れませんでした: " + e.getMessage());
                    }
                    refresh();
                })
                .setNegativeButton("やめる", null)
                .show();
    }

    // ------------------------------------------------------------------ ISO

    private void chooseIso() {
        List<File> files = WinVm.isoCandidates();
        List<String> labels = new ArrayList<>();
        for (File f : files) labels.add(f.getName() + " (" + Formatter.formatShortFileSize(this, f.length()) + ")");
        labels.add("ほかのファイルを選ぶ…");
        new AlertDialog.Builder(this)
                .setTitle("Windows 11 の ISO (ARM64 版)")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which < files.size()) {
                        setIso(files.get(which).getAbsolutePath());
                    } else {
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");
                        startActivityForResult(i, REQ_ISO);
                    }
                })
                .setNegativeButton("やめる", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_ISO || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        String path = pathOf(data.getData());
        if (path == null) {
            new AlertDialog.Builder(this)
                    .setTitle("この選び方では使えません")
                    .setMessage("仮想マシン (QEMU) は、ファイルを端末の中の場所で読みます。選んだ場所からは、それが分かりません。\n\n"
                            + "ファイルの選択の画面で「内部ストレージ」(端末の名前) から ISO をたどって選ぶか、ISO を「ダウンロード」に"
                            + "置いて、一覧から選んでください。")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        setIso(path);
    }

    /** ファイルの選択の画面が返した場所を、端末の中の場所に直す (分からなければ null) */
    private static String pathOf(Uri uri) {
        try {
            String id = DocumentsContract.getDocumentId(uri);
            String auth = uri.getAuthority();
            if ("com.android.externalstorage.documents".equals(auth) && id.startsWith("primary:")) {
                return "/storage/emulated/0/" + id.substring(8);
            }
            if (id.startsWith("raw:")) return id.substring(4);
        } catch (IllegalArgumentException ignored) {
            // 文書の場所ではない
        }
        return null;
    }

    private void setIso(String path) {
        cfg.iso = path;
        cfg.attachIso = true;
        attachIso.setChecked(true);
        saveCfg();
        String w = WinVm.isoNameWarning(path);
        if (w != null) toast(w);
        refresh();
    }

    // ------------------------------------------------------------------ 起動と停止

    private void start() {
        if (busy) return;
        if (KvmCheck.savedAny(this) == null) {
            toast("先に「仮想マシン」の画面で KVM の診断をしてください");
            return;
        }
        if (!WinVm.efiReady(this) || !WinVm.disk(this).isFile()) {
            toast("先に UEFI と仮想ディスクを用意してください");
            return;
        }
        if (cfg.attachIso && cfg.iso.isEmpty() && WinVm.diskUsed(this) < 1024L * 1024 * 1024) {
            toast("Windows の ISO を選んでください (まだ Windows が入っていません)");
            return;
        }
        String w = WinVm.memoryWarning(this, cfg.memoryMb);
        if (w != null) {
            new AlertDialog.Builder(this)
                    .setTitle("メモリについて")
                    .setMessage(w + "\n\nこのまま起動しますか？")
                    .setPositiveButton("起動する", (d, x) -> doStart())
                    .setNegativeButton("やめる", null)
                    .show();
            return;
        }
        doStart();
    }

    private void doStart() {
        busy = true;
        log.setText("");
        refresh();
        new Thread(() -> {
            String err = null;
            if (!RootShell.probeRoot()) {
                err = "root が使えません (KVM はアプリの権限では使えないため、root が必要です)";
            } else {
                try {
                    WinVm.start(this, cfg, this::logLine);
                } catch (IOException e) {
                    err = e.getMessage();
                }
            }
            final String e2 = err;
            ui.post(() -> {
                busy = false;
                refresh();
                if (e2 != null) {
                    logLine(e2);
                    new AlertDialog.Builder(this)
                            .setTitle("起動できませんでした")
                            .setMessage(e2)
                            .setPositiveButton("OK", null)
                            .show();
                    return;
                }
                logLine("起動しました。画面を開きます");
                startActivity(new Intent(this, WinVmScreenActivity.class));
            });
        }, "winvm-start").start();
    }

    private void powerdown() {
        new Thread(() -> {
            String out = WinVm.powerdown(this);
            ui.post(() -> {
                logLine("Windows にシャットダウンを伝えました。" + (out.isEmpty() ? "" : " " + out));
                toast("Windows にシャットダウンを伝えました (止まるまで少しかかります)");
                ui.postDelayed(this::refresh, 5000);
            });
        }, "winvm-powerdown").start();
    }

    private void confirmForceStop() {
        new AlertDialog.Builder(this)
                .setTitle("強制終了")
                .setMessage("仮想マシンをすぐに止めます。Windows で保存していない内容は消え、Windows のディスクが壊れることもあります。\n"
                        + "できるだけ Windows の中から、または「シャットダウン」で止めてください。")
                .setPositiveButton("強制終了する", (d, w) -> new Thread(() -> {
                    WinVm.forceStop(this);
                    ui.post(this::refresh);
                }, "winvm-kill").start())
                .setNegativeButton("やめる", null)
                .show();
    }

    private void confirmRemoveDisk() {
        new AlertDialog.Builder(this)
                .setTitle("仮想ディスクを削除")
                .setMessage("仮想ディスクと UEFI の設定を消します。中の Windows とファイルはすべて消え、元に戻せません。")
                .setPositiveButton("削除する", (d, w) -> {
                    if (WinVm.runningPort(this) > 0) {
                        toast("仮想マシンが動いています。止めてから削除してください");
                        return;
                    }
                    WinVm.removeDisk(this);
                    refresh();
                })
                .setNegativeButton("やめる", null)
                .show();
    }

    private void confirmRemoveAll() {
        new AlertDialog.Builder(this)
                .setTitle("すべて削除")
                .setMessage("UEFI・virtio-win・仮想ディスク (中の Windows も) をすべて消します。Windows の ISO は消しません。")
                .setPositiveButton("削除する", (d, w) -> {
                    if (WinVm.runningPort(this) > 0) {
                        toast("仮想マシンが動いています。止めてから削除してください");
                        return;
                    }
                    WinVm.removeAll(this);
                    cfg = WinVm.config(this);
                    refresh();
                })
                .setNegativeButton("やめる", null)
                .show();
    }
}
