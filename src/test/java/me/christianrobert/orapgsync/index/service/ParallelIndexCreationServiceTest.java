package me.christianrobert.orapgsync.index.service;

import me.christianrobert.orapgsync.core.job.model.index.IndexCreationResult;
import me.christianrobert.orapgsync.core.job.model.index.IndexOutcome;
import me.christianrobert.orapgsync.index.service.IndexCreationPlanner.PlannedIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the real worker loop through the package-private context seam, so the coordination logic
 * under test is the production one - only the database connection is replaced.
 */
@Timeout(30)
class ParallelIndexCreationServiceTest {

    private final ParallelIndexCreationService service = new ParallelIndexCreationService();

    private static List<PlannedIndex> plannedIndexes(int count) {
        List<PlannedIndex> planned = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            planned.add(new PlannedIndex("hr.t" + i, "ix" + i, "col",
                    "CREATE INDEX ix" + i + " ON hr.t" + i + " (col)", null));
        }
        return planned;
    }

    /** Records what every worker did, safely across threads. */
    private static class Recorder {
        final List<String> executed = new CopyOnWriteArrayList<>();
        final Set<Object> contextsOpened = ConcurrentHashMap.newKeySet();
        final Set<Object> contextsClosed = ConcurrentHashMap.newKeySet();
        final AtomicInteger openAttempts = new AtomicInteger();
    }

    private static class FakeContext implements ParallelIndexCreationService.WorkerContext {
        private final Recorder recorder;
        private final Runnable beforeExecute;
        private final Set<String> failingSql;

        FakeContext(Recorder recorder, Runnable beforeExecute, Set<String> failingSql) {
            this.recorder = recorder;
            this.beforeExecute = beforeExecute;
            this.failingSql = failingSql;
            recorder.contextsOpened.add(this);
        }

        @Override
        public void execute(String sql) throws Exception {
            if (beforeExecute != null) {
                beforeExecute.run();
            }
            recorder.executed.add(sql);
            if (failingSql.contains(sql)) {
                throw new IllegalStateException("relation already exists");
            }
        }

        @Override
        public void close() {
            recorder.contextsClosed.add(this);
        }
    }

    private static Supplier<ParallelIndexCreationService.WorkerContext> contextFactory(Recorder recorder) {
        return contextFactory(recorder, null, Set.of());
    }

    private static Supplier<ParallelIndexCreationService.WorkerContext> contextFactory(
            Recorder recorder, Runnable beforeExecute, Set<String> failingSql) {
        return () -> {
            recorder.openAttempts.incrementAndGet();
            return new FakeContext(recorder, beforeExecute, failingSql);
        };
    }

    @Test
    @DisplayName("every planned index is created exactly once")
    void everyIndexCreatedExactlyOnce() {
        Recorder recorder = new Recorder();
        List<PlannedIndex> planned = plannedIndexes(50);

        IndexCreationResult result = service.createIndexes(planned, 8, null, contextFactory(recorder));

        assertEquals(50, result.getCreatedCount());
        assertEquals(50, result.getTotalProcessed());
        assertEquals(50, recorder.executed.size());
        assertEquals(50, new HashSet<>(recorder.executed).size(), "no statement was executed twice");
    }

    @Test
    @DisplayName("workers really do run concurrently")
    void actuallyRunsInParallel() throws Exception {
        int workers = 4;
        // Every statement blocks until all workers have arrived. If the loop were sequential the
        // latch would never reach zero and this test would hit its timeout.
        CountDownLatch allWorkersArrived = new CountDownLatch(workers);
        Recorder recorder = new Recorder();

        Runnable barrier = () -> {
            allWorkersArrived.countDown();
            try {
                assertTrue(allWorkersArrived.await(20, TimeUnit.SECONDS),
                        "workers did not run concurrently");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        IndexCreationResult result = service.createIndexes(plannedIndexes(workers), workers, null,
                contextFactory(recorder, barrier, Set.of()));

        assertEquals(0, allWorkersArrived.getCount());
        assertEquals(workers, result.getCreatedCount());
    }

    @Test
    @DisplayName("a failing index does not stop the others")
    void failureIsIsolated() {
        Recorder recorder = new Recorder();
        List<PlannedIndex> planned = plannedIndexes(10);
        Set<String> failing = Set.of(planned.get(3).sql(), planned.get(7).sql());

        IndexCreationResult result = service.createIndexes(planned, 3, null,
                contextFactory(recorder, null, failing));

        assertEquals(8, result.getCreatedCount());
        assertEquals(2, result.getErrorCount());
        assertEquals(10, result.getTotalProcessed());
    }

    @Test
    @DisplayName("a failure carries the statement that failed")
    void errorOutcomeCarriesSql() {
        Recorder recorder = new Recorder();
        List<PlannedIndex> planned = plannedIndexes(1);

        IndexCreationResult result = service.createIndexes(planned, 1, null,
                contextFactory(recorder, null, Set.of(planned.get(0).sql())));

        IndexOutcome error = result.getErrors().get(0);
        assertEquals(planned.get(0).sql(), error.sqlStatement());
        assertNotNull(error.reason());
    }

    @Test
    @DisplayName("progress is reported once per index, in order, on one thread")
    void progressIsSingleThreadedAndOrdered() {
        Recorder recorder = new Recorder();
        // Deliberately unsynchronized: if the listener were called concurrently these would be
        // corrupted or would lose entries, and the assertions below would fail.
        List<Integer> completedCounts = new ArrayList<>();
        Set<Thread> callingThreads = new HashSet<>();

        IndexCreationResult result = service.createIndexes(plannedIndexes(20), 5,
                (completed, total, outcome) -> {
                    completedCounts.add(completed);
                    callingThreads.add(Thread.currentThread());
                },
                contextFactory(recorder));

        assertEquals(20, result.getTotalProcessed());
        assertEquals(20, completedCounts.size());
        assertEquals(1, callingThreads.size(), "listener must only ever be called on one thread");

        List<Integer> expected = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            expected.add(i);
        }
        assertEquals(expected, completedCounts);
    }

    @Test
    @DisplayName("connections are capped at the worker count, and all are closed")
    void capsConnectionsAtWorkerCountAndClosesThem() {
        Recorder recorder = new Recorder();

        service.createIndexes(plannedIndexes(40), 4, null, contextFactory(recorder));

        int opened = recorder.openAttempts.get();

        // Not "exactly 4": if the queue is drained before the last worker thread starts, the
        // coordinator finishes and shutdownNow() cancels it before it opens a connection. That is
        // correct behaviour - an idle worker should not connect. What must hold is the cap, which
        // is what distinguishes a worker loop from one task (and one connection) per index.
        assertTrue(opened >= 1 && opened <= 4,
                "one connection per worker at most, not one per index, but was " + opened);
        assertEquals(recorder.contextsOpened, recorder.contextsClosed, "every context was closed");
    }

    @Test
    @DisplayName("the run does not hang when no worker can connect")
    void doesNotHangWhenAllWorkersFailToStart() {
        Supplier<ParallelIndexCreationService.WorkerContext> failing = () -> {
            throw new IllegalStateException("connection refused");
        };

        IndexCreationResult result = service.createIndexes(plannedIndexes(12), 4, null, failing);

        assertEquals(12, result.getErrorCount(), "every index is reported rather than lost");
        assertEquals(12, result.getTotalProcessed());
    }

    @Test
    @DisplayName("surviving workers finish the run when some cannot connect")
    void survivingWorkersFinishTheRun() {
        Recorder recorder = new Recorder();
        AtomicInteger attempts = new AtomicInteger();

        Supplier<ParallelIndexCreationService.WorkerContext> flaky = () -> {
            if (attempts.incrementAndGet() <= 2) {
                throw new IllegalStateException("connection refused");
            }
            return new FakeContext(recorder, null, Set.of());
        };

        IndexCreationResult result = service.createIndexes(plannedIndexes(30), 4, null, flaky);

        assertEquals(30, result.getCreatedCount(), "remaining workers picked up all the indexes");
        assertEquals(0, result.getErrorCount());
    }

    @Test
    void clampsWorkerCount() {
        assertEquals(1, ParallelIndexCreationService.effectiveWorkerCount(0, 10));
        assertEquals(1, ParallelIndexCreationService.effectiveWorkerCount(-5, 10));
        assertEquals(ParallelIndexCreationService.MAX_WORKERS,
                ParallelIndexCreationService.effectiveWorkerCount(100, 500));
        assertEquals(3, ParallelIndexCreationService.effectiveWorkerCount(8, 3),
                "never more workers than indexes");
    }

    @Test
    void emptyPlanOpensNoConnections() {
        Recorder recorder = new Recorder();

        IndexCreationResult result = service.createIndexes(
                Collections.emptyList(), 4, null, contextFactory(recorder));

        assertEquals(0, result.getTotalProcessed());
        assertEquals(0, recorder.openAttempts.get());
    }

    @Test
    void nullPlanIsHandled() {
        Recorder recorder = new Recorder();

        IndexCreationResult result = service.createIndexes(null, 4, null, contextFactory(recorder));

        assertEquals(0, result.getTotalProcessed());
    }
}
