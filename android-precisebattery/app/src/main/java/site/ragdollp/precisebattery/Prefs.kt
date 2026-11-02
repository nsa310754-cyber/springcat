package site.ragdollp.precisebattery

import android.content.Context

object Prefs {
    private const val FILE = "precisebattery"
    private const val K_AUTOSTART = "autostart"
    private const val K_USEROOT = "useroot"
    private const val K_X = "pos_x"
    private const val K_Y = "pos_y"
    private const val K_DIGITS = "digits"

    /** 100% の時に表示したい桁数 (2〜10)。デフォルトは 3 (= 0〜1000)。 */
    const val DIGITS_MIN = 2
    const val DIGITS_MAX = 10
    const val DIGITS_DEFAULT = 3

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var Context.autoStart: Boolean
        get() = sp(this).getBoolean(K_AUTOSTART, false)
        set(v) { sp(this).edit().putBoolean(K_AUTOSTART, v).apply() }

    var Context.useRoot: Boolean
        get() = sp(this).getBoolean(K_USEROOT, false)
        set(v) { sp(this).edit().putBoolean(K_USEROOT, v).apply() }

    var Context.displayDigits: Int
        get() = sp(this).getInt(K_DIGITS, DIGITS_DEFAULT).coerceIn(DIGITS_MIN, DIGITS_MAX)
        set(v) { sp(this).edit().putInt(K_DIGITS, v.coerceIn(DIGITS_MIN, DIGITS_MAX)).apply() }

    fun pos(ctx: Context): Pair<Int, Int> =
        sp(ctx).getInt(K_X, 50) to sp(ctx).getInt(K_Y, 200)

    fun setPos(ctx: Context, x: Int, y: Int) {
        sp(ctx).edit().putInt(K_X, x).putInt(K_Y, y).apply()
    }
}
