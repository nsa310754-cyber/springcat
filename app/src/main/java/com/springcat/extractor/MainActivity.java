package com.springcat.extractor;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.method.ScrollingMovementMethod;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import com.springcat.extractor.databinding.ActivityMainBinding;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
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

    // Compression state
    private final List<Uri> compressFiles = new ArrayList<>();
    private Uri compressFolder;
    private Compressor.Format pendingFormat;

    private ActivityResultLauncher<String[]> pickArchive;
    private ActivityResultLauncher<Uri> pickOutput;
    private ActivityResultLauncher<String[]> pickFiles;
    private ActivityResultLauncher<Uri> pickFolder;
    private ActivityResultLauncher<String> createOutput;

    private static final Compressor.Format[] FORMATS = Compressor.Format.values();

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

        pickFiles = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                    if (uris != null && !uris.isEmpty()) {
                        compressFiles.clear();
                        compressFiles.addAll(uris);
                        compressFolder = null;
                        for (Uri u : uris) {
                            try {
                                getContentResolver().takePersistableUriPermission(
                                        u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            } catch (Exception ignored) { }
                        }
                        b.txtInputs.setText(uris.size() + " 個のファイルを選択");
                    }
                });

        pickFolder = registerForActivityResult(
                new ActivityResultContracts.OpenDocumentTree(), uri -> {
                    if (uri != null) {
                        compressFolder = uri;
                        compressFiles.clear();
                        try {
                            getContentResolver().takePersistableUriPermission(
                                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (Exception ignored) { }
                        DocumentFile d = DocumentFile.fromTreeUri(this, uri);
                        b.txtInputs.setText("フォルダ: " + (d != null ? d.getName() : uri.toString()));
                    }
                });

        createOutput = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/octet-stream"), uri -> {
                    if (uri != null) {
                        try {
                            getContentResolver().takePersistableUriPermission(uri,
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        } catch (Exception ignored) { }
                        runCompression(uri);
                    }
                });

        b.btnPickArchive.setOnClickListener(v -> pickArchive.launch(new String[]{"*/*"}));
        b.btnPickOutput.setOnClickListener(v -> pickOutput.launch(null));
        b.btnStart.setOnClickListener(v -> start());
        b.btnCancel.setOnClickListener(v -> cancelled.set(true));

        b.btnPickFiles.setOnClickListener(v -> pickFiles.launch(new String[]{"*/*"}));
        b.btnPickFolder.setOnClickListener(v -> pickFolder.launch(null));
        b.btnCompress.setOnClickListener(v -> startCompress());

        b.modeToggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            boolean compress = checkedId == b.btnModeCompress.getId();
            b.groupCompress.setVisibility(compress ? View.VISIBLE : View.GONE);
            b.groupDecompress.setVisibility(compress ? View.GONE : View.VISIBLE);
        });
        b.modeToggle.check(b.btnModeDecompress.getId());
    }

    private void startCompress() {
        if (running) return;
        if (compressFolder == null && compressFiles.isEmpty()) {
            toastLog("圧縮するファイルまたはフォルダを選択してください"); return;
        }
        pendingFormat = FORMATS[b.spinnerFormat.getSelectedItemPosition()];
        String suggested = "archive." + pendingFormat.ext;
        createOutput.launch(suggested);
    }

    private void runCompression(Uri output) {
        if (running) return;
        cancelled.set(false);
        running = true;
        setBusy(true);
        b.txtLog.setText("");
        appendLog("=== 圧縮開始 (" + pendingFormat.ext + ") ===");
        final long startMs = System.currentTimeMillis();
        final Uri folder = compressFolder;
        final List<Uri> files = new ArrayList<>(compressFiles);
        final Compressor.Format fmt = pendingFormat;

        pool.execute(() -> {
            Compressor.Callback cb = new Compressor.Callback() {
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
                Compressor c = new Compressor(getApplicationContext(), cb);
                List<Compressor.Item> items = (folder != null)
                        ? c.fromFolder(folder) : c.fromFiles(files);
                if (items.isEmpty()) throw new java.io.IOException("圧縮対象が空です");
                int count = c.compress(items, fmt, output);
                long sec = (System.currentTimeMillis() - startMs) / 1000;
                runOnUiThread(() -> {
                    b.progress.setProgress(100);
                    b.txtStatus.setText("完了: " + count + " 個 / " + sec + " 秒");
                    appendLog("=== 完了: " + count + " 個を圧縮 (" + sec + "秒) ===");
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
        final String password = b.editPassword.getText() != null
                ? b.editPassword.getText().toString() : "";

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
                int count = ex.extract(archiveUri, archiveName, archiveSize, outRoot, password);
                long sec = (System.currentTimeMillis() - startMs) / 1000;
                runOnUiThread(() -> {
                    b.progress.setProgress(100);
                    b.txtStatus.setText("完了: " + count + " 個 / " + sec + " 秒");
                    appendLog("=== 完了: " + count + " 個のファイルを展開 (" + sec + "秒) ===");
                });
            } catch (InterruptedException ie) {
                runOnUiThread(() -> { b.txtStatus.setText("中止しました"); appendLog("中止しました"); });
            } catch (Exception e) {
                final boolean pwLikely = looksLikePassword(e);
                runOnUiThread(() -> {
                    b.txtStatus.setText("エラー");
                    appendLog("エラー: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    if (pwLikely) {
                        appendLog("→ パスワードが間違っているか、パスワードが必要な可能性があります");
                    }
                });
            } finally {
                running = false;
                runOnUiThread(() -> setBusy(false));
            }
        });
    }

    private static boolean looksLikePassword(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m != null) {
                String s = m.toLowerCase(Locale.US);
                if (s.contains("password") || s.contains("wrong") || s.contains("encrypt")
                        || s.contains("aes") || s.contains("crc")) return true;
            }
        }
        return false;
    }

    private void setBusy(boolean busy) {
        b.btnStart.setEnabled(!busy);
        b.btnPickArchive.setEnabled(!busy);
        b.btnPickOutput.setEnabled(!busy);
        b.btnCompress.setEnabled(!busy);
        b.btnPickFiles.setEnabled(!busy);
        b.btnPickFolder.setEnabled(!busy);
        b.btnModeDecompress.setEnabled(!busy);
        b.btnModeCompress.setEnabled(!busy);
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
