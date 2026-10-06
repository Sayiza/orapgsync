package me.christianrobert.orapgsync.index.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.model.index.IndexCreationResult;
import me.christianrobert.orapgsync.core.job.model.index.IndexOutcome;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.index.service.IndexCreationPlanner.PlannedIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Executes a plan of {@code CREATE INDEX} statements concurrently, N at a time.
 *
 * <p>Building an index over a freshly loaded table is CPU- and IO-bound work that PostgreSQL
 * performs per statement, so running several at once is the difference between the index step
 * taking minutes and taking hours on a large schema.</p>
 *
 * <h2>Same shape as the parallel data transfer</h2>
 *
 * <p>Each worker opens one connection and pulls statements off a shared queue, which caps
 * connections at the worker count and self-balances: a worker building a large index simply takes
 * fewer of them. Workers only publish {@link IndexOutcome}s; aggregation and progress reporting
 * happen on the calling thread, so neither {@link IndexCreationResult} nor the progress callback
 * needs to be thread-safe.</p>
 *
 * <h2>Workers make no decisions</h2>
 *
 * <p>Everything requiring shared state - name allocation, skip decisions, expression
 * transformation - has already happened in {@link IndexCreationPlanner}. A worker receives
 * finished SQL and does nothing but execute it.</p>
 *
 * <h2>Why plain CREATE INDEX</h2>
 *
 * <p>Not {@code CONCURRENTLY}: nothing else is using the database during a migration, and the
 * concurrent build is strictly slower and cannot run inside a transaction. Auto-commit is left
 * on, so each index is its own transaction and one failure neither rolls back nor blocks the
 * others.</p>
 */
@ApplicationScoped
public class ParallelIndexCreationService {

    private static final Logger log = LoggerFactory.getLogger(ParallelIndexCreationService.class);

    /** How long the coordinator waits for an outcome before re-checking whether workers are alive. */
    private static final long OUTCOME_POLL_MILLIS = 200;

    public static final int MIN_WORKERS = 1;
    public static final int MAX_WORKERS = 32;

    @Inject
    PostgresConnectionService postgresConnectionService;

    /** Notified once per finished index, always on the calling thread and never concurrently. */
    @FunctionalInterface
    public interface IndexProgressListener {
        void onIndexCompleted(int completedCount, int totalCount, IndexOutcome outcome);
    }

    /** One worker's connection. Package-private so tests can drive the worker loop without a database. */
    interface WorkerContext extends AutoCloseable {

        void execute(String sql) throws Exception;

        @Override
        void close();
    }

    /**
     * Creates all planned indexes using {@code workerCount} concurrent workers.
     *
     * @param plannedIndexes statements to execute, in the order they should be started
     * @param workerCount    requested worker count; clamped to [1, 32] and to the statement count
     * @param listener       progress listener, may be null
     */
    public IndexCreationResult createIndexes(List<PlannedIndex> plannedIndexes, int workerCount,
                                             IndexProgressListener listener) {
        return createIndexes(plannedIndexes, workerCount, listener, this::openWorkerContext);
    }

