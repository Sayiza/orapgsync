package me.christianrobert.orapgsync.transfer.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.model.table.TableMetadata;
import me.christianrobert.orapgsync.core.job.model.transfer.DataTransferResult;
import me.christianrobert.orapgsync.core.job.model.transfer.TableTransferOutcome;
import me.christianrobert.orapgsync.database.service.OracleConnectionService;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
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
 * Transfers tables concurrently, N tables at a time.
 *
 * <p>Parallelism is <em>across</em> tables only. The per-table producer/consumer pipe, LOB staging
 * workflow and per-table transaction in {@link CsvDataTransferService} are used unchanged — a
 * worker behaves exactly like the previous sequential loop, there are just several of them.</p>
 *
 * <h2>Why a worker loop rather than one task per table</h2>
 * Each worker opens <em>one</em> Oracle/PostgreSQL connection pair and pulls tables off a shared
 * queue until it is empty. That caps connections at the worker count instead of opening a pair per
 * table, and it load-balances naturally: a worker stuck on a large table simply takes fewer tables.
 *
 * <h2>Thread confinement</h2>
 * Workers only publish {@link TableTransferOutcome}s. All aggregation into
 * {@link DataTransferResult} and all progress reporting happen on the calling thread, so neither
 * the result object nor the progress callback needs to be thread-safe.
 *
 * <h2>Safety of concurrent target writes</h2>
 * Every table is transferred by exactly one worker, and each worker's statements (TRUNCATE, COPY,
 * LOB staging DDL) target only its own table. In the migration order, data transfer runs before
 * constraints are created, so there are no cross-table foreign keys to violate or deadlock on.
 */
@ApplicationScoped
public class ParallelTableTransferService {

    private static final Logger log = LoggerFactory.getLogger(ParallelTableTransferService.class);

    /** How long the coordinator waits for an outcome before re-checking whether workers are alive. */
    private static final long OUTCOME_POLL_MILLIS = 200;

    public static final int MIN_WORKERS = 1;
    public static final int MAX_WORKERS = 32;

    @Inject
    OracleConnectionService oracleConnectionService;

    @Inject
    PostgresConnectionService postgresConnectionService;

    @Inject
    CsvDataTransferService csvDataTransferService;

    /**
     * Notified once per completed table, always on the calling thread and never concurrently.
     */
    @FunctionalInterface
    public interface TableProgressListener {
        void onTableCompleted(int completedCount, int totalCount, TableTransferOutcome outcome);
    }

    /**
     * One worker's database resources. Package-private so tests can drive the worker loop without
     * a database.
     */
    interface WorkerContext extends AutoCloseable {

        long transfer(TableMetadata table) throws Exception;

        /**
         * Resets the PostgreSQL connection after a failed table. PostgreSQL aborts the current
         * transaction on any error, so the connection is unusable until it is rolled back.
         */
        void rollbackQuietly();

        @Override
        void close();
    }

    /**
     * Transfers all tables using {@code workerCount} concurrent workers.
     *
     * @param tables      tables to transfer, in the order they should be started
     * @param workerCount requested worker count; clamped to [1, 32] and to the table count
     * @param listener    progress listener, may be null
     */
    public DataTransferResult transferTables(List<TableMetadata> tables, int workerCount,
                                             TableProgressListener listener) {
        return transferTables(tables, workerCount, listener, this::openWorkerContext);
    }

