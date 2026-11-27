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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    /** root のシェルで出来ない操作は、アプリ自身の権限で行う */
    private final FileOps ops = new FileOps(session);
    private final List<RootSession.Entry> entries = new ArrayList<>();

    private String appFiles;
    private String current = "/";
    /** 「戻る」で帰る場所の履歴 */
    private final List<String> history = new ArrayList<>();
    private boolean listed;
    /** 最初の準備 (シェルの起動) が済んだか */
    private volatile boolean started;
    private boolean currentEditable;
    /** コピー・移動の対象として覚えている場所 (無ければ空) */
    private final List<String> clipPaths = new ArrayList<>();
    private boolean clipMove;
    /** 複数選択の最中か、選んである項目の名前 */
    private boolean selecting;
    private final Set<String> selected = new HashSet<>();
    /** 「全ファイルへのアクセス」の許可を、開いたときに自分から求めたか (何度も出さない) */
    private boolean askedStorage;
    /** 許可が無いために編集できない場所を表示しているか */
    private boolean storageBlocked;

    private View browser;
    private View editor;
    private TextView pathView;
    private TextView modeView;
    private TextView emptyView;
    private LinearLayout actions;
    private View selectBar;
    private TextView selectCount;
    private final List<View> selectEditButtons = new ArrayList<>();
    private Button pasteButton;
    private EntryAdapter adapter;

    private TextView editorTitle;
    private ScrollView editorScroll;
    /** 表示中の中身。編集できるファイルでは EditText、閲覧のみでは選択できる TextView */
    private TextView editorText;
    private Button saveButton;
    private String editorPath;
    private boolean editorDirty;

    /** 最初に開くフォルダ (省略時は内部ストレージ) */
    static final String EXTRA_DIR = "dir";

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
        ops.setStageDir(getCacheDir());
        SevenZip.init(getApplicationInfo().nativeLibraryDir);

        modeView.setText("準備中… su の許可ダイアログが出たら「許可」してください");
        worker.execute(() -> {
            try {
                session.start();
                ops.setRootStarted(true);
            } catch (IOException e) {
                // シェルを起動できなくても、アプリの権限で読める範囲は表示できる
            }
            started = true;
            // 開く場所の指定があれば (アイコンの情報の「保存場所を開く」など)、そこから
            final String start = getIntent().getStringExtra(EXTRA_DIR);
            ui.post(() -> open(start != null && new File(start).isDirectory() ? start : internalStorage()));
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
                ui.post(() -> failed(e));
                return;
            }
            if (done != null) ui.post(done);
        });
    }

    /** 圧縮や解凍など長くかかる操作用。終わるまで「作業中」の表示を出して、ほかの操作をさせない */
    private void runBusy(String message, final Job job, final Runnable done) {
        final AlertDialog busy = new AlertDialog.Builder(this)
                .setMessage(message)
                .setCancelable(false)
                .show();
        worker.execute(() -> {
            IOException error = null;
            try {
                job.run();
            } catch (IOException e) {
                error = e;
            }
            final IOException fe = error;
            ui.post(() -> {
                busy.dismiss();
                if (fe != null) {
                    failed(fe);
                    reload(); // 途中まで出来たものがあれば見えるように
                } else if (done != null) {
                    done.run();
                }
            });
        });
    }

    /** 操作の失敗を知らせる。原因が「全ファイルへのアクセス」の未許可らしければ、許可の案内も出す */
    private void failed(IOException e) {
        toast(e.getMessage());
        if (needsStorageAccess(current)) askStorageAccess();
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

    /** 内部ストレージや SD カードの中で、まだ「全ファイルへのアクセス」を許可していないか */
    private boolean needsStorageAccess(String dir) {
        boolean storage = dir.startsWith("/storage") || dir.startsWith("/sdcard")
                || dir.startsWith("/mnt/") || dir.startsWith("/data/media");
        if (!storage) return false;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            return !Environment.isExternalStorageManager();
        }
        return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** 「全ファイルへのアクセス」の許可を求める。許可すると root が無くても内部ストレージを読み書きできる */
    private void askStorageAccess() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            return;
        }
        if (Environment.isExternalStorageManager()) {
            toast("全ファイルへのアクセスは許可済みです");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("ファイルへのアクセス許可")
                .setMessage("内部ストレージや SD カードを表示・編集するには、このアプリに"
                        + "「全ファイルへのアクセス」を許可してください。次の画面でスイッチをオンにして、"
                        + "戻ってくると表示されます。")
                .setPositiveButton("設定を開く", (d, w) -> {
                    try {
                        startActivity(new android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                android.net.Uri.parse("package:" + getPackageName())));
                    } catch (android.content.ActivityNotFoundException e) {
                        startActivity(new android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    }
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (listed) reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 許可の画面から戻ってきたときなど、開けていなければ開き直す
        if (browser != null && browser.getVisibility() == View.VISIBLE && !listed && started) {
            open(internalStorage());
        } else if (storageBlocked && !needsStorageAccess(current)) {
            reload(); // 許可されたので「編集できます」の表示に直す
        }
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
        places.addView(barButton("アクセス許可", v -> askStorageAccess()));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setBackgroundColor(0xFF1F2937);
        hs.addView(places);
        col.addView(hs);

        FrameLayout listArea = new FrameLayout(this);
        ListView list = new ListView(this);
        adapter = new EntryAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            if (selecting) toggleSelected(entries.get(pos).name);
            else onEntryClick(entries.get(pos));
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            if (selecting) toggleSelected(entries.get(pos).name);
            else showEntryMenu(entries.get(pos));
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

        // 複数選択の最中だけ出る帯。選んだものをまとめてコピー・移動・圧縮・削除する
        LinearLayout sel = new LinearLayout(this);
        sel.setGravity(Gravity.CENTER_VERTICAL);
        sel.setPadding(dp(12), 0, dp(4), 0);
        selectCount = new TextView(this);
        selectCount.setTextColor(Color.WHITE);
        selectCount.setTextSize(13);
        sel.addView(selectCount);
        sel.addView(barButton("すべて", v -> {
            for (RootSession.Entry e : entries) selected.add(e.name);
            updateSelection();
        }));
        sel.addView(barButton("コピー", v -> clipSelected(false)));
        Button selMove = barButton("移動", v -> clipSelected(true));
        Button selPack = barButton("圧縮", v -> {
            if (requireSelection()) askCompress(selectedPaths());
        });
        Button selDelete = barButton("削除", v -> deleteSelected());
        selDelete.setTextColor(0xFFF87171);
        sel.addView(selMove);
        sel.addView(selPack);
        sel.addView(selDelete);
        selectEditButtons.add(selMove);
        selectEditButtons.add(selPack);
        selectEditButtons.add(selDelete);
        sel.addView(barButton("解除", v -> stopSelecting()));
        HorizontalScrollView selScroll = new HorizontalScrollView(this);
        selScroll.setHorizontalScrollBarEnabled(false);
        selScroll.setBackgroundColor(0xFF1E3A8A);
        selScroll.addView(sel);
        selScroll.setVisibility(View.GONE);
        selectBar = selScroll;
        col.addView(selScroll);

        actions = new LinearLayout(this);
        actions.setBackgroundColor(0xFF1F2937);
        actions.setPadding(dp(4), 0, dp(4), 0);
        actions.addView(barButton("選択", v -> startSelecting(null)));
        actions.addView(barButton("＋ ファイル", v -> askName("新しいファイルの名前", "", name ->
                runJob(() -> ops.makeFile(join(current, name)), this::reload))));
        actions.addView(barButton("＋ フォルダ", v -> askName("新しいフォルダの名前", "", name ->
                runJob(() -> ops.makeDir(join(current, name)), this::reload))));
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
            final RootSession.Listing listing = ops.list(dir);
            ui.post(() -> {
                if (listing == null) {
                    emptyView.setText(entries.isEmpty() ? "開けませんでした" : "");
                    if (needsStorageAccess(dir)) askStorageAccess();
                    else toast("開けませんでした: " + dir);
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
        // 別のフォルダへ移ったら、前のフォルダでの選択は引き継がない
        if (!listing.path.equals(current)) {
            selecting = false;
            selected.clear();
        }
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
        // 消えたものが選ばれたままにならないようにする
        Set<String> present = new HashSet<>();
        for (RootSession.Entry e : entries) present.add(e.name);
        selected.retainAll(present);
        adapter.notifyDataSetChanged();
        pathView.setText(current);
        // root が無く「全ファイルへのアクセス」も未許可だと、一覧は見えても書き換えはできない
        storageBlocked = currentEditable && !session.isRoot() && needsStorageAccess(current);
        if (storageBlocked) {
            modeView.setText("編集するには「アクセス許可」で全ファイルへのアクセスを許可してください");
            modeView.setTextColor(0xFFFACC15);
            if (!askedStorage) {
                askedStorage = true;
                askStorageAccess();
            }
        } else if (currentEditable) {
            modeView.setText("編集できます (長押しで名前変更・コピー・移動・圧縮・解凍・削除)");
            modeView.setTextColor(0xFF4ADE80);
        } else {
            modeView.setText("閲覧のみ (システム領域は変更できません)");
            modeView.setTextColor(0xFFFACC15);
        }
        actions.setVisibility(currentEditable ? View.VISIBLE : View.GONE);
        updatePasteButton();
        updateSelection();
        emptyView.setText(entries.isEmpty() ? "空のフォルダです" : "");
    }

    private void updatePasteButton() {
        pasteButton.setVisibility(clipPaths.isEmpty() ? View.GONE : View.VISIBLE);
        if (!clipPaths.isEmpty()) {
            pasteButton.setText((clipMove ? "ここへ移動: " : "ここへコピー: ")
                    + (clipPaths.size() == 1 ? baseName(clipPaths.get(0))
                    : clipPaths.size() + " 個"));
        }
    }

    // ---------------------------------------------------------------- 複数選択

    /** 複数選択を始める。first があれば、それを選んだ状態から始める */
    private void startSelecting(String first) {
        selecting = true;
        selected.clear();
        if (first != null) selected.add(first);
        updateSelection();
    }

    private void stopSelecting() {
        selecting = false;
        selected.clear();
        updateSelection();
    }

    private void toggleSelected(String name) {
        if (!selected.remove(name)) selected.add(name);
        updateSelection();
    }

    private void updateSelection() {
        selectBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        selectCount.setText(selected.size() + " 個を選択中");
        for (View b : selectEditButtons) b.setVisibility(currentEditable ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    /** 選んである項目の場所 (一覧に出ている順) */
    private List<String> selectedPaths() {
        List<String> out = new ArrayList<>();
        for (RootSession.Entry e : entries) {
            if (selected.contains(e.name)) out.add(join(current, e.name));
        }
        return out;
    }

    private boolean requireSelection() {
        if (!selected.isEmpty()) return true;
        toast("項目をタップして選んでください");
        return false;
    }

    private void clipSelected(boolean move) {
        if (!requireSelection()) return;
        List<String> paths = selectedPaths();
        stopSelecting();
        setClip(paths, move);
    }

    private void deleteSelected() {
        if (!requireSelection()) return;
        final List<String> paths = selectedPaths();
        new AlertDialog.Builder(this)
                .setTitle("削除")
                .setMessage(paths.size() + " 個を削除します。フォルダは中身もすべて消えます。元に戻せません。")
                .setPositiveButton("削除", (d, w) -> runBusy("削除中…", () -> {
                    for (String p : paths) ops.delete(p);
                }, () -> {
                    stopSelecting();
                    reload();
                }))
                .setNegativeButton("キャンセル", null)
                .show();
    }

    // ------------------------------------------------------------ 圧縮と解凍

    /** このフォルダでまだ使われていない名前にする。同じ名前があれば「名前 (2).zip」のように番号を付ける */
    private String uniqueName(String want, Set<String> taken) {
        Set<String> used = new HashSet<>(taken);
        for (RootSession.Entry e : entries) used.add(e.name);
        String name = want;
        String stem = Archive.stripExtension(want);
        String ext = want.substring(stem.length());
        for (int i = 2; used.contains(name); i++) name = stem + " (" + i + ")" + ext;
        taken.add(name);
        return name;
    }

    /** 圧縮の形式と名前を尋ねて実行する。複数あるときは、1 つにまとめるか別々にするかも選べる */
    private void askCompress(final List<String> paths) {
        final boolean many = paths.size() > 1;
        String initial = many ? baseName(current) : baseName(paths.get(0));
        if (initial.isEmpty()) initial = "archive";
        final EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("圧縮ファイルの名前");
        name.setText(initial);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        final android.widget.RadioGroup kind = new android.widget.RadioGroup(this);
        final String[] kinds = Archive.KINDS;
        final String[] kindNotes = {"Windows などでもそのまま開ける", "よく縮む。名前の一覧も暗号化できる",
                "Linux 向け。実行できる印が残る", "Linux 向け。よく縮む", "Linux 向け", "まとめるだけで縮めない"};
        final int[] kindIds = new int[kinds.length];
        for (int i = 0; i < kinds.length; i++) {
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setText(kinds[i] + "  (" + kindNotes[i] + ")");
            rb.setTextSize(13);
            kindIds[i] = View.generateViewId();
            rb.setId(kindIds[i]);
            kind.addView(rb);
        }
        kind.check(kindIds[0]);
        final EditText password = new EditText(this);
        password.setSingleLine(true);
        password.setHint("パスワード (zip と 7z のみ。空なら暗号化しない)");
        // 打ち間違えると二度と開けなくなるので、入力した文字が見える形にする
        password.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        kind.setOnCheckedChangeListener((g, id) -> {
            for (int i = 0; i < kinds.length; i++) {
                if (kindIds[i] == id) password.setEnabled(Archive.canEncrypt(kinds[i]));
            }
        });
        final android.widget.CheckBox each = new android.widget.CheckBox(this);
        each.setText("1 つにまとめず、それぞれ別の圧縮ファイルにする");
        each.setOnCheckedChangeListener((b, on) -> name.setEnabled(!on));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(name);
        box.addView(kind);
        box.addView(password);
        if (many) box.addView(each);
        ScrollView boxScroll = new ScrollView(this);
        boxScroll.addView(box);
        new AlertDialog.Builder(this)
                .setTitle(many ? paths.size() + " 個を圧縮" : "圧縮: " + baseName(paths.get(0)))
                .setView(boxScroll)
                .setPositiveButton("圧縮", (d, w) -> {
                    String chosen = kinds[0];
                    for (int i = 0; i < kinds.length; i++) {
                        if (kindIds[i] == kind.getCheckedRadioButtonId()) chosen = kinds[i];
                    }
                    final String tar = chosen;
                    final String ext = "." + chosen;
                    final boolean separate = many && each.isChecked();
                    final char[] pw = Archive.canEncrypt(chosen)
                            ? password.getText().toString().toCharArray() : null;
                    String stem = name.getText().toString().trim();
                    // 拡張子まで入力されていたら二重に付けない
                    if (stem.toLowerCase(java.util.Locale.ROOT).endsWith(ext)) {
                        stem = stem.substring(0, stem.length() - ext.length());
                    }
                    if (!separate && !validName(stem)) {
                        toast("その名前は使えません");
                        return;
                    }
                    final Set<String> taken = new HashSet<>();
                    final List<String> outputs = new ArrayList<>();
                    if (separate) {
                        for (String p : paths) outputs.add(uniqueName(baseName(p) + ext, taken));
                    } else {
                        outputs.add(uniqueName(stem + ext, taken));
                    }
                    final String here = current;
                    runBusy("圧縮中…", () -> {
                        if (separate) {
                            for (int i = 0; i < paths.size(); i++) {
                                ops.compress(Collections.singletonList(paths.get(i)),
                                        join(here, outputs.get(i)), tar, pw);
                            }
                        } else {
                            ops.compress(paths, join(here, outputs.get(0)), tar, pw);
                        }
                    }, () -> {
                        stopSelecting();
                        reload();
                        toast("圧縮しました: " + (outputs.size() == 1 ? outputs.get(0)
                                : outputs.size() + " 個"));
                    });
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    /** 圧縮ファイルを、同じフォルダに新しいフォルダを作って解凍する */
    private void extractHere(final String path) {
        String base = baseName(path);
        String stem = Archive.stripExtension(base);
        if (stem.equals(base)) stem = base + "_解凍";
        extractTo(path, join(current, uniqueName(stem, new HashSet<String>())), null);
    }

    /** 解凍する。パスワード付きの zip で、パスワードが無いか違っていれば、尋ねてやり直す */
    private void extractTo(final String path, final String dest, final char[] password) {
        final AlertDialog busy = new AlertDialog.Builder(this)
                .setMessage("解凍中…")
                .setCancelable(false)
                .show();
        worker.execute(() -> {
            IOException error = null;
            try {
                ops.extract(path, dest, password);
            } catch (IOException e) {
                error = e;
            }
            final IOException fe = error;
            ui.post(() -> {
                busy.dismiss();
                if (fe instanceof ZipCrypt.PasswordException) {
                    askPassword(baseName(path), ((ZipCrypt.PasswordException) fe).wrong,
                            pw -> extractTo(path, dest, pw.toCharArray()));
                } else if (fe != null) {
                    failed(fe);
                    reload();
                } else {
                    reload();
                    toast("解凍しました: " + baseName(dest));
                }
            });
        });
    }

    private void askPassword(String name, boolean wrong, final NameUse use) {
        final EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(24), dp(8), dp(24), 0);
        wrap.addView(field);
        new AlertDialog.Builder(this)
                .setTitle(wrong ? "パスワードが違います" : "パスワード付きの圧縮ファイルです")
                .setMessage(name + " のパスワードを入力してください。")
                .setView(wrap)
                .setPositiveButton("解凍", (d, w) -> use.use(field.getText().toString()))
                .setNegativeButton("キャンセル", null)
                .show();
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
                final RootSession.Listing l = ops.list(path);
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
        if (e.isRegular() && Lang.of(e.name) != null) {
            labels.add("実行 (ゲームモード / 実行モード)");
            runs.add(() -> {
                // コマンドプロンプトへ渡す。そこでモードを選び、出力もそこに出る
                MainActivity.setPending(MainActivity.ACTION_RUN_FILE, path, null);
                startActivity(new android.content.Intent(this, MainActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            });
        }
        if (e.isRegular() && e.name.toLowerCase(java.util.Locale.ROOT).endsWith(".exe")) {
            labels.add("Wine で実行 (Windows のプログラム)");
            runs.add(() -> {
                // コマンドプロンプトへ渡す。出力はそこに出て、ウィンドウは X11 の画面に出る
                MainActivity.setPending(MainActivity.ACTION_WINE, path, null);
                startActivity(new android.content.Intent(this, MainActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            });
        }
        labels.add("選択 (複数選んでまとめて操作)");
        runs.add(() -> startSelecting(e.name));
        labels.add("コピー (貼り付け先を開いて「ここへコピー」)");
        runs.add(() -> setClip(Collections.singletonList(path), false));
        if (currentEditable) {
            if (e.isRegular() && Archive.looksLikeArchive(e.name)) {
                labels.add("解凍 (ここにフォルダを作る)");
                runs.add(() -> extractHere(path));
            }
            labels.add("圧縮 (zip / 7z / tar.gz など)");
            runs.add(() -> askCompress(Collections.singletonList(path)));
            labels.add("移動");
            runs.add(() -> setClip(Collections.singletonList(path), true));
            labels.add("名前を変更");
            runs.add(() -> askName("新しい名前", e.name, name ->
                    runJob(() -> ops.move(path, join(current, name)), this::reload)));
            labels.add("削除");
            runs.add(() -> new AlertDialog.Builder(this)
                    .setTitle("削除")
                    .setMessage(e.name + " を削除します。"
                            + (e.isDir() ? "フォルダの中身もすべて消えます。" : "") + "元に戻せません。")
                    .setPositiveButton("削除", (d, w) ->
                            runJob(() -> ops.delete(path), this::reload))
                    .setNegativeButton("キャンセル", null)
                    .show());
        }
        new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setItems(labels.toArray(new String[0]), (d, which) -> runs.get(which).run())
                .show();
    }

    private void setClip(List<String> paths, boolean move) {
        clipPaths.clear();
        clipPaths.addAll(paths);
        clipMove = move;
        updatePasteButton();
        toast(currentEditable ? "貼り付け先のフォルダを開いてください"
                : "内部ストレージか SD カードのフォルダを開くと貼り付けられます");
    }

    private void paste() {
        if (clipPaths.isEmpty()) return;
        final List<String> from = new ArrayList<>(clipPaths);
        final boolean move = clipMove;
        final String here = current;
        for (String f : from) {
            if ((here + "/").startsWith(f + "/")) {
                toast("自分自身の中へは貼り付けられません");
                return;
            }
        }
        runBusy(move ? "移動中…" : "コピー中…", () -> {
            for (String f : from) {
                String to = join(here, baseName(f));
                if (move) ops.move(f, to);
                else ops.copy(f, to);
            }
        }, () -> {
            if (move) clipPaths.clear(); // 移動は 1 回きり。コピーは続けて別の場所にも貼れる
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
            boolean on = selecting && selected.contains(e.name);
            ((TextView) row.getChildAt(0)).setText((selecting ? (on ? "☑ " : "☐ ") : "")
                    + icon + e.name);
            row.setBackgroundColor(on ? 0x553B82F6 : Color.TRANSPARENT);
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

        // 中身は showFile がファイルごとに作り直して、この中に入れる。縦のスクロールはここが受け持つ
        editorScroll = new ScrollView(this);
        editorScroll.setFillViewport(true);
        editorScroll.setBackgroundColor(Color.BLACK);
        col.addView(editorScroll, new LinearLayout.LayoutParams(
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
                data = ops.read(path, MAX_VIEW + 1);
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
        final String text;
        if (binary) {
            note = "バイナリファイル (先頭 " + Math.min(length, HEX_BYTES) + " バイトを 16 進表示)";
            text = hexDump(data, Math.min(length, HEX_BYTES));
        } else {
            note = truncated ? "大きいため先頭 " + (MAX_VIEW / 1024) + " KB だけ表示 (編集不可)"
                    : canEdit ? "編集できます" : "閲覧のみ";
            text = new String(data, 0, length, StandardCharsets.UTF_8);
        }
        editorPath = path;
        editorTitle.setText(path + "\n" + note);
        // 表示欄は使い回さず、開くたびに作り直す。編集用と閲覧用で設定を切り替えて使い回すと、
        // 前の設定が残って「タップしても入力できない」「スクロールできない」状態になることがあった。
        final TextView view;
        if (canEdit) {
            EditText e = new EditText(this);
            e.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            view = e;
        } else {
            view = new TextView(this);
            view.setTextIsSelectable(true); // 閲覧のみ: 書き換えられないが、選択してコピーはできる
        }
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextSize(13);
        view.setTextColor(0xFFE5E7EB);
        view.setBackgroundColor(Color.BLACK);
        view.setGravity(Gravity.TOP | Gravity.START);
        view.setPadding(dp(10), dp(8), dp(10), dp(8));
        view.setHorizontallyScrolling(false);
        view.setText(text);
        if (canEdit) {
            view.addTextChangedListener(new TextWatcher() {
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
        }
        editorText = view;
        editorScroll.removeAllViews();
        editorScroll.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        editorScroll.scrollTo(0, 0);
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
        if (editorText == null) return;
        final byte[] data = editorText.getText().toString().getBytes(StandardCharsets.UTF_8);
        runJob(() -> ops.write(path, data), () -> {
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
        editorScroll.removeAllViews(); // 大きなファイルの中身を抱えたままにしない
        editorText = null;
        editor.setVisibility(View.GONE);
        browser.setVisibility(View.VISIBLE);
        reload(); // 保存で大きさや時刻が変わっているかもしれない
    }

    @Override
    public void onBackPressed() {
        if (editor.getVisibility() == View.VISIBLE) {
            closeEditor();
        } else if (selecting) {
            stopSelecting();
        } else if (!history.isEmpty()) {
            open(history.remove(history.size() - 1), false);
        } else {
            finish();
        }
    }
}
