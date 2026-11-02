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
        /** 比率 0.0..1.0。root 経由なら sysfs の生値、非root なら CAPACITY%/100。 */
        val fraction: Double,
        val charging: Boolean,
        val source: String,         // "root/charge" | "root/energy" | "capacity"
        val voltageMv: Int?,
        val temperatureC: Float?,
    ) {
        /** 100% の時に 10^digits になるようにスケールした整数(= 従来の milliPercent 相当)。 */
        fun scaled(digits: Int): Long {
            val d = digits.coerceIn(1, 18)
            val max = pow10(d)                 // 100% = 10^d
            val v = (fraction * max.toDouble()).let { Math.round(it) }
            return v.coerceIn(0L, max)
        }
        private fun pow10(n: Int): Long {
            var r = 1L; repeat(n) { r *= 10L }; return r
        }
    }

    fun read(ctx: Context): Snapshot {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val intent: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val charging = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)?.let {
            it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL
        } ?: false
        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)?.takeIf { it > 0 }
        val tempRaw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val temperatureC = tempRaw?.takeIf { it != Int.MIN_VALUE }?.let { it / 10f }

        if (allowRoot) {
            readViaRootSysfs()?.let { ratio ->
                return Snapshot(ratio, charging, "root/charge", voltageMv, temperatureC)
            }
            readViaRootEnergy()?.let { ratio ->
                return Snapshot(ratio, charging, "root/energy", voltageMv, temperatureC)
            }
        }

        val capacity = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val safe = if (capacity in 0..100) capacity else {
            val l = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val s = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (l >= 0 && s > 0) (l * 100 / s) else 0
        }
        return Snapshot(safe / 100.0, charging, "capacity", voltageMv, temperatureC)
    }

    private fun readViaRootSysfs(): Double? {
        val now = suReadLong("/sys/class/power_supply/battery/charge_counter") ?: return null
        val full = suReadLong("/sys/class/power_supply/battery/charge_full") ?: return null
        if (full <= 0) return null
        return (now.toDouble() / full.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun readViaRootEnergy(): Double? {
        val now = suReadLong("/sys/class/power_supply/battery/energy_now") ?: return null
        val full = suReadLong("/sys/class/power_supply/battery/energy_full") ?: return null
        if (full <= 0) return null
        return (now.toDouble() / full.toDouble()).coerceIn(0.0, 1.0)
    }

    private fun suReadLong(path: String): Long? = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $path"))
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        out.toLongOrNull()
    } catch (_: Throwable) { null }
}
