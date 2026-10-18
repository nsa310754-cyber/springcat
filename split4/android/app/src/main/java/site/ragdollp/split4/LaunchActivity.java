package site.ragdollp.split4;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/** 通知・クイック設定タイル・ショートカットから呼ばれ、4 分割起動だけして閉じる透明 Activity。 */
public class LaunchActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SplitLauncher.launch(this, true, true, msg -> {
            Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
            finish();
        });
    }
}
