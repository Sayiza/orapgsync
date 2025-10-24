package me.christianrobert.orapgsync.oraclecompat.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseWriteJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.oraclecompat.catalog.OracleBuiltinCatalog;
import me.christianrobert.orapgsync.oraclecompat.model.OracleBuiltinFunction;
import me.christianrobert.orapgsync.oraclecompat.model.OracleCompatInstallationResult;
import me.christianrobert.orapgsync.oraclecompat.model.SupportLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.function.Consumer;

/**
 * Installs Oracle compatibility layer functions in PostgreSQL.
 * <p>
 * Creates the oracle_compat schema and installs PostgreSQL equivalents for commonly-used
 * Oracle built-in packages (DBMS_OUTPUT, DBMS_UTILITY, UTL_FILE, DBMS_LOB, etc.).
 * <p>
 * Functions are installed with three support levels:
 * - FULL: Complete PostgreSQL equivalent, behavior matches Oracle closely
 * - PARTIAL: Covers common use cases but with limitations
 * - STUB: Minimal or no-op implementation
 */
@Dependent
public class PostgresOracleCompatInstallationJob extends AbstractDatabaseWriteJob<OracleCompatInstallationResult> {

    private static final Logger log = LoggerFactory.getLogger(PostgresOracleCompatInstallationJob.class);

    private static final String COMPAT_SCHEMA = "oracle_compat";

    @Inject
    private PostgresConnectionService postgresConnectionService;

    private final OracleBuiltinCatalog catalog = new OracleBuiltinCatalog();

    @Override
    public String getTargetDatabase() {
        return "POSTGRES";
    }

    @Override
    public String getWriteOperationType() {
        return "ORACLE_COMPAT_INSTALLATION";
    }

    @Override
    public Class<OracleCompatInstallationResult> getResultType() {
        return OracleCompatInstallationResult.class;
    }

    @Override
    protected void saveResultsToState(OracleCompatInstallationResult result) {
        stateService.setOracleCompatInstallationResult(result);
    }

    @Override
    protected OracleCompatInstallationResult performWriteOperation(Consumer<JobProgress> progressCallback) throws Exception {
        log.info("Starting Oracle compatibility layer installation...");

        OracleCompatInstallationResult result = new OracleCompatInstallationResult();
        long startTime = System.currentTimeMillis();

        List<OracleBuiltinFunction> functions = catalog.getAllFunctions();
        result.setTotalFunctions(functions.size());

        updateProgress(progressCallback, 0, "Starting installation",
                String.format("Installing %d Oracle compatibility functions", functions.size()));

        try (Connection conn = postgresConnectionService.getConnection()) {
            // Step 1: Create oracle_compat schema
            updateProgress(progressCallback, 5, "Creating schema",
                    String.format("Creating %s schema", COMPAT_SCHEMA));
            createSchema(conn, result);

            // Step 2: Install functions
            int processed = 0;
            for (OracleBuiltinFunction function : functions) {
                processed++;
                int progressPercent = 5 + ((processed * 90) / functions.size());

                updateProgress(progressCallback, progressPercent, "Installing functions",
                        String.format("Installing %s (%d/%d)",
                                function.getFullOracleName(), processed, functions.size()));

                installFunction(conn, function, result);
            }

            result.setExecutionTimeMs(System.currentTimeMillis() - startTime);

            updateProgress(progressCallback, 100, "Installation complete",
                    String.format("Installed %d functions (full=%d, partial=%d, stubs=%d) in %dms",
                            result.getTotalInstalled(),
                            result.getInstalledFull().size(),
                            result.getInstalledPartial().size(),
                            result.getInstalledStubs().size(),
                            result.getExecutionTimeMs()));

            log.info("Oracle compatibility layer installation complete: {}", result);

            if (result.hasErrors()) {
                log.warn("Installation completed with {} errors", result.getFailed().size());
            }

        } catch (Exception e) {
            log.error("Failed to install Oracle compatibility layer", e);
            result.addFailed("OVERALL", e.getMessage());
            throw e;
        }

        return result;
    }

    private void createSchema(Connection conn, OracleCompatInstallationResult result) {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA IF NOT EXISTS " + COMPAT_SCHEMA);
            log.info("Created schema: {}", COMPAT_SCHEMA);
        } catch (Exception e) {
            log.error("Failed to create schema: {}", COMPAT_SCHEMA, e);
            result.addFailed("SCHEMA_CREATION", e.getMessage());
        }
    }

    private void installFunction(Connection conn, OracleBuiltinFunction function, OracleCompatInstallationResult result) {
        if (function.getSupportLevel() == SupportLevel.NONE) {
            result.addSkipped(function.getFullOracleName());
            log.debug("Skipped (not implemented): {}", function.getFullOracleName());
            return;
        }

        try (Statement stmt = conn.createStatement()) {
            // Execute function definition (may contain multiple statements)
            stmt.execute(function.getSqlDefinition());

            result.addInstalled(function.getFullOracleName(), function.getSupportLevel());

            String levelLabel = function.getSupportLevel().name();
            log.info("Installed [{}]: {} → {}",
                    levelLabel,
                    function.getFullOracleName(),
                    function.getPostgresFunction());

        } catch (Exception e) {
            result.addFailed(function.getFullOracleName(), e.getMessage());
            log.error("Failed to install: {}", function.getFullOracleName(), e);
        }
    }
}
