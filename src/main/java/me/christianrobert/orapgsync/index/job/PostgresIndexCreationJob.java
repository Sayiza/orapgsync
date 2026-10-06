package me.christianrobert.orapgsync.index.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseWriteJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.index.IndexCreationResult;
import me.christianrobert.orapgsync.core.job.model.index.IndexMetadata;
import me.christianrobert.orapgsync.core.job.model.index.IndexOutcome;
import me.christianrobert.orapgsync.core.job.model.index.PostgresIndexCatalog;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.database.service.PostgresIndexCatalogService;
import me.christianrobert.orapgsync.index.service.IndexCreationPlanner;
import me.christianrobert.orapgsync.index.service.ParallelIndexCreationService;
import me.christianrobert.orapgsync.transformer.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Creates the extracted Oracle indexes in PostgreSQL.
 *
 * <p>Runs after data transfer - building an index over a populated table is far cheaper than
 * maintaining it row by row during the load - and after constraint creation, so that the indexes
 * PostgreSQL generates for primary keys and unique constraints are already visible and are not
 * duplicated here.</p>
 *
 * <p>Work is split into a single-threaded planning pass ({@link IndexCreationPlanner}) and a
 * parallel execution pass ({@link ParallelIndexCreationService}). Every extracted index appears
 * exactly once in the result, whether it was created, skipped, refused as unsupported, or
 * failed.</p>
 */
@Dependent
public class PostgresIndexCreationJob extends AbstractDatabaseWriteJob<IndexCreationResult> {

    private static final Logger log = LoggerFactory.getLogger(PostgresIndexCreationJob.class);

    public static final String WORKER_COUNT_CONFIG_KEY = "index.parallel-workers";
    public static final int DEFAULT_WORKER_COUNT = 4;

    /** Progress span reserved for the index creation itself. */
    private static final int PROGRESS_CREATE_START = 30;
    private static final int PROGRESS_CREATE_SPAN = 65;

    @Inject
    private PostgresConnectionService postgresConnectionService;

    @Inject
    private PostgresIndexCatalogService postgresIndexCatalogService;

    @Inject
    private IndexCreationPlanner indexCreationPlanner;

    @Inject
    private ParallelIndexCreationService parallelIndexCreationService;

    @Override
    public String getTargetDatabase() {
        return "POSTGRES";
    }

    @Override
    public String getWriteOperationType() {
        return "INDEX_CREATION";
    }

    @Override
    public Class<IndexCreationResult> getResultType() {
        return IndexCreationResult.class;
    }

    @Override
    protected void saveResultsToState(IndexCreationResult result) {
        stateService.setIndexCreationResult(result);
    }

    @Override
    protected IndexCreationResult performWriteOperation(Consumer<JobProgress> progressCallback) throws Exception {
        updateProgress(progressCallback, 0, "Initializing", "Starting PostgreSQL index creation");

        List<IndexMetadata> oracleIndexes = stateService.getOracleIndexMetadata();
        if (oracleIndexes == null || oracleIndexes.isEmpty()) {
            updateProgress(progressCallback, 100, "No indexes to create",
                    "No Oracle indexes found in state. Please extract Oracle indexes first.");
            log.warn("No Oracle indexes found in state for index creation");
            return new IndexCreationResult();
        }

        List<IndexMetadata> validIndexes = filterValidIndexes(oracleIndexes);
        if (validIndexes.isEmpty()) {
            updateProgress(progressCallback, 100, "No valid indexes",
                    "No Oracle indexes in migratable schemas");
            return new IndexCreationResult();
        }

        updateProgress(progressCallback, 10, "Reading PostgreSQL indexes",
                String.format("Found %d Oracle indexes; reading existing PostgreSQL indexes", validIndexes.size()));

        PostgresIndexCatalog catalog;
        try (Connection connection = postgresConnectionService.getConnection()) {
            catalog = postgresIndexCatalogService.load(connection);
        }

        updateProgress(progressCallback, 20, "Planning index creation",
                String.format("PostgreSQL already has %d indexes", catalog.getIndexCount()));

        TransformationIndices transformationIndices =
                MetadataIndexBuilder.build(stateService, stateService.getOracleSchemaNames());

        IndexCreationPlanner.IndexPlan plan =
                indexCreationPlanner.plan(validIndexes, catalog, transformationIndices);

        int workerCount = resolveWorkerCount();
        int toCreate = plan.statements().size();

        updateProgress(progressCallback, PROGRESS_CREATE_START, "Creating indexes",
                String.format("Creating %d indexes with %d parallel workers (%d already decided)",
                        toCreate, workerCount, plan.decidedOutcomes().size()));

        try {
            IndexCreationResult executed = parallelIndexCreationService.createIndexes(
                    plan.statements(), workerCount,
                    (completed, total, outcome) -> updateProgress(progressCallback,
                            PROGRESS_CREATE_START + (completed * PROGRESS_CREATE_SPAN / total),
                            "Created: " + outcome.indexName(),
                            String.format("Index %d of %d", completed, total)));

            IndexCreationResult result = merge(plan.decidedOutcomes(), executed);

            updateProgress(progressCallback, 95, "Finalizing", "Index creation completed");
            log.debug("Index creation job completed: {}", result);

            return result;

        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed", "Index creation failed: " + e.getMessage());
            log.error("Index creation job failed", e);
            throw e;
        }
    }

    /**
     * Combines the outcomes decided during planning with those produced by execution, so the
     * result accounts for every index that was extracted.
     */
    private IndexCreationResult merge(List<IndexOutcome> decided, IndexCreationResult executed) {
        IndexCreationResult result = new IndexCreationResult();
        decided.forEach(result::add);
        executed.getOutcomes().forEach(result::add);
        return result;
    }

    private List<IndexMetadata> filterValidIndexes(List<IndexMetadata> indexes) {
        List<IndexMetadata> valid = new ArrayList<>();
        for (IndexMetadata index : indexes) {
            if (!filterValidSchemas(List.of(index.getSchema())).isEmpty()) {
                valid.add(index);
            }
        }
        return valid;
    }

    /**
     * Reads the configured worker count, falling back to the default if it is missing or not a
     * number. Out-of-range values are clamped by the creation service.
     */
    private int resolveWorkerCount() {
        Object configured = configService.getConfigValue(WORKER_COUNT_CONFIG_KEY);
        if (configured == null) {
            return DEFAULT_WORKER_COUNT;
        }

        try {
            return Integer.parseInt(configured.toString().trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid {} value '{}', falling back to {}",
                    WORKER_COUNT_CONFIG_KEY, configured, DEFAULT_WORKER_COUNT);
            return DEFAULT_WORKER_COUNT;
        }
    }

    @Override
    protected String generateSummaryMessage(IndexCreationResult result) {
        if (result.getTotalProcessed() == 0) {
            return "No indexes processed";
        }

        String message = String.format("Index creation completed: %d created, %d skipped, %d unsupported",
                result.getCreatedCount(), result.getSkippedCount(), result.getUnsupportedCount());

        if (result.hasErrors()) {
            message += String.format(", %d failed", result.getErrorCount());
        }

        return message;
    }
}
