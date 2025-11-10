package me.christianrobert.orapgsync.trigger.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseExtractionJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.trigger.TriggerMetadata;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * PostgreSQL Trigger Verification Job (Shell).
 *
 * This job will extract trigger metadata from PostgreSQL database
 * to verify what triggers have been successfully created.
 *
 * The job will:
 * 1. Query PostgreSQL system catalogs (pg_trigger, pg_proc, etc.)
 * 2. Extract all trigger metadata
 * 3. Filter out system schemas
 * 4. Return the list for frontend display and comparison
 *
 * CURRENT STATUS: Shell implementation - returns empty result
 * TODO: Implement actual trigger verification from PostgreSQL
 */
@Dependent
public class PostgresTriggerVerificationJob extends AbstractDatabaseExtractionJob<TriggerMetadata> {

    private static final Logger log = LoggerFactory.getLogger(PostgresTriggerVerificationJob.class);

    @Inject
    private PostgresConnectionService postgresConnectionService;

    @Override
    public String getSourceDatabase() {
        return "POSTGRES";
    }

    @Override
    public String getExtractionType() {
        return "TRIGGER_VERIFICATION";
    }

    @Override
    public Class<TriggerMetadata> getResultType() {
        return TriggerMetadata.class;
    }

    @Override
    protected void saveResultsToState(List<TriggerMetadata> results) {
        stateService.setPostgresTriggerMetadata(results);
    }

    @Override
    protected List<TriggerMetadata> performExtraction(Consumer<JobProgress> progressCallback) throws Exception {
        log.info("PostgresTriggerVerificationJob: Shell implementation - returning empty result");

        updateProgress(progressCallback, 0, "Shell Implementation",
                "Trigger verification job is not yet implemented (shell only)");

        // Shell implementation - return empty list
        return new ArrayList<>();
    }
}
