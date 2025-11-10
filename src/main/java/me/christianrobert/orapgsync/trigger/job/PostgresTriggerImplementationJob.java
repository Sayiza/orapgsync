package me.christianrobert.orapgsync.trigger.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseWriteJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.trigger.TriggerImplementationResult;
import me.christianrobert.orapgsync.database.service.OracleConnectionService;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * PostgreSQL Trigger Implementation Job (Shell).
 *
 * This job will transform Oracle triggers to PostgreSQL triggers and create them.
 *
 * The job will:
 * 1. Retrieve Oracle trigger metadata from state
 * 2. For each trigger:
 *    a. Transform Oracle PL/SQL trigger body to PostgreSQL PL/pgSQL
 *    b. Handle :NEW/:OLD reference transformation
 *    c. Transform trigger timing and events
 *    d. Create trigger function (PL/pgSQL)
 *    e. Create trigger definition (CREATE TRIGGER)
 * 3. Return results tracking success/skipped/errors
 *
 * Key transformations:
 * - :NEW → NEW (PostgreSQL trigger record)
 * - :OLD → OLD (PostgreSQL trigger record)
 * - BEFORE/AFTER/INSTEAD OF timing preserved
 * - ROW/STATEMENT level preserved
 * - Trigger function must return NEW/OLD/NULL as appropriate
 *
 * CURRENT STATUS: Shell implementation - returns empty result
 * TODO: Implement actual trigger transformation logic
 */
@Dependent
public class PostgresTriggerImplementationJob extends AbstractDatabaseWriteJob<TriggerImplementationResult> {

    private static final Logger log = LoggerFactory.getLogger(PostgresTriggerImplementationJob.class);

    @Inject
    private OracleConnectionService oracleConnectionService;

    @Inject
    private PostgresConnectionService postgresConnectionService;

    @Override
    public String getTargetDatabase() {
        return "POSTGRES";
    }

    @Override
    public String getWriteOperationType() {
        return "TRIGGER_IMPLEMENTATION";
    }

    @Override
    public Class<TriggerImplementationResult> getResultType() {
        return TriggerImplementationResult.class;
    }

    @Override
    protected void saveResultsToState(TriggerImplementationResult result) {
        stateService.setTriggerImplementationResult(result);
    }

    @Override
    protected TriggerImplementationResult performWriteOperation(Consumer<JobProgress> progressCallback) throws Exception {
        log.info("PostgresTriggerImplementationJob: Shell implementation - returning empty result");

        // Shell implementation - return empty result
        TriggerImplementationResult result = new TriggerImplementationResult();

        updateProgress(progressCallback, 0, "Shell Implementation",
                "Trigger implementation job is not yet implemented (shell only)");

        return result;
    }
}
