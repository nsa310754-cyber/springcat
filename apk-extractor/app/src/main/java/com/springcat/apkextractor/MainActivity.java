package com.springcat.apkextractor;

import android.Manifest;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.ChipGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int FILTER_ALL = 0;
    private static final int FILTER_USER = 1;
    private static final int FILTER_SYSTEM = 2;
    private static final int REQ_STORAGE = 100;
    private static final int REQ_MANAGE_STORAGE = 101;

    private final List<AppInfo> allApps = new ArrayList<>();
    private final List<AppInfo> filteredApps = new ArrayList<>();
    private AppAdapter adapter;
    private TextView appCount;
    private ProgressBar loading;
    private EditText searchBox;
    private int currentFilter = FILTER_ALL;
    private AppInfo pendingExtractApp;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        searchBox = findViewById(R.id.searchBox);
        appCount = findViewById(R.id.appCount);
        loading = findViewById(R.id.loading);
        RecyclerView appList = findViewById(R.id.appList);
        ChipGroup filterChips = findViewById(R.id.filterChips);

        adapter = new AppAdapter();
        adapter.setOnExtractListener(this::onExtractApp);
        appList.setLayoutManager(new LinearLayoutManager(this));
        appList.setAdapter(adapter);

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                applyFilter();
            }
        });

        filterChips.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.contains(R.id.chipUser)) {
                currentFilter = FILTER_USER;
            } else if (checkedIds.contains(R.id.chipSystem)) {
                currentFilter = FILTER_SYSTEM;
            } else {
                currentFilter = FILTER_ALL;
            }
            applyFilter();
        });

        loadApps();
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            new AlertDialog.Builder(this)
                    .setTitle("ストレージ権限が必要")
                    .setMessage("APKファイルをDownloadsフォルダに保存するため、\n「すべてのファイルへのアクセス」を許可してください。")
                    .setPositiveButton("設定を開く", (d, w) -> {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivityForResult(intent, REQ_MANAGE_STORAGE);
                    })
                    .setNegativeButton("キャンセル", null)
                    .show();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.READ_EXTERNAL_STORAGE
                    }, REQ_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (pendingExtractApp != null) {
                doExtract(pendingExtractApp);
                pendingExtractApp = null;
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_MANAGE_STORAGE && hasStoragePermission()) {
            if (pendingExtractApp != null) {
                doExtract(pendingExtractApp);
                pendingExtractApp = null;
            }
        }
    }

    private void loadApps() {
        loading.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            PackageManager pm = getPackageManager();
            List<PackageInfo> packages = pm.getInstalledPackages(0);
            List<AppInfo> apps = new ArrayList<>();

            for (PackageInfo pi : packages) {
                ApplicationInfo ai = pi.applicationInfo;
                String name = pm.getApplicationLabel(ai).toString();
                Drawable icon = pm.getApplicationIcon(ai);
                String[] splits = ai.splitSourceDirs;
                boolean isSystem = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;

                apps.add(new AppInfo(
                        name,
                        pi.packageName,
                        pi.versionName != null ? pi.versionName : "?",
                        pi.versionCode,
                        icon,
                        ai.sourceDir,
                        splits,
                        isSystem
                ));
            }

            Collections.sort(apps, (a, b) -> a.name.compareToIgnoreCase(b.name));

            handler.post(() -> {
                allApps.clear();
                allApps.addAll(apps);
                loading.setVisibility(View.GONE);
                applyFilter();
            });
        });
    }

    private void applyFilter() {
        filteredApps.clear();
        String q = searchBox.getText().toString().toLowerCase(Locale.getDefault()).trim();

        for (AppInfo app : allApps) {
            if (currentFilter == FILTER_USER && app.isSystemApp) continue;
            if (currentFilter == FILTER_SYSTEM && !app.isSystemApp) continue;

            if (q.isEmpty()
                    || app.name.toLowerCase(Locale.getDefault()).contains(q)
                    || app.packageName.toLowerCase(Locale.getDefault()).contains(q)) {
                filteredApps.add(app);
            }
        }

        adapter.setApps(filteredApps);
        updateCount();
    }

    private void updateCount() {
        appCount.setText(filteredApps.size() + " / " + allApps.size());
    }

    private void onExtractApp(AppInfo app) {
        StringBuilder msg = new StringBuilder();
        msg.append("アプリ: ").append(app.name).append("\n");
        msg.append("パッケージ: ").append(app.packageName).append("\n");
        msg.append("バージョン: ").append(app.versionName).append("\n");
        msg.append("種類: ").append(app.isSystemApp ? "システム" : "ユーザー").append("\n");
        msg.append("base APK: ").append(app.sourceDir).append("\n");
        if (app.hasSplits()) {
            msg.append("split APK: ").append(app.splitSourceDirs.length).append("個\n");
            for (String s : app.splitSourceDirs) {
                msg.append("  ").append(s).append("\n");
            }
        }
        msg.append("\nこのアプリをZIPで抽出しますか？");

        new AlertDialog.Builder(this)
                .setTitle("APK抽出")
                .setMessage(msg.toString())
                .setPositiveButton("抽出する", (d, w) -> {
                    if (!hasStoragePermission()) {
                        pendingExtractApp = app;
                        requestStoragePermission();
                    } else {
                        doExtract(app);
                    }
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void doExtract(AppInfo app) {
        loading.setVisibility(View.VISIBLE);
        Toast.makeText(this, getString(R.string.extracting), Toast.LENGTH_SHORT).show();

        executor.execute(() -> {
            try {
                String path = RootApkExtractor.extract(app);
                handler.post(() -> {
                    loading.setVisibility(View.GONE);
                    new AlertDialog.Builder(this)
                            .setTitle("抽出完了")
                            .setMessage("保存先:\n" + path)
                            .setPositiveButton("OK", null)
                            .show();
                });
            } catch (Exception e) {
                handler.post(() -> {
                    loading.setVisibility(View.GONE);
                    new AlertDialog.Builder(this)
                            .setTitle(getString(R.string.extract_failed))
                            .setMessage(e.getMessage())
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
