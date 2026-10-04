package site.ragdollp.mmrlzipguard;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.View;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private TextView mStatusText;
    private TextView mRootText;
    private TextView mLogText;
    private TextView mBackupInfo;
    private Button mToggleBtn;
    private Button mOpenBackupBtn;
    private ScrollView mLogScroll;
    private boolean mServiceRunning = false;

    private final StringBuilder mLogBuffer = new StringBuilder();
    private final SimpleDateFormat mTimeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private final BroadcastReceiver mLogReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String msg = intent.getStringExtra(GuardService.EXTRA_MESSAGE);
            if (msg != null) appendLog(msg);
            refreshBackupInfo();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mStatusText = findViewById(R.id.status_text);
        mRootText = findViewById(R.id.root_text);
        mLogText = findViewById(R.id.log_text);
        mBackupInfo = findViewById(R.id.backup_info);
        mToggleBtn = findViewById(R.id.toggle_btn);
        mOpenBackupBtn = findViewById(R.id.open_backup_btn);
        mLogScroll = findViewById(R.id.log_scroll);

        mToggleBtn.setOnClickListener(v -> toggleService());
        mOpenBackupBtn.setOnClickListener(v -> openBackupDir());

        checkPermissions();
        checkRoot();
        refreshBackupInfo();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(GuardService.ACTION_LOG);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mLogReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mLogReceiver, filter);
        }
        updateUI();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mLogReceiver);
    }

    private void toggleService() {
        if (mServiceRunning) {
            stopService(new Intent(this, GuardService.class));
            mServiceRunning = false;
            appendLog("監視を停止しました");
        } else {
            if (!hasStoragePermission()) {
                requestStoragePermission();
                return;
            }
            Intent svc = new Intent(this, GuardService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
            mServiceRunning = true;
            appendLog("監視を開始しました");
        }
        updateUI();
    }

    private void updateUI() {
        if (mServiceRunning) {
            mStatusText.setText("● 監視中");
            mStatusText.setTextColor(0xFF22A06B);
            mToggleBtn.setText("監視を停止");
        } else {
            mStatusText.setText("○ 停止中");
            mStatusText.setTextColor(0xFFBB3333);
            mToggleBtn.setText("監視を開始");
        }
    }

    private void checkRoot() {
        new Thread(() -> {
            boolean hasRoot = Shell.isRootAvailable();
            runOnUiThread(() -> {
                if (hasRoot) {
                    mRootText.setText("Root: ✓ 利用可能");
                    mRootText.setTextColor(0xFF22A06B);
                } else {
                    mRootText.setText("Root: ✗ なし（通常モード）");
                    mRootText.setTextColor(0xFF999999);
                }
            });
        }).start();
    }

    private void appendLog(String msg) {
        String time = mTimeFmt.format(new Date());
        mLogBuffer.append('[').append(time).append("] ").append(msg).append('\n');
        mLogText.setText(mLogBuffer.toString());
        mLogScroll.post(() -> mLogScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void refreshBackupInfo() {
        File backupDir = new File(Environment.getExternalStorageDirectory(), "MMRL-Zip-Backup");
        if (backupDir.isDirectory()) {
            File[] zips = backupDir.listFiles((d, n) -> n.toLowerCase().endsWith(".zip"));
            int count = zips != null ? zips.length : 0;
            long totalSize = 0;
            if (zips != null) for (File f : zips) totalSize += f.length();
            String sizeStr = totalSize < 1024 * 1024
                    ? String.format(Locale.US, "%.1f KB", totalSize / 1024.0)
                    : String.format(Locale.US, "%.1f MB", totalSize / (1024.0 * 1024.0));
            mBackupInfo.setText("バックアップ: " + count + " 件 (" + sizeStr + ")\n" + backupDir.getAbsolutePath());
        } else {
            mBackupInfo.setText("バックアップ: 0 件\n" + backupDir.getAbsolutePath());
        }
    }

    private void openBackupDir() {
        File backupDir = new File(Environment.getExternalStorageDirectory(), "MMRL-Zip-Backup");
        if (!backupDir.exists()) backupDir.mkdirs();
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3AMMRL-Zip-Backup");
            intent.setDataAndType(uri, "vnd.android.document/directory");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, backupDir.getAbsolutePath(), Toast.LENGTH_LONG).show();
        }
    }

    private boolean hasStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivity(intent);
            }
            Toast.makeText(this, "「すべてのファイルへのアクセス」を許可してください", Toast.LENGTH_LONG).show();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    100);
        }
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 200);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100 && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            toggleService();
        }
    }
}