    /**
     * Test seam: same logic, but the worker's database resources come from {@code contextFactory}.
     */
    DataTransferResult transferTables(List<TableMetadata> tables, int workerCount,
                                      TableProgressListener listener,
                                      Supplier<WorkerContext> contextFactory) {

        DataTransferResult result = new DataTransferResult();
        if (tables == null || tables.isEmpty()) {
            return result;
        }

        int effectiveWorkers = effectiveWorkerCount(workerCount, tables.size());
        log.info("Starting parallel data transfer of {} tables with {} workers", tables.size(), effectiveWorkers);

        Queue<TableMetadata> pending = new ConcurrentLinkedQueue<>(tables);
        BlockingQueue<TableTransferOutcome> outcomes = new LinkedBlockingQueue<>();
        AtomicInteger activeWorkers = new AtomicInteger(effectiveWorkers);

        AtomicInteger threadCounter = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(effectiveWorkers, runnable -> {
            Thread thread = new Thread(runnable, "data-transfer-worker-" + threadCounter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });

        try {
            for (int workerId = 0; workerId < effectiveWorkers; workerId++) {
                int id = workerId;
                executor.submit(() -> runWorker(id, pending, outcomes, activeWorkers, contextFactory));
            }

            collectOutcomes(tables.size(), pending, outcomes, activeWorkers, result, listener);

        } finally {
            executor.shutdownNow();
        }

        log.info("Parallel data transfer finished: {}", result);
        return result;
    }

    /**
     * Clamps the configured worker count. More workers than tables would only open idle
     * connections.
     */
    static int effectiveWorkerCount(int requested, int tableCount) {
        int clamped = Math.max(MIN_WORKERS, Math.min(MAX_WORKERS, requested));
        return Math.min(clamped, tableCount);
    }

    /**
     * Aggregates outcomes on the calling thread until every table is accounted for.
     *
     * <p>The wait is bounded: if all workers have exited — for example because none could open a
     * connection — the remaining tables are reported as failures instead of blocking forever.</p>
     */
    private void collectOutcomes(int totalTables, Queue<TableMetadata> pending,
                                 BlockingQueue<TableTransferOutcome> outcomes,
                                 AtomicInteger activeWorkers, DataTransferResult result,
                                 TableProgressListener listener) {

        int completed = 0;

        while (completed < totalTables) {
            TableTransferOutcome outcome;
            try {
                outcome = outcomes.poll(OUTCOME_POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Data transfer interrupted after {} of {} tables", completed, totalTables);
                break;
            }

            if (outcome == null) {
                if (activeWorkers.get() == 0 && outcomes.isEmpty()) {
                    completed += abandonRemainingTables(pending, result, listener, completed, totalTables);
                    break;
                }
                continue;
            }

            record(result, outcome);
            completed++;
            notifyListener(listener, completed, totalTables, outcome);
        }
    }

    /**
     * Reports tables that no worker will process, so the result covers every table that was asked
     * for rather than silently omitting some.
     */
    private int abandonRemainingTables(Queue<TableMetadata> pending, DataTransferResult result,
                                       TableProgressListener listener, int completed, int totalTables) {
        int abandoned = 0;
        TableMetadata table;

        while ((table = pending.poll()) != null) {
            TableTransferOutcome outcome = TableTransferOutcome.failed(qualifiedName(table),
                    "No transfer worker available - all workers stopped before this table was processed");
            log.error("Table {} not transferred: no worker available", outcome.qualifiedTableName());

            record(result, outcome);
            abandoned++;
            notifyListener(listener, completed + abandoned, totalTables, outcome);
        }
        return abandoned;
    }

    private void notifyListener(TableProgressListener listener, int completed, int total,
                                TableTransferOutcome outcome) {
        if (listener != null) {
            listener.onTableCompleted(completed, total, outcome);
        }
    }

    private void record(DataTransferResult result, TableTransferOutcome outcome) {
        if (outcome.isError()) {
            result.addError(outcome.qualifiedTableName(), outcome.errorMessage());
        } else if (outcome.isSkipped()) {
            result.addSkippedTable(outcome.qualifiedTableName());
        } else {
            result.addTransferredTable(outcome.qualifiedTableName(), outcome.rowsTransferred());
        }
    }

    /**
     * Pulls tables off the shared queue until it is empty, publishing exactly one outcome per
     * table taken. A failure on one table never stops the worker or affects the others.
     */
    private void runWorker(int workerId, Queue<TableMetadata> pending,
                           BlockingQueue<TableTransferOutcome> outcomes,
                           AtomicInteger activeWorkers, Supplier<WorkerContext> contextFactory) {
        TableMetadata inFlight = null;

        try (WorkerContext context = contextFactory.get()) {
            log.debug("Transfer worker {} started", workerId);

            TableMetadata table;
            while ((table = pending.poll()) != null) {
                inFlight = table;
                outcomes.add(transferOne(context, table));
                inFlight = null;
            }

            log.debug("Transfer worker {} finished - no tables left", workerId);

        } catch (Throwable t) {
            // Either setup/teardown failed, or a table failed with something transferOne does not
            // catch - an OutOfMemoryError on a large LOB table, for example. Report the table that
            // was in flight rather than letting it vanish from the result; tables still queued are
            // picked up by the other workers.
            log.error("Transfer worker {} stopped: {}", workerId, t.getMessage(), t);
            if (inFlight != null) {
                outcomes.add(TableTransferOutcome.failed(qualifiedName(inFlight),
                        "Transfer worker stopped: " + t));
            }
        } finally {
            activeWorkers.decrementAndGet();
        }
    }

    private TableTransferOutcome transferOne(WorkerContext context, TableMetadata table) {
        String qualifiedTableName = qualifiedName(table);

        try {
            long rowsTransferred = context.transfer(table);

            if (rowsTransferred == 0) {
                log.debug("Table {} skipped (no data or already transferred)", qualifiedTableName);
            } else {
                log.debug("Table {} transferred: {} rows", qualifiedTableName, rowsTransferred);
            }
            return TableTransferOutcome.transferred(qualifiedTableName, rowsTransferred);

        } catch (Exception e) {
            log.error("Failed to transfer table: {}", qualifiedTableName, e);
            context.rollbackQuietly();
            return TableTransferOutcome.failed(qualifiedTableName, e.getMessage());
        }
    }

    private static String qualifiedName(TableMetadata table) {
        return table.getSchema() + "." + table.getTableName();
    }

    /**
     * Opens one worker's connection pair. Called on the worker thread, so a slow connect does not
     * delay the other workers.
     */
    private WorkerContext openWorkerContext() {
        Connection oracleConnection = null;
        Connection postgresConnection = null;

        try {
            oracleConnection = oracleConnectionService.getConnection();
            postgresConnection = postgresConnectionService.getConnection();
            postgresConnection.setAutoCommit(false);

            return new JdbcWorkerContext(oracleConnection, postgresConnection, csvDataTransferService);

        } catch (SQLException e) {
            closeQuietly(oracleConnection);
            closeQuietly(postgresConnection);
            throw new IllegalStateException("Failed to open transfer connections: " + e.getMessage(), e);
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException e) {
            log.debug("Failed to close connection: {}", e.getMessage());
        }
    }

    /**
     * A worker's Oracle/PostgreSQL connection pair.
     */
    private record JdbcWorkerContext(Connection oracleConnection, Connection postgresConnection,
                                     CsvDataTransferService csvDataTransferService) implements WorkerContext {

        @Override
        public long transfer(TableMetadata table) throws Exception {
            return csvDataTransferService.transferTable(oracleConnection, postgresConnection, table);
        }

        @Override
        public void rollbackQuietly() {
            try {
                postgresConnection.rollback();
            } catch (SQLException e) {
                // Even if the rollback fails, the worker continues; the next table will fail fast
                // and be reported rather than silently skipped.
                log.error("Failed to rollback transaction after error: {}", e.getMessage());
            }
        }

        @Override
        public void close() {
            closeQuietly(oracleConnection);
            closeQuietly(postgresConnection);
        }
    }
}
