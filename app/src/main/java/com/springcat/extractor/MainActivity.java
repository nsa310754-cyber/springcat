package com.springcat.extractor;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.method.ScrollingMovementMethod;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import com.springcat.extractor.databinding.ActivityMainBinding;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding b;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile boolean running = false;

    private Uri archiveUri;
    private String archiveName = "";
    private long archiveSize = 0;
    private Uri outputTreeUri;

    private ActivityResultLauncher<String[]> pickArchive;
    private ActivityResultLauncher<Uri> pickOutput;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.txtLog.setMovementMethod(new ScrollingMovementMethod());

        pickArchive = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), uri -> {
                    if (uri != null) {
                        archiveUri = uri;
                        try {
                            getContentResolver().takePersistableUriPermission(
                                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (Exception ignored) { }
                        queryNameSize(uri);
                        b.txtArchive.setText(archiveName + "  (" + human(archiveSize) + ")");
                    }
                });

        pickOutput = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(), uri -> {
                    if (uri != null) {
                        outputTreeUri = uri;
                        try {
                            getContentResolver().takePersistableUriPermission(uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        } catch (Exception ignored) { }
                        DocumentFile d = DocumentFile.fromTreeUri(this, uri);
                        b.txtOutput.setText(d != null ? d.getName() : uri.toString());
                    }
                });

        b.btnPickArchive.setOnClickListener(v -> pickArchive.launch(new String[]{"*/*"}));
        b.btnPickOutput.setOnClickListener(v -> pickOutput.launch(null));
        b.btnStart.setOnClickListener(v -> start());
        b.btnCancel.setOnClickListener(v -> cancelled.set(true));
    }

    private void start() {
        if (running) return;
        if (archiveUri == null) { toastLog("アーカイブを選択してください"); return; }
        if (outputTreeUri == null) { toastLog("出力先フォルダを選択してください"); return; }

        DocumentFile outRoot = DocumentFile.fromTreeUri(this, outputTreeUri);
        if (outRoot == null || !outRoot.canWrite()) {
            toastLog("出力先に書き込めません"); return;
        }

        cancelled.set(false);
        running = true;
        setBusy(true);
        b.txtLog.setText("");
        appendLog("=== 解凍開始: " + archiveName + " ===");
        final long startMs = System.currentTimeMillis();

        pool.execute(() -> {
            ArchiveExtractor.Callback cb = new ArchiveExtractor.Callback() {
                @Override public void log(String line) { runOnUiThread(() -> appendLog(line)); }
                @Override public void progress(int percent, String message) {
                    runOnUiThread(() -> {
                        if (percent >= 0) b.progress.setProgress(percent);
                        b.txtStatus.setText(message);
                    });
                }
                @Override public boolean isCancelled() { return cancelled.get(); }
            };
            try {
                ArchiveExtractor ex = new ArchiveExtractor(getApplicationContext(), cb);
                int count = ex.extract(archiveUri, archiveName, archiveSize, outRoot);
                long sec = (System.currentTimeMillis() - startMs) / 1000;
                runOnUiThread(() -> {
                    b.progress.setProgress(100);
                    b.txtStatus.setText("完了: " + count + " 個 / " + sec + " 秒");
                    appendLog("=== 完了: " + count + " 個のファイルを展開 (" + sec + "秒) ===");
                });
            } catch (InterruptedException ie) {
                runOnUiThread(() -> { b.txtStatus.setText("中止しました"); appendLog("中止しました"); });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    b.txtStatus.setText("エラー");
                    appendLog("エラー: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                });
            } finally {
                running = false;
                runOnUiThread(() -> setBusy(false));
            }
        });
    }

    private void setBusy(boolean busy) {
        b.btnStart.setEnabled(!busy);
        b.btnPickArchive.setEnabled(!busy);
        b.btnPickOutput.setEnabled(!busy);
        b.btnCancel.setEnabled(busy);
    }

    private void queryNameSize(Uri uri) {
        archiveName = "archive";
        archiveSize = 0;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0 && !c.isNull(ni)) archiveName = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) archiveSize = c.getLong(si);
            }
        } catch (Exception ignored) { }
    }

    private void appendLog(String line) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        b.txtLog.append("[" + ts + "] " + line + "\n");
    }

    private void toastLog(String m) { appendLog(m); }

    private static String human(long bytes) {
        if (bytes <= 0) return "?";
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        double v = bytes;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return String.format(Locale.US, "%.1f %s", v, u[i]);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pool.shutdownNow();
    }
}
