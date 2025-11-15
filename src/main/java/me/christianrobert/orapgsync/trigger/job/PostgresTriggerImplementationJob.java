package me.christianrobert.orapgsync.trigger.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseWriteJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.trigger.TriggerImplementationResult;
import me.christianrobert.orapgsync.core.job.model.trigger.TriggerMetadata;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.transformer.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.trigger.transformer.TriggerTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * PostgreSQL Trigger Implementation Job.
 *
 * This job transforms Oracle triggers to PostgreSQL triggers and creates them.
 * The implementation is IDEMPOTENT - it can be run multiple times without errors.
 *
 * The job:
 * 1. Retrieves Oracle trigger metadata from state
 * 2. For each trigger:
 *    a. Transforms Oracle PL/SQL trigger body to PostgreSQL PL/pgSQL
 *    b. Removes colons from :NEW/:OLD references
 *    c. Injects appropriate RETURN statement
 *    d. **Drops existing trigger (if exists)** - ensures idempotency
 *    e. Creates or replaces trigger function (PL/pgSQL)
 *    f. Creates trigger definition (CREATE TRIGGER)
 * 3. Returns results tracking success/skipped/errors
 *
 * Key transformations:
 * - :NEW → NEW (PostgreSQL trigger record)
 * - :OLD → OLD (PostgreSQL trigger record)
 * - BEFORE/AFTER/INSTEAD OF timing preserved
 * - ROW/STATEMENT level preserved
 * - Trigger function returns NEW/NULL as appropriate
 *
 * Idempotency strategy (2025-11-15):
 * - DROP TRIGGER IF EXISTS before creation (no error if trigger doesn't exist)
 * - CREATE OR REPLACE FUNCTION for trigger functions (already idempotent)
 * - CREATE TRIGGER after drop (safe, no conflicts)
 * - This ensures Oracle is always the source of truth, and job re-runs update changed triggers
 */
@Dependent
public class PostgresTriggerImplementationJob extends AbstractDatabaseWriteJob<TriggerImplementationResult> {

    private static final Logger log = LoggerFactory.getLogger(PostgresTriggerImplementationJob.class);

    @Inject
    private PostgresConnectionService postgresConnectionService;

    @Inject
    private TriggerTransformer triggerTransformer;

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
        log.info("Starting PostgreSQL trigger implementation");

        updateProgress(progressCallback, 0, "Initializing",
                "Starting trigger implementation process");

        TriggerImplementationResult result = new TriggerImplementationResult();

        // Get Oracle triggers from state
        List<TriggerMetadata> oracleTriggers = stateService.getOracleTriggerMetadata();

        if (oracleTriggers == null || oracleTriggers.isEmpty()) {
            log.warn("No Oracle triggers found in state for implementation");
            updateProgress(progressCallback, 100, "No triggers to process",
                    "No Oracle triggers found in state. Please extract Oracle triggers first.");
            return result;
        }

        // Filter valid triggers (exclude those without bodies)
        List<TriggerMetadata> validTriggers = filterValidTriggers(oracleTriggers);

        if (validTriggers.isEmpty()) {
            log.warn("No valid Oracle triggers found for implementation");
            updateProgress(progressCallback, 100, "No valid triggers",
                    "No valid Oracle triggers with trigger bodies found");
            return result;
        }

        log.info("Found {} valid Oracle triggers to implement", validTriggers.size());
        updateProgress(progressCallback, 10, "Building metadata indices",
                String.format("Processing %d triggers", validTriggers.size()));

        // Build transformation indices once at the start
        List<String> schemas = stateService.getOracleSchemaNames();
        TransformationIndices indices = MetadataIndexBuilder.build(stateService, schemas);

        updateProgress(progressCallback, 15, "Metadata indices built",
                "Ready to transform triggers");

        updateProgress(progressCallback, 20, "Connecting to PostgreSQL",
                "Establishing database connection");

        try (Connection pgConnection = postgresConnectionService.getConnection()) {
            updateProgress(progressCallback, 25, "Connected",
                    "Successfully connected to PostgreSQL");

            int totalTriggers = validTriggers.size();
            int processedTriggers = 0;

            for (TriggerMetadata trigger : validTriggers) {
                String qualifiedName = trigger.getQualifiedName();
                int progressPercentage = 25 + (processedTriggers * 70 / totalTriggers);

                updateProgress(progressCallback, progressPercentage,
                        "Implementing trigger: " + qualifiedName,
                        String.format("Trigger %d of %d", processedTriggers + 1, totalTriggers));

                try {
                    implementTrigger(pgConnection, trigger, indices, result);
                } catch (Exception e) {
                    log.error("Failed to implement trigger: " + qualifiedName, e);
                    result.addError(qualifiedName, "Implementation failed: " + e.getMessage(),
                            trigger.getTriggerBody());
                }

                processedTriggers++;
            }

            updateProgress(progressCallback, 95, "Finalizing",
                    "Trigger implementation completed");

            log.info("Trigger implementation completed: {} implemented, {} skipped, {} errors",
                    result.getImplementedCount(), result.getSkippedCount(), result.getErrorCount());

            updateProgress(progressCallback, 100, "Completed",
                    String.format("Implemented %d triggers: %d succeeded, %d skipped, %d errors",
                            result.getImplementedCount() + result.getSkippedCount() + result.getErrorCount(),
                            result.getImplementedCount(), result.getSkippedCount(), result.getErrorCount()));

            return result;

        } catch (Exception e) {
            log.error("Trigger implementation failed", e);
            updateProgress(progressCallback, -1, "Failed",
                    "Trigger implementation failed: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Implements a single trigger by transforming and creating it in PostgreSQL.
     *
     * This method ensures idempotency by dropping existing triggers before recreation.
     * The implementation follows a three-step process:
     * 1. Drop existing trigger (if exists) - ensures no conflicts
     * 2. Create or replace trigger function - contains the PL/pgSQL logic
     * 3. Create trigger - binds function to table events
     */
    private void implementTrigger(Connection pgConnection, TriggerMetadata trigger,
                                   TransformationIndices indices,
                                   TriggerImplementationResult result) {

        String qualifiedName = trigger.getQualifiedName();

        // Check if trigger body is available
        if (trigger.getTriggerBody() == null || trigger.getTriggerBody().trim().isEmpty()) {
            log.warn("No trigger body for: {}", qualifiedName);
            result.addSkippedTrigger(trigger);
            return;
        }

        log.debug("Transforming trigger: {}", qualifiedName);

        try {
            // Transform trigger using TriggerTransformer
            TriggerTransformer.TriggerDdlPair ddl =
                triggerTransformer.transformTrigger(trigger, indices);

            // Step 1: Drop existing trigger (if exists)
            // This ensures idempotency - job can be run multiple times without errors
            dropTriggerIfExists(pgConnection, trigger);

            // Step 2: Create or replace function DDL
            // Functions are already idempotent (CREATE OR REPLACE), but we drop trigger first
            // to avoid any edge cases where trigger references old function signature
            log.debug("Executing function DDL for {}", qualifiedName);
            log.trace("Function DDL: {}", ddl.getFunctionDdl());
            executeDdl(pgConnection, ddl.getFunctionDdl());

            // Step 3: Create trigger DDL
            // Safe to execute because we dropped any existing trigger in Step 1
            log.debug("Executing trigger DDL for {}", qualifiedName);
            log.trace("Trigger DDL: {}", ddl.getTriggerDdl());
            executeDdl(pgConnection, ddl.getTriggerDdl());

            result.addImplementedTrigger(trigger);
            log.info("Successfully implemented trigger: {}", qualifiedName);

        } catch (TriggerTransformer.TriggerTransformationException e) {
            // Transformation error
            log.error("Transformation failed for trigger {}: {}", qualifiedName, e.getMessage());
            result.addError(qualifiedName, "Transformation failed: " + e.getMessage(),
                    trigger.getTriggerBody());
        } catch (SQLException e) {
            // SQL execution error
            log.error("SQL execution failed for trigger {}: {}", qualifiedName, e.getMessage());
            result.addError(qualifiedName, "SQL execution failed: " + e.getMessage(),
                    trigger.getTriggerBody());
        } catch (Exception e) {
            // Unexpected error
            log.error("Unexpected error implementing trigger: " + qualifiedName, e);
            result.addError(qualifiedName, "Unexpected error: " + e.getMessage(),
                    trigger.getTriggerBody());
        }
    }

    /**
     * Drops an existing trigger if it exists.
     *
     * This ensures idempotency - the job can be run multiple times without errors.
     * Uses DROP TRIGGER IF EXISTS which is safe (no error if trigger doesn't exist).
     *
     * This is necessary because PostgreSQL does not support CREATE OR REPLACE TRIGGER,
     * unlike functions which support CREATE OR REPLACE.
     *
     * @param pgConnection PostgreSQL database connection
     * @param trigger Trigger metadata containing schema, trigger name, and table name
     * @throws SQLException if drop operation fails
     */
    private void dropTriggerIfExists(Connection pgConnection, TriggerMetadata trigger)
            throws SQLException {
        String dropSql = String.format(
            "DROP TRIGGER IF EXISTS %s ON %s.%s",
            trigger.getTriggerName(),
            trigger.getSchema(),
            trigger.getTableName()
        );

        log.debug("Dropping existing trigger (if exists): {} on {}.{}",
            trigger.getTriggerName(), trigger.getSchema(), trigger.getTableName());
        log.trace("Drop SQL: {}", dropSql);

        executeDdl(pgConnection, dropSql);
    }

    /**
     * Executes a DDL statement in PostgreSQL.
     */
    private void executeDdl(Connection pgConnection, String ddl) throws SQLException {
        try (PreparedStatement ps = pgConnection.prepareStatement(ddl)) {
            ps.executeUpdate();
        }
    }

    /**
     * Filters triggers to only include valid ones (with trigger bodies and in valid schemas).
     */
    private List<TriggerMetadata> filterValidTriggers(List<TriggerMetadata> triggers) {
        List<TriggerMetadata> validTriggers = new ArrayList<>();

        for (TriggerMetadata trigger : triggers) {
            // Check if schema is valid
            if (filterValidSchemas(List.of(trigger.getSchema())).isEmpty()) {
                log.debug("Skipping trigger {} - schema excluded", trigger.getQualifiedName());
                continue;
            }

            // Check if trigger body exists
            if (trigger.getTriggerBody() == null || trigger.getTriggerBody().trim().isEmpty()) {
                log.debug("Skipping trigger {} - no trigger body", trigger.getQualifiedName());
                continue;
            }

            validTriggers.add(trigger);
        }

        return validTriggers;
    }

    @Override
    protected String generateSummaryMessage(TriggerImplementationResult result) {
        if (result == null) {
            return "Trigger implementation completed: No triggers processed";
        }

        return String.format("Trigger implementation completed: %d implemented, %d skipped, %d errors",
                result.getImplementedCount(), result.getSkippedCount(), result.getErrorCount());
    }
}
