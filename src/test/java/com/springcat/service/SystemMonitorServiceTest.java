package com.springcat.service;

import com.springcat.model.SystemSnapshot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SystemMonitorServiceTest {

    private final SystemMonitorService service = new SystemMonitorService();

    @Test
    void snapshotPopulatesHostAndJvmInfo() {
        SystemSnapshot snapshot = service.snapshot();

        assertThat(snapshot.timestamp()).isPositive();
        assertThat(snapshot.host().availableProcessors()).isGreaterThanOrEqualTo(1);
        assertThat(snapshot.host().osName()).isNotBlank();
        assertThat(snapshot.host().javaVersion()).isNotBlank();

        // Heap is always reported by the JVM.
        assertThat(snapshot.jvm().heapUsedBytes()).isPositive();
        assertThat(snapshot.jvm().threadCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void diskUsageFractionsAreWithinBounds() {
        SystemSnapshot snapshot = service.snapshot();

        assertThat(snapshot.disks()).isNotEmpty();
        snapshot.disks().forEach(disk -> {
            assertThat(disk.usedFraction()).isBetween(0.0, 1.0);
            assertThat(disk.totalBytes()).isEqualTo(disk.usedBytes() + disk.freeBytes());
        });
    }
}
