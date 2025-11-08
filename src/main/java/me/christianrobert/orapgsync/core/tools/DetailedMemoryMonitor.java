package me.christianrobert.orapgsync.core.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;

/**
 * Detailed memory monitoring for diagnosing memory leaks.
 * Shows breakdown by memory pool (Eden, Old Gen, etc.)
 */
public class DetailedMemoryMonitor {

    private static final Logger log = LoggerFactory.getLogger(DetailedMemoryMonitor.class);

    /**
     * Logs detailed memory usage by pool (Heap, Eden, Old Gen, etc.)
     *
     * @param context Descriptive context
     */
    public static void logDetailedMemory(String context) {
        Runtime runtime = Runtime.getRuntime();

        // Overall heap
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;
        long maxMemory = runtime.maxMemory();

        double usedMB = usedMemory / (1024.0 * 1024.0);
        double maxMB = maxMemory / (1024.0 * 1024.0);
        double usedPercent = (usedMemory * 100.0) / maxMemory;

        log.debug("[DETAILED MEMORY] {} | Heap Used: {:.1f}/{:.1f} MB ({:.1f}%)",
                context, usedMB, maxMB, usedPercent);

        // Memory pool breakdown
        List<MemoryPoolMXBean> pools = ManagementFactory.getMemoryPoolMXBeans();
        for (MemoryPoolMXBean pool : pools) {
            MemoryUsage usage = pool.getUsage();
            String poolName = pool.getName();
            long poolUsed = usage.getUsed();
            long poolMax = usage.getMax();

            if (poolMax > 0) {
                double poolUsedMB = poolUsed / (1024.0 * 1024.0);
                double poolMaxMB = poolMax / (1024.0 * 1024.0);
                double poolPercent = (poolUsed * 100.0) / poolMax;

                log.debug("  [POOL] {} | Used: {:.1f}/{:.1f} MB ({:.1f}%)",
                        poolName, poolUsedMB, poolMaxMB, poolPercent);
            } else {
                double poolUsedMB = poolUsed / (1024.0 * 1024.0);
                log.debug("  [POOL] {} | Used: {:.1f} MB (no max limit)",
                        poolName, poolUsedMB);
            }
        }
    }

    /**
     * Logs memory before and after GC to see what can be freed.
     *
     * @param context Descriptive context
     */
    public static void forceGCAndLog(String context) {
        Runtime runtime = Runtime.getRuntime();

        long beforeUsed = runtime.totalMemory() - runtime.freeMemory();
        double beforeMB = beforeUsed / (1024.0 * 1024.0);

        log.debug("[GC TEST] Before GC ({}): {:.1f} MB used", context, beforeMB);

        // Force GC multiple times (more effective)
        System.gc();
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        System.gc();
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        long afterUsed = runtime.totalMemory() - runtime.freeMemory();
        double afterMB = afterUsed / (1024.0 * 1024.0);
        double freedMB = (beforeUsed - afterUsed) / (1024.0 * 1024.0);

        log.debug("[GC TEST] After GC ({}): {:.1f} MB used | Freed: {:.1f} MB", context, afterMB, freedMB);

        if (freedMB < 10) {
            log.warn("[GC TEST] Warning: Only {:.1f} MB freed - most memory is NOT garbage!", freedMB);
        }
    }

    /**
     * Logs top memory statistics periodically.
     *
     * @param packageCount Number of packages processed so far
     */
    public static void logPeriodicStats(int packageCount) {
        if (packageCount % 10 == 0) {
            logDetailedMemory("After " + packageCount + " packages");

            // If memory is high, test if it's garbage
            Runtime runtime = Runtime.getRuntime();
            long usedMemory = runtime.totalMemory() - runtime.freeMemory();
            double usedPercent = (usedMemory * 100.0) / runtime.maxMemory();

            if (usedPercent > 60) {
                log.warn("[MEMORY HIGH] Memory at {:.1f}% - testing if it's garbage...", usedPercent);
                forceGCAndLog("Package " + packageCount);
            }
        }
    }
}