    /** Test seam: same logic, but the worker's connection comes from {@code contextFactory}. */
    IndexCreationResult createIndexes(List<PlannedIndex> plannedIndexes, int workerCount,
                                      IndexProgressListener listener,
                                      Supplier<WorkerContext> contextFactory) {

        IndexCreationResult result = new IndexCreationResult();
        if (plannedIndexes == null || plannedIndexes.isEmpty()) {
            return result;
        }

        int effectiveWorkers = effectiveWorkerCount(workerCount, plannedIndexes.size());
        log.info("Creating {} indexes with {} workers", plannedIndexes.size(), effectiveWorkers);

        Queue<PlannedIndex> pending = new ConcurrentLinkedQueue<>(plannedIndexes);
        BlockingQueue<IndexOutcome> outcomes = new LinkedBlockingQueue<>();
        AtomicInteger activeWorkers = new AtomicInteger(effectiveWorkers);

        AtomicInteger threadCounter = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(effectiveWorkers, runnable -> {
            Thread thread = new Thread(runnable, "index-creation-worker-" + threadCounter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });

        try {
            for (int workerId = 0; workerId < effectiveWorkers; workerId++) {
                int id = workerId;
                executor.submit(() -> runWorker(id, pending, outcomes, activeWorkers, contextFactory));
            }

            collectOutcomes(plannedIndexes.size(), pending, outcomes, activeWorkers, result, listener);

        } finally {
            executor.shutdownNow();
        }

        log.info("Index creation finished: {}", result);
        return result;
    }

    /** Clamps the configured worker count. More workers than indexes would only open idle connections. */
    static int effectiveWorkerCount(int requested, int indexCount) {
        int clamped = Math.max(MIN_WORKERS, Math.min(MAX_WORKERS, requested));
        return Math.min(clamped, indexCount);
    }

    /**
     * Aggregates outcomes on the calling thread until every index is accounted for.
     *
     * <p>The wait is bounded: if all workers have exited - for example because none could open a
     * connection - the remaining indexes are reported as failures instead of blocking forever.</p>
     */
    private void collectOutcomes(int totalIndexes, Queue<PlannedIndex> pending,
                                 BlockingQueue<IndexOutcome> outcomes, AtomicInteger activeWorkers,
                                 IndexCreationResult result, IndexProgressListener listener) {

        int completed = 0;

        while (completed < totalIndexes) {
            IndexOutcome outcome;
            try {
                outcome = outcomes.poll(OUTCOME_POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Index creation interrupted after {} of {} indexes", completed, totalIndexes);
                break;
            }

            if (outcome == null) {
                if (activeWorkers.get() == 0 && outcomes.isEmpty()) {
                    completed += abandonRemaining(pending, result, listener, completed, totalIndexes);
                    break;
                }
                continue;
            }

            result.add(outcome);
            completed++;
            notifyListener(listener, completed, totalIndexes, outcome);
        }
    }

    /**
     * Reports indexes that no worker will process, so the result covers every planned index rather
     * than silently omitting some.
     */
    private int abandonRemaining(Queue<PlannedIndex> pending, IndexCreationResult result,
                                 IndexProgressListener listener, int completed, int totalIndexes) {
        int abandoned = 0;
        PlannedIndex planned;

        while ((planned = pending.poll()) != null) {
            IndexOutcome outcome = IndexOutcome.error(planned.qualifiedTableName(), planned.indexName(),
                    planned.keyDisplay(),
                    "No index worker available - all workers stopped before this index was created",
                    planned.sql());
            log.error("Index {} not created: no worker available", planned.indexName());

            result.add(outcome);
            abandoned++;
            notifyListener(listener, completed + abandoned, totalIndexes, outcome);
        }
        return abandoned;
    }

    private void notifyListener(IndexProgressListener listener, int completed, int total,
                                IndexOutcome outcome) {
        if (listener != null) {
            listener.onIndexCompleted(completed, total, outcome);
        }
    }

    /**
     * Pulls statements off the shared queue until it is empty, publishing exactly one outcome per
     * statement taken. A failure on one index never stops the worker or affects the others.
     */
    private void runWorker(int workerId, Queue<PlannedIndex> pending, BlockingQueue<IndexOutcome> outcomes,
                           AtomicInteger activeWorkers, Supplier<WorkerContext> contextFactory) {
        PlannedIndex inFlight = null;

        try (WorkerContext context = contextFactory.get()) {
            log.debug("Index worker {} started", workerId);

            PlannedIndex planned;
            while ((planned = pending.poll()) != null) {
                inFlight = planned;
                outcomes.add(createOne(context, planned));
                inFlight = null;
            }

            log.debug("Index worker {} finished - no indexes left", workerId);

        } catch (Throwable t) {
            // Either opening the connection failed, or a statement failed with something createOne
            // does not catch. Report the index that was in flight rather than letting it vanish;
            // anything still queued is picked up by the other workers.
            log.error("Index worker {} stopped: {}", workerId, t.getMessage(), t);
            if (inFlight != null) {
                outcomes.add(IndexOutcome.error(inFlight.qualifiedTableName(), inFlight.indexName(),
                        inFlight.keyDisplay(), "Index worker stopped: " + t, inFlight.sql()));
            }
        } finally {
            activeWorkers.decrementAndGet();
        }
    }

    private IndexOutcome createOne(WorkerContext context, PlannedIndex planned) {
        try {
            context.execute(planned.sql());
            log.debug("Created index {} on {}", planned.indexName(), planned.qualifiedTableName());
            return IndexOutcome.created(planned.qualifiedTableName(), planned.indexName(),
                    planned.keyDisplay(), planned.sql(), planned.note());

        } catch (Exception e) {
            log.error("Failed to create index {} on {}: {}",
                    planned.indexName(), planned.qualifiedTableName(), e.getMessage());
            return IndexOutcome.error(planned.qualifiedTableName(), planned.indexName(),
                    planned.keyDisplay(), e.getMessage(), planned.sql());
        }
    }

    /**
     * Opens one worker's connection. Called on the worker thread, so a slow connect does not delay
     * the other workers.
     */
    private WorkerContext openWorkerContext() {
        try {
            Connection connection = postgresConnectionService.getConnection();
            // Auto-commit stays on: each index is its own transaction, so a failure neither rolls
            // back the indexes already built nor leaves the connection in an aborted state.
            connection.setAutoCommit(true);
            return new JdbcWorkerContext(connection);

        } catch (SQLException e) {
            throw new IllegalStateException("Failed to open PostgreSQL connection for index creation: "
                    + e.getMessage(), e);
        }
    }

    /** A worker's PostgreSQL connection. */
    private record JdbcWorkerContext(Connection connection) implements WorkerContext {

        @Override
        public void execute(String sql) throws Exception {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }

        @Override
        public void close() {
            try {
                connection.close();
            } catch (SQLException e) {
                log.debug("Failed to close connection: {}", e.getMessage());
            }
        }
    }
}
