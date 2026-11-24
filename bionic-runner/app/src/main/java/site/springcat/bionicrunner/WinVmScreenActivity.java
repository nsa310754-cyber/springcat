package site.springcat.bionicrunner;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Windows の仮想マシンの画面。QEMU の VNC (127.0.0.1・パスワード付き) を VncView で映して操作する。
 * 画面を閉じても仮想マシンは動き続ける (止めるのは「シャットダウン」か、Windows の中から)。
 *
 * 操作: 軽く触る = 左クリック (タッチの場所にそのままポインタが動く)、長押しして離す = 右クリック、
 * 長押ししてなぞる = 左ボタンを押したまま動かす、1 本指でなぞる = スクロール、2 本指 = 拡大・縮小と移動。
 */
public class WinVmScreenActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private VncView vnc;
    private TextView status;
    private Button ctrlButton, altButton, rightButton;
    private boolean leaving;
    private int retries;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Windows 11 (仮想マシン)");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFF000000);
        status = new TextView(this);
        status.setTextColor(0xFFE5E7EB);
        status.setTextSize(12);
        status.setPadding(GameKeys.dp(this, 8), GameKeys.dp(this, 4), GameKeys.dp(this, 8), GameKeys.dp(this, 4));
        status.setText("つないでいます…");
        col.addView(status);
        vnc = new VncView(this);
        vnc.onModifiersUsed = this::refreshToggles;
        vnc.listener = new VncView.Listener() {
            @Override
            public void onConnected(int width, int height, String name) {
                retries = 0;
                status.setText("Windows の仮想マシン (" + width + "×" + height + ")。閉じても仮想マシンは動き続けます");
            }

            @Override
            public void onDisconnected(String reason) {
                if (leaving) return;
                // 起動の直後は QEMU の VNC がまだ待ち受けていないことがあるので、少し待ってつなぎ直す
                if (retries++ < 10 && WinVm.runningPort(WinVmScreenActivity.this) > 0) {
                    status.setText("つなぎ直しています… (" + reason + ")");
                    ui.postDelayed(WinVmScreenActivity.this::connect, 1500);
                    return;
                }
                status.setText("切れました: " + reason);
                new AlertDialog.Builder(WinVmScreenActivity.this)
                        .setTitle("Windows の画面が切れました")
                        .setMessage(reason + "\n\n仮想マシンが止まったか、つながらなくなりました。")
                        .setPositiveButton("閉じる", (d, w) -> finish())
                        .setNegativeButton("もう一度つなぐ", (d, w) -> {
                            retries = 0;
                            connect();
                        })
                        .show();
            }
        };
        col.addView(vnc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        col.addView(buildBar());
        setContentView(col);
        connect();
    }

    private void connect() {
        new Thread(() -> {
            int port = WinVm.runningPort(this);
            String pw = WinVm.password(this);
            ui.post(() -> {
                if (port <= 0 || pw == null) {
                    status.setText("仮想マシンが動いていません");
                    Toast.makeText(this, "仮想マシンが動いていません。前の画面で起動してください", Toast.LENGTH_LONG).show();
                    return;
                }
                vnc.connectTcp(port, pw, "仮想マシンが止まりました");
            });
        }, "winvm-connect").start();
    }

    private View buildBar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = GameKeys.dp(this, 6);
        row.setPadding(p, p, p, p);
        add(row, "⌨", v -> vnc.showKeyboard());
        add(row, "Esc", v -> vnc.tapKey(VncView.XK_ESCAPE, false, false));
        add(row, "Tab", v -> vnc.tapKey(VncView.XK_TAB, false, false));
        add(row, "Win", v -> vnc.tapKey(VncView.XK_SUPER_L, false, false));
        ctrlButton = add(row, "Ctrl", v -> {
            vnc.ctrlNext = !vnc.ctrlNext;
            refreshToggles();
        });
        altButton = add(row, "Alt", v -> {
            vnc.altNext = !vnc.altNext;
            refreshToggles();
        });
        // インストールの途中でコマンドプロンプトを開く (要件の確認を手で外すとき)
        add(row, "Shift+F10", v -> vnc.tapKey(VncView.XK_F1 + 9, false, false, true, false));
        add(row, "Ctrl+Alt+Del", v -> vnc.tapKey(VncView.XK_DELETE, true, true));
        add(row, "Enter", v -> vnc.tapKey(VncView.XK_RETURN, false, false));
        add(row, "←", v -> vnc.tapKey(VncView.XK_LEFT, false, false));
        add(row, "↑", v -> vnc.tapKey(VncView.XK_UP, false, false));
        add(row, "↓", v -> vnc.tapKey(VncView.XK_DOWN, false, false));
        add(row, "→", v -> vnc.tapKey(VncView.XK_RIGHT, false, false));
        rightButton = add(row, "右クリック", v -> {
            vnc.rightNext = !vnc.rightNext;
            refreshToggles();
        });
        add(row, "全体", v -> vnc.resetZoom());
        add(row, "✕ 閉じる", v -> finish());
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setBackgroundColor(0xFF111827);
        hs.addView(row);
        return hs;
    }

    private Button add(LinearLayout row, String label, View.OnClickListener l) {
        Button b = GameKeys.button(this, label);
        b.setOnClickListener(l);
        b.setFocusable(false);
        row.addView(b);
        return b;
    }

    private void refreshToggles() {
        tint(ctrlButton, vnc.ctrlNext);
        tint(altButton, vnc.altNext);
        tint(rightButton, vnc.rightNext);
    }

    private void tint(Button b, boolean on) {
        if (b == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(on ? 0xFF2563EB : 0xFF374151);
        bg.setCornerRadius(GameKeys.dp(this, 8));
        b.setBackground(bg);
    }

    @Override
    protected void onDestroy() {
        leaving = true;
        if (vnc != null) vnc.disconnect();
        super.onDestroy();
    }
}
