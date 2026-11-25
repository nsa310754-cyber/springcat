package site.springcat.bionicrunner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * セキュリティ研究用の仮想マシンの画面。準備 (Alpine Linux のダウンロード)、共有フォルダへの
 * ファイルの出し入れ、起動を行う。起動した仮想マシンは、文字端末の画面 ({@link TermGameActivity}) で動く。
 */
public class VmActivity extends Activity {

    private static final int REQ_ADD = 1;
    private static final int[] MEMORY = {512, 1024, 2048};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView shareList;
    private ProgressBar bar;
    private Button prepareButton;
    private LinearLayout startBox;
    private RadioGroup memory;
    private TextView kvmStatus;
    private boolean busy;
    /** 選んでいる仮想マシンの CPU の種類 */
    private Vm.Arch arch = Vm.Arch.AARCH64;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("仮想マシン");
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
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
        title.setText("仮想マシン (セキュリティ研究用)");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);
        col.addView(note("怪しいプログラムを、端末から切り離された使い捨ての Linux (Alpine Linux) の中で動かします。\n"
                + "・中は別の OS です。中で root になっても、端末には届きません\n"
                + "・ディスクを持たず、電源を切ると中で起きたことはすべて消えます。次はまた、まっさらな状態から始まります\n"
                + "・共有フォルダは中からは読み取り専用です。中から端末のファイルを書き換えることはできません\n"
                + "・ネットワークは既定で無しです"));

        col.addView(heading("CPU の種類"));
        RadioGroup archGroup = new RadioGroup(this);
        String[] archLabels = {"ARM64 (端末と同じ CPU。速め)", "x86_64 (PC 用のプログラムを中で動かす。遅め)"};
        for (int i = 0; i < archLabels.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setText(archLabels[i]);
            r.setTextColor(0xFFE5E7EB);
            r.setTextSize(13);
            r.setId(100 + i);
            archGroup.addView(r);
        }
        archGroup.check(100);
        archGroup.setOnCheckedChangeListener((g, id) -> {
            if (busy) return;
            arch = id == 101 ? Vm.Arch.X86_64 : Vm.Arch.AARCH64;
            refresh();
        });
        col.addView(archGroup);
        col.addView(note("動かしたいプログラムの CPU に合わせて選びます。x86_64 用の検体 (PC 用の Linux の"
                + "プログラム) は x86_64 を選んでください。2 つは別々に準備・起動します。"));

        col.addView(heading("1. 準備"));
        status = note("");
        status.setTextColor(0xFFE5E7EB);
        col.addView(status);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        col.addView(bar);
        prepareButton = button("Alpine Linux をダウンロード (約 90MB)", 0xFF2563EB, v -> prepare());
        col.addView(prepareButton);

        col.addView(heading("2. 調べるファイルを共有フォルダへ"));
        col.addView(note("ここに入れたファイルが、仮想マシンの中の /dev/vdb1 (FAT のディスク、読み取り専用) に見えます。"
                + "エクスプローラーの「アプリの作業領域」→ vm-share からも出し入れできます。"));
        shareList = note("");
        shareList.setTextColor(0xFFE5E7EB);
        col.addView(shareList);
        LinearLayout shareRow = new LinearLayout(this);
        shareRow.addView(button("ファイルを追加…", 0xFF2563EB, v -> pickFiles()), weight());
        shareRow.addView(button("空にする", 0xFF475569, v -> clearShare()), weight());
        col.addView(shareRow);

        col.addView(heading("KVM (CPU の仮想化の機能で速く動かす)"));
        col.addView(note("端末の CPU の仮想化の機能 (/dev/kvm) を使えると、ARM64 の仮想マシンが CPU を真似るより"
                + "ずっと速く動きます。使えるかどうかは端末と SELinux の設定しだいなので、診断で確かめます。\n"
                + "診断では、/dev/kvm をアプリの権限と root (使えれば) の両方で開き、Alpine Linux を何回か起動して、"
                + "CPU のコアを固定しない場合と、同じ種類のコアだけに固定した場合を比べます (数分かかります)。"));
        kvmStatus = note("");
        kvmStatus.setTextColor(0xFFE5E7EB);
        col.addView(kvmStatus);
        LinearLayout kvmRow = new LinearLayout(this);
        kvmRow.addView(button("KVM の診断", 0xFF7C3AED, v -> runKvmCheck()), weight());
        kvmRow.addView(button("KVM を使わない", 0xFF475569, v -> {
            KvmCheck.forget(this);
            refresh();
        }), weight());
        col.addView(kvmRow);

        col.addView(heading("Windows 11 (ARM64) の仮想マシン (試験中)"));
        col.addView(note("KVM を root で使えるときは、ARM64 版の Windows 11 を仮想マシンの中で動かせます (UEFI・64GB の"
                + "仮想ディスク・VNC の画面)。Windows の ISO はご自分で用意します。"));
        col.addView(button("Windows 11 の仮想マシンを開く", 0xFF0EA5E9,
                v -> startActivity(new Intent(this, WinVmActivity.class))));

        startBox = new LinearLayout(this);
        startBox.setOrientation(LinearLayout.VERTICAL);
        startBox.addView(heading("3. 起動"));
        memory = new RadioGroup(this);
        memory.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < MEMORY.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setText("メモリ " + MEMORY[i] + "MB");
            r.setTextColor(0xFFE5E7EB);
            r.setTextSize(13);
            r.setId(i + 1);
            memory.addView(r);
        }
        memory.check(1);
        startBox.addView(memory);
        startBox.addView(button("起動 (ネットワーク無し・おすすめ)", 0xFF16A34A, v -> start(false)));
        startBox.addView(button("起動 (ネットワーク有り)", 0xFFB45309, v -> confirmNetwork()));
        startBox.addView(note("起動には 1〜数分かかります (CPU を真似て動かすため、端末の速さによります。KVM を使えれば数秒〜数十秒)。"
                + "「localhost login:」と出たら root と入力してください (パスワードはありません)。\n"
                + "共有フォルダを見るには:  mkdir /mnt/s; mount -o ro /dev/vdb1 /mnt/s; ls /mnt/s\n"
                + "ネットワーク有りで起動したときは:  ip link set eth0 up; udhcpc -i eth0\n"
                + "終わるときは poweroff (または「閉じる」)。"));
        col.addView(startBox);

        col.addView(heading("後片付け"));
        col.addView(button("ダウンロードした Alpine Linux を削除", 0xFF7F1D1D, v -> confirmRemove()));

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF111827);
        sv.addView(col);
        return sv;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(6);
        lp.rightMargin = dp(6);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private void refresh() {
        if (kvmStatus != null) {
            KvmCheck.Plan p = KvmCheck.savedAny(this);
            if (p == null) {
                kvmStatus.setText("KVM: 使っていません (CPU を真似て動かします)。診断で使えるか確かめられます");
            } else if (p.root) {
                kvmStatus.setText("KVM: root でだけ使えました (" + p.label + ")。普段の起動ではまだ使いません");
            } else {
                kvmStatus.setText("✔ KVM: 使います (" + p.label + ")。ARM64 の仮想マシンだけ");
            }
        }
        if (!Vm.supported(this, arch)) {
            status.setText("✖ この端末では使えません (仮想マシンのエンジンは 64bit の ARM 端末用だけを入れています)");
            prepareButton.setVisibility(View.GONE);
            startBox.setVisibility(View.GONE);
        } else if (Vm.ready(this, arch)) {
            status.setText("✔ 準備済み: Alpine Linux " + Vm.version(this, arch) + " (" + arch.label + "、"
                    + Formatter.formatShortFileSize(this, Vm.size(this, arch)) + ")");
            prepareButton.setText("最新版にする (もう一度ダウンロード)");
            startBox.setVisibility(View.VISIBLE);
        } else {
            status.setText(busy ? status.getText() : "まだ準備していません。Alpine Linux を公式サイトから取ってきます"
                    + " (公式の SHA-256 で、壊れていないこと・すり替えられていないことを確かめます)");
            prepareButton.setText("Alpine Linux (" + arch.label + ") をダウンロード (約 "
                    + (arch == Vm.Arch.X86_64 ? 70 : 90) + "MB)");
            startBox.setVisibility(View.GONE);
        }
        prepareButton.setEnabled(!busy);
        File[] files = Vm.shareDir(this).listFiles();
        if (files == null || files.length == 0) {
            shareList.setText("(共有フォルダは空です)");
        } else {
            StringBuilder sb = new StringBuilder();
            for (File f : files) {
                sb.append(f.isDirectory() ? "📁 " : "📄 ").append(f.getName());
                if (f.isFile()) sb.append("  (").append(Formatter.formatShortFileSize(this, f.length())).append(')');
                sb.append('\n');
            }
            shareList.setText(sb.toString().trim());
        }
    }

    // ------------------------------------------------------------------ 準備

    private void prepare() {
        if (busy) return;
        busy = true;
        final Vm.Arch preparing = arch;
        bar.setVisibility(View.VISIBLE);
        bar.setProgress(0);
        refresh();
        new Thread(() -> {
            String error = null;
            try {
                Vm.prepare(this, preparing, (message, percent) -> ui.post(() -> {
                    if (message != null) status.setText(message);
                    bar.setProgress(percent);
                }));
            } catch (IOException | RuntimeException e) {
                error = e.getMessage();
            }
            final String fe = error;
            ui.post(() -> {
                busy = false;
                bar.setVisibility(View.GONE);
                if (fe != null) {
                    status.setText("✖ 準備できませんでした: " + fe);
                    prepareButton.setEnabled(true);
                } else {
                    toast("準備できました");
                    refresh();
                }
            });
        }, "vm-prepare").start();
    }

    private void confirmRemove() {
        new AlertDialog.Builder(this)
                .setTitle("削除")
                .setMessage("ダウンロードした Alpine Linux (" + arch.label + ") を削除します (共有フォルダの中身は残ります)。"
                        + "もう一度使うときは、またダウンロードします。")
                .setPositiveButton("削除", (d, w) -> {
                    Vm.remove(this, arch);
                    refresh();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    // ------------------------------------------------------------------ 共有フォルダ

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_ADD);
        } catch (ActivityNotFoundException e) {
            toast("ファイル選択アプリが見つかりません");
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQ_ADD || result != RESULT_OK || data == null) return;
        final java.util.List<Uri> uris = new java.util.ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        new Thread(() -> {
            File dir = Vm.shareDir(this);
            dir.mkdirs();
            int ok = 0;
            for (Uri u : uris) {
                File out = new File(dir, safeName(displayName(u)));
                try (InputStream in = getContentResolver().openInputStream(u);
                     OutputStream os = new FileOutputStream(out)) {
                    if (in == null) continue;
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    ok++;
                } catch (IOException | SecurityException e) {
                    out.delete();
                }
            }
            final int count = ok;
            ui.post(() -> {
                toast(count + " 個のファイルを共有フォルダに入れました");
                refresh();
            });
        }, "vm-share").start();
    }

    /** 仮想マシンの中の FAT で困らない名前にする */
    private static String safeName(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").trim();
        if (s.isEmpty() || s.equals(".") || s.equals("..")) s = "file";
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (RuntimeException ignored) {
            // 名前を教えてくれない提供元もある
        }
        String last = uri.getLastPathSegment();
        return last == null ? "file" : last;
    }

    private void clearShare() {
        new AlertDialog.Builder(this)
                .setTitle("共有フォルダを空にする")
                .setMessage("共有フォルダの中身をすべて削除します。")
                .setPositiveButton("削除", (d, w) -> {
                    File[] files = Vm.shareDir(this).listFiles();
                    if (files != null) for (File f : files) Archive.deleteTree(f);
                    refresh();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    // ------------------------------------------------------------------ 起動

    private void confirmNetwork() {
        new AlertDialog.Builder(this)
                .setTitle("ネットワーク有りで起動")
                .setMessage("仮想マシンの中から、インターネットへ出られるようになります。\n\n"
                        + "注意: QEMU の仕組み上、中から 10.0.2.2 へつなぐと、この端末の中で待ち受けているもの"
                        + " (127.0.0.1 のサービス) にも届いてしまいます。マルウェアなど怪しいものを動かすときは、"
                        + "ネットワーク無しで起動してください。")
                .setPositiveButton("起動する", (d, w) -> start(true))
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void start(boolean network) {
        if (!Vm.ready(this, arch)) {
            toast("先に準備 (ダウンロード) をしてください");
            return;
        }
        Vm.shareDir(this).mkdirs();
        int mem = MEMORY[Math.max(0, Math.min(MEMORY.length - 1, memory.getCheckedRadioButtonId() - 1))];
        KvmCheck.Plan kvm = arch == Vm.Arch.AARCH64 ? KvmCheck.saved(this) : null;
        if (kvm == null) {
            launch(Vm.argv(this, arch, network, mem, null), Vm.env(this), network);
            return;
        }
        // 前の診断のあとで使えなくなっていないか (端末の更新など)、起動の前にすばやく確かめる
        final Vm.Arch a = arch;
        new Thread(() -> {
            int[] code = new int[1];
            KvmCheck.probe(this, false, kvm.cpus, code);
            boolean ok = code[0] == 0;
            ui.post(() -> {
                if (!ok) toast("KVM が使えなくなっていたので、CPU を真似て動かします (診断をやり直してください)");
                launch(Vm.argv(this, a, network, mem, ok ? kvm : null), Vm.env(this, ok, false), network);
            });
        }, "kvm-quick").start();
    }

    private void launch(List<String> argv, String[] env, boolean network) {
        if (network) Vm.writeResolv(this);
        startActivity(new Intent(this, TermGameActivity.class)
                .putExtra(TermGameActivity.EXTRA_ARGV, argv.toArray(new String[0]))
                .putExtra(TermGameActivity.EXTRA_ENV, env)
                .putExtra(TermGameActivity.EXTRA_TITLE, "仮想マシン " + arch.label
                        + (network ? " (ネットワーク有り)" : "")));
    }

    // ------------------------------------------------------------------ KVM の診断

    private void runKvmCheck() {
        if (busy) return;
        busy = true;
        TextView log = note("");
        log.setTextColor(0xFFE5E7EB);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(11);
        ScrollView sv = new ScrollView(this);
        sv.setPadding(dp(12), dp(8), dp(12), dp(8));
        sv.addView(log);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("KVM の診断 (数分かかります)")
                .setView(sv)
                .setCancelable(false)
                .setPositiveButton("閉じる", null)
                .setNeutralButton("コピー", null)
                .create();
        dlg.show();
        Button close = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
        Button copy = dlg.getButton(AlertDialog.BUTTON_NEUTRAL);
        close.setEnabled(false);
        copy.setEnabled(false);
        new Thread(() -> {
            String text = KvmCheck.diagnose(this, 2, line -> ui.post(() -> {
                log.append(line + "\n");
                sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
            }));
            ui.post(() -> {
                busy = false;
                log.append("\n記録を保存しました: アプリの作業領域の kvm-diag.txt\n");
                close.setEnabled(true);
                copy.setEnabled(true);
                close.setOnClickListener(v -> dlg.dismiss());
                copy.setOnClickListener(v -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("KVM の診断", text));
                        toast("コピーしました");
                    }
                });
                refresh();
            });
        }, "kvm-check").start();
    }
}
