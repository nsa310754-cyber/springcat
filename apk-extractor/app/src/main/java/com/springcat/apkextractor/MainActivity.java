package com.springcat.apkextractor;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private final List<AppInfo> allApps = new ArrayList<>();
    private final List<AppInfo> filteredApps = new ArrayList<>();
    private AppAdapter adapter;
    private TextView appCount;
    private ProgressBar loading;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        EditText searchBox = findViewById(R.id.searchBox);
        appCount = findViewById(R.id.appCount);
        loading = findViewById(R.id.loading);
        RecyclerView appList = findViewById(R.id.appList);

        adapter = new AppAdapter();
        adapter.setOnExtractListener(this::onExtractApp);
        appList.setLayoutManager(new LinearLayoutManager(this));
        appList.setAdapter(adapter);

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                filterApps(s.toString());
            }
        });

        loadApps();
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

                apps.add(new AppInfo(
                        name,
                        pi.packageName,
                        pi.versionName != null ? pi.versionName : "?",
                        pi.versionCode,
                        icon,
                        ai.sourceDir,
                        splits
                ));
            }

            Collections.sort(apps, (a, b) -> a.name.compareToIgnoreCase(b.name));

            handler.post(() -> {
                allApps.clear();
                allApps.addAll(apps);
                filteredApps.clear();
                filteredApps.addAll(apps);
                adapter.setApps(filteredApps);
                loading.setVisibility(View.GONE);
                updateCount();
            });
        });
    }

    private void filterApps(String query) {
        filteredApps.clear();
        String q = query.toLowerCase(Locale.getDefault()).trim();
        if (q.isEmpty()) {
            filteredApps.addAll(allApps);
        } else {
            for (AppInfo app : allApps) {
                if (app.name.toLowerCase(Locale.getDefault()).contains(q)
                        || app.packageName.toLowerCase(Locale.getDefault()).contains(q)) {
                    filteredApps.add(app);
                }
            }
        }
        adapter.setApps(filteredApps);
        updateCount();
    }

    private void updateCount() {
        appCount.setText(filteredApps.size() + " / " + allApps.size() + " アプリ");
    }

    private void onExtractApp(AppInfo app) {
        StringBuilder msg = new StringBuilder();
        msg.append("アプリ: ").append(app.name).append("\n");
        msg.append("パッケージ: ").append(app.packageName).append("\n");
        msg.append("バージョン: ").append(app.versionName).append("\n");
        msg.append("base APK: ").append(app.sourceDir).append("\n");
        if (app.hasSplits()) {
            msg.append("split APK: ").append(app.splitSourceDirs.length).append("個\n");
            for (String s : app.splitSourceDirs) {
                msg.append("  ").append(s).append("\n");
            }
        }
        msg.append("\nこのアプリをZIPで抽出しますか？\n");
        msg.append("(root権限が必要です)");

        new AlertDialog.Builder(this)
                .setTitle("APK抽出")
                .setMessage(msg.toString())
                .setPositiveButton("抽出する", (d, w) -> doExtract(app))
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
                    String errMsg = e.getMessage();
                    if (errMsg != null && errMsg.contains("su")) {
                        errMsg = getString(R.string.no_root);
                    }
                    new AlertDialog.Builder(this)
                            .setTitle(getString(R.string.extract_failed))
                            .setMessage(errMsg)
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
