package com.springcat.monitor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.springcat.monitor.databinding.ActivityMainBinding
import java.util.Locale

/**
 * Single-screen dashboard. Sampling itself lives in [MonitorService] so it continues in the
 * background; this Activity only observes [MonitorState] and renders the latest [Snapshot].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Called on the main thread by MonitorState for every new sample.
    private val observer: (Snapshot) -> Unit = { snapshot ->
        render(snapshot)
        binding.chart.push(snapshot.cpuPercent ?: 0f, snapshot.memPercent)
    }

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* SSID simply stays hidden if the user declines. */ }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* The service still runs; only the ongoing notification is affected. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.chart.fixedMax = 100f
        binding.chart.yLabelFormatter = { v -> "${v.toInt()}%" }
        binding.chart.addSeries(LineChartView.Series("CPU", Color.parseColor("#5B8CFF"), CAPACITY))
        binding.chart.addSeries(LineChartView.Series("メモリ", Color.parseColor("#35C98A"), CAPACITY))

        binding.devModel.text = "${Build.MANUFACTURER} ${Build.MODEL}"
        binding.devOs.text = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        binding.devCores.text = Runtime.getRuntime().availableProcessors().toString()

        binding.batteryButton.setOnClickListener { requestIgnoreBatteryOptimizations() }

        requestPermissionsIfNeeded()
        MonitorService.start(this)
    }

    override fun onResume() {
        super.onResume()
        // Reseed the chart from the history the service accumulated while we were away.
        binding.chart.clearData()
        MonitorState.historySnapshot().forEach { binding.chart.push(it.cpu, it.mem) }
        MonitorState.latest?.let { render(it) }
        MonitorState.addListener(observer)
    }

    override fun onPause() {
        super.onPause()
        MonitorState.removeListener(observer)
    }

    private fun requestPermissionsIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            // Already exempt — just open the general battery settings so the user can confirm.
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }.onFailure {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun render(s: Snapshot) {
        // CPU. Preference order is system-wide (unprivileged), then system-wide via root, then
        // this app's own process usage. Label the value so its scope is never ambiguous.
        val cpu = s.cpuPercent
        binding.cpuValue.text = when {
            cpu == null -> "計測不可"
            s.cpuSource == CpuSource.ROOT -> "${pct(cpu)} · root"
            s.cpuSource == CpuSource.PROCESS -> "${pct(cpu)} · アプリ"
            else -> pct(cpu)
        }
        binding.legendCpu.text = when (s.cpuSource) {
            CpuSource.ROOT -> "CPU (root)"
            CpuSource.PROCESS -> "CPU (アプリ)"
            else -> "CPU"
        }
        setBar(binding.cpuBar, cpu ?: 0f)

        // Memory
        binding.memValue.text = pct(s.memPercent)
        setBar(binding.memBar, s.memPercent)
        binding.memDetail.text =
            "${SystemStats.formatBytes(s.memUsedBytes)} / ${SystemStats.formatBytes(s.memTotalBytes)}"

        // Storage
        binding.storageValue.text = pct(s.storagePercent)
        setBar(binding.storageBar, s.storagePercent)
        binding.storageDetail.text =
            "${SystemStats.formatBytes(s.storageUsedBytes)} / ${SystemStats.formatBytes(s.storageTotalBytes)}"

        // Network
        val net = s.network
        binding.netType.text = net.type + if (net.validated) "" else " (未検証)"
        binding.netSsid.text = net.ssid ?: run {
            val hasPerm = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (net.type == "Wi-Fi" && !hasPerm) "位置情報の許可が必要" else "–"
        }
        binding.netIp.text = net.ipAddress ?: "–"
        binding.netLink.text = net.linkSpeedMbps?.let { "$it Mbps" } ?: "–"
        binding.netRssi.text = net.rssiDbm?.let { "$it dBm" } ?: "–"
        binding.netThroughput.text =
            "↓ ${SystemStats.formatRate(s.rxRatePerSec)}  ↑ ${SystemStats.formatRate(s.txRatePerSec)}"

        // Uptime
        binding.devUptime.text = formatUptime(SystemClock.elapsedRealtime())

        binding.statusText.text = "● live"
        binding.statusText.setTextColor(Color.parseColor("#35C98A"))
    }

    private fun setBar(bar: ProgressBar, percent: Float) {
        bar.progress = (percent * 10).toInt().coerceIn(0, 1000)
        val color = when {
            percent >= 90f -> "#EF5F6B"
            percent >= 70f -> "#F2B640"
            else -> "#5B8CFF"
        }
        bar.progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(color))
    }

    private fun pct(v: Float) = String.format(Locale.US, "%.1f%%", v)

    private fun formatUptime(ms: Long): String {
        var s = ms / 1000
        val d = s / 86400; s %= 86400
        val h = s / 3600; s %= 3600
        val m = s / 60; s %= 60
        return buildString {
            if (d > 0) append("${d}日 ")
            append(String.format(Locale.US, "%02d:%02d:%02d", h, m, s))
        }
    }

    companion object {
        private const val CAPACITY = 60 // seconds of history shown
    }
}
