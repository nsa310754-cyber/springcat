package site.springcat.bionicrunner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * zip.exe: 最初から入っている圧縮・解凍アプリ。ファイルを選んで zip にまとめ (パスワードを付ければ
 * AES-256 で暗号化)、圧縮ファイルを開いて中身を確かめ、選んだフォルダへ解凍する。
 *
 * ファイルの受け渡しは Android のファイル選択画面を通すので、root も「全ファイルへのアクセス」の
 * 許可も要らない。作業はアプリの一時置き場で行い、出来上がったものを選ばれた場所へ書き出す。
 */
public class ZipActivity extends Activity {

    private static final int REQ_ADD = 1;
    private static final int REQ_SAVE = 2;
    private static final int REQ_OPEN = 3;
    private static final int REQ_TREE = 4;
    /** 一覧に出す項目の上限 (それ以上は「ほか N 個」とする) */
    private static final int MAX_LISTED = 300;

    private interface Job {
        void run() throws IOException;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private File work;

    // 圧縮
    private final List<Uri> inputs = new ArrayList<>();
    private final List<String> inputNames = new ArrayList<>();
    private TextView inputList;
    private EditText zipName;
    private EditText zipPassword;
    private android.widget.RadioGroup zipKind;
    private int kindZipId, kind7zId;
    /** 出来上がって、保存先が選ばれるのを待っている zip */
    private File pendingZip;

    // 解凍
    private File archive;
    private String archiveName;
    private Archive.Format archiveFormat;
    private TextView archiveInfo;
    private EditText unzipPassword;
    private Button extractButton;
    /** 展開が済み、解凍先が選ばれるのを待っているもの */
    private File pendingTree;
    private Archive.Result pendingResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        work = new File(getCacheDir(), "zipapp");
        // 前回の作業の残りを片付ける。選択画面から戻る途中の作り直しでは消さない
        if (savedInstanceState == null) Archive.deleteTree(work);
        work.mkdirs();
        SevenZip.init(getApplicationInfo().nativeLibraryDir);
        setContentView(buildUi());
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        if (isFinishing()) Archive.deleteTree(work);
        super.onDestroy();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ 画面

    private View buildUi() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(14), dp(16), dp(24));

