package site.ragdollp.precisebattery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * バッテリー残量を取得。
 *
 * - 基本は BatteryManager.BATTERY_PROPERTY_CAPACITY (整数 %) を 10倍して 0..1000 にする。
 * - root がある & 設定で有効なら /sys/class/power_supply/battery/charge_counter と
 *   charge_full を直接読み、小数まで含めた比率 (0..1000) を返す。
 *   charge_counter / charge_full * 1000 を四捨五入。
 * - charge_full が無い機種では energy_counter / energy_full、どちらも駄目なら CAPACITY にフォールバック。
 *
 * 返り値は 0..1000 の整数(= 0.1% 刻み)。
 */
object BatteryReader {

    /** root 読み出しを許可するかの状態キャッシュ(呼び出し側が切り替える) */
    @Volatile var allowRoot: Boolean = false

    data class Snapshot(
        val milliPercent: Int,      // 0..1000
        val charging: Boolean,
        val source: String,         // "root/charge" | "root/energy" | "capacity"
        val voltageMv: Int?,        // ある場合
        val temperatureC: Float?,   // ある場合
    )

    fun read(ctx: Context): Snapshot {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val intent: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val charging = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.let {
            it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
        } ?: false
        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }
        val tempRaw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val temperatureC = tempRaw?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }

        // root パス
        if (allowRoot) {
            readViaRootSysfs()?.let { ratio ->
                val mp = (ratio * 1000f).toInt().coerceIn(0, 1000)
                return Snapshot(mp, charging, "root/charge", voltageMv, temperatureC)
            }
            readViaRootEnergy()?.let { ratio ->
                val mp = (ratio * 1000f).toInt().coerceIn(0, 1000)
                return Snapshot(mp, charging, "root/energy", voltageMv, temperatureC)
            }
        }

        // 通常パス: 整数 % × 10
        val capacity = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val safe = if (capacity in 0..100) capacity else {
            // 一部機種で CAPACITY が未実装のとき intent 側を使う
            val l = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val s = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (l >= 0 && s > 0) (l * 100 / s) else 0
        }
        return Snapshot(safe * 10, charging, "capacity", voltageMv, temperatureC)
    }

    private fun readViaRootSysfs(): Float? {
        val now = suReadLong("/sys/class/power_supply/battery/charge_counter") ?: return null
        val full = suReadLong("/sys/class/power_supply/battery/charge_full") ?: return null
        if (full <= 0) return null
        return (now.toFloat() / full.toFloat()).coerceIn(0f, 1f)
    }

    private fun readViaRootEnergy(): Float? {
        val now = suReadLong("/sys/class/power_supply/battery/energy_now") ?: return null
        val full = suReadLong("/sys/class/power_supply/battery/energy_full") ?: return null
        if (full <= 0) return null
        return (now.toFloat() / full.toFloat()).coerceIn(0f, 1f)
    }

    private fun suReadLong(path: String): Long? = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $path"))
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        out.toLongOrNull()
    } catch (_: Throwable) { null }
}
