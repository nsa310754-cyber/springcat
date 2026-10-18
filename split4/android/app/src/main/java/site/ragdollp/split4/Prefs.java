package site.ragdollp.split4;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

/** 4 つのスロットに割り当てたアプリと各種設定を保存する。 */
final class Prefs {
    static final int SLOTS = 4;

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("split4", Context.MODE_PRIVATE);
    }

    ComponentName getSlot(int i) {
        String s = sp.getString("slot" + i, null);
        return s == null ? null : ComponentName.unflattenFromString(s);
    }

    void setSlot(int i, ComponentName cn) {
        sp.edit().putString("slot" + i, cn == null ? null : cn.flattenToString()).apply();
    }

    /** 起動前に既存タスクを閉じる (フルスクリーンで残っているとフリーフォームにならないため)。 */
    boolean forceStop() {
        return sp.getBoolean("forceStop", true);
    }

    void setForceStop(boolean v) {
        sp.edit().putBoolean("forceStop", v).apply();
    }

    /** ウィンドウ間の隙間 (px)。 */
    int gap() {
        return sp.getInt("gap", 0);
    }

    void setGap(int v) {
        sp.edit().putInt("gap", v).apply();
    }

    boolean startOnBoot() {
        return sp.getBoolean("startOnBoot", false);
    }

    void setStartOnBoot(boolean v) {
        sp.edit().putBoolean("startOnBoot", v).apply();
    }
}
