package site.ragdollp.springkey.receiver;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 受信側アプリのホーム画面。
 * ・この端末の IP / ポート / PIN を表示（送信側に入力してもらう）
 * ・サーバーの開始・停止
 * ・IME（文字入力）とアクセシビリティ（マウス操作）の有効化への導線
 */
public class MainActivity extends Activity implements ServerService.Listener {

    private TextView ipView, statusView, log;
    private Button serverBtn;
    private StringBuilder logBuf = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = dp(16);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        root.addView(title("🐈 SpringKey 受信", 22));
        root.addView(hint("この端末を「操作される側」にします。送信側アプリに下の情報を入れて接続してください。"));

        // 接続情報カード
        LinearLayout card = card();
        ipView = new TextView(this);
        ipView.setTextSize(16);
        ipView.setTypeface(Typeface.MONOSPACE);
        ipView.setPadding(0, dp(4), 0, dp(4));
        card.addView(ipView);
        root.addView(card);

        statusView = new TextView(this);
        statusView.setPadding(0, dp(8), 0, dp(8));
        statusView.setText("● 未起動");
        statusView.setTextColor(0xFFB00020);
        root.addView(statusView);

        serverBtn = bigButton("サーバーを開始", v -> toggleServer());
        root.addView(serverBtn);

        root.addView(section("必要な設定（初回のみ）"));
        root.addView(hint("1) 文字入力：この端末で SpringKey を「キーボード」に設定 → テキスト欄をタップして選択。\n2) マウス操作：SpringKey 受信の「アクセシビリティ」をオン。"));
        root.addView(bigButton("① キーボード設定を開く", v ->
                startActivitySafe(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))));
        root.addView(bigButton("② 入力方法を選ぶ（IME 切替）", v -> {
            try {
                ((android.view.inputmethod.InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker();
            } catch (Exception ignored) {}
        }));
        root.addView(bigButton("③ アクセシビリティ設定を開く", v ->
                startActivitySafe(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));

        root.addView(section("ログ"));
        log = new TextView(this);
        log.setTextSize(12);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextColor(0xFF444444);
        root.addView(log);

        setContentView(scroll);

        ensurePin();
        requestNotif();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ServerService.setListener(this);
        refreshInfo();
        refreshStatus();
    }

    @Override
    protected void onPause() {
        ServerService.setListener(null);
        super.onPause();
    }

    private void toggleServer() {
        Intent i = new Intent(this, ServerService.class);
        if (ServerService.RUNNING) {
            stopService(i);
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i);
            else startService(i);
        }
        serverBtn.postDelayed(this::refreshStatus, 400);
    }

    private void refreshStatus() {
        boolean on = ServerService.RUNNING;
        statusView.setText(on ? "● 受信中（接続待ち）" : "● 未起動");
        statusView.setTextColor(on ? 0xFF2E7D32 : 0xFFB00020);
        serverBtn.setText(on ? "サーバーを停止" : "サーバーを開始");
    }

    private void refreshInfo() {
        String ip = localIp();
        String pin = prefs().getString("pin", "----");
        ipView.setText("IP アドレス : " + ip + "\nポート       : " + ServerService.PORT + "\nPIN          : " + pin);
    }

    private void ensurePin() {
        SharedPreferences p = prefs();
        if (TextUtils.isEmpty(p.getString("pin", ""))) {
            String pin = String.format("%04d", new Random().nextInt(10000));
            p.edit().putString("pin", pin).apply();
        }
    }

    private void requestNotif() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }
    }

    /** サイトローカルな IPv4 を探す。 */
    private static String localIp() {
        try {
            List<NetworkInterface> ifs = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface ni : ifs) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a.isLoopbackAddress() || a instanceof java.net.Inet6Address) continue;
                    String h = a.getHostAddress();
                    if (h != null && h.indexOf(':') < 0) return h;
                }
            }
        } catch (Exception ignored) {
        }
        return "不明（Wi-Fi/テザリングを確認）";
    }

    // ---- ServerService.Listener ----
    @Override
    public void onLog(String line) {
        logBuf.insert(0, line + "\n");
        if (logBuf.length() > 4000) logBuf.setLength(4000);
        if (log != null) log.setText(logBuf.toString());
    }

    @Override
    public void onClient(boolean connected, String who) {
        refreshStatus();
        if (statusView != null && connected) {
            statusView.setText("● 接続中: " + who);
            statusView.setTextColor(0xFF1565C0);
        }
    }

    // ---- UI helpers ----
    private SharedPreferences prefs() { return getSharedPreferences("sk_recv", MODE_PRIVATE); }

    private void startActivitySafe(Intent i) {
        try { startActivity(i); } catch (Exception ignored) {}
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView title(String t, int sz) {
        TextView v = new TextView(this);
        v.setText(t); v.setTextSize(sz); v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(0xFF22282F); v.setPadding(0, 0, 0, dp(6));
        return v;
    }

    private TextView section(String t) {
        TextView v = new TextView(this);
        v.setText(t); v.setTextSize(14); v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(0xFFE8743B); v.setPadding(0, dp(18), 0, dp(6));
        return v;
    }

    private TextView hint(String t) {
        TextView v = new TextView(this);
        v.setText(t); v.setTextSize(13); v.setLineSpacing(dp(3), 1f);
        v.setTextColor(0xFF55606B); v.setPadding(0, 0, 0, dp(8));
        return v;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(0xFFF2F4F7);
        int p = dp(14);
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        c.setLayoutParams(lp);
        return c;
    }

    private Button bigButton(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t); b.setAllCaps(false); b.setTextSize(15);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(2));
        b.setLayoutParams(lp);
        return b;
    }
}
