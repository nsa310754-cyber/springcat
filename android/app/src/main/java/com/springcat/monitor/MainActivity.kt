package com.springcat.monitor

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.springcat.monitor.databinding.ActivityMainBinding
import java.util.Locale

/**
 * Single-screen dashboard that samples device metrics once per second, updates the metric
 * cards, and feeds the CPU / memory history into the [LineChartView].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var stats: SystemStats

    private val uiHandler = Handler(Looper.getMainLooper())
    private val intervalMs = 1000L

    // Sampling runs off the main thread: reading /proc and, in root mode, spawning `su`
    // must never block the UI thread (the first `su` call can wait on a superuser prompt).
    private var samplerThread: HandlerThread? = null
    private var samplerHandler: Handler? = null

    // Cumulative traffic counters from the previous tick, for throughput deltas.
    private var lastRx = -1L
    private var lastTx = -1L
    private var lastTickAt = 0L

    private val cpuSeries = LineChartView.Series("CPU", Color.parseColor("#5B8CFF"), CAPACITY)
    private val memSeries = LineChartView.Series("メモリ", Color.parseColor("#35C98A"), CAPACITY)

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* SSID simply stays hidden if the user declines. */ }

    private val ticker = object : Runnable {
        override fun run() {
            val snapshot = stats.sample()          // background thread
            val now = SystemClock.elapsedRealtime()
            uiHandler.post { render(snapshot, now) } // marshal to UI thread
            samplerHandler?.postDelayed(this, intervalMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        stats = SystemStats(this)

        binding.chart.fixedMax = 100f
        binding.chart.yLabelFormatter = { v -> "${v.toInt()}%" }
        binding.chart.addSeries(cpuSeries)
        binding.chart.addSeries(memSeries)

        binding.devModel.text = "${Build.MANUFACTURER} ${Build.MODEL}"
        binding.devOs.text = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        binding.devCores.text = Runtime.getRuntime().availableProcessors().toString()

        maybeRequestLocation()
    }

    override fun onResume() {
        super.onResume()
        val thread = HandlerThread("springcat-sampler").also { it.start() }
        samplerThread = thread
        samplerHandler = Handler(thread.looper).also { it.post(ticker) }
    }

    override fun onPause() {
        super.onPause()
        samplerHandler?.removeCallbacks(ticker)
        samplerThread?.quitSafely()
        samplerHandler = null
        samplerThread = null
    }

    private fun maybeRequestLocation() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun render(s: Snapshot, now: Long) {
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

        // Throughput (delta since last tick, normalised to per-second).
        if (lastRx >= 0 && lastTickAt > 0) {
            val dt = ((now - lastTickAt).coerceAtLeast(1)).toFloat() / 1000f
            val rxRate = ((s.rxBytes - lastRx).coerceAtLeast(0) / dt).toLong()
            val txRate = ((s.txBytes - lastTx).coerceAtLeast(0) / dt).toLong()
            binding.netThroughput.text =
                "↓ ${SystemStats.formatRate(rxRate)}  ↑ ${SystemStats.formatRate(txRate)}"
        }
        lastRx = s.rxBytes
        lastTx = s.txBytes
        lastTickAt = now

        // Chart history
        binding.chart.push(cpu ?: 0f, s.memPercent)

        // Uptime
        binding.devUptime.text = formatUptime(SystemClock.elapsedRealtime())

        binding.statusText.text = "● live"
        binding.statusText.setTextColor(Color.parseColor("#35C98A"))
    }

    private fun setBar(bar: android.widget.ProgressBar, percent: Float) {
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
