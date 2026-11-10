package site.ragdollp.stayawake

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

/**
 * スイッチ 1 つの超シンプル UI。
 *  - OFF → 通常通り (OS のスリープ任せ)
 *  - ON  → この画面で FLAG_KEEP_SCREEN_ON、かつ KeepAwakeService 起動
 *          => 他アプリを開いても画面は消えない
 */
class MainActivity : AppCompatActivity() {

    private lateinit var sw: Switch
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 96, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = "💡 画面オン維持"; textSize = 26f; gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        })
        root.addView(TextView(this).apply {
            text = "スマホの自動スリープ(画面消灯)を停止させるツール"
            textSize = 13f; gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        })

        sw = Switch(this).apply {
            text = "   画面を暗くしない"
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            isChecked = isServiceRunning()
            applyKeepScreenOn(isChecked)
            setOnCheckedChangeListener { _, on ->
                applyKeepScreenOn(on)
                if (on) KeepAwakeService.start(this@MainActivity)
                else KeepAwakeService.stop(this@MainActivity)
                refreshStatus()
            }
        }
        root.addView(sw)

        status = TextView(this).apply {
            setPadding(0, 32, 0, 0); textSize = 14f; gravity = Gravity.CENTER
        }
        root.addView(status)

        root.addView(TextView(this).apply {
            text = "\n【仕組み】\n" +
                   "・この画面を開いている間: 画面常時オン (FLAG_KEEP_SCREEN_ON)\n" +
                   "・他アプリに切り替え後: WakeLock で画面オン継続\n" +
                   "・通知バーの「停止」で即 OFF 可能\n\n" +
                   "【省電力オプションから除外推奨】\n" +
                   "機種によってはバッテリー最適化で勝手にサービスが落とされます。\n" +
                   "下のボタンから「最適化しない」に設定してください。"
            setPadding(0, 40, 0, 0); textSize = 12f
            setTextColor(0xFFAABBCC.toInt())
        })

        root.addView(Button(this).apply {
            text = "バッテリー最適化を除外する"
            setPadding(24, 24, 24, 24)
            setOnClickListener { requestIgnoreBatteryOpt() }
        })

        setContentView(root)
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        // サービスの実際の状態に UI を合わせる
        val running = isServiceRunning()
        if (sw.isChecked != running) {
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = running
            applyKeepScreenOn(running)
            sw.setOnCheckedChangeListener { _, on ->
                applyKeepScreenOn(on)
                if (on) KeepAwakeService.start(this) else KeepAwakeService.stop(this)
                refreshStatus()
            }
        }
        refreshStatus()
    }

    private fun applyKeepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun refreshStatus() {
        val running = isServiceRunning()
        val ignoring = isIgnoringBatteryOpt()
        status.text = "状態: ${if (running) "✅ ON (画面消灯しません)" else "⚪ OFF"}\n" +
                      "バッテリー最適化除外: ${if (ignoring) "✅" else "❌"}"
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == KeepAwakeService::class.java.name }
    }

    private fun isIgnoringBatteryOpt(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    @android.annotation.SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOpt() {
        try {
            if (!isIgnoringBatteryOpt()) {
                startActivity(android.content.Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName")))
            } else {
                Toast.makeText(this, "既に除外済みです", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Throwable) {
            startActivity(android.content.Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
