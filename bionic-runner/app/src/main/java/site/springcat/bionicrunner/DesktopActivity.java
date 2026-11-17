package site.springcat.bionicrunner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * パソコンのデスクトップ風のホーム画面。端末のホームアプリに設定できる。
 * 登録した実行ファイルがアイコンとして並び、元から入っている Android アプリは
 * タスクバーの「スタート」から起動する。
 */
public class DesktopActivity extends Activity {

    private static final int KIND_TERMINAL = 0;
    private static final int KIND_ADD = 1;
    private static final int KIND_APK = 2;
    private static final int KIND_AI = 3;
    private static final int KIND_EXE = 4;
    private static final int KIND_FILES = 5;
    private static final int KIND_SETTINGS = 6;
    private static final int KIND_TASKS = 7;
    private static final int KIND_ZIP = 8;
    private static final int KIND_FOLDER = 9;
    private static final int KIND_VM = 10;
    private static final int KIND_X11 = 11;
    private static final int KIND_LINUX_ADDONS = 12;

    /** デスクトップのアイコン 1 個 */
    private static final class Item {
        final int kind;
        /** 並びの表で使う、変わらない名前 (組み込みは "b:種類"、実行ファイルは "x:名前"、フォルダは "f:番号") */
        final String id;
        final String label;
        final String glyph;
        final int color;
        final File file;
        /** フォルダの中身 (フォルダ以外では null) */
        final List<Item> children;

        Item(int kind, String label, String glyph, int color, File file) {
            this.kind = kind;
            this.id = kind == KIND_EXE ? "x:" + file.getName() : "b:" + kind;
            this.label = label;
            this.glyph = glyph;
            this.color = color;
            this.file = file;
            this.children = null;
        }

        /** フォルダ */
        Item(String id, String name, List<Item> children) {
            this.kind = KIND_FOLDER;
            this.id = id;
            this.label = name;
            this.glyph = "";
            this.color = 0;
            this.file = null;
            this.children = children;
        }
    }

    /** スタートメニューに出す Android アプリ 1 個 */
    private static final class AppEntry {
        final String label;
        final ComponentName component;
        final Drawable icon;

        AppEntry(String label, ComponentName component, Drawable icon) {
            this.label = label;
            this.component = component;
            this.icon = icon;
        }
    }

    private static final int[] EXE_COLORS = {
            0xFF2563EB, 0xFF7C3AED, 0xFFDB2777, 0xFFEA580C, 0xFF059669, 0xFF0891B2, 0xFF4F46E5};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Item> items = new ArrayList<>();
    /** アイコンの並びとフォルダ分け。items と同じ順で、1 対 1 に対応する */
    private List<DesktopLayout.Node> layout = new ArrayList<>();
    private GridView iconGrid;
    // ドラッグ中の状態: つかんでいる項目の番号と、いま指している落とし先
    private int dragFrom = -1;
    private int dropPos = -1;
    private int dropMode = DROP_NONE;
    private static final int DROP_NONE = 0, DROP_SELF = 1, DROP_INSERT = 2, DROP_MERGE = 3;
    private final List<AppEntry> allApps = new ArrayList<>();
    private final List<AppEntry> shownApps = new ArrayList<>();

