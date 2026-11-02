package site.ragdollp.precisebattery

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import site.ragdollp.precisebattery.Prefs.autoStart
import site.ragdollp.precisebattery.Prefs.displayDigits
import site.ragdollp.precisebattery.Prefs.useRoot

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 72, 48, 48)
        }

        root.addView(TextView(this).apply {
            text = "細密バッテリー (0〜1000)"; textSize = 24f
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.unit_label); setPadding(0, 16, 0, 16)
        })

        // 桁数ピッカー: 100% の時に 10^N と表示される
        root.addView(TextView(this).apply {
            text = "100% のときの表示値 (桁数)"
            setPadding(0, 16, 0, 8)
        })
        val digitsPicker = NumberPicker(this).apply {
            minValue = Prefs.DIGITS_MIN
            maxValue = Prefs.DIGITS_MAX
            value = displayDigits
            wrapSelectorWheel = false
            // 2→100, 3→1000, ... 10→10000000000 の表示にする
            setFormatter { n -> "$n 桁 (100% → ${"1".padEnd(n + 1, '0')})" }
            setOnValueChangedListener { _, _, newVal ->
                displayDigits = newVal
                refreshStatus()
            }
        }
        root.addView(digitsPicker)

        status = TextView(this).apply { setPadding(0, 16, 0, 24) }
        root.addView(status)

        root.addView(button(getString(R.string.grant_overlay)) {
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        })

        root.addView(button(getString(R.string.start)) {
            if (!Settings.canDrawOverlays(this)) {
                status.text = "先に「他のアプリの上に表示」を許可してください"
                return@button
            }
            startForegroundService(Intent(this, BatteryService::class.java))
            refreshStatus()
        })

        root.addView(button(getString(R.string.stop)) {
            stopService(Intent(this, BatteryService::class.java))
            refreshStatus()
        })

        root.addView(checkbox(getString(R.string.auto_start), autoStart) {
            autoStart = it
        })
        root.addView(checkbox(getString(R.string.use_root), useRoot) {
            useRoot = it
            BatteryReader.allowRoot = it
        })

        setContentView(root)
    }

    override fun onResume() { super.onResume(); refreshStatus() }

    private fun refreshStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        BatteryReader.allowRoot = useRoot
        val s = BatteryReader.read(this)
        val digits = displayDigits
        val max = "1".padEnd(digits + 1, '0')
        val value = s.scaled(digits)
        status.text = """
            オーバーレイ権限: ${if (overlayOk) "✅" else "❌"}
            現在値 (0〜$max) : $value  [${s.source}]
            ${if (s.voltageMv != null) "電圧 ${s.voltageMv}mV" else ""}
            ${if (s.temperatureC != null) "温度 %.1f°C".format(s.temperatureC) else ""}
        """.trimIndent()
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; setPadding(24, 24, 24, 24)
        setOnClickListener { onClick() }
    }
    private fun checkbox(label: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        CheckBox(this).apply {
            text = label; isChecked = checked
            gravity = Gravity.CENTER_VERTICAL
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
}
