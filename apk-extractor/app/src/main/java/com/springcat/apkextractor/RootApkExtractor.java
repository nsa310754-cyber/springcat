package com.springcat.apkextractor;

import android.os.Environment;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class RootApkExtractor {

    public static boolean hasRoot() {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            os.writeBytes("id\n");
            os.writeBytes("exit\n");
            os.flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            int exitCode = process.waitFor();
            return exitCode == 0 && line != null && line.contains("uid=0");
        } catch (Exception e) {
            return false;
        } finally {
            if (process != null) process.destroy();
        }
    }

    public static String extract(AppInfo app) throws IOException, InterruptedException {
        File outDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "APKExtractor");
        outDir.mkdirs();

        String safeName = app.name.replaceAll("[^a-zA-Z0-9\\u3040-\\u309F\\u30A0-\\u30FF\\u4E00-\\u9FFF_\\-. ]", "_");
        String zipName = safeName + "_v" + app.versionName + ".zip";
        File zipFile = new File(outDir, zipName);

        File tempDir = new File(outDir, ".temp_" + app.packageName);
        tempDir.mkdirs();

        try {
            copyApk(app.sourceDir, tempDir);

            if (app.hasSplits()) {
                for (String splitPath : app.splitSourceDirs) {
                    copyApk(splitPath, tempDir);
                }
            }

            createZip(tempDir, zipFile);
            return zipFile.getAbsolutePath();
        } finally {
            deleteDir(tempDir);
        }
    }

    private static void copyApk(String srcPath, File destDir) throws IOException, InterruptedException {
        File srcFile = new File(srcPath);
        String fileName = srcFile.getName();
        File destFile = new File(destDir, fileName);

        if (srcFile.canRead()) {
            copyFileDirect(srcFile, destFile);
            return;
        }

        copyFileWithRoot(srcPath, destFile);
    }

    private static void copyFileDirect(File src, File dest) throws IOException {
        byte[] buffer = new byte[8192];
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dest)) {
            int len;
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
        }
    }

    private static void copyFileWithRoot(String srcPath, File destFile) throws IOException, InterruptedException {
        Process process = Runtime.getRuntime().exec("su");
        DataOutputStream os = new DataOutputStream(process.getOutputStream());
        os.writeBytes("cp \"" + srcPath + "\" \"" + destFile.getAbsolutePath() + "\"\n");
        os.writeBytes("chmod 644 \"" + destFile.getAbsolutePath() + "\"\n");
        os.writeBytes("exit\n");
        os.flush();

        int exitCode = process.waitFor();
        process.destroy();

        if (exitCode != 0 || !destFile.exists()) {
            throw new IOException("Failed to copy: " + srcPath + " (root権限が必要です)");
        }
    }

    private static void createZip(File srcDir, File zipFile) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            File[] files = srcDir.listFiles();
            if (files == null) return;

            byte[] buffer = new byte[8192];
            for (File file : files) {
                zos.putNextEntry(new ZipEntry(file.getName()));
                try (FileInputStream fis = new FileInputStream(file)) {
                    int len;
                    while ((len = fis.read(buffer)) > 0) {
                        zos.write(buffer, 0, len);
                    }
                }
                zos.closeEntry();
            }
        }
    }

    private static void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
        dir.delete();
    }
}
