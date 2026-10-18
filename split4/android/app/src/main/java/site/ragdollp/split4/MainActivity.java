package site.ragdollp.split4;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 設定画面: 4 つのスロットにアプリを割り当て、4 分割起動や常駐通知を操作する。 */
public class MainActivity extends Activity {
    private static final String[] SLOT_NAMES = {"左上", "右上", "左下", "右下"};
    private static final int[] SLOT_COLORS = {0xFFE8EAF6, 0xFFFFF8E1, 0xFFE0F7FA, 0xFFFFEBEE};
    private static final int[] GAPS = {0, 8, 16, 32};

    private Prefs prefs;
    private final ImageView[] slotIcons = new ImageView[Prefs.SLOTS];
    private final TextView[] slotLabels = new TextView[Prefs.SLOTS];
    private TextView rootStatus;
    private Button gapButton;
    private Switch serviceSwitch;

    /** ランチャーに表示されるアプリ一覧 (初回のアプリ選択時に読み込む)。 */
    private List<ResolveInfo> apps;
    private final Map<String, Drawable> iconCache = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        setContentView(buildUi());
        refreshSlots();
        checkRoot();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (serviceSwitch != null) serviceSwitch.setChecked(SplitService.running);
    }

    // ------------------------------------------------------------------ UI

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        root.addView(text("各マスをタップしてアプリを選択 (長押しで解除)", 14, false));

        for (int row = 0; row < 2; row++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int col = 0; col < 2; col++) {
                line.addView(slotView(row * 2 + col), new LinearLayout.LayoutParams(0, dp(130), 1f));
            }
            root.addView(line);
        }

        Button launch = new Button(this);
        launch.setText("4分割で起動");
        launch.setTextSize(20);
        launch.setOnClickListener(v -> {
            startActivity(new Intent(this, LaunchActivity.class));
            moveTaskToBack(true);
        });
        root.addView(launch, matchWrap(dp(12)));

        rootStatus = text("root: 確認中…", 14, false);
        root.addView(rootStatus, matchWrap(dp(16)));

        Button freeform = new Button(this);
        freeform.setText("フリーフォームを有効化 (root)");
        freeform.setOnClickListener(v -> runInBackground(() -> {
            Root.reset();
            String msg = SplitLauncher.enableFreeform();
            runOnUiThread(() -> {
                updateRootStatus(Root.isAvailable());
                new AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show();
            });
        }));
        root.addView(freeform, matchWrap(dp(4)));

        Switch forceStop = new Switch(this);
        forceStop.setText("起動前に4つのアプリを一度終了する (root・推奨)\n全画面で開いたままのアプリも確実に分割表示されます");
        forceStop.setChecked(prefs.forceStop());
        forceStop.setOnCheckedChangeListener((b, on) -> prefs.setForceStop(on));
        root.addView(forceStop, matchWrap(dp(12)));

        gapButton = new Button(this);
        updateGapButton();
        gapButton.setOnClickListener(v -> {
            int idx = 0;
            for (int i = 0; i < GAPS.length; i++) if (GAPS[i] == prefs.gap()) idx = i;
            prefs.setGap(GAPS[(idx + 1) % GAPS.length]);
            updateGapButton();
        });
        root.addView(gapButton, matchWrap(dp(8)));

        serviceSwitch = new Switch(this);
        serviceSwitch.setText("常駐通知 (通知のボタンから4分割起動)");
        serviceSwitch.setChecked(SplitService.running);
        serviceSwitch.setOnCheckedChangeListener((b, on) -> {
            if (on) {
                if (Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
                }
                SplitService.start(this);
            } else {
                SplitService.stop(this);
            }
        });
        root.addView(serviceSwitch, matchWrap(dp(12)));

        Switch boot = new Switch(this);
        boot.setText("端末の起動時に常駐通知を開始");
        boot.setChecked(prefs.startOnBoot());
        boot.setOnCheckedChangeListener((b, on) -> prefs.setStartOnBoot(on));
        root.addView(boot, matchWrap(dp(12)));

        Button shortcut = new Button(this);
        shortcut.setText("ホーム画面に「4分割起動」ショートカットを追加");
        shortcut.setOnClickListener(v -> addShortcut());
        root.addView(shortcut, matchWrap(dp(12)));

        root.addView(text("クイック設定パネル (通知を下に引いた所) の編集から「4分割起動」タイルも追加できます。\n\n"
                + "うまく分割されない場合: 「フリーフォームを有効化」を押してから端末を再起動してください。"
                + "メーカー独自 ROM ではフリーフォームが使えないことがあります。", 13, false), matchWrap(dp(16)));

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        return sv;
    }

    private View slotView(int i) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SLOT_COLORS[i]);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), 0xFF9E9E9E);
        box.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(130), 1f);
        box.setLayoutParams(lp);

        ImageView icon = new ImageView(this);
        box.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView label = text("", 14, true);
        label.setGravity(Gravity.CENTER);
        label.setPadding(dp(4), dp(6), dp(4), 0);
        box.addView(label);

        slotIcons[i] = icon;
        slotLabels[i] = label;
        box.setOnClickListener(v -> pickApp(i));
        box.setOnLongClickListener(v -> {
            prefs.setSlot(i, null);
            refreshSlots();
            return true;
        });

        // マス同士の間隔
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(4), dp(4), dp(4), dp(4));
        wrap.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        return wrap;
    }

    private void refreshSlots() {
        PackageManager pm = getPackageManager();
        for (int i = 0; i < Prefs.SLOTS; i++) {
            ComponentName cn = prefs.getSlot(i);
            String name = null;
            Drawable icon = null;
            if (cn != null) {
                try {
                    name = pm.getActivityInfo(cn, 0).loadLabel(pm).toString();
                    icon = pm.getActivityIcon(cn);
                } catch (PackageManager.NameNotFoundException e) {
                    prefs.setSlot(i, null); // アンインストールされた
                }
            }
            slotLabels[i].setText(SLOT_NAMES[i] + "\n" + (name == null ? "(未設定)" : name));
            if (icon != null) {
                slotIcons[i].setImageDrawable(icon);
                slotIcons[i].clearColorFilter();
            } else {
                slotIcons[i].setImageResource(R.drawable.ic_grid);
                slotIcons[i].setColorFilter(0xFF9E9E9E);
            }
        }
    }

    private void updateGapButton() {
        gapButton.setText("ウィンドウの隙間: " + prefs.gap() + "px (タップで変更)");
    }

    private void checkRoot() {
        runInBackground(() -> {
            boolean ok = Root.isAvailable();
            runOnUiThread(() -> updateRootStatus(ok));
        });
    }

    private void updateRootStatus(boolean ok) {
        if (ok) {
            rootStatus.setText("root: 利用可能 ✓ (root モードで起動します)");
            rootStatus.setTextColor(0xFF2E7D32);
        } else {
            rootStatus.setText("root: 利用不可 (root なしモードで起動します)");
            rootStatus.setTextColor(0xFFC62828);
        }
    }

    // ------------------------------------------------------------------ app picker

    private void pickApp(int slot) {
        if (apps != null) {
            showPicker(slot);
            return;
        }
        Toast.makeText(this, "アプリ一覧を読み込み中…", Toast.LENGTH_SHORT).show();
        runInBackground(() -> {
            PackageManager pm = getPackageManager();
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> list = new ArrayList<>();
            Map<ResolveInfo, String> labels = new HashMap<>();
            for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                if (ri.activityInfo.packageName.equals(getPackageName())) continue;
                labels.put(ri, ri.loadLabel(pm).toString());
                list.add(ri);
            }
            Collator col = Collator.getInstance();
            list.sort((a, b) -> col.compare(labels.get(a), labels.get(b)));
            runOnUiThread(() -> {
                apps = list;
                appLabels = labels;
                showPicker(slot);
            });
        });
    }

    private Map<ResolveInfo, String> appLabels;

    private void showPicker(int slot) {
        PackageManager pm = getPackageManager();
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return apps.size(); }
            @Override public Object getItem(int p) { return apps.get(p); }
            @Override public long getItemId(int p) { return p; }

            @Override
            public View getView(int p, View convert, ViewGroup parent) {
                LinearLayout row = (LinearLayout) convert;
                if (row == null) {
                    row = new LinearLayout(MainActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(16), dp(8), dp(16), dp(8));
                    row.addView(new ImageView(MainActivity.this), new LinearLayout.LayoutParams(dp(40), dp(40)));
                    TextView t = text("", 16, false);
                    t.setPadding(dp(16), 0, 0, 0);
                    row.addView(t);
                }
                ResolveInfo ri = apps.get(p);
                String key = ri.activityInfo.packageName + "/" + ri.activityInfo.name;
                Drawable d = iconCache.get(key);
                if (d == null) {
                    d = ri.loadIcon(pm);
                    iconCache.put(key, d);
                }
                ((ImageView) row.getChildAt(0)).setImageDrawable(d);
                ((TextView) row.getChildAt(1)).setText(appLabels.get(ri));
                return row;
            }
        };
        new AlertDialog.Builder(this)
                .setTitle(SLOT_NAMES[slot] + " に表示するアプリ")
                .setAdapter(adapter, (d, which) -> {
                    ResolveInfo ri = apps.get(which);
                    prefs.setSlot(slot, new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
                    refreshSlots();
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    // ------------------------------------------------------------------ misc

    private void addShortcut() {
        ShortcutManager sm = getSystemService(ShortcutManager.class);
        if (sm == null || !sm.isRequestPinShortcutSupported()) {
            Toast.makeText(this, "このホームアプリはショートカットの追加に対応していません", Toast.LENGTH_LONG).show();
            return;
        }
        ShortcutInfo si = new ShortcutInfo.Builder(this, "launch4")
                .setShortLabel(getString(R.string.shortcut_label))
                .setIcon(Icon.createWithResource(this, R.drawable.ic_launcher))
                .setIntent(new Intent("site.ragdollp.split4.LAUNCH").setClass(this, LaunchActivity.class))
                .build();
        sm.requestPinShortcut(si, null);
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.DKGRAY);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout.LayoutParams matchWrap(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static void runInBackground(Runnable r) {
        new Thread(r, "split4-bg").start();
    }
}
