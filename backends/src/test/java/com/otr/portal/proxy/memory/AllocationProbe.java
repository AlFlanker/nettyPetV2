package com.otr.portal.proxy.memory;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Замер аллокаций в куче на потоках прокси.
 * <p>
 * Общий объём берётся из {@link com.sun.management.ThreadMXBean#getThreadAllocatedBytes(long[])}
 * (учитывает и TLAB, и аллокации вне TLAB, в том числе humongous).
 * Крупные объекты фиксируются JFR-событием {@code jdk.ObjectAllocationOutsideTLAB}.
 */
public final class AllocationProbe {
    private static final String OUTSIDE_TLAB_EVENT = "jdk.ObjectAllocationOutsideTLAB";

    private final Predicate<String> threadFilter;
    private final long largeAllocationThreshold;
    private final com.sun.management.ThreadMXBean threadBean;
    private final Map<Long, Long> startBytes;
    private final Recording recording;

    private AllocationProbe(Predicate<String> threadFilter, long largeAllocationThreshold) {
        this.threadFilter = threadFilter;
        this.largeAllocationThreshold = largeAllocationThreshold;
        this.threadBean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        this.recording = new Recording();
        this.recording.enable(OUTSIDE_TLAB_EVENT);
        this.recording.start();
        this.startBytes = snapshotAllocatedBytes();
    }

    public static AllocationProbe start(Predicate<String> threadFilter, long largeAllocationThreshold) {
        return new AllocationProbe(threadFilter, largeAllocationThreshold);
    }

    public AllocationReport stop() throws IOException {
        Map<Long, Long> endBytes = snapshotAllocatedBytes();
        recording.stop();
        long allocated = 0;
        for (Map.Entry<Long, Long> entry : endBytes.entrySet()) {
            allocated += entry.getValue() - startBytes.getOrDefault(entry.getKey(), 0L);
        }
        Path dump = Files.createTempFile("proxy-alloc", ".jfr");
        try {
            recording.dump(dump);
            return collectLargeAllocations(allocated, RecordingFile.readAllEvents(dump));
        } finally {
            recording.close();
            Files.deleteIfExists(dump);
        }
    }

    private AllocationReport collectLargeAllocations(long allocated, List<RecordedEvent> events) {
        long count = 0;
        long bytes = 0;
        long largest = 0;
        Map<String, Long> bytesByClass = new HashMap<>();
        for (RecordedEvent event : events) {
            RecordedThread thread = event.getThread();
            long size = event.getLong("allocationSize");
            if (thread == null || size < largeAllocationThreshold || !threadFilter.test(thread.getJavaName())) {
                continue;
            }
            count++;
            bytes += size;
            largest = Math.max(largest, size);
            bytesByClass.merge(event.getClass("objectClass").getName(), size, Long::sum);
        }
        return new AllocationReport(allocated, count, bytes, largest, bytesByClass);
    }

    private Map<Long, Long> snapshotAllocatedBytes() {
        long[] ids = threadBean.getAllThreadIds();
        ThreadInfo[] infos = threadBean.getThreadInfo(ids);
        long[] allocated = threadBean.getThreadAllocatedBytes(ids);
        Map<Long, Long> result = new HashMap<>();
        for (int i = 0; i < ids.length; i++) {
            if (infos[i] != null && allocated[i] >= 0 && threadFilter.test(infos[i].getThreadName())) {
                result.put(ids[i], allocated[i]);
            }
        }
        return result;
    }

    /**
     * Итог замера за серию запросов.
     */
    public static final class AllocationReport {
        private final long allocatedBytes;
        private final long largeAllocationCount;
        private final long largeAllocationBytes;
        private final long largestAllocation;
        private final Map<String, Long> largeBytesByClass;

        AllocationReport(long allocatedBytes, long largeAllocationCount, long largeAllocationBytes,
                         long largestAllocation, Map<String, Long> largeBytesByClass) {
            this.allocatedBytes = allocatedBytes;
            this.largeAllocationCount = largeAllocationCount;
            this.largeAllocationBytes = largeAllocationBytes;
            this.largestAllocation = largestAllocation;
            this.largeBytesByClass = largeBytesByClass;
        }

        public long getAllocatedBytes() {
            return allocatedBytes;
        }

        public long getLargeAllocationCount() {
            return largeAllocationCount;
        }

        public long getLargeAllocationBytes() {
            return largeAllocationBytes;
        }

        public long getLargestAllocation() {
            return largestAllocation;
        }

        public Map<String, Long> getLargeBytesByClass() {
            return largeBytesByClass;
        }
    }
}
