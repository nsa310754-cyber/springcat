package com.springcat.apkextractor;

import android.graphics.drawable.Drawable;

public class AppInfo {
    public final String name;
    public final String packageName;
    public final String versionName;
    public final int versionCode;
    public final Drawable icon;
    public final String sourceDir;
    public final String[] splitSourceDirs;

    public AppInfo(String name, String packageName, String versionName, int versionCode,
                   Drawable icon, String sourceDir, String[] splitSourceDirs) {
        this.name = name;
        this.packageName = packageName;
        this.versionName = versionName;
        this.versionCode = versionCode;
        this.icon = icon;
        this.sourceDir = sourceDir;
        this.splitSourceDirs = splitSourceDirs;
    }

    public boolean hasSplits() {
        return splitSourceDirs != null && splitSourceDirs.length > 0;
    }
}
