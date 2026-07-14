package com.springcat.service;

import com.springcat.model.SystemSnapshot;
import com.springcat.model.SystemSnapshot.CpuInfo;
import com.springcat.model.SystemSnapshot.DiskInfo;
import com.springcat.model.SystemSnapshot.HostInfo;
import com.springcat.model.SystemSnapshot.JvmInfo;
import com.springcat.model.SystemSnapshot.MemoryInfo;
import org.springframework.stereotype.Service;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;

/**
 * Collects live host and JVM metrics using the JDK management beans.
 *
 * <p>Rich CPU and memory figures come from {@link com.sun.management.OperatingSystemMXBean}
 * when the running JVM exposes it (all HotSpot-based JVMs do); otherwise those values fall
 * back to {@code -1} to signal "unavailable" rather than failing.
 */
@Service
public class SystemMonitorService {

    private final OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private final RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

    /** Non-null only when the JVM exposes the extended Sun/Oracle OS bean. */
    private final com.sun.management.OperatingSystemMXBean sunOsBean =
            osBean instanceof com.sun.management.OperatingSystemMXBean sun ? sun : null;

    public SystemSnapshot snapshot() {
        return new SystemSnapshot(
                System.currentTimeMillis(),
                hostInfo(),
                cpuInfo(),
                memoryInfo(),
                jvmInfo(),
                diskInfo());
    }

    private HostInfo hostInfo() {
        return new HostInfo(
                osBean.getName(),
                osBean.getVersion(),
                osBean.getArch(),
                osBean.getAvailableProcessors(),
                System.getProperty("java.version"),
                System.getProperty("java.vendor"),
                runtimeBean.getUptime());
    }

    private CpuInfo cpuInfo() {
        double systemLoad = sunOsBean != null ? sunOsBean.getCpuLoad() : -1;
        double processLoad = sunOsBean != null ? sunOsBean.getProcessCpuLoad() : -1;
        double loadAverage = osBean.getSystemLoadAverage();
        return new CpuInfo(systemLoad, processLoad, loadAverage);
    }

    private MemoryInfo memoryInfo() {
        if (sunOsBean == null) {
            return new MemoryInfo(-1, -1, -1, -1);
        }
        long total = sunOsBean.getTotalMemorySize();
        long free = sunOsBean.getFreeMemorySize();
        long used = total > 0 && free >= 0 ? total - free : -1;
        double usedFraction = total > 0 && used >= 0 ? (double) used / total : -1;
        return new MemoryInfo(total, used, free, usedFraction);
    }

    private JvmInfo jvmInfo() {
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        long used = heap.getUsed();
        long max = heap.getMax();
        double usedFraction = max > 0 ? (double) used / max : -1;
        return new JvmInfo(used, max, usedFraction, threadBean.getThreadCount());
    }

    private List<DiskInfo> diskInfo() {
        List<DiskInfo> disks = new ArrayList<>();
        for (File root : File.listRoots()) {
            long total = root.getTotalSpace();
            if (total <= 0) {
                continue;
            }
            long free = root.getUsableSpace();
            long used = total - free;
            double usedFraction = (double) used / total;
            disks.add(new DiskInfo(root.getAbsolutePath(), total, used, free, usedFraction));
        }
        return disks;
    }
}