        TextView title = new TextView(this);
        title.setText("zip.exe");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);
        col.addView(note("ファイルの圧縮と解凍。パスワードを付けると AES-256 で暗号化します。"));

        // ---- 圧縮
        col.addView(heading("圧縮する"));
        col.addView(button("ファイルを追加…", 0xFF2563EB, v -> pick(REQ_ADD, true)));
        inputList = note("");
        inputList.setTextColor(0xFFE5E7EB);
        col.addView(inputList);
        zipName = field("圧縮ファイルの名前", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        col.addView(zipName);
        zipKind = new android.widget.RadioGroup(this);
        zipKind.setOrientation(LinearLayout.HORIZONTAL);
        kindZipId = View.generateViewId();
        kind7zId = View.generateViewId();
        zipKind.addView(radio("zip (どこでも開ける)", kindZipId));
        zipKind.addView(radio("7z (よく縮む)", kind7zId));
        zipKind.check(kindZipId);
        col.addView(zipKind);
        zipPassword = field("パスワード (空なら暗号化しない)", passwordType(false));
        col.addView(zipPassword);
        col.addView(showToggle(zipPassword));
        col.addView(note("パスワードを忘れると、中身は取り出せません。zip は決まりにより、ファイルの名前と"
                + "大きさは暗号化されません (中身だけ)。7z は名前の一覧も暗号化します。"));
        LinearLayout row = new LinearLayout(this);
        row.addView(button("圧縮して保存…", 0xFF16A34A, v -> compress()), weight());
        row.addView(button("クリア", 0xFF475569, v -> {
            inputs.clear();
            inputNames.clear();
            showInputs();
        }));
        col.addView(row);

        // ---- 解凍
        col.addView(heading("解凍する"));
        col.addView(button("圧縮ファイルを開く…", 0xFF2563EB, v -> pick(REQ_OPEN, false)));
        archiveInfo = note("zip・7z・rar・tar (gz / xz / bz2 / zst)・cab・iso・msi・deb・rpm・lzh などと、"
                + "インストーラーや自己解凍形式の exe を開けます。");
        archiveInfo.setTextColor(0xFFE5E7EB);
        archiveInfo.setTextIsSelectable(true);
        col.addView(archiveInfo);
        unzipPassword = field("パスワード", passwordType(false));
        unzipPassword.setVisibility(View.GONE);
        col.addView(unzipPassword);
        extractButton = button("解凍先を選んで解凍…", 0xFF16A34A, v -> extract());
        extractButton.setVisibility(View.GONE);
        col.addView(extractButton);

        showInputs();
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF111827);
        sv.addView(col);
        return sv;
    }

    private static int passwordType(boolean visible) {
        return InputType.TYPE_CLASS_TEXT | (visible ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                : InputType.TYPE_TEXT_VARIATION_PASSWORD);
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
        t.setPadding(0, dp(22), 0, dp(6));
        return t;
    }

    private android.widget.RadioButton radio(String label, int id) {
        android.widget.RadioButton r = new android.widget.RadioButton(this);
        r.setText(label);
        r.setTextColor(0xFFE5E7EB);
        r.setTextSize(13);
        r.setId(id);
        r.setPadding(0, 0, dp(14), 0);
        return r;
    }

    private TextView note(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0x99FFFFFF);
        t.setTextSize(12);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private EditText field(String hint, int inputType) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setInputType(inputType);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(0x66FFFFFF);
        e.setTextSize(15);
        return e;
    }

    /** パスワード欄の「表示する」の切り替え。打ち間違いに気づけるようにする */
    private CheckBox showToggle(final EditText target) {
        CheckBox c = new CheckBox(this);
        c.setText("パスワードを表示する");
        c.setTextColor(0xCCFFFFFF);
        c.setTextSize(12);
        c.setOnCheckedChangeListener((b, on) -> {
            int at = target.getSelectionEnd();
            target.setInputType(passwordType(on));
            target.setSelection(Math.max(0, Math.min(at, target.length())));
        });
        return c;
    }

    private Button button(String label, int color, View.OnClickListener l) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
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

    // ---------------------------------------------------------------- 共通の道具

    private void pick(int request, boolean multiple) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        if (multiple) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        launch(i, request);
    }

    private void launch(Intent i, int request) {
        try {
            startActivityForResult(i, request);
        } catch (ActivityNotFoundException e) {
            toast("ファイル選択アプリが見つかりません");
        }
    }

    /** 長くかかる作業を別スレッドで行う。終わるまで「作業中」を出し、失敗したら理由を表示する */
    private void runBusy(String message, final Job job, final Runnable done) {
        final AlertDialog busy = new AlertDialog.Builder(this)
                .setMessage(message)
                .setCancelable(false)
                .show();
        worker.execute(() -> {
            IOException error = null;
            try {
                job.run();
            } catch (IOException | SecurityException e) {
                error = e instanceof IOException ? (IOException) e : new IOException(e.getMessage());
            }
            final IOException fe = error;
            ui.post(() -> {
                busy.dismiss();
                if (fe == null) {
                    done.run();
                } else if (fe instanceof ZipCrypt.PasswordException) {
                    // 名前まで暗号化されたものは、開いてみるまでパスワードが要ると分からない
                    toast(fe.getMessage());
                    unzipPassword.setVisibility(View.VISIBLE);
                    unzipPassword.requestFocus();
                } else {
                    toast(fe instanceof java.io.EOFException || fe instanceof java.util.zip.ZipException
                            ? "圧縮ファイルが壊れているか、途中で切れています"
                            : String.valueOf(fe.getMessage()));
                }
            });
        });
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) name = c.getString(0);
        } catch (RuntimeException ignored) {
            // 名前を教えてくれない提供元もある。下の代替を使う
        }
        if (name == null) name = uri.getLastPathSegment();
        if (name == null) name = "file";
        // フォルダの区切りに見える文字は、一時置き場の外へ出ないよう置き換える
        name = name.replace('/', '_').replace('\\', '_').replace('\0', '_');
        return name.isEmpty() || name.equals(".") || name.equals("..") ? "file" : name;
    }

    private void copyIn(Uri from, File to) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            if (in == null) throw new IOException("開けませんでした: " + to.getName());
            pump(in, out);
        }
    }

    private void copyOut(File from, Uri to) throws IOException {
        try (InputStream in = new FileInputStream(from);
             OutputStream out = getContentResolver().openOutputStream(to, "w")) {
            if (out == null) throw new IOException("書き込めませんでした: " + from.getName());
            pump(in, out);
        }
    }

    private static void pump(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static char[] chars(EditText e) {
        return e.getText().toString().toCharArray();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        boolean ok = result == RESULT_OK && data != null;
        switch (request) {
            case REQ_ADD:
                if (ok) addInputs(data);
                break;
            case REQ_SAVE:
                if (ok && data.getData() != null) saveZip(data.getData());
                else dropPendingZip();
                break;
            case REQ_OPEN:
                if (ok && data.getData() != null) openArchive(data.getData());
                break;
            case REQ_TREE:
                if (ok && data.getData() != null) writeTree(data.getData());
                else dropPendingTree();
                break;
            default:
        }
    }

    // ------------------------------------------------------------------ 圧縮

    private void addInputs(Intent data) {
        List<Uri> picked = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                picked.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            picked.add(data.getData());
        }
        Set<String> used = new HashSet<>(inputNames);
        for (Uri u : picked) {
            if (u == null || inputs.contains(u)) continue;
            // zip の中で同じ名前が重ならないよう、重なったら番号を付ける
            String name = displayName(u), unique = name;
            for (int i = 2; !used.add(unique); i++) {
                int dot = name.lastIndexOf('.');
                unique = dot > 0 ? name.substring(0, dot) + " (" + i + ")" + name.substring(dot)
                        : name + " (" + i + ")";
            }
            inputs.add(u);
            inputNames.add(unique);
        }
        if (zipName.getText().toString().trim().isEmpty() && !inputNames.isEmpty()) {
            String first = inputNames.get(0);
            int dot = first.lastIndexOf('.');
            zipName.setText(inputs.size() == 1 && dot > 0 ? first.substring(0, dot)
                    : inputs.size() == 1 ? first : "archive");
        }
        showInputs();
    }

    private void showInputs() {
        if (inputs.isEmpty()) {
            inputList.setText("まだファイルがありません。「ファイルを追加」で選んでください (複数選べます)。"
                    + "フォルダごと圧縮するときは、エクスプローラーの長押し →「圧縮」を使ってください。");
            return;
        }
        StringBuilder sb = new StringBuilder(inputs.size() + " 個のファイル\n");
        for (int i = 0; i < inputNames.size() && i < MAX_LISTED; i++) {
            sb.append("  📄 ").append(inputNames.get(i)).append('\n');
        }
        if (inputNames.size() > MAX_LISTED) {
            sb.append("  … ほか ").append(inputNames.size() - MAX_LISTED).append(" 個\n");
        }
        inputList.setText(sb.toString().trim());
    }

    private void compress() {
        if (inputs.isEmpty()) {
            toast("先に「ファイルを追加」で圧縮するファイルを選んでください");
            return;
        }
        String name = zipName.getText().toString().trim();
        final String kind = zipKind.getCheckedRadioButtonId() == kind7zId ? "7z" : "zip";
        if (name.toLowerCase(java.util.Locale.ROOT).endsWith("." + kind)) {
            name = name.substring(0, name.length() - kind.length() - 1);
        }
        if (name.isEmpty() || name.contains("/")) {
            toast("圧縮ファイルの名前を入れてください");
            return;
        }
        final String outName = name + "." + kind;
        final char[] password = chars(zipPassword);
        final List<Uri> uris = new ArrayList<>(inputs);
        final List<String> names = new ArrayList<>(inputNames);
        final File stage = new File(work, "in-" + System.nanoTime());
        final File zip = new File(work, "out-" + System.nanoTime() + "." + kind);
        runBusy(password.length > 0 ? "暗号化して圧縮中…" : "圧縮中…", () -> {
            try {
                if (!stage.mkdirs()) throw new IOException("一時置き場を作れません");
                List<File> files = new ArrayList<>();
                for (int i = 0; i < uris.size(); i++) {
                    File f = new File(stage, names.get(i));
                    copyIn(uris.get(i), f);
                    files.add(f);
                }
                Archive.create(files, zip, kind, password);
            } catch (IOException | RuntimeException e) {
                zip.delete();
                throw e;
            } finally {
                Archive.deleteTree(stage);
            }
        }, () -> {
            pendingZip = zip;
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType(kind.equals("7z") ? "application/x-7z-compressed" : "application/zip");
            i.putExtra(Intent.EXTRA_TITLE, outName);
            launch(i, REQ_SAVE);
        });
    }

    private void saveZip(final Uri to) {
        final File zip = pendingZip;
        pendingZip = null;
        if (zip == null) return;
        final long size = zip.length();
        runBusy("保存中…", () -> {
            try {
                copyOut(zip, to);
            } finally {
                zip.delete();
            }
        }, () -> toast("保存しました (" + Formatter.formatShortFileSize(this, size) + ")"));
    }

    private void dropPendingZip() {
        if (pendingZip != null) pendingZip.delete();
        pendingZip = null;
    }

    // ------------------------------------------------------------------ 解凍

    private void openArchive(final Uri uri) {
        final String name = displayName(uri);
        final File copy = new File(work, "archive-" + System.nanoTime());
        final Archive.Format[] format = {Archive.Format.NONE};
        final StringBuilder info = new StringBuilder();
        final boolean[] encrypted = {false};
        runBusy("読み込み中…", () -> {
            try {
                copyIn(uri, copy);
                format[0] = Archive.detect(copy);
                if (format[0] == Archive.Format.NONE) {
                    throw new IOException(Archive.isWindowsExe(copy)
                            ? "この exe は自己解凍形式でもインストーラーでもないので、解凍できません"
                            + " (Windows 用のプログラムを Linux 用に変換することもできません)"
                            : "圧縮ファイルではありません: " + name);
                }
                if (format[0] == Archive.Format.UNSUPPORTED) {
                    throw new IOException("この圧縮形式 (7z / rar / zstd) は開けません: " + name);
                }
                encrypted[0] = describe(copy, name, format[0], info);
            } catch (IOException | RuntimeException e) {
                copy.delete();
                throw e;
            }
        }, () -> {
            if (archive != null) archive.delete();
            archive = copy;
            archiveName = name;
            archiveFormat = format[0];
            archiveInfo.setText(info.toString().trim());
            unzipPassword.setText("");
            unzipPassword.setVisibility(encrypted[0] ? View.VISIBLE : View.GONE);
            extractButton.setVisibility(View.VISIBLE);
        });
    }

    /** 圧縮ファイルの説明 (zip なら中身の一覧) を作る。パスワードが要るなら真を返す */
    private boolean describe(File f, String name, Archive.Format format, StringBuilder sb)
            throws IOException {
        sb.append(name).append("  (").append(Formatter.formatShortFileSize(this, f.length()))
                .append(")\n");
        if (format == Archive.Format.OTHER) {
            SevenZip.Info info;
            try {
                info = SevenZip.probe(f, null);
            } catch (ZipCrypt.PasswordException e) {
                sb.append("🔒 パスワード付き (名前の一覧も暗号化されています)\n"
                        + "パスワードを入れて解凍してください。");
                return true;
            }
            if (info == null) throw new IOException("圧縮ファイルではありません: " + name);
            sb.append("形式: ").append(info.type).append("  ·  ").append(info.files)
                    .append(" 個  ·  展開後 ")
                    .append(Formatter.formatShortFileSize(this, info.totalSize)).append('\n');
            if (info.encrypted) sb.append("🔒 パスワード付き\n");
            for (SevenZip.Item it : info.items) {
                sb.append(it.dir ? "  📁 " : it.encrypted ? "  🔒 " : "  📄 ").append(it.path);
                if (!it.dir) {
                    sb.append("  (").append(Formatter.formatShortFileSize(this, it.size)).append(')');
                }
                sb.append('\n');
            }
            if (info.files > info.items.size()) sb.append("  … ほか\n");
            return info.encrypted;
        }
        if (format != Archive.Format.ZIP) {
            sb.append("形式: ").append(format == Archive.Format.TAR ? "tar"
                    : format == Archive.Format.GZIP ? "gzip" : format == Archive.Format.XZ ? "xz"
                    : "bzip2").append("\n中身の一覧は、解凍すると確認できます。");
            return false;
        }
        List<ZipCrypt.ZEntry> entries = ZipCrypt.list(f);
        boolean locked = ZipCrypt.isEncrypted(entries);
        long total = 0;
        int files = 0;
        boolean weak = false;
        for (ZipCrypt.ZEntry e : entries) {
            if (e.dir) continue;
            files++;
            total += e.usize;
            if (e.encrypted && !e.aes) weak = true;
        }
        sb.append("形式: zip  ·  ").append(files).append(" 個  ·  展開後 ")
                .append(Formatter.formatShortFileSize(this, total)).append('\n');
        if (locked) {
            sb.append(weak ? "🔒 パスワード付き (古い方式の暗号。解読されやすい)\n"
                    : "🔒 パスワード付き (AES)\n");
        }
        int shown = 0;
        for (ZipCrypt.ZEntry e : entries) {
            if (shown++ >= MAX_LISTED) {
                sb.append("  … ほか ").append(entries.size() - MAX_LISTED).append(" 個\n");
                break;
            }
            sb.append(e.dir ? "  📁 " : e.encrypted ? "  🔒 " : "  📄 ").append(e.name);
            if (!e.dir) sb.append("  (").append(Formatter.formatShortFileSize(this, e.usize)).append(')');
            sb.append('\n');
        }
        return locked;
    }

    /** まず一時置き場へ展開する (パスワードの正しさもここで分かる)。済んだら解凍先を選んでもらう */
    private void extract() {
        if (archive == null) return;
        final File src = archive;
        final Archive.Format format = archiveFormat;
        String stem = Archive.stripExtension(archiveName);
        if (stem.equals(archiveName)) stem = archiveName + "_解凍";
        final String outName = stem;
        final char[] password = unzipPassword.getVisibility() == View.VISIBLE
                ? chars(unzipPassword) : null;
        final File stage = new File(work, "x-" + System.nanoTime());
        final Archive.Result[] result = {null};
        runBusy("解凍中…", () -> {
            try {
                result[0] = Archive.extract(src, format, new File(stage, outName), outName, password);
            } catch (IOException | RuntimeException e) {
                Archive.deleteTree(stage);
                throw e;
            }
        }, () -> {
            dropPendingTree();
            pendingTree = stage;
            pendingResult = result[0];
            toast("解凍先のフォルダを選んでください");
            launch(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE);
        });
    }

    /** 展開したものを、選ばれたフォルダの中へ書き出す */
    private void writeTree(final Uri tree) {
        final File stage = pendingTree;
        final Archive.Result result = pendingResult;
        pendingTree = null;
        pendingResult = null;
        if (stage == null || result == null) return;
        final int[] count = {0};
        runBusy("書き出し中…", () -> {
            try {
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree,
                        DocumentsContract.getTreeDocumentId(tree));
                File[] top = stage.listFiles();
                if (top == null || top.length != 1) throw new IOException("展開したものが見つかりません");
                // tar や zip ならフォルダごと、圧縮しただけの 1 個のファイルならそのファイルを置く
                File root = result.tree ? top[0] : result.files.get(0).file;
                writeOut(root, parent, count);
            } finally {
                Archive.deleteTree(stage);
            }
        }, () -> toast("解凍しました (" + count[0] + " 個のファイル)"
                + (result.skipped > 0 ? "。安全でない項目 " + result.skipped + " 個は取り出していません"
                : "")));
    }

    private void writeOut(File f, Uri parent, int[] count) throws IOException {
        ContentResolver cr = getContentResolver();
        // リンクは書き出さない (自分の親を指すリンクをたどると、終わらなくなる)
        if (Archive.isSymlink(f)) return;
        if (f.isDirectory()) {
            Uri dir = DocumentsContract.createDocument(cr, parent,
                    DocumentsContract.Document.MIME_TYPE_DIR, f.getName());
            if (dir == null) throw new IOException("フォルダを作成できませんでした: " + f.getName());
            File[] kids = f.listFiles();
            if (kids == null) return;
            java.util.Arrays.sort(kids);
            for (File k : kids) writeOut(k, dir, count);
        } else if (f.isFile()) {
            // 種類を「不明」にしておくと、保存先が名前に勝手な拡張子を足さない
            Uri doc = DocumentsContract.createDocument(cr, parent,
                    "application/octet-stream", f.getName());
            if (doc == null) throw new IOException("ファイルを作成できませんでした: " + f.getName());
            copyOut(f, doc);
            count[0]++;
        }
    }

    private void dropPendingTree() {
        if (pendingTree != null) {
            final File stage = pendingTree;
            worker.execute(() -> Archive.deleteTree(stage));
        }
        pendingTree = null;
        pendingResult = null;
    }
}
