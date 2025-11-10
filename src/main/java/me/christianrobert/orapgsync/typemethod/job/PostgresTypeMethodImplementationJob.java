package me.christianrobert.orapgsync.typemethod.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseWriteJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.typemethod.TypeMethodImplementationResult;
import me.christianrobert.orapgsync.database.service.OracleConnectionService;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * PostgreSQL Type Method Implementation Job (Shell).
 *
 * This job will implement type member methods by transforming Oracle PL/SQL to PostgreSQL PL/pgSQL
 * and replacing stub implementations.
 *
 * The job will:
 * 1. Retrieve Oracle type method metadata from state
 * 2. For each type method:
 *    a. Extract Oracle PL/SQL source from ALL_SOURCE
 *    b. Transform PL/SQL → PL/pgSQL using ANTLR
 *    c. Execute CREATE OR REPLACE FUNCTION in PostgreSQL
 * 3. Return results tracking success/skipped/errors
 *
 * CURRENT STATUS: Shell implementation - returns empty result
 * TODO: Implement actual type method transformation logic
 */
@Dependent
public class PostgresTypeMethodImplementationJob extends AbstractDatabaseWriteJob<TypeMethodImplementationResult> {

    private static final Logger log = LoggerFactory.getLogger(PostgresTypeMethodImplementationJob.class);

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
        return "TYPE_METHOD_IMPLEMENTATION";
    }

    @Override
    public Class<TypeMethodImplementationResult> getResultType() {
        return TypeMethodImplementationResult.class;
    }

    @Override
    protected void saveResultsToState(TypeMethodImplementationResult result) {
        // TODO: Save implementation results to state when implementation is complete
        stateService.setTypeMethodImplementationResult(result);
    }

    @Override
    protected TypeMethodImplementationResult performWriteOperation(Consumer<JobProgress> progressCallback) throws Exception {
        log.info("PostgresTypeMethodImplementationJob: Shell implementation - returning empty result");

        // Shell implementation - return empty result
        TypeMethodImplementationResult result = new TypeMethodImplementationResult();

        updateProgress(progressCallback, 0, "Shell Implementation",
                "Type method implementation job is not yet implemented (shell only)");

        return result;
    }
}