    private File binDir;
    private View desktop;
    private Button rootChip;
    private IconAdapter iconAdapter;
    private AppAdapter appAdapter;
    private View scrim;
    private View startMenu;
    private EditText search;
    private TextView appsEmpty;
    private int appLoadGeneration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binDir = new File(getFilesDir(), "bin");
        buildUi();
        // 起動時に root を要求する (Magisk ならここで許可の確認画面が出る)。
        // 使えなかった場合の説明は、初めてのときだけ自動で出す。
        checkRoot(!Prefs.rootExplained(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyWallpaper(); // 設定画面で変えた壁紙を反映する
        reloadIcons(); // ターミナル側で登録・削除した結果を反映する
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        closeStartMenu(); // ホームボタンでデスクトップに戻ったら素の状態にする
    }

    @Override
    public void onBackPressed() {
        // ホーム画面なので「戻る」で閉じない。開いているメニューだけ閉じる
        closeStartMenu();
    }

    /** root が使えるか確かめてタスクバーに表示する。explain が真なら結果の説明も出す */
    private void checkRoot(final boolean explain) {
        rootChip.setText("root …");
        rootChip.setTextColor(Color.WHITE);
        new Thread(() -> {
            final RootCheck.Result r = RootCheck.check();
            ui.post(() -> {
                boolean ok = r.state == RootCheck.State.GRANTED;
                rootChip.setText(ok ? "root ✔" : "root ✖");
                rootChip.setTextColor(ok ? 0xFF4ADE80 : 0xFFF87171);
                if (!explain) return;
                if (!ok) Prefs.setRootExplained(this);
                RootUi.show(this, r, () -> checkRoot(true));
            });
        }, "root-check").start();
    }

    private void applyWallpaper() {
        desktop.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                Prefs.wallpaperColors(this)));
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    // ------------------------------------------------------------------ 画面

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        desktop = root;
        applyWallpaper();

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);

        final GridView grid = new GridView(this);
        iconGrid = grid;
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(84));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(dp(10));
        grid.setPadding(dp(8), dp(12), dp(8), dp(12));
        grid.setClipToPadding(false);
        grid.setSelector(new GradientDrawable() {{
            setColor(0x33FFFFFF);
            setCornerRadius(dp(8));
        }});
        iconAdapter = new IconAdapter(items, true);
        grid.setAdapter(iconAdapter);
        grid.setOnItemClickListener((p, v, pos, id) -> onIconClick(items.get(pos)));
        // 長押しでアイコンをつかむ。そのまま動かせば並べ替え、別のアイコンに重ねればフォルダになる。
        // 動かさずに離したときは、これまで通りメニューを出す
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            dragFrom = pos;
            setDropTarget(pos, DROP_SELF);
            if (!v.startDragAndDrop(null, new View.DragShadowBuilder(v), null, 0)) {
                // つかめなかった (端末側の都合)。動かせない代わりに、メニューだけは出す
                endDrag();
                showIconMenu(pos);
            }
            return true;
        });
        grid.setOnDragListener((v, e) -> onIconDrag(e));
        column.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        column.addView(buildTaskbar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // メニューの外側をタップしたら閉じるための透明な板
        scrim = new View(this);
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(v -> closeStartMenu());
        root.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        startMenu = buildStartMenu();
        startMenu.setVisibility(View.GONE);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Math.min(dp(340), screenW - dp(16)), ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.BOTTOM | Gravity.START);
        lp.setMargins(dp(8), dp(32), dp(8), dp(48 + 6));
        root.addView(startMenu, lp);

        setContentView(root);
    }

    private View buildTaskbar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xE6111827);
        bar.setPadding(dp(4), 0, dp(12), 0);

        Button start = taskbarButton("▦ スタート");
        start.setTypeface(Typeface.DEFAULT_BOLD);
        start.setOnClickListener(v -> toggleStartMenu());
        bar.addView(start);

        Button term = taskbarButton("C:\\ コマンド");
        term.setOnClickListener(v -> openTerminal(null, null, null));
        bar.addView(term);

        Button files = taskbarButton("📁 ファイル");
        files.setOnClickListener(v -> openFiles());
        bar.addView(files);

        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        // root の状態。タップすると原因と直し方を表示する
        rootChip = taskbarButton("root …");
        rootChip.setOnClickListener(v -> checkRoot(true));
        bar.addView(rootChip);

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("M/d  H:mm");
        clock.setFormat24Hour("M/d  H:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(13);
        bar.addView(clock);
        return bar;
    }

    private Button taskbarButton(String label) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(12), 0, dp(12), 0);
        return b;
    }

    private View buildStartMenu() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF21F2937);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), 0x33FFFFFF);
        box.setBackground(bg);
        box.setClickable(true); // 中の余白をタップしても下の板に抜けて閉じないように
        box.setPadding(dp(8), dp(8), dp(8), dp(4));

        search = new EditText(this);
        search.setHint("アプリを検索");
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(0x99FFFFFF);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                applyFilter();
            }
        });
        box.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout listArea = new FrameLayout(this);
        ListView list = new ListView(this);
        list.setDividerHeight(0);
        appAdapter = new AppAdapter();
        list.setAdapter(appAdapter);
        list.setOnItemClickListener((p, v, pos, id) -> launchApp(shownApps.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            openAppInfo(shownApps.get(pos));
            return true;
        });
        listArea.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        appsEmpty = new TextView(this);
        appsEmpty.setTextColor(0xCCFFFFFF);
        appsEmpty.setGravity(Gravity.CENTER);
        appsEmpty.setPadding(dp(16), dp(16), dp(16), dp(16));
        listArea.addView(appsEmpty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        box.addView(listArea, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        Button home = taskbarButton("ホームアプリの設定");
        home.setOnClickListener(v -> {
            closeStartMenu();
            openSettings(Settings.ACTION_HOME_SETTINGS);
        });
        foot.addView(home);
        Button settings = taskbarButton("Android の設定");
        settings.setOnClickListener(v -> {
            closeStartMenu();
            openSettings(Settings.ACTION_SETTINGS);
        });
        foot.addView(settings);
        box.addView(foot, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        return box;
    }

    // ---------------------------------------------------- デスクトップのアイコン

    private void reloadIcons() {
        if (dragFrom >= 0) return; // つかんでいる最中に並びを作り直さない
        // いま実際にあるアイコンを、既定の順で集める
        final List<Item> items = new ArrayList<>();
        items.add(new Item(KIND_TERMINAL, "コマンドプロンプト", "C:\\", 0xFF111827, null));
        items.add(new Item(KIND_FILES, "エクスプローラー", "📁", 0xFFCA8A04, null));
        items.add(new Item(KIND_SETTINGS, "設定", "⚙", 0xFF475569, null));
        items.add(new Item(KIND_TASKS, "タスクマネージャー", "CPU", 0xFF0E7490, null));
        // 最初から入っている圧縮・解凍アプリ (アンインストールはできない)
        items.add(new Item(KIND_ZIP, "zip.exe", "ZIP", 0xFFB45309, null));
        // セキュリティ研究用の仮想マシン (64bit の ARM 端末だけ)
        if (Vm.supported(this)) items.add(new Item(KIND_VM, "仮想マシン", "VM", 0xFF7F1D1D, null));
        // X11 (Xサーバー): アプリの中の Linux 環境で X のアプリを動かす
        if (Linux.supported(this)) {
            items.add(new Item(KIND_X11, "X11", "X", 0xFF1E3A5F, null));
            items.add(new Item(KIND_LINUX_ADDONS, "Linux の追加機能", "+L", 0xFF0F766E, null));
        }
        items.add(new Item(KIND_ADD, "プログラムを追加", "+", 0xFF16A34A, null));
        items.add(new Item(KIND_APK, "APK を入れる", "APK", 0xFF65A30D, null));
        items.add(new Item(KIND_AI, "AI に作らせる", "AI", 0xFFD97706, null));
        File[] files = binDir.listFiles();
        if (files != null) {
            Arrays.sort(files);
            for (File f : files) {
                if (!f.isFile()) continue;
                String name = f.getName();
                String glyph = name.substring(0, Math.min(2, name.length()))
                        .toUpperCase(Locale.ROOT);
                // 同じ名前はいつも同じ色になるよう、名前から色を決める
                int color = EXE_COLORS[(name.hashCode() & 0x7fffffff) % EXE_COLORS.length];
                items.add(new Item(KIND_EXE, name, glyph, color, f));
            }
        }
        // 保存してある並びとフォルダ分けに当てはめる (無くなったものは外れ、増えたものは末尾に付く)
        java.util.Map<String, Item> byId = new java.util.HashMap<>();
        List<String> ids = new ArrayList<>();
        for (Item it : items) {
            byId.put(it.id, it);
            ids.add(it.id);
        }
        layout = DesktopLayout.reconcile(DesktopLayout.parse(Prefs.desktopLayout(this)), ids);
        this.items.clear();
        for (DesktopLayout.Node n : layout) {
            if (!n.isFolder()) {
                this.items.add(byId.get(n.id));
                continue;
            }
            List<Item> kids = new ArrayList<>();
            for (String c : n.children) kids.add(byId.get(c));
            this.items.add(new Item(n.id, n.name, kids));
        }
        iconAdapter.notifyDataSetChanged();
        if (!Prefs.desktopHintShown(this) && this.items.size() > 1) {
            Prefs.setDesktopHintShown(this);
            Toast.makeText(this, "アイコンは長押ししたまま動かすと並べ替えられます。"
                    + "別のアイコンに重ねるとフォルダになります", Toast.LENGTH_LONG).show();
        }
    }

    /** 並びを保存して、画面に反映する */
    private void saveLayout() {
        Prefs.setDesktopLayout(this, DesktopLayout.format(layout));
        reloadIcons();
    }

    // ------------------------------------------------------ アイコンの移動とフォルダ

    private boolean onIconDrag(android.view.DragEvent e) {
        switch (e.getAction()) {
            case android.view.DragEvent.ACTION_DRAG_STARTED:
                return dragFrom >= 0;
            case android.view.DragEvent.ACTION_DRAG_LOCATION:
                findDropTarget(e.getX(), e.getY());
                return true;
            case android.view.DragEvent.ACTION_DRAG_EXITED:
                setDropTarget(-1, DROP_NONE);
                return true;
            case android.view.DragEvent.ACTION_DROP: {
                findDropTarget(e.getX(), e.getY());
                int from = dragFrom, pos = dropPos, mode = dropMode;
                endDrag();
                if (from < 0 || from >= items.size()) return true;
                if (mode == DROP_SELF) {
                    showIconMenu(from); // 動かさずに離した: 長押しメニュー
                } else if (mode == DROP_MERGE) {
                    if (DesktopLayout.merge(layout, from, pos, DesktopLayout.newFolderId(layout),
                            "フォルダ")) {
                        saveLayout();
                    }
                } else if (mode == DROP_INSERT) {
                    DesktopLayout.move(layout, from, pos);
                    saveLayout();
                }
                return true;
            }
            case android.view.DragEvent.ACTION_DRAG_ENDED:
                endDrag(); // 画面の外などで離した場合もここに来る
                return true;
            default:
                return true;
        }
    }

    private void endDrag() {
        dragFrom = -1;
        setDropTarget(-1, DROP_NONE);
    }

    /**
     * 指の位置から落とし先を決める。アイコンの中央あたりなら「重ねる」(フォルダにする)、
     * 左右の端寄りなら「その手前 / 後ろに入れる」、何も無い所なら「末尾へ」。
     */
    private void findDropTarget(float x, float y) {
        int pos = iconGrid.pointToPosition((int) x, (int) y);
        if (pos == GridView.INVALID_POSITION || pos >= items.size()) {
            setDropTarget(items.size(), DROP_INSERT);
            return;
        }
        if (pos == dragFrom) {
            setDropTarget(pos, DROP_SELF);
            return;
        }
        View cell = iconGrid.getChildAt(pos - iconGrid.getFirstVisiblePosition());
        float rel = cell == null || cell.getWidth() == 0 ? 0.5f
                : (x - cell.getLeft()) / cell.getWidth();
        boolean draggingFolder = dragFrom >= 0 && dragFrom < items.size()
                && items.get(dragFrom).kind == KIND_FOLDER;
        if (rel > 0.25f && rel < 0.75f && !draggingFolder) setDropTarget(pos, DROP_MERGE);
        else setDropTarget(rel < 0.5f ? pos : pos + 1, DROP_INSERT);
    }

    private void setDropTarget(int pos, int mode) {
        if (pos == dropPos && mode == dropMode) return;
        dropPos = pos;
        dropMode = mode;
        iconAdapter.notifyDataSetChanged(); // 落とし先の目印を描き直す
    }

    private void showIconMenu(final int index) {
        final Item it = items.get(index);
        if (it.kind == KIND_FOLDER) {
            new AlertDialog.Builder(this)
                    .setTitle(it.label)
                    .setItems(new String[]{"開く", "名前を変更", "フォルダを解除 (中身をデスクトップに戻す)"},
                            (d, which) -> {
                                if (which == 0) {
                                    openFolder(index);
                                } else if (which == 1) {
                                    renameFolder(index);
                                } else {
                                    DesktopLayout.dissolve(layout, index);
                                    saveLayout();
                                }
                            })
                    .show();
        } else {
            onIconLongClick(it);
        }
    }

    private void renameFolder(final int index) {
        final EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setText(items.get(index).label);
        name.setSelection(name.length());
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(24), dp(8), dp(24), 0);
        wrap.addView(name);
        new AlertDialog.Builder(this)
                .setTitle("フォルダの名前")
                .setView(wrap)
                .setPositiveButton("OK", (d, w) -> {
                    String s = DesktopLayout.clean(name.getText().toString()).trim();
                    if (s.isEmpty() || index >= layout.size() || !layout.get(index).isFolder()) return;
                    layout.get(index).name = s;
                    saveLayout();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    /** フォルダを開く: 中のアイコンを並べた小さな窓を出す */
    private void openFolder(final int index) {
        final Item folder = items.get(index);
        GridView g = new GridView(this);
        g.setNumColumns(3);
        g.setVerticalSpacing(dp(8));
        g.setPadding(dp(12), dp(12), dp(12), dp(4));
        g.setAdapter(new IconAdapter(folder.children, false));
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("📁 " + folder.label)
                .setView(g)
                .setPositiveButton("閉じる", null)
                .setNeutralButton("名前を変更", (d, w) -> renameFolder(index))
                .show();
        g.setOnItemClickListener((p, v, pos, id) -> {
            dialog.dismiss();
            onIconClick(folder.children.get(pos));
        });
        g.setOnItemLongClickListener((p, v, pos, id) -> {
            final Item child = folder.children.get(pos);
            final boolean exe = child.kind == KIND_EXE;
            String[] choices = exe
                    ? new String[]{"フォルダから出す", "実行", "引数を付けて実行", "アンインストール"}
                    : new String[]{"フォルダから出す"};
            new AlertDialog.Builder(this)
                    .setTitle(child.label)
                    .setItems(choices, (d, which) -> {
                        dialog.dismiss();
                        if (which == 0) {
                            DesktopLayout.takeOut(layout, index, child.id);
                            saveLayout();
                        } else if (which == 1) {
                            openTerminal(MainActivity.ACTION_RUN, child.file.getName(), "");
                        } else if (which == 2) {
                            askArgsAndRun(child);
                        } else {
                            confirmDelete(child);
                        }
                    })
                    .show();
            return true;
        });
    }

    /** フォルダのアイコン: 半透明の台の上に、中身の色を小さな四角で 4 つまで並べる */
    private final class FolderIcon extends Drawable {
        private final List<Item> children;
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rect = new android.graphics.RectF();

        FolderIcon(List<Item> children) {
            this.children = children;
        }

        @Override
        public void draw(android.graphics.Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float r = dp(12);
            rect.set(b);
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setColor(0x66FFFFFF);
            canvas.drawRoundRect(rect, r, r, paint);
            float pad = b.width() * 0.14f, gap = b.width() * 0.08f;
            float size = (b.width() - pad * 2 - gap) / 2;
            for (int i = 0; i < children.size() && i < 4; i++) {
                float left = b.left + pad + (i % 2) * (size + gap);
                float top = b.top + pad + (i / 2) * (size + gap);
                rect.set(left, top, left + size, top + size);
                Item child = children.get(i);
                android.graphics.Bitmap picture = child.kind == KIND_EXE
                        ? Icons.load(DesktopActivity.this, child.file.getName()) : null;
                if (picture != null) {
                    paint.setColor(Color.WHITE);
                    paint.setFilterBitmap(true);
                    canvas.drawBitmap(picture, null, rect, paint);
                } else {
                    paint.setColor(child.color);
                    canvas.drawRoundRect(rect, size / 4, size / 4, paint);
                }
            }
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter filter) {
            paint.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    private void onIconClick(Item it) {
        switch (it.kind) {
            case KIND_TERMINAL:
                openTerminal(null, null, null);
                break;
            case KIND_FILES:
                openFiles();
                break;
            case KIND_SETTINGS:
                openSettings(SettingsActivity.TAB_SYSTEM);
                break;
            case KIND_ZIP:
                closeStartMenu();
                startActivity(new Intent(this, ZipActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                break;
            case KIND_VM:
                closeStartMenu();
                startActivity(new Intent(this, VmActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                break;
            case KIND_X11:
                closeStartMenu();
                startActivity(new Intent(this, X11Activity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                break;
            case KIND_LINUX_ADDONS:
                closeStartMenu();
                startActivity(new Intent(this, LinuxAddonsActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                break;
            case KIND_TASKS:
                openSettings(SettingsActivity.TAB_TASKS);
                break;
            case KIND_ADD:
                openTerminal(MainActivity.ACTION_PICK_EXEC, null, null);
                break;
            case KIND_APK:
                openTerminal(MainActivity.ACTION_PICK_APK, null, null);
                break;
            case KIND_AI:
                openTerminal(MainActivity.ACTION_AI, null, null);
                break;
            case KIND_FOLDER:
                openFolder(items.indexOf(it));
                break;
            default:
                openTerminal(MainActivity.ACTION_RUN, it.file.getName(), "");
        }
    }

    private boolean onIconLongClick(final Item it) {
        if (it.kind != KIND_EXE) return false;
        new AlertDialog.Builder(this)
                .setTitle(it.label)
                .setItems(new String[]{"実行", "引数を付けて実行", "アンインストール", "アイコンを変更",
                        "アイコンを元に戻す"}, (d, which) -> {
                    if (which == 0) {
                        openTerminal(MainActivity.ACTION_RUN, it.file.getName(), "");
                    } else if (which == 1) {
                        askArgsAndRun(it);
                    } else if (which == 2) {
                        confirmDelete(it);
                    } else if (which == 3) {
                        pickIcon(it.file.getName());
                    } else {
                        Icons.delete(this, it.file.getName());
                        reloadIcons();
                    }
                })
                .show();
        return true;
    }

    private static final int REQ_PICK_ICON = 1;
    /** アイコンを選んでいる最中のプログラムの登録名 */
    private String iconTarget;

    /** 画像を選んでもらい、そのプログラムのアイコンにする */
    private void pickIcon(String name) {
        iconTarget = name;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        try {
            startActivityForResult(i, REQ_PICK_ICON);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "ファイル選択アプリが見つかりません", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        final String name = iconTarget;
        if (requestCode != REQ_PICK_ICON || name == null) return;
        iconTarget = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        new Thread(() -> {
            android.graphics.Bitmap picture = null;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                if (in != null) picture = Icons.decode(Icons.readAll(in));
            } catch (java.io.IOException | SecurityException e) {
                picture = null;
            }
            final boolean ok = picture != null && Icons.save(this, name, picture);
            ui.post(() -> {
                if (!ok) Toast.makeText(this, "この画像は使えませんでした", Toast.LENGTH_LONG).show();
                reloadIcons();
            });
        }, "icon").start();
    }

    private void askArgsAndRun(final Item it) {
        final EditText args = new EditText(this);
        args.setSingleLine(true);
        args.setHint("引数");
        args.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(24), dp(8), dp(24), 0);
        wrap.addView(args);
        new AlertDialog.Builder(this)
                .setTitle(it.label)
                .setView(wrap)
                .setPositiveButton("実行", (d, w) -> openTerminal(MainActivity.ACTION_RUN,
                        it.file.getName(), args.getText().toString()))
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void confirmDelete(final Item it) {
        new AlertDialog.Builder(this)
                .setTitle("アンインストール")
                .setMessage(it.label + " を削除します。デスクトップのアイコンも消えます。")
                .setPositiveButton("アンインストール", (d, w) -> {
                    // glibc 用は本体が別の場所にあるので一緒に消す
                    new File(new File(getFilesDir(), "glibc-bin"), it.file.getName()).delete();
                    // 圧縮ファイルから展開したものは、展開先の一式も消す
                    Archive.deletePackage(getFilesDir(), it.file.getName());
                    // エミュレータで動かす 32bit 用は、本体が別の場所にある
                    new File(Emu.binDir(this), it.file.getName()).delete();
                    Icons.delete(this, it.file.getName());
                    it.file.delete();
                    reloadIcons();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    /** ターミナル画面を前面に出す。action があれば、開いたあとにその操作をさせる */
    private void openTerminal(String action, String name, String args) {
        closeStartMenu();
        MainActivity.setPending(action, name, args);
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    private void openSettings(int tab) {
        closeStartMenu();
        startActivity(new Intent(this, SettingsActivity.class)
                .putExtra(SettingsActivity.EXTRA_TAB, tab)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void openFiles() {
        closeStartMenu();
        startActivity(new Intent(this, FilesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private final class IconAdapter extends BaseAdapter {
        private final List<Item> items;
        /** デスクトップ本体の一覧か (ドラッグ中の目印を出すのはこちらだけ)。偽ならフォルダの中 */
        private final boolean main;

        IconAdapter(List<Item> items, boolean main) {
            this.items = items;
            this.main = main;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout cell = (LinearLayout) convertView;
            if (cell == null) {
                cell = new LinearLayout(DesktopActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(dp(2), dp(6), dp(2), dp(6));

                TextView icon = new TextView(DesktopActivity.this);
                icon.setGravity(Gravity.CENTER);
                icon.setTextColor(Color.WHITE);
                icon.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                icon.setTextSize(17);
                cell.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));

                TextView label = new TextView(DesktopActivity.this);
                label.setGravity(Gravity.CENTER_HORIZONTAL);
                label.setTextColor(Color.WHITE);
                label.setTextSize(12);
                label.setMaxLines(2);
                label.setEllipsize(TextUtils.TruncateAt.END);
                label.setShadowLayer(3f, 0f, 1f, Color.BLACK);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(4);
                cell.addView(label, lp);
            }
            Item it = items.get(position);
            TextView icon = (TextView) cell.getChildAt(0);
            // 登録したプログラムにアイコンが設定されていれば、頭文字の代わりにそれを出す
            android.graphics.Bitmap picture = it.kind == KIND_EXE
                    ? Icons.load(DesktopActivity.this, it.file.getName()) : null;
            if (it.kind == KIND_FOLDER) {
                icon.setBackground(new FolderIcon(it.children));
            } else if (picture != null) {
                icon.setBackground(new android.graphics.drawable.BitmapDrawable(getResources(), picture));
            } else {
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(it.color);
                bg.setCornerRadius(dp(12));
                bg.setStroke(dp(1), 0x40FFFFFF);
                icon.setBackground(bg);
            }
            icon.setText(picture != null ? "" : it.glyph);
            ((TextView) cell.getChildAt(1)).setText(it.label);
            // ドラッグ中の目印: つかんでいるものは薄く、重ねる先は枠で囲み、
            // 入れる位置の両隣は左右に少し寄せて「ここに入る」隙間を見せる
            boolean dragging = main && dragFrom >= 0;
            cell.setAlpha(dragging && position == dragFrom ? 0.35f : 1f);
            if (dragging && dropMode == DROP_MERGE && position == dropPos) {
                GradientDrawable ring = new GradientDrawable();
                ring.setColor(0x40FFFFFF);
                ring.setCornerRadius(dp(14));
                ring.setStroke(dp(2), Color.WHITE);
                cell.setBackground(ring);
            } else {
                cell.setBackground(null);
            }
            float shift = 0;
            if (dragging && dropMode == DROP_INSERT) {
                if (position == dropPos) shift = dp(10);
                else if (position == dropPos - 1) shift = -dp(10);
            }
            cell.setTranslationX(shift);
            return cell;
        }
    }

    // ------------------------------------------------------ スタートメニュー

    private void toggleStartMenu() {
        if (startMenu.getVisibility() == View.VISIBLE) {
            closeStartMenu();
            return;
        }
        search.setText("");
        scrim.setVisibility(View.VISIBLE);
        startMenu.setVisibility(View.VISIBLE);
        loadApps();
    }

    private void closeStartMenu() {
        if (startMenu == null || startMenu.getVisibility() != View.VISIBLE) return;
        startMenu.setVisibility(View.GONE);
        scrim.setVisibility(View.GONE);
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    /** 端末に入っている「起動できるアプリ」を集める。数が多いと時間がかかるので別スレッドで行う */
    private void loadApps() {
        final int gen = ++appLoadGeneration;
        if (allApps.isEmpty()) appsEmpty.setText("アプリを読み込み中…");
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            final List<AppEntry> found = new ArrayList<>();
            for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
                if (ri.activityInfo == null) continue;
                if (getPackageName().equals(ri.activityInfo.packageName)) continue;
                CharSequence label = ri.loadLabel(pm);
                found.add(new AppEntry(
                        label == null ? ri.activityInfo.packageName : label.toString(),
                        new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name),
                        ri.loadIcon(pm)));
            }
            Collections.sort(found, (a, b) -> a.label.compareToIgnoreCase(b.label));
            ui.post(() -> {
                if (gen != appLoadGeneration) return;
                allApps.clear();
                allApps.addAll(found);
                applyFilter();
            });
        }, "load-apps").start();
    }

    private void applyFilter() {
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        shownApps.clear();
        for (AppEntry a : allApps) {
            if (q.isEmpty() || a.label.toLowerCase(Locale.ROOT).contains(q)) shownApps.add(a);
        }
        appAdapter.notifyDataSetChanged();
        if (!shownApps.isEmpty()) {
            appsEmpty.setText("");
        } else if (!allApps.isEmpty()) {
            appsEmpty.setText("「" + search.getText().toString().trim() + "」に一致するアプリはありません");
        }
    }

    private void launchApp(AppEntry a) {
        closeStartMenu();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        i.setComponent(a.component);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, a.label + " を起動できませんでした", Toast.LENGTH_LONG).show();
        }
    }

    /** アプリ情報 (アンインストールや強制停止ができる Android の画面) を開く */
    private void openAppInfo(AppEntry a) {
        closeStartMenu();
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + a.component.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "アプリ情報を開けませんでした", Toast.LENGTH_LONG).show();
        }
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(this, "設定を開けませんでした", Toast.LENGTH_LONG).show();
            }
        }
    }

    private final class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return shownApps.size();
        }

        @Override
        public Object getItem(int position) {
            return shownApps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row = (LinearLayout) convertView;
            if (row == null) {
                row = new LinearLayout(DesktopActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(8), dp(8), dp(8), dp(8));
                ImageView icon = new ImageView(DesktopActivity.this);
                row.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
                TextView label = new TextView(DesktopActivity.this);
                label.setTextColor(Color.WHITE);
                label.setTextSize(15);
                label.setSingleLine(true);
                label.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.leftMargin = dp(14);
                row.addView(label, lp);
            }
            AppEntry a = shownApps.get(position);
            ((ImageView) row.getChildAt(0)).setImageDrawable(a.icon);
            ((TextView) row.getChildAt(1)).setText(a.label);
            return row;
        }
    }
}
