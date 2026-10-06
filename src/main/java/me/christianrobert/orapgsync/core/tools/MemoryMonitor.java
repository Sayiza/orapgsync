package me.christianrobert.orapgsync.core.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility for monitoring JVM memory usage during job execution.
 * Helps diagnose memory leaks and excessive memory consumption.
 */
public class MemoryMonitor {

    private static final Logger log = LoggerFactory.getLogger(MemoryMonitor.class);
    private static final Runtime runtime = Runtime.getRuntime();

    /**
     * Logs current memory usage with a descriptive message.
     *
     * @param context Descriptive context (e.g., "Before parsing package X", "After transformation")
     */
    public static void logMemoryUsage(String context) {
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();

        double usedMB = usedMemory / (1024.0 * 1024.0);
        double totalMB = totalMemory / (1024.0 * 1024.0);
        double maxMB = maxMemory / (1024.0 * 1024.0);
        double usedPercent = (usedMemory * 100.0) / maxMemory;

        log.debug("[MEMORY] {} | Used: {}/{} MB ({:.1f}%) | Max: {} MB",
                context,
                String.format("%.1f", usedMB),
                String.format("%.1f", totalMB),
                usedPercent,
                String.format("%.1f", maxMB));
    }

    /**
     * Logs memory usage and returns current used memory in bytes.
     * Useful for tracking deltas between operations.
     *
     * @param context Descriptive context
     * @return Current used memory in bytes
     */
    public static long logAndGetUsedMemory(String context) {
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        logMemoryUsage(context);
        return usedMemory;
    }

    /**
     * Logs the memory delta between two points.
     *
     * @param beforeBytes Memory usage before (from logAndGetUsedMemory)
     * @param afterBytes Memory usage after (from logAndGetUsedMemory)
     * @param operation Description of operation
     */
    public static void logMemoryDelta(long beforeBytes, long afterBytes, String operation) {
        long deltaBytes = afterBytes - beforeBytes;
        double deltaMB = deltaBytes / (1024.0 * 1024.0);

        if (deltaMB > 0) {
            log.debug("[MEMORY DELTA] {} increased memory by {:.1f} MB", operation, deltaMB);
        } else {
            log.debug("[MEMORY DELTA] {} reduced memory by {:.1f} MB", operation, Math.abs(deltaMB));
        }
    }

    /**
     * Suggests GC and logs memory before/after.
     * WARNING: Only use this for diagnostics, not in production.
     *
     * @param context Descriptive context
     */
    public static void suggestGC(String context) {
        long beforeUsed = runtime.totalMemory() - runtime.freeMemory();
        log.debug("[MEMORY] Suggesting GC before: {}", context);

        System.gc();

        // Give GC time to run
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long afterUsed = runtime.totalMemory() - runtime.freeMemory();
        long freed = beforeUsed - afterUsed;
        double freedMB = freed / (1024.0 * 1024.0);

        if (freed > 0) {
            log.debug("[MEMORY] GC freed {:.1f} MB after: {}", freedMB, context);
        } else {
            log.debug("[MEMORY] GC did not free significant memory after: {}", context);
        }
    }

    /**
     * Checks if memory usage is above a threshold percentage.
     *
     * @param thresholdPercent Threshold (0-100)
     * @return true if used memory exceeds threshold
     */
    public static boolean isMemoryAboveThreshold(int thresholdPercent) {
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        double usedPercent = (usedMemory * 100.0) / maxMemory;

        return usedPercent >= thresholdPercent;
    }

    /**
     * Logs a warning if memory is above threshold.
     *
     * @param thresholdPercent Threshold (0-100)
     * @param context Descriptive context
     */
    public static void warnIfMemoryHigh(int thresholdPercent, String context) {
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long maxMemory = runtime.maxMemory();
        double usedPercent = (usedMemory * 100.0) / maxMemory;

        if (usedPercent >= thresholdPercent) {
            double usedMB = usedMemory / (1024.0 * 1024.0);
            double maxMB = maxMemory / (1024.0 * 1024.0);
            log.warn("[MEMORY WARNING] {} | Memory usage at {:.1f}% ({:.1f}/{:.1f} MB) - approaching limit!",
                    context, usedPercent, usedMB, maxMB);
        }
    }
}
