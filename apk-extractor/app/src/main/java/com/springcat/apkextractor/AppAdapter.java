package com.springcat.apkextractor;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class AppAdapter extends RecyclerView.Adapter<AppAdapter.ViewHolder> {

    public interface OnExtractListener {
        void onExtract(AppInfo app);
    }

    private final List<AppInfo> apps = new ArrayList<>();
    private OnExtractListener listener;

    public void setOnExtractListener(OnExtractListener listener) {
        this.listener = listener;
    }

    public void setApps(List<AppInfo> newApps) {
        apps.clear();
        apps.addAll(newApps);
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return apps.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int pos) {
        AppInfo app = apps.get(pos);
        h.appIcon.setImageDrawable(app.icon);
        h.appName.setText(app.name);
        h.appPackage.setText(app.packageName);

        String version = "v" + app.versionName + " (" + app.versionCode + ")";
        if (app.hasSplits()) {
            version += " [split x" + app.splitSourceDirs.length + "]";
        }
        h.appVersion.setText(version);

        h.extractBtn.setOnClickListener(v -> {
            if (listener != null) listener.onExtract(app);
        });
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView appIcon;
        final TextView appName;
        final TextView appPackage;
        final TextView appVersion;
        final Button extractBtn;

        ViewHolder(View v) {
            super(v);
            appIcon = v.findViewById(R.id.appIcon);
            appName = v.findViewById(R.id.appName);
            appPackage = v.findViewById(R.id.appPackage);
            appVersion = v.findViewById(R.id.appVersion);
            extractBtn = v.findViewById(R.id.extractBtn);
        }
    }
}
