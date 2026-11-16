package site.springcat.bionicrunner;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * ゲームモード (HTML・JavaScript・TypeScript): ファイルをアプリの中のブラウザ部品で動かして、
 * 画面いっぱいに表示する。下に操作ボタンを並べ、キーで操作するゲームも遊べるようにする。
 * プログラムに誤りがあれば、ページ側の補助スクリプトがエラーとコードを画面に表示する。
 */
public class WebGameActivity extends Activity {

    /** 読み込むファイル (アプリが読める場所にある写し) */
    static final String EXTRA_FILE = "file";
    /** 元のファイルの場所 (同じフォルダの画像などを読むためと、表示用) */
    static final String EXTRA_ORIGIN = "origin";
    /** これより大きいファイルは開かない (ブラウザ部品へ文字列で渡すため) */
    static final int MAX_SOURCE = 8 * 1024 * 1024;

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String file = getIntent().getStringExtra(EXTRA_FILE);
        String origin = getIntent().getStringExtra(EXTRA_ORIGIN);
        if (file == null) {
            finish();
            return;
        }
        if (origin == null) origin = file;
        Lang lang = Lang.of(origin);
        String source;
        try {
            source = readText(new File(file));
        } catch (IOException e) {
            Toast.makeText(this, "読み込めませんでした: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (lang == null || lang.type != Lang.Type.WEB) {
            finish();
            return;
        }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFF000000);
        web = new WebView(this);
        web.setBackgroundColor(0xFF000000);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        RunBridge bridge = new RunBridge(lang.id, "game", new File(origin).getName(), source,
                new RunBridge.Listener() {
                    @Override
                    public void onOutput(String text, boolean error) {
                        // ゲームモードでは出力は使わない (エラーはページの上に表示される)
                    }

                    @Override
                    public void onExit(int code) {
                        // ゲームモードのページは自分からは終わらない
                    }
                });
        RunBridge.attach(web, bridge);
        col.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button close = GameKeys.button(this, "✕ 閉じる");
        close.setOnClickListener(v -> finish());
        col.addView(GameKeys.bar(this, (k, down) -> web.evaluateJavascript(
                "window.__brKey&&__brKey('" + (down ? "keydown" : "keyup") + "','" + k.key + "','"
                        + k.code + "'," + k.keyCode + ")", null), close));
        setContentView(col);
        setTitle(new File(origin).getName());
        RunBridge.load(web, bridge, new File(origin).getParentFile());
    }

    static String readText(File f) throws IOException {
        if (f.length() > MAX_SOURCE) throw new IOException("ファイルが大きすぎます");
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause(); // 裏に回ったらゲームを止める (音も止まる)
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            ((ViewGroup) web.getParent()).removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }
}
