package me.christianrobert.orapgsync.trigger.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseExtractionJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.trigger.TriggerMetadata;
import me.christianrobert.orapgsync.database.service.OracleConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Oracle Trigger Extraction Job (Shell).
 *
 * This job will extract trigger metadata and DDL from Oracle database.
 *
 * The job will:
 * 1. Query Oracle system catalogs (ALL_TRIGGERS, etc.)
 * 2. Extract trigger metadata (name, table, type, timing, event, level)
 * 3. Extract trigger body (PL/SQL code)
 * 4. Extract WHEN clauses if present
 * 5. Store results in state for transformation
 *
 * CURRENT STATUS: Shell implementation - returns empty result
 * TODO: Implement actual trigger extraction from Oracle
 */
@Dependent
public class OracleTriggerExtractionJob extends AbstractDatabaseExtractionJob<TriggerMetadata> {

    private static final Logger log = LoggerFactory.getLogger(OracleTriggerExtractionJob.class);

    @Inject
    private OracleConnectionService oracleConnectionService;

    @Override
    public String getSourceDatabase() {
        return "ORACLE";
    }

    @Override
    public String getExtractionType() {
        return "TRIGGER";
    }

    @Override
    public Class<TriggerMetadata> getResultType() {
        return TriggerMetadata.class;
    }

    @Override
    protected void saveResultsToState(List<TriggerMetadata> results) {
        stateService.setOracleTriggerMetadata(results);
    }

    @Override
    protected List<TriggerMetadata> performExtraction(Consumer<JobProgress> progressCallback) throws Exception {
        log.info("OracleTriggerExtractionJob: Shell implementation - returning empty result");

        updateProgress(progressCallback, 0, "Shell Implementation",
                "Trigger extraction job is not yet implemented (shell only)");

        // Shell implementation - return empty list
        return new ArrayList<>();
    }
}
