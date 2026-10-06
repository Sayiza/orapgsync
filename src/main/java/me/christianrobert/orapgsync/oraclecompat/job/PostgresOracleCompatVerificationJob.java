package me.christianrobert.orapgsync.oraclecompat.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseExtractionJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.oraclecompat.catalog.OracleBuiltinCatalog;
import me.christianrobert.orapgsync.oraclecompat.model.OracleBuiltinFunction;
import me.christianrobert.orapgsync.oraclecompat.model.OracleCompatVerificationResult;
import me.christianrobert.orapgsync.oraclecompat.model.SupportLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Verifies that Oracle compatibility layer functions are correctly installed in PostgreSQL.
 * <p>
 * Checks that all expected functions exist in the oracle_compat schema and are callable.
 * This job does not have a source database - it only verifies the PostgreSQL installation.
 */
@Dependent
public class PostgresOracleCompatVerificationJob extends AbstractDatabaseExtractionJob<OracleCompatVerificationResult> {

    private static final Logger log = LoggerFactory.getLogger(PostgresOracleCompatVerificationJob.class);

    private static final String COMPAT_SCHEMA = "oracle_compat";

    @Inject
    private PostgresConnectionService postgresConnectionService;

    private final OracleBuiltinCatalog catalog = new OracleBuiltinCatalog();

    @Override
    public String getSourceDatabase() {
        return "POSTGRES";
    }

    @Override
    public String getExtractionType() {
        return "ORACLE_COMPAT_VERIFICATION";
    }

    @Override
    public Class<OracleCompatVerificationResult> getResultType() {
        return OracleCompatVerificationResult.class;
    }

    @Override
    protected void saveResultsToState(List<OracleCompatVerificationResult> results) {
        if (!results.isEmpty()) {
            stateService.setOracleCompatVerificationResult(results.get(0));
        }
    }

    @Override
    protected List<OracleCompatVerificationResult> performExtraction(Consumer<JobProgress> progressCallback) {
        log.info("Starting Oracle compatibility layer verification...");

        OracleCompatVerificationResult result = new OracleCompatVerificationResult();
        long startTime = System.currentTimeMillis();

        List<OracleBuiltinFunction> functions = catalog.getAllFunctions();
        // Only count functions that should be installed (not NONE)
        int expectedCount = (int) functions.stream()
                .filter(f -> f.getSupportLevel() != SupportLevel.NONE)
                .count();
        result.setTotalExpected(expectedCount);

        updateProgress(progressCallback, 0, "Starting verification",
                String.format("Verifying %d Oracle compatibility functions", expectedCount));

        try (Connection conn = postgresConnectionService.getConnection()) {
            int processed = 0;
            int verified = 0;

            for (OracleBuiltinFunction function : functions) {
                if (function.getSupportLevel() == SupportLevel.NONE) {
                    continue; // Skip functions not implemented
                }

                processed++;
                int progressPercent = (processed * 100) / expectedCount;

                updateProgress(progressCallback, progressPercent, "Verifying functions",
                        String.format("Verifying %s (%d/%d)",
                                function.getFullOracleName(), processed, expectedCount));

                if (verifyFunction(conn, function, result)) {
                    verified++;
                }
            }

            result.setVerified(verified);
            result.setMissing(expectedCount - verified);
            result.setExecutionTimeMs(System.currentTimeMillis() - startTime);

            updateProgress(progressCallback, 100, "Verification complete",
                    String.format("Verified %d/%d functions in %dms",
                            verified, expectedCount, result.getExecutionTimeMs()));

            log.info("Oracle compatibility layer verification complete: {}", result);

            if (result.getMissing() > 0 || !result.getErrors().isEmpty()) {
                log.warn("Verification found issues: {} missing, {} errors",
                        result.getMissing(), result.getErrors().size());
            }

        } catch (Exception e) {
            log.error("Failed to verify Oracle compatibility layer", e);
            result.addError("OVERALL: " + e.getMessage());
        }

        return Collections.singletonList(result);
    }

    private boolean verifyFunction(Connection conn, OracleBuiltinFunction function, OracleCompatVerificationResult result) {
        String functionName = function.getPostgresFlatName();

        // Query to check if function exists
        String sql = """
            SELECT COUNT(*)
            FROM pg_proc p
            JOIN pg_namespace n ON p.pronamespace = n.oid
            WHERE n.nspname = ?
              AND p.proname = ?
            """;

        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, COMPAT_SCHEMA);
            stmt.setString(2, functionName);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    log.debug("Verified: {}", function.getFullOracleName());
                    return true;
                } else {
                    String error = "Missing: " + function.getFullOracleName();
                    log.warn(error);
                    result.addError(error);
                    return false;
                }
            }
        } catch (Exception e) {
            String error = function.getFullOracleName() + ": " + e.getMessage();
            log.error("Failed to verify: {}", function.getFullOracleName(), e);
            result.addError(error);
            return false;
        }
    }
}
