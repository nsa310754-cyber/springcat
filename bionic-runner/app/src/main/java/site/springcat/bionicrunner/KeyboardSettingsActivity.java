package site.springcat.bionicrunner;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * 専用キーボードの設定 (キーの高さ) と、有効にする・切り替える手順の案内。
 * Android の設定の「キーボード」からも、専用キーボードの ⚙ キーからも開ける。
 */
public class KeyboardSettingsActivity extends Activity {

    private TextView state;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("専用キーボードの設定");
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(0xFF0F172A);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = GameKeys.dp(this, 16);
        col.setPadding(p, p, p, p);
        sv.addView(col);

        TextView head = text("Bionic Runner 専用キーボード", 20);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(head);
        col.addView(text("Esc・F1〜F12・Ctrl・Alt・Shift・Win・矢印・Home/End・PgUp/PgDn・Ins/Del 付きの"
                + "キーボードです。ターミナルや X11 のほか、どのアプリでも使えます。打った内容は保存も送信もしません。",
                14));
        state = text("", 14);
        state.setTextColor(0xFFFDE68A);
        col.addView(state);

        Button enable = GameKeys.button(this, "1. Android の設定で有効にする");
        enable.setOnClickListener(v -> startActivity(
                new android.content.Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS)));
        col.addView(enable);
        Button choose = GameKeys.button(this, "2. 専用キーボードに切り替える");
        choose.setOnClickListener(v -> KeyboardSwitch.pick(this, null));
        col.addView(choose);

        col.addView(heightSlider("縦画面のキーの高さ", false));
        col.addView(heightSlider("横画面のキーの高さ", true));

        col.addView(text("ここで試し打ちできます (内容はどこにも残りません):", 14));
        EditText test = new EditText(this);
        test.setTextColor(0xFFFFFFFF);
        test.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        test.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        col.addView(test);

        col.addView(text("使い方\n"
                + "・Ctrl / Alt / Shift / Win は、1 回押すと次の 1 キーだけ有効 (青)、2 回押すと固定 (濃い青・黄色の字)、"
                + "もう一度押すと解除\n"
                + "・キーを長押しすると繰り返し入力\n"
                + "・1 段目 (Esc F1〜F12 Ins Del Home End PgUp PgDn ⚙) は横に流せる\n"
                + "・🌐 で元のキーボードに戻る。長押しでキーボードの一覧\n"
                + "・日本語入力はできません (英数字と記号だけ)", 13));
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        state.setText("状態: " + (!KeyboardSwitch.enabled(this) ? "まだ有効になっていません (1 から)"
                : KeyboardSwitch.selected(this) ? "使用中" : "有効 (2 で切り替えられます)"));
    }

    private TextView text(String s, float size) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(0xFFE2E8F0);
        t.setPadding(0, GameKeys.dp(this, 8), 0, GameKeys.dp(this, 4));
        return t;
    }

    private LinearLayout heightSlider(final String label, final boolean landscape) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final TextView t = text("", 14);
        box.addView(t);
        SeekBar bar = new SeekBar(this);
        // 28〜72dp
        bar.setMax(44);
        int cur = BrKeyboardService.keyHeight(this, landscape);
        bar.setProgress(cur - 28);
        t.setText(label + ": " + cur + "dp");
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                int v = 28 + progress;
                t.setText(label + ": " + v + "dp");
                BrKeyboardService.setKeyHeight(KeyboardSettingsActivity.this, landscape, v);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        box.addView(bar);
        return box;
    }
}
