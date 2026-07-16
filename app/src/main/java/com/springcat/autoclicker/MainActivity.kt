package com.springcat.autoclicker

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import androidx.appcompat.app.AppCompatActivity
import com.springcat.autoclicker.databinding.ActivityMainBinding

/**
 * Setup screen. On Fire tablets there is no Play Store install flow, so the
 * one thing the user must do is turn the accessibility service ON. Everything
 * else (the floating panel, the target, tapping) is handled by [ClickerService].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnOpenSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        val enabled = isServiceEnabled()
        binding.txtStatus.text = getString(
            if (enabled) R.string.status_on else R.string.status_off
        )
        binding.txtStatus.setBackgroundResource(
            if (enabled) R.drawable.bg_status_on else R.drawable.bg_status_off
        )
        binding.btnOpenSettings.text = getString(
            if (enabled) R.string.open_settings_manage else R.string.open_settings_enable
        )
    }

    /** Reads the platform's list of enabled accessibility services. */
    private fun isServiceEnabled(): Boolean {
        val expected = "$packageName/${ClickerService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expected, ignoreCase = true)) return true
        }
        return false
    }
}
