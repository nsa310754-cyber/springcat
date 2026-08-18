package com.springcat.wifiqrcode;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private TextView textSsid;
    private EditText editSsid;
    private EditText editPassword;
    private RadioGroup radioGroupSecurity;
    private CheckBox checkHidden;
    private ImageView imageQr;
    private android.view.View layoutResult;

    private Bitmap currentQrBitmap;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> detectCurrentSsid());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textSsid = findViewById(R.id.textSsid);
        editSsid = findViewById(R.id.editSsid);
        editPassword = findViewById(R.id.editPassword);
        radioGroupSecurity = findViewById(R.id.radioGroupSecurity);
        checkHidden = findViewById(R.id.checkHidden);
        imageQr = findViewById(R.id.imageQr);
        layoutResult = findViewById(R.id.layoutResult);

        findViewById(R.id.buttonRescan).setOnClickListener(v -> requestPermissionsAndDetect());
        findViewById(R.id.buttonGenerate).setOnClickListener(v -> generateQrCode());
        findViewById(R.id.buttonSave).setOnClickListener(v -> saveQrToGallery());
        findViewById(R.id.buttonShare).setOnClickListener(v -> shareQrImage());

        radioGroupSecurity.setOnCheckedChangeListener((group, checkedId) -> {
            boolean noPass = checkedId == R.id.radioNone;
            editPassword.setEnabled(!noPass);
            if (noPass) {
                editPassword.setText("");
            }
        });

        requestPermissionsAndDetect();
    }

    private void requestPermissionsAndDetect() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        }
        if (needed.isEmpty()) {
            detectCurrentSsid();
        } else {
            permissionLauncher.launch(needed.toArray(new String[0]));
        }
    }

    /** 現在接続中のWi-FiのSSIDを検出する。パスワードはOSの制限により取得できないため対象外。 */
    private void detectCurrentSsid() {
        textSsid.setText("検出中…");

        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network = cm != null ? cm.getActiveNetwork() : null;
        NetworkCapabilities caps = network != null ? cm.getNetworkCapabilities(network) : null;
        boolean isWifi = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);

        if (!isWifi) {
            textSsid.setText("Wi-Fiに接続されていません");
            return;
        }

        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        String ssid = null;
        if (wifiManager != null) {
            WifiInfo info = wifiManager.getConnectionInfo();
            if (info != null) {
                String raw = info.getSSID();
                if (raw != null && !raw.equals(WifiManager.UNKNOWN_SSID) && !raw.equals("<unknown ssid>")) {
                    ssid = stripQuotes(raw);
                }
            }
        }

        if (ssid == null || ssid.trim().isEmpty()) {
            textSsid.setText("SSIDを取得できませんでした");
            Toast.makeText(this,
                    "端末の「位置情報(GPS)」をONにしてから再検出してください。下のネットワーク名欄に手入力することもできます。",
                    Toast.LENGTH_LONG).show();
            return;
        }

        textSsid.setText(ssid);
        editSsid.setText(ssid);
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private void generateQrCode() {
        String ssid = editSsid.getText() != null ? editSsid.getText().toString().trim() : "";
        String password = editPassword.getText() != null ? editPassword.getText().toString() : "";
        boolean hidden = checkHidden.isChecked();

        if (ssid.isEmpty()) {
            editSsid.setError("ネットワーク名を入力してください");
            return;
        }

        String securityType;
        int checkedId = radioGroupSecurity.getCheckedRadioButtonId();
        if (checkedId == R.id.radioWep) {
            securityType = "WEP";
        } else if (checkedId == R.id.radioNone) {
            securityType = "nopass";
        } else {
            securityType = "WPA";
        }

        if (!"nopass".equals(securityType) && password.isEmpty()) {
            editPassword.setError("パスワードを入力してください");
            return;
        }

        String qrText = QrCodeUtil.buildWifiQrText(ssid, password, securityType, hidden);
        try {
            currentQrBitmap = QrCodeUtil.encodeToBitmap(qrText, 800);
            imageQr.setImageBitmap(currentQrBitmap);
            layoutResult.setVisibility(android.view.View.VISIBLE);
        } catch (Exception e) {
            Toast.makeText(this, "QRコードの生成に失敗しました: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void saveQrToGallery() {
        if (currentQrBitmap == null) return;
        String fileName = "wifi_qr_" + System.currentTimeMillis() + ".png";
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WifiQrCode");
                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("MediaStoreへの書き込みに失敗しました");
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    currentQrBitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "WifiQrCode");
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, fileName);
                try (FileOutputStream out = new FileOutputStream(file)) {
                    currentQrBitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                MediaStore.Images.Media.insertImage(getContentResolver(), file.getAbsolutePath(), fileName, null);
            }
            Toast.makeText(this, "画像を保存しました (ピクチャ/WifiQrCode)", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存に失敗しました: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void shareQrImage() {
        if (currentQrBitmap == null) return;
        try {
            File cacheDir = new File(getCacheDir(), "qr");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            File file = new File(cacheDir, "wifi_qr_share.png");
            try (FileOutputStream out = new FileOutputStream(file)) {
                currentQrBitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);

            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("image/png");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "QRコードを共有"));
        } catch (Exception e) {
            Toast.makeText(this, "共有に失敗しました: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
