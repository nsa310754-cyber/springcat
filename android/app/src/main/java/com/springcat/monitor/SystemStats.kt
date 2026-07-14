package com.springcat.monitor

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.net.TrafficStats
import java.io.RandomAccessFile
import java.util.Locale

/**
 * Point-in-time device metrics. All values are self-observations of the running device;
 * nothing here reads other apps' data or stored credentials.
 */
data class Snapshot(
    val cpuPercent: Float?,      // null when /proc/stat is not readable on this device
    val memUsedBytes: Long,
    val memTotalBytes: Long,
    val storageUsedBytes: Long,
    val storageTotalBytes: Long,
    val network: NetworkStatus,
    val rxBytes: Long,           // cumulative device totals; deltas are computed by the caller
    val txBytes: Long
) {
    val memPercent: Float
        get() = if (memTotalBytes > 0) memUsedBytes * 100f / memTotalBytes else 0f
    val storagePercent: Float
        get() = if (storageTotalBytes > 0) storageUsedBytes * 100f / storageTotalBytes else 0f
}

data class NetworkStatus(
    val type: String,            // "Wi-Fi" / "モバイル" / "イーサネット" / "未接続" …
    val validated: Boolean,
    val ssid: String?,           // null unless connected to Wi-Fi with location permission
    val ipAddress: String?,
    val linkSpeedMbps: Int?,     // Wi-Fi only
    val rssiDbm: Int?,           // Wi-Fi signal strength
    val frequencyMhz: Int?
)

/**
 * Collects [Snapshot]s using standard Android system services and {@code /proc/stat}.
 * Designed to degrade gracefully: any metric that cannot be read becomes null.
 */
class SystemStats(private val context: Context) {

    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // Previous /proc/stat totals, for computing CPU load between samples.
    private var prevTotal = 0L
    private var prevIdle = 0L

    fun sample(): Snapshot {
        val mem = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val memTotal = mem.totalMem
        val memUsed = memTotal - mem.availMem

        val stat = StatFs(Environment.getDataDirectory().path)
        val storageTotal = stat.blockCountLong * stat.blockSizeLong
        val storageFree = stat.availableBlocksLong * stat.blockSizeLong
        val storageUsed = storageTotal - storageFree

        return Snapshot(
            cpuPercent = readCpuPercent(),
            memUsedBytes = memUsed,
            memTotalBytes = memTotal,
            storageUsedBytes = storageUsed,
            storageTotalBytes = storageTotal,
            network = readNetwork(),
            rxBytes = TrafficStats.getTotalRxBytes().coerceAtLeast(0),
            txBytes = TrafficStats.getTotalTxBytes().coerceAtLeast(0)
        )
    }

    /**
     * Reads aggregate CPU busy percentage from the first line of /proc/stat by differencing
     * against the previous read. Returns null when the file is unreadable (common on newer
     * Android releases, which sandbox it) or on the very first sample.
     */
    private fun readCpuPercent(): Float? {
        return try {
            RandomAccessFile("/proc/stat", "r").use { reader ->
                val line = reader.readLine() ?: return null
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.isEmpty() || parts[0] != "cpu") return null
                val nums = parts.drop(1).mapNotNull { it.toLongOrNull() }
                if (nums.size < 4) return null
                val idle = nums[3] + (nums.getOrNull(4) ?: 0L) // idle + iowait
                val total = nums.sum()

                val totalDelta = total - prevTotal
                val idleDelta = idle - prevIdle
                prevTotal = total
                prevIdle = idle

                if (totalDelta <= 0) return null
                val usage = (totalDelta - idleDelta) * 100f / totalDelta
                usage.coerceIn(0f, 100f)
            }
        } catch (e: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun readNetwork(): NetworkStatus {
        val active = connectivityManager.activeNetwork
        val caps = active?.let { connectivityManager.getNetworkCapabilities(it) }
        val link = active?.let { connectivityManager.getLinkProperties(it) }

        if (caps == null) {
            return NetworkStatus("未接続", false, null, null, null, null, null)
        }

        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "モバイル"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "イーサネット"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "その他"
        }
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val ip = firstGlobalIp(link)

        var ssid: String? = null
        var linkSpeed: Int? = null
        var rssi: Int? = null
        var freq: Int? = null

        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wifi?.connectionInfo
            if (info != null) {
                val raw = info.ssid
                ssid = when {
                    raw.isNullOrEmpty() || raw == WifiManager.UNKNOWN_SSID -> null
                    else -> raw.trim('"')
                }
                linkSpeed = info.linkSpeed.takeIf { it > 0 }
                rssi = info.rssi.takeIf { it != Int.MIN_VALUE }
                freq = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    info.frequency.takeIf { it > 0 }
                } else null
            }
        }

        return NetworkStatus(type, validated, ssid, ip, linkSpeed, rssi, freq)
    }

    private fun firstGlobalIp(link: LinkProperties?): String? {
        link ?: return null
        return link.linkAddresses
            .map { it.address }
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }

    companion object {
        fun formatBytes(n: Long): String {
            if (n < 0) return "n/a"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var v = n.toDouble()
            var i = 0
            while (v >= 1024 && i < units.size - 1) {
                v /= 1024; i++
            }
            return if (i == 0) "${n} B"
            else String.format(Locale.US, "%.1f %s", v, units[i])
        }

        /** Formats a per-second byte rate as e.g. "1.2 MB/s". */
        fun formatRate(bytesPerSec: Long): String = "${formatBytes(bytesPerSec)}/s"
    }
}
