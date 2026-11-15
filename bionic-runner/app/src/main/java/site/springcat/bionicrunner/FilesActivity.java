package site.springcat.bionicrunner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * root 権限のエクスプローラー。端末のどこでも見られるが、変更できるのは
 * 内部ストレージ・外部 SD カード・このアプリの作業領域だけ。システム領域は閲覧のみにして、
 * 誤操作で端末が起動しなくなる事故を防ぐ (どうしても変更したい場合はコマンドプロンプトで行う)。
 */
public class FilesActivity extends Activity {

    /** 表示・編集のために読み込む上限。これより大きいファイルは先頭だけ表示し、編集はさせない */
    private static final int MAX_VIEW = 512 * 1024;
    private static final int HEX_BYTES = 4096;

    private interface Job {
        void run() throws IOException;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final RootSession session = new RootSession();
    private final List<RootSession.Entry> entries = new ArrayList<>();

    private String appFiles;
    private String current = "/";
    /** 「戻る」で帰る場所の履歴 */
    private final List<String> history = new ArrayList<>();
    private boolean listed;
    private boolean currentEditable;
    /** コピー・移動の対象として覚えている場所 (無ければ null) */
    private String clipPath;
    private boolean clipMove;

    private View browser;
    private View editor;
    private TextView pathView;
    private TextView modeView;
    private TextView emptyView;
    private LinearLayout actions;
    private Button pasteButton;
    private EntryAdapter adapter;

    private TextView editorTitle;
    private EditText editorText;
    private Button saveButton;
    private String editorPath;
    private boolean editorDirty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            appFiles = getFilesDir().getCanonicalPath();
        } catch (IOException e) {
            appFiles = getFilesDir().getAbsolutePath();
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF111827);
        browser = buildBrowser();
        editor = buildEditor();
        editor.setVisibility(View.GONE);
        root.addView(browser);
        root.addView(editor);
        setContentView(root);

