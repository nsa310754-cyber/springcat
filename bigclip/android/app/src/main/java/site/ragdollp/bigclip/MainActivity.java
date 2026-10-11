package site.ragdollp.bigclip;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_OPEN = 1;
    private static final int REQ_SAVE = 2;
    private static final String CHANNEL = "root_paste";
    private static final int NOTIFY_ID = 1;

    /** これを超える文字数は EditText で編集させずプレビュー表示にする (重くなるため)。 */
    private static final int EDIT_MAX_CHARS = 200_000;
    private static final int PREVIEW_CHARS = 20_000;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private EditText editor;
    private TextView status;
    private TextView rootStatus;
    /** 大きすぎて EditText に載せていない本文 (null なら editor の内容が本文)。 */
    private String largeText;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        handleIncoming(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIncoming(intent);
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        int pad = dp(12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setFitsSystemWindows(true);

        TextView title = new TextView(this);
        title.setText("BigClip — 1MB コピー＆ペースト");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        status = new TextView(this);
        status.setPadding(0, dp(4), 0, dp(4));
        root.addView(status);

        editor = new EditText(this);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setHint("ここにテキストを入力・貼り付け、またはファイルを読み込み (最大 1MB)");
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setTextSize(13);
        root.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1f));

        root.addView(row(
                button("📋 1MB コピー", v -> copyCurrent()),
                button("📥 クリップボード読込", v -> pasteFromClipboard())));
        root.addView(row(
                button("📂 ファイルを開く", v -> openFile()),
                button("💾 ファイルに保存", v -> saveFile())));
        root.addView(row(
                button("🧪 1MB テスト文生成", v -> generateTest()),
                button("🗑 クリア", v -> setText(""))));

        rootStatus = new TextView(this);
        rootStatus.setPadding(0, dp(8), 0, 0);
        rootStatus.setText("root: 未確認");
        root.addView(rootStatus);
        root.addView(row(
                button("# root 確認", v -> checkRoot()),
                button("# root 貼り付け通知", v -> showRootPasteNotification())));
        root.addView(row(button("ℹ️ 仕組み / 注意", v -> showHelp())));

        setContentView(root);
        updateStatus();
        editor.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable s) {
                if (largeText == null) updateStatus();
            }
        });
    }

    private LinearLayout row(View... views) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (View v : views) r.addView(v, new LinearLayout.LayoutParams(0, -2, 1f));
        return r;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ---------------------------------------------------------------- 本文

    private String currentText() {
        return largeText != null ? largeText : editor.getText().toString();
    }

    private void setText(String text) {
        if (text.length() > EDIT_MAX_CHARS) {
            largeText = text;
            editor.setText(text.substring(0, PREVIEW_CHARS)
                    + "\n\n…(長文のため先頭 " + PREVIEW_CHARS + " 文字のみプレビュー表示。コピー/保存は全文)");
            editor.setEnabled(false);
        } else {
            largeText = null;
            editor.setEnabled(true);
            editor.setText(text);
        }
        updateStatus();
    }

    private void updateStatus() {
        String t = currentText();
        int bytes = t.getBytes(StandardCharsets.UTF_8).length;
        String mode = t.length() > ClipStore.INLINE_MAX_CHARS ? "大容量モード(URI)" : "通常モード";
        status.setText(String.format("%,d 文字 / %s (上限 %s) — %s", t.length(), Fmt.bytes(bytes),
                Fmt.bytes(ClipStore.MAX_BYTES), mode));
        status.setTextColor(bytes > ClipStore.MAX_BYTES ? 0xFFD32F2F : 0xFF888888);
    }

    // ---------------------------------------------------------------- 操作

    private void copyCurrent() {
        String t = currentText();
        if (t.isEmpty()) { toast("テキストが空です"); return; }
        io.execute(() -> {
            String msg;
            try {
                boolean uri = ClipStore.copy(this, t);
                msg = "コピーしました (" + Fmt.bytes(t.getBytes(StandardCharsets.UTF_8).length)
                        + (uri ? "・大容量モード" : "") + ")";
            } catch (Exception e) {
                msg = "コピー失敗: " + e.getMessage();
            }
            String m = msg;
            main.post(() -> toast(m));
        });
    }

    private void pasteFromClipboard() {
        // Android 10+ ではフォーカス中のアプリしかクリップボードを読めないので UI スレッドで取得
        String t;
        try {
            t = ClipStore.readPrimary(this);
        } catch (Exception e) {
            toast("読み込み失敗: " + e.getMessage());
            return;
        }
        if (t == null) { toast("クリップボードは空です"); return; }
        setText(t);
        toast("クリップボードから " + String.format("%,d", t.length()) + " 文字読み込みました");
    }

    private void openFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/*");
        startActivityForResult(i, REQ_OPEN);
    }

    private void saveFile() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE, "bigclip.txt");
        startActivityForResult(i, REQ_SAVE);
    }

    private void generateTest() {
        StringBuilder sb = new StringBuilder(ClipStore.MAX_BYTES);
        int line = 1;
        while (true) {
            String s = String.format("%06d: BigClip 1MB test line ABCDEFGHIJKLMNOPQRSTUVWXYZ 0123456789\n", line++);
            if (sb.length() + s.length() > ClipStore.MAX_BYTES) break;
            sb.append(s);
        }
        setText(sb.toString());
        toast("約 1MB のテスト文を生成しました。「1MB コピー」→ 他アプリで貼り付けを試してください");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (req == REQ_OPEN) {
            loadUri(uri, false);
        } else if (req == REQ_SAVE) {
            String t = currentText();
            io.execute(() -> {
                String msg;
                try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
                    os.write(t.getBytes(StandardCharsets.UTF_8));
                    msg = "保存しました";
                } catch (Exception e) {
                    msg = "保存失敗: " + e.getMessage();
                }
                String m = msg;
                main.post(() -> toast(m));
            });
        }
    }

    /** uri のテキストを読み込み、autoCopy なら続けてクリップボードへ。 */
    private void loadUri(Uri uri, boolean autoCopy) {
        io.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                String t = ClipStore.readLimited(in);
                main.post(() -> {
                    setText(t);
                    if (autoCopy) copyCurrent();
                    else toast("読み込みました");
                });
            } catch (Exception e) {
                main.post(() -> toast("読み込み失敗: " + e.getMessage()));
            }
        });
    }

    /** 共有 (ACTION_SEND) / テキストファイルを開く (ACTION_VIEW) で受け取ったら即コピー。 */
    private void handleIncoming(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null) {
                loadUri(stream, true);
            } else {
                CharSequence t = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (t != null) { setText(t.toString()); copyCurrent(); }
            }
        } else if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            loadUri(intent.getData(), true);
        }
        intent.setAction(null);
    }

    // ---------------------------------------------------------------- root

    private void checkRoot() {
        rootStatus.setText("root: 確認中… (su の許可ダイアログが出たら「許可」)");
        io.execute(() -> {
            boolean ok = RootShell.available();
            main.post(() -> rootStatus.setText(ok
                    ? "root: ✅ 利用可能 — 「root 貼り付け通知」が使えます"
                    : "root: ❌ 利用できません (Magisk / KernelSU で許可してください)"));
        });
    }

    private void showRootPasteNotification() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "root 貼り付け",
                    NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        PendingIntent paste = PendingIntent.getBroadcast(this, 0,
                new Intent(this, RootPasteReceiver.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        b.setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentTitle("BigClip: root で貼り付け")
                .setContentText("貼り付けたい入力欄をタップしてから通知の「貼り付け」を押す")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "貼り付け", paste).build());
        nm.notify(NOTIFY_ID, b.build());
        toast("通知を表示しました");
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) showRootPasteNotification();
    }

    private void showHelp() {
        new AlertDialog.Builder(this)
                .setTitle("仕組み / 注意")
                .setMessage(
                        "■ なぜ普通は大きなテキストをコピーできないか\n"
                        + "Android のクリップボードは Binder(プロセス間通信)で本文を丸ごと送ります。"
                        + "Binder のバッファは 1 プロセスあたり約 1MB で、文字は UTF-16(1文字2バイト)で送られるため、"
                        + "数十万文字で TransactionTooLargeException になり失敗します。"
                        + "これは libbinder/カーネルに埋め込まれた値で、root でも設定変更では上げられません。\n\n"
                        + "■ BigClip の方式\n"
                        + "10万文字を超える場合、本文をアプリ内ファイルに置き、クリップボードには content:// URI だけを載せます。"
                        + "貼り付け先アプリは標準の貼り付け処理(coerceToText)でファイルから全文を読み込むため、Binder 上限を回避して最大 1MB を貼り付けられます。\n\n"
                        + "■ root 機能\n"
                        + "「root 貼り付け通知」は前面アプリの入力欄へ KEYCODE_PASTE を root で送ります。"
                        + "長押しメニューが出ない入力欄でも貼り付けできます。\n\n"
                        + "■ 制限\n"
                        + "・独自の貼り付け処理を持つアプリ(一部のキーボードのクリップボード履歴、ゲーム、WebView の一部など)は URI を読まず、大容量モードの内容を貼り付けられない場合があります。\n"
                        + "・入力欄自体に文字数制限(maxLength)があるアプリでは、その上限までしか入りません。\n"
                        + "・他アプリで 1MB をコピーする場合は、そのアプリの「共有」から BigClip を選ぶか、ファイルで渡してください。")
                .setPositiveButton("OK", null)
                .show();
    }
}
