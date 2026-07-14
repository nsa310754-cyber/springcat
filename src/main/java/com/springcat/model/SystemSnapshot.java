package com.springcat.model;

import java.util.List;

/**
 * An immutable point-in-time snapshot of the host and JVM state.
 *
 * @param timestamp epoch millis at which the snapshot was taken
 * @param host      static host / OS information
 * @param cpu       processor load information
 * @param memory    physical memory usage
 * @param jvm       JVM heap and runtime information
 * @param disks     per-filesystem usage
 */
public record SystemSnapshot(
        long timestamp,
        HostInfo host,
        CpuInfo cpu,
        MemoryInfo memory,
        JvmInfo jvm,
        List<DiskInfo> disks) {

    /** Static information about the host operating system. */
    public record HostInfo(
            String osName,
            String osVersion,
            String arch,
            int availableProcessors,
            String javaVersion,
            String javaVendor,
            long uptimeMillis) {
    }

    /** Current processor load. Values are fractions in the range [0, 1], or -1 when unavailable. */
    public record CpuInfo(
            double systemLoad,
            double processLoad,
            double loadAverage1m) {
    }

    /** Physical (system) memory usage in bytes. */
    public record MemoryInfo(
            long totalBytes,
            long usedBytes,
            long freeBytes,
            double usedFraction) {
    }

    /** JVM heap usage in bytes. */
    public record JvmInfo(
            long heapUsedBytes,
            long heapMaxBytes,
            double heapUsedFraction,
            int threadCount) {
    }

    /** Usage of a single filesystem root. */
    public record DiskInfo(
            String path,
            long totalBytes,
            long usedBytes,
            long freeBytes,
            double usedFraction) {
    }
}
