package site.springcat.bionicrunner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

/**
 * 専用キーボードへの切り替え (ターミナル・X11 の「⌨ 専用キーボード」ボタンから)。
 * まだ有効になっていなければ、Android の設定で有効にする手順を案内する。
 */
final class KeyboardSwitch {

    private KeyboardSwitch() {
    }

    static String id(Context c) {
        return new ComponentName(c, BrKeyboardService.class).flattenToShortString();
    }

    /** Android の設定で「有効」になっているか */
    static boolean enabled(Context c) {
        InputMethodManager imm = (InputMethodManager) c.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return false;
        for (InputMethodInfo i : imm.getEnabledInputMethodList()) {
            if (i.getPackageName().equals(c.getPackageName())
                    && i.getServiceName().equals(BrKeyboardService.class.getName())) {
                return true;
            }
        }
        return false;
    }

    /** いま使われているキーボードか */
    static boolean selected(Context c) {
        String cur = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (cur == null) return false;
        ComponentName cn = ComponentName.unflattenFromString(cur);
        return cn != null && cn.getPackageName().equals(c.getPackageName())
                && cn.getClassName().equals(BrKeyboardService.class.getName());
    }

    /**
     * キーボードの選択画面を出す。有効になっていなければ、有効にする手順を案内する。
     * beforePicker は、選んだあとにキーボードが出るよう、入力先にフォーカスを当てておく処理 (null 可)
     */
    static void pick(final Activity a, Runnable beforePicker) {
        if (!enabled(a)) {
            new AlertDialog.Builder(a)
                    .setTitle("専用キーボードを有効にする")
                    .setMessage("専用キーボード (Esc・F1〜F12・Ctrl・Alt・矢印など付き) を使うには、"
                            + "最初に 1 回だけ Android の設定で有効にします。\n\n"
                            + "1. 下の「設定を開く」を押す\n"
                            + "2. 一覧の「Bionic Runner 専用キーボード」をオンにする\n"
                            + "   (「入力内容を収集できます」という Android の決まった注意が出ます。"
                            + "このキーボードは打った内容を保存も送信もしません)\n"
                            + "3. この画面に戻り、もう一度「⌨ 専用キーボード」を押して選ぶ\n\n"
                            + "元のキーボードには、専用キーボードの 🌐 キーで戻れます。")
                    .setPositiveButton("設定を開く", (d, w) -> {
                        try {
                            a.startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
                        } catch (RuntimeException e) {
                            Toast.makeText(a, "設定を開けませんでした。Android の設定 → システム → キーボード から"
                                    + "有効にしてください", Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("キャンセル", null)
                    .show();
            return;
        }
        if (beforePicker != null) beforePicker.run();
        InputMethodManager imm = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showInputMethodPicker();
        if (!selected(a)) {
            Toast.makeText(a, "一覧から「Bionic Runner 専用キーボード」を選んでください", Toast.LENGTH_SHORT).show();
        }
    }
}
