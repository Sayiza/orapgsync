package me.christianrobert.orapgsync.transfer.service;

import me.christianrobert.orapgsync.core.job.model.table.TableMetadata;
import me.christianrobert.orapgsync.core.job.model.transfer.DataTransferResult;
import me.christianrobert.orapgsync.core.job.model.transfer.TableTransferOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the parallel transfer coordination without a database.
 *
 * <p>The service's {@code WorkerContext} seam is filled with a fake, so these tests exercise the
 * real worker loop, queue handling and result aggregation — the parts where concurrency bugs would
 * live — while the per-table transfer itself is simulated.</p>
 */
class ParallelTableTransferServiceTest {

    private static final int TEST_TIMEOUT_SECONDS = 30;

    private final ParallelTableTransferService service = new ParallelTableTransferService();

    /**
     * A fake worker context. {@code behaviour} maps a qualified table name to the rows it should
     * report, or throws to simulate a failing table.
     */
    private static class FakeWorkerContext implements ParallelTableTransferService.WorkerContext {

        private final Function<String, Long> behaviour;
        private final TransferRecorder recorder;

        FakeWorkerContext(Function<String, Long> behaviour, TransferRecorder recorder) {
            this.behaviour = behaviour;
            this.recorder = recorder;
            recorder.contextsOpened.incrementAndGet();
        }

        @Override
        public long transfer(TableMetadata table) {
            String name = table.getSchema() + "." + table.getTableName();
            recorder.threadsPerTable.put(name, Thread.currentThread().getName());
            recorder.transferOrder.add(name);
            return behaviour.apply(name);
        }

        @Override
        public void rollbackQuietly() {
            recorder.rollbacks.incrementAndGet();
        }

        @Override
        public void close() {
            recorder.contextsClosed.incrementAndGet();
        }
    }

    /** Shared, thread-safe record of what the workers did. */
    private static class TransferRecorder {
        final AtomicInteger contextsOpened = new AtomicInteger();
        final AtomicInteger contextsClosed = new AtomicInteger();
        final AtomicInteger rollbacks = new AtomicInteger();
        final Map<String, String> threadsPerTable = new ConcurrentHashMap<>();
        final List<String> transferOrder = java.util.Collections.synchronizedList(new ArrayList<>());
    }

