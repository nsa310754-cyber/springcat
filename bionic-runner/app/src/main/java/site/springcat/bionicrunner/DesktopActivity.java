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

    /** デスクトップのアイコン 1 個 */
    private static final class Item {
        final int kind;
        final String label;
        final String glyph;
        final int color;
        final File file;

        Item(int kind, String label, String glyph, int color, File file) {
            this.kind = kind;
            this.label = label;
            this.glyph = glyph;
            this.color = color;
            this.file = file;
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
    private final List<AppEntry> allApps = new ArrayList<>();
    private final List<AppEntry> shownApps = new ArrayList<>();

    private File binDir;
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
    }

    @Override
    protected void onResume() {
        super.onResume();
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

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    // ------------------------------------------------------------------ 画面

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0B3A6E, 0xFF14639B, 0xFF0A2A4F}));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);

        GridView grid = new GridView(this);
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
        iconAdapter = new IconAdapter();
        grid.setAdapter(iconAdapter);
        grid.setOnItemClickListener((p, v, pos, id) -> onIconClick(items.get(pos)));
        grid.setOnItemLongClickListener((p, v, pos, id) -> onIconLongClick(items.get(pos)));
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
        items.clear();
        items.add(new Item(KIND_TERMINAL, "コマンドプロンプト", "C:\\", 0xFF111827, null));
        items.add(new Item(KIND_FILES, "エクスプローラー", "📁", 0xFFCA8A04, null));
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
        iconAdapter.notifyDataSetChanged();
    }

    private void onIconClick(Item it) {
        switch (it.kind) {
            case KIND_TERMINAL:
                openTerminal(null, null, null);
                break;
            case KIND_FILES:
                openFiles();
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
            default:
                openTerminal(MainActivity.ACTION_RUN, it.file.getName(), "");
        }
    }

    private boolean onIconLongClick(final Item it) {
        if (it.kind != KIND_EXE) return false;
        new AlertDialog.Builder(this)
                .setTitle(it.label)
                .setItems(new String[]{"実行", "引数を付けて実行", "デスクトップから削除"}, (d, which) -> {
                    if (which == 0) {
                        openTerminal(MainActivity.ACTION_RUN, it.file.getName(), "");
                    } else if (which == 1) {
                        askArgsAndRun(it);
                    } else {
                        confirmDelete(it);
                    }
                })
                .show();
        return true;
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
                .setTitle("削除")
                .setMessage(it.label + " を削除します。プログラム本体も消えます。")
                .setPositiveButton("削除", (d, w) -> {
                    // glibc 用は本体が別の場所にあるので一緒に消す
                    new File(new File(getFilesDir(), "glibc-bin"), it.file.getName()).delete();
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

    private void openFiles() {
        closeStartMenu();
        startActivity(new Intent(this, FilesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private final class IconAdapter extends BaseAdapter {
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
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(it.color);
            bg.setCornerRadius(dp(12));
            bg.setStroke(dp(1), 0x40FFFFFF);
            icon.setBackground(bg);
            icon.setText(it.glyph);
            ((TextView) cell.getChildAt(1)).setText(it.label);
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
