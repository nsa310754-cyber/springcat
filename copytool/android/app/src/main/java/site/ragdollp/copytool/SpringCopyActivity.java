package site.ragdollp.copytool;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * どのアプリのテキスト選択メニューにも「springコピー」を追加するハンドラ。
 *
 * ACTION_PROCESS_TEXT に対応することで、ブラウザやメモ帳などで文字を選択した
 * 際の標準メニュー（コピー/切り取り/共有 …）に「springコピー」が並ぶ。
 * 選択元アプリから渡ってくるテキスト自体は Intent（Binder経由）に載るため、
 * その時点で Android の Binder 上限（実質 1MB 前後）の影響は受けるが、
 * 受け取った範囲内では ClipHelper で確実にクリップボードへコピーする。
 * 画面は表示せず、結果を Toast で知らせてすぐ終了する。
 */
public class SpringCopyActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        CharSequence extra = getIntent() != null
                ? getIntent().getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                : null;
        String text = extra != null ? extra.toString() : "";

        String result = ClipHelper.copyLargeText(this, text);
        Toast.makeText(this, result, Toast.LENGTH_SHORT).show();

        finish();
    }
}