    private List<TableMetadata> tables(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new TableMetadata("HR", String.format("T%03d", i)))
                .collect(Collectors.toList());
    }

    @Test
    @Timeout(TEST_TIMEOUT_SECONDS)
    @DisplayName("Every table is transferred exactly once across all workers")
    void transfersEveryTableExactlyOnce() {
        List<TableMetadata> tables = tables(50);
        TransferRecorder recorder = new TransferRecorder();

        DataTransferResult result = service.transferTables(tables, 8, null,
                () -> new FakeWorkerContext(name -> 100L, recorder));

        assertEquals(50, result.getTransferredCount());
        assertEquals(50 * 100L, result.getTotalRowsTransferred());
        assertEquals(0, result.getErrorCount());

        assertEquals(50, recorder.transferOrder.size(), "no table may be transferred twice");
        assertEquals(50, Set.copyOf(recorder.transferOrder).size(), "no table may be transferred twice");
    }

    @Test
    @Timeout(TEST_TIMEOUT_SECONDS)
    @DisplayName("Tables are spread over multiple worker threads")
    void actuallyRunsInParallel() throws InterruptedException {
        int workers = 4;
        List<TableMetadata> tables = tables(workers);
        TransferRecorder recorder = new TransferRecorder();

        // Each table blocks until all workers have arrived; if the transfer were sequential this
        // would never release and the test would hit its timeout.
        CountDownLatch allWorkersArrived = new CountDownLatch(workers);

        service.transferTables(tables, workers, null,
                () -> new FakeWorkerContext(name -> {
                    allWorkersArrived.countDown();
                    try {
                        assertTrue(allWorkersArrived.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                                "all workers should run concurrently");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return 1L;
                }, recorder));

        assertEquals(0, allWorkersArrived.getCount());
        assertEquals(workers, Set.copyOf(recorder.threadsPerTable.values()).size(),
                "each table should have been handled by a distinct worker thread");
    }

    @Test
    @Timeout(TEST_TIMEOUT_SECONDS)
    @DisplayName("A failing table does not stop the other workers")
    void isolatesTableFailures() {
        List<TableMetadata> tables = tables(20);
        TransferRecorder recorder = new TransferRecorder();

        DataTransferResult result = service.transferTables(tables, 4, null,
                () -> new FakeWorkerContext(name -> {
                    if (name.endsWith("5") || name.endsWith("7")) {
                        throw new IllegalStateException("boom on " + name);
                    }
                    return 10L;
                }, recorder));

        assertEquals(4, result.getErrorCount(), "T005, T007, T015 and T017 should fail");
        assertEquals(16, result.getTransferredCount());
        assertEquals(20, result.getTotalProcessed(), "every table must be accounted for");
        assertEquals(4, recorder.rollbacks.get(), "each failed table must roll back its transaction");

        assertTrue(result.getErrors().stream()
                .allMatch(error -> error.getErrorMessage().startsWith("boom on")),
                "the original failure message must survive to the result");
    }

    @Test
    @Timeout(TEST_TIMEOUT_SECONDS)
    @DisplayName("Empty tables are reported as skipped, not transferred")
    void reportsEmptyTablesAsSkipped() {
        List<TableMetadata> tables = tables(6);
        TransferRecorder recorder = new TransferRecorder();

        DataTransferResult result = service.transferTables(tables, 3, null,
                () -> new FakeWorkerContext(name -> name.endsWith("0") || name.endsWith("1") ? 0L : 5L,
                        recorder));

        assertEquals(2, result.getSkippedCount());
        assertEquals(4, result.getTransferredCount());
        assertEquals(6, result.getTotalProcessed());
    }

    @Test
    @Timeout(TEST_TIMEOUT_SECONDS)
    @DisplayName("Progress is reported once per table, in ascending order, on one thread")
    void reportsProgressSequentially() {
        List<TableMetadata> tables = tables(25);
        TransferRecorder recorder = new TransferRecorder();

        List<Integer> completedCounts = new ArrayList<>();
        Set<String> listenerThreads = new java.util.HashSet<>();
        List<TableTransferOutcome> outcomes = new ArrayList<>();

        service.transferTables(tables, 5,
                (completed, total, outcome) -> {
                    // Deliberately unsynchronized collections: the service guarantees the listener
                    // is only ever called from the calling thread.
                    completedCounts.add(completed);
                    listenerThreads.add(Thread.currentThread().getName());
                    outcomes.add(outcome);
                    assertEquals(25, total);
                },
                () -> new FakeWorkerContext(name -> 1L, recorder));

        assertEquals(IntStream.rangeClosed(1, 25).boxed().toList(), completedCounts,
                "progress must count up by one per table without gaps or duplicates");
        assertEquals(1, listenerThreads.size(), "the listener must not be called concurrently");
        assertEquals(25, outcomes.size());
    }

    @Test
    @Timeout(TEST_TIMEOUT_SECONDS)
    @DisplayName("Connections are capped at the worker count and always closed")
    void capsConnectionsAtWorkerCountAndClosesThem() {
        List<TableMetadata> tables = tables(40);
        TransferRecorder recorder = new TransferRecorder();

        service.transferTables(tables, 5, null, () -> new FakeWorkerContext(name -> 1L, recorder));

        int opened = recorder.contextsOpened.get();

        // Not "exactly 5": if the queue is drained before the last worker thread starts, the
        // coordinator finishes and shutdownNow() cancels it before it opens a connection. That is
        // correct behaviour - an idle worker should not connect. What must hold is the cap, which
        // is what distinguishes a worker loop from one task (and one connection pair) per table.
        assertTrue(opened >= 1 && opened <= 5,
                "one connection pair per worker at most, not one per table, but was " + opened);
        assertEquals(opened, recorder.contextsClosed.get(), "every connection pair must be closed");
    }

    @Test
    @Timeout(TEST_TIMEOUT_SECONDS)
    @DisplayName("Tables are picked up in the order they were scheduled")
    void consumesTablesInScheduledOrder() {
        List<TableMetadata> tables = tables(10);
        TransferRecorder recorder = new TransferRecorder();

        // A single worker makes the consumption order observable and deterministic.
        service.transferTables(tables, 1, null, () -> new FakeWorkerContext(name -> 1L, recorder));

        assertEquals(tables.stream().map(t -> t.getSchema() + "." + t.getTableName()).toList(),
                List.copyOf(recorder.transferOrder),
                "the largest-first order computed by TransferOrdering must be preserved");
    }

    @Test
    @Timeout(TEST_TIMEOUT_SECONDS)
    @DisplayName("Tables are reported as failed when no worker can start, rather than hanging")
    void doesNotHangWhenAllWorkersFailToStart() {
        List<TableMetadata> tables = tables(7);

        DataTransferResult result = service.transferTables(tables, 3, null,
                () -> {
                    throw new IllegalStateException("Failed to open transfer connections");
                });

        assertEquals(7, result.getErrorCount(), "no table may silently disappear");
        assertEquals(7, result.getTotalProcessed());
        assertTrue(result.getErrors().stream()
                .allMatch(error -> error.getErrorMessage().contains("No transfer worker available")));
    }

    @Test
    @Timeout(TEST_TIMEOUT_SECONDS)
    @DisplayName("Surviving workers finish the run when some workers cannot start")
    void survivingWorkersFinishTheRun() {
        List<TableMetadata> tables = tables(30);
        TransferRecorder recorder = new TransferRecorder();
        AtomicInteger contextAttempts = new AtomicInteger();

        DataTransferResult result = service.transferTables(tables, 4, null,
                () -> {
                    // Two of the four workers fail to open their connections.
                    if (contextAttempts.incrementAndGet() <= 2) {
                        throw new IllegalStateException("Failed to open transfer connections");
                    }
                    return new FakeWorkerContext(name -> 3L, recorder);
                });

        assertEquals(30, result.getTransferredCount(), "the healthy workers must cover every table");
        assertEquals(0, result.getErrorCount());
        assertTrue(recorder.contextsOpened.get() >= 1,
                "at least one worker must have connected to cover the tables");
    }

    @Test
    @Timeout(TEST_TIMEOUT_SECONDS)
    @DisplayName("Worker count is clamped to sane bounds and to the number of tables")
    void clampsWorkerCount() {
        assertEquals(1, ParallelTableTransferService.effectiveWorkerCount(0, 10));
        assertEquals(1, ParallelTableTransferService.effectiveWorkerCount(-5, 10));
        assertEquals(4, ParallelTableTransferService.effectiveWorkerCount(4, 10));
        assertEquals(32, ParallelTableTransferService.effectiveWorkerCount(1000, 100));
        assertEquals(3, ParallelTableTransferService.effectiveWorkerCount(8, 3),
                "more workers than tables would only open idle connections");
    }

    @Test
    @Timeout(TEST_TIMEOUT_SECONDS)
    @DisplayName("An empty table list opens no connections")
    void handlesEmptyTableList() {
        TransferRecorder recorder = new TransferRecorder();

        DataTransferResult result = service.transferTables(List.of(), 4, null,
                () -> new FakeWorkerContext(name -> 1L, recorder));

        assertEquals(0, result.getTotalProcessed());
        assertEquals(0, recorder.contextsOpened.get());
        assertFalse(result.hasErrors());
    }
}