        modeView.setText("準備中… su の許可ダイアログが出たら「許可」してください");
        worker.execute(() -> {
            try {
                session.start();
            } catch (IOException e) {
                ui.post(() -> modeView.setText("シェルを起動できませんでした: " + e.getMessage()));
                return;
            }
            ui.post(() -> {
                if (!session.isRoot()) {
                    toast("root が無いため、このアプリが読める範囲だけ表示します");
                }
                open(internalStorage());
            });
        });
    }

    @Override
    protected void onDestroy() {
        worker.execute(session::close);
        worker.shutdown();
        super.onDestroy();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    /** 時間のかかる操作を別スレッドで行い、終わったら done を、失敗したら理由を表示する */
    private void runJob(final Job job, final Runnable done) {
        worker.execute(() -> {
            try {
                job.run();
            } catch (IOException e) {
                ui.post(() -> toast(e.getMessage()));
                return;
            }
            if (done != null) ui.post(done);
        });
    }

    // ------------------------------------------------------------ 場所と権限

    private String internalStorage() {
        return Environment.getExternalStorageDirectory().getAbsolutePath();
    }

    /** 外部 SD カードの場所。挿さっていなければ null */
    private String sdCard() {
        File[] dirs = getExternalFilesDirs(null);
        for (int i = 1; i < dirs.length; i++) {
            if (dirs[i] == null) continue;
            String p = dirs[i].getAbsolutePath();
            int at = p.indexOf("/Android/");
            if (at > 0) return p.substring(0, at);
        }
        return null;
    }

    private static String join(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    private static String parent(String path) {
        int at = path.lastIndexOf('/');
        return at <= 0 ? "/" : path.substring(0, at);
    }

    private static String baseName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** 名前として使えない入力 (空、区切り文字入り、. と ..) を弾く */
    private static boolean validName(String name) {
        return !name.isEmpty() && !name.contains("/") && !name.equals(".") && !name.equals("..");
    }

    // ---------------------------------------------------------------- 一覧画面

    private View buildBrowser() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        pathView = new TextView(this);
        pathView.setTextColor(Color.WHITE);
        pathView.setTextSize(15);
        pathView.setTypeface(Typeface.MONOSPACE);
        pathView.setSingleLine(true);
        pathView.setEllipsize(TextUtils.TruncateAt.START);
        pathView.setPadding(dp(12), dp(10), dp(12), dp(2));
        col.addView(pathView);

        modeView = new TextView(this);
        modeView.setTextSize(12);
        modeView.setTextColor(0xFFFACC15);
        modeView.setPadding(dp(12), 0, dp(12), dp(6));
        col.addView(modeView);

        LinearLayout places = new LinearLayout(this);
        places.setPadding(dp(4), 0, dp(4), 0);
        places.addView(barButton("↑ 上へ", v -> open(parent(current))));
        places.addView(barButton("システム", v -> open("/")));
        places.addView(barButton("内部ストレージ", v -> open(internalStorage())));
        places.addView(barButton("SD カード", v -> {
            String sd = sdCard();
            if (sd == null) {
                toast("SD カードが見つかりません。/storage を開きます");
                open("/storage");
            } else {
                open(sd);
            }
        }));
        places.addView(barButton("アプリの作業領域", v -> open(new File(appFiles, "home").getPath())));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setBackgroundColor(0xFF1F2937);
        hs.addView(places);
        col.addView(hs);

        FrameLayout listArea = new FrameLayout(this);
        ListView list = new ListView(this);
        adapter = new EntryAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> onEntryClick(entries.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            showEntryMenu(entries.get(pos));
            return true;
        });
        listArea.addView(list);
        emptyView = new TextView(this);
        emptyView.setTextColor(0xCCFFFFFF);
        emptyView.setGravity(Gravity.CENTER);
        listArea.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        col.addView(listArea, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        actions = new LinearLayout(this);
        actions.setBackgroundColor(0xFF1F2937);
        actions.setPadding(dp(4), 0, dp(4), 0);
        actions.addView(barButton("＋ ファイル", v -> askName("新しいファイルの名前", "", name ->
                runJob(() -> session.makeFile(join(current, name)), this::reload))));
        actions.addView(barButton("＋ フォルダ", v -> askName("新しいフォルダの名前", "", name ->
                runJob(() -> session.makeDir(join(current, name)), this::reload))));
        pasteButton = barButton("貼り付け", v -> paste());
        actions.addView(pasteButton);
        col.addView(actions);
        return col;
    }

    private Button barButton(String label, View.OnClickListener l) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setOnClickListener(l);
        return b;
    }

    /** フォルダを開く。開けなければ今の場所にとどまる */
    private void open(String dir) {
        open(dir, true);
    }

    /** remember が真なら、今の場所を「戻る」の履歴に積む */
    private void open(final String dir, final boolean remember) {
        emptyView.setText("読み込み中…");
        worker.execute(() -> {
            final RootSession.Listing listing;
            try {
                listing = session.list(dir);
            } catch (IOException e) {
                ui.post(() -> {
                    emptyView.setText("");
                    toast(e.getMessage());
                });
                return;
            }
            ui.post(() -> {
                if (listing == null) {
                    emptyView.setText(entries.isEmpty() ? "開けませんでした" : "");
                    toast("開けませんでした: " + dir);
                    return;
                }
                showListing(listing, remember);
            });
        });
    }

    private void reload() {
        open(current, false);
    }

    private void showListing(RootSession.Listing listing, boolean remember) {
        if (remember && listed && !listing.path.equals(current)) history.add(current);
        listed = true;
        current = listing.path;
        currentEditable = RootSession.isEditable(current, appFiles);
        // フォルダを先に、あとは名前順
        Collections.sort(listing.entries, (a, b) -> {
            if (a.isDir() != b.isDir()) return a.isDir() ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        entries.clear();
        entries.addAll(listing.entries);
        adapter.notifyDataSetChanged();
        pathView.setText(current);
        if (currentEditable) {
            modeView.setText("編集できます (長押しで名前変更・コピー・移動・削除)");
            modeView.setTextColor(0xFF4ADE80);
        } else {
            modeView.setText("閲覧のみ (システム領域は変更できません)");
            modeView.setTextColor(0xFFFACC15);
        }
        actions.setVisibility(currentEditable ? View.VISIBLE : View.GONE);
        updatePasteButton();
        emptyView.setText(entries.isEmpty() ? "空のフォルダです" : "");
    }

    private void updatePasteButton() {
        pasteButton.setVisibility(clipPath == null ? View.GONE : View.VISIBLE);
        if (clipPath != null) {
            pasteButton.setText((clipMove ? "ここへ移動: " : "ここへコピー: ") + baseName(clipPath));
        }
    }

    private void onEntryClick(final RootSession.Entry e) {
        final String path = join(current, e.name);
        if (e.isDir()) {
            open(path);
        } else if (e.isRegular()) {
            openFile(path, e.size);
        } else if (e.isLink()) {
            // リンク先がフォルダなら入る。そうでなければファイルとして開く
            worker.execute(() -> {
                RootSession.Listing listing = null;
                try {
                    listing = session.list(path);
                } catch (IOException ignored) {
                    // 下でファイルとして開いてみる
                }
                final RootSession.Listing l = listing;
                ui.post(() -> {
                    if (l != null) showListing(l, true);
                    else openFile(path, -1);
                });
            });
        } else {
            toast("開けない種類です: " + e.type);
        }
    }

    private void showEntryMenu(final RootSession.Entry e) {
        final String path = join(current, e.name);
        final List<String> labels = new ArrayList<>();
        final List<Runnable> runs = new ArrayList<>();
        labels.add("開く");
        runs.add(() -> onEntryClick(e));
        labels.add("場所 (パス) をコピー");
        runs.add(() -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("path", path));
            toast("コピーしました: " + path);
        });
        labels.add("コピー (貼り付け先を開いて「ここへコピー」)");
        runs.add(() -> setClip(path, false));
        if (currentEditable) {
            labels.add("移動");
            runs.add(() -> setClip(path, true));
            labels.add("名前を変更");
            runs.add(() -> askName("新しい名前", e.name, name ->
                    runJob(() -> session.move(path, join(current, name)), this::reload)));
            labels.add("削除");
            runs.add(() -> new AlertDialog.Builder(this)
                    .setTitle("削除")
                    .setMessage(e.name + " を削除します。"
                            + (e.isDir() ? "フォルダの中身もすべて消えます。" : "") + "元に戻せません。")
                    .setPositiveButton("削除", (d, w) ->
                            runJob(() -> session.delete(path), this::reload))
                    .setNegativeButton("キャンセル", null)
                    .show());
        }
        new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setItems(labels.toArray(new String[0]), (d, which) -> runs.get(which).run())
                .show();
    }

    private void setClip(String path, boolean move) {
        clipPath = path;
        clipMove = move;
        updatePasteButton();
        toast(currentEditable ? "貼り付け先のフォルダを開いてください"
                : "内部ストレージか SD カードのフォルダを開くと貼り付けられます");
    }

    private void paste() {
        if (clipPath == null) return;
        final String from = clipPath;
        final boolean move = clipMove;
        final String to = join(current, baseName(from));
        if ((current + "/").startsWith(from + "/")) {
            toast("自分自身の中へは貼り付けられません");
            return;
        }
        runJob(() -> {
            if (move) session.move(from, to);
            else session.copy(from, to);
        }, () -> {
            if (move) clipPath = null; // 移動は 1 回きり。コピーは続けて別の場所にも貼れる
            reload();
        });
    }

    private interface NameUse {
        void use(String name);
    }

    private void askName(String title, String initial, final NameUse use) {
        final EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setText(initial);
        field.setSelection(initial.length());
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(24), dp(8), dp(24), 0);
        wrap.addView(field);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(wrap)
                .setPositiveButton("OK", (d, w) -> {
                    String name = field.getText().toString().trim();
                    if (validName(name)) use.use(name);
                    else toast("その名前は使えません");
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private final class EntryAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row = (LinearLayout) convertView;
            if (row == null) {
                row = new LinearLayout(FilesActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(14), dp(9), dp(14), dp(9));
                TextView name = new TextView(FilesActivity.this);
                name.setTextColor(Color.WHITE);
                name.setTextSize(15);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                row.addView(name);
                TextView detail = new TextView(FilesActivity.this);
                detail.setTextColor(0x99FFFFFF);
                detail.setTextSize(11);
                row.addView(detail);
            }
            RootSession.Entry e = entries.get(position);
            String icon = e.isDir() ? "📁 " : e.isLink() ? "🔗 " : e.isRegular() ? "📄 " : "⚙ ";
            ((TextView) row.getChildAt(0)).setText(icon + e.name);
            String when = DateFormat.format("yyyy/MM/dd HH:mm", e.mtime * 1000L).toString();
            String what = e.isDir() ? "フォルダ" : e.isLink() ? "リンク"
                    : e.isRegular() ? Formatter.formatShortFileSize(FilesActivity.this, e.size)
                    : e.type;
            ((TextView) row.getChildAt(1)).setText(what + "  ·  " + when);
            return row;
        }
    }

    // ------------------------------------------------------- 表示・編集の画面

    private View buildEditor() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFF111827);

        editorTitle = new TextView(this);
        editorTitle.setTextColor(Color.WHITE);
        editorTitle.setTextSize(13);
        editorTitle.setPadding(dp(12), dp(10), dp(12), dp(6));
        col.addView(editorTitle);

        editorText = new EditText(this);
        editorText.setTypeface(Typeface.MONOSPACE);
        editorText.setTextSize(13);
        editorText.setTextColor(0xFFE5E7EB);
        editorText.setBackgroundColor(Color.BLACK);
        editorText.setGravity(Gravity.TOP | Gravity.START);
        editorText.setPadding(dp(10), dp(8), dp(10), dp(8));
        editorText.setHorizontallyScrolling(false);
        editorText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                editorDirty = true;
            }
        });
        col.addView(editorText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(0xFF1F2937);
        saveButton = barButton("保存", v -> saveEditor());
        saveButton.setTextColor(0xFF4ADE80);
        bar.addView(saveButton);
        bar.addView(barButton("閉じる", v -> closeEditor()));
        col.addView(bar);
        return col;
    }

    /** ファイルを読み込んで表示する。size は一覧で分かっている大きさ (不明なら -1) */
    private void openFile(final String path, final long size) {
        toast("読み込み中: " + baseName(path));
        worker.execute(() -> {
            final byte[] data;
            try {
                data = session.read(path, MAX_VIEW + 1);
            } catch (IOException e) {
                ui.post(() -> toast(e.getMessage()));
                return;
            }
            ui.post(() -> showFile(path, data));
        });
    }

    private void showFile(String path, byte[] data) {
        boolean truncated = data.length > MAX_VIEW;
        int length = Math.min(data.length, MAX_VIEW);
        boolean binary = false;
        for (int i = 0; i < Math.min(length, 8000); i++) {
            if (data[i] == 0) {
                binary = true;
                break;
            }
        }
        boolean canEdit = RootSession.isEditable(parent(path), appFiles) && !binary && !truncated;
        String note;
        if (binary) {
            note = "バイナリファイル (先頭 " + Math.min(length, HEX_BYTES) + " バイトを 16 進表示)";
            editorText.setText(hexDump(data, Math.min(length, HEX_BYTES)));
        } else {
            note = truncated ? "大きいため先頭 " + (MAX_VIEW / 1024) + " KB だけ表示 (編集不可)"
                    : canEdit ? "編集できます" : "閲覧のみ";
            editorText.setText(new String(data, 0, length, StandardCharsets.UTF_8));
        }
        editorPath = path;
        editorTitle.setText(path + "\n" + note);
        if (canEdit) {
            editorText.setTextIsSelectable(false);
            editorText.setFocusable(true);
            editorText.setFocusableInTouchMode(true);
            editorText.setCursorVisible(true);
            // 入力の種類を設定し直すと、下で外したキー入力の受け口も元に戻る
            editorText.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        } else {
            // 閲覧のみ: キー入力の受け口を外して書き換えられなくする (選択してコピーはできる)
            editorText.setKeyListener(null);
            editorText.setTextIsSelectable(true);
            editorText.setCursorVisible(false);
        }
        saveButton.setVisibility(canEdit ? View.VISIBLE : View.GONE);
        editorDirty = false;
        browser.setVisibility(View.GONE);
        editor.setVisibility(View.VISIBLE);
    }

    static String hexDump(byte[] d, int length) {
        StringBuilder sb = new StringBuilder();
        for (int off = 0; off < length; off += 8) {
            sb.append(String.format("%06x ", off));
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                if (off + i < length) {
                    int b = d[off + i] & 255;
                    sb.append(String.format(" %02x", b));
                    text.append(b >= 32 && b < 127 ? (char) b : '.');
                } else {
                    sb.append("   ");
                }
            }
            sb.append("  ").append(text).append('\n');
        }
        return sb.toString();
    }

    private void saveEditor() {
        final String path = editorPath;
        final byte[] data = editorText.getText().toString().getBytes(StandardCharsets.UTF_8);
        runJob(() -> session.write(path, data), () -> {
            editorDirty = false;
            toast("保存しました");
        });
    }

    private void closeEditor() {
        if (editorDirty && saveButton.getVisibility() == View.VISIBLE) {
            new AlertDialog.Builder(this)
                    .setMessage("保存していない変更があります。破棄して閉じますか？")
                    .setPositiveButton("破棄して閉じる", (d, w) -> hideEditor())
                    .setNegativeButton("編集に戻る", null)
                    .show();
        } else {
            hideEditor();
        }
    }

    private void hideEditor() {
        editorDirty = false;
        editor.setVisibility(View.GONE);
        browser.setVisibility(View.VISIBLE);
        reload(); // 保存で大きさや時刻が変わっているかもしれない
    }

    @Override
    public void onBackPressed() {
        if (editor.getVisibility() == View.VISIBLE) {
            closeEditor();
        } else if (!history.isEmpty()) {
            open(history.remove(history.size() - 1), false);
        } else {
            finish();
        }
    }
}
