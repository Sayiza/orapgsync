package me.christianrobert.orapgsync.schema.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseWriteJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.schema.model.SchemaCreationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

@Dependent
public class PostgresSchemaCreationJob extends AbstractDatabaseWriteJob<SchemaCreationResult> {

    private static final Logger log = LoggerFactory.getLogger(PostgresSchemaCreationJob.class);

    @Inject
    private PostgresConnectionService postgresConnectionService;

    @Override
    public String getTargetDatabase() {
        return "POSTGRES";
    }

    @Override
    public String getWriteOperationType() {
        return "SCHEMA_CREATION";
    }

    @Override
    public Class<SchemaCreationResult> getResultType() {
        return SchemaCreationResult.class;
    }

    @Override
    protected void saveResultsToState(SchemaCreationResult result) {
        stateService.updateSchemaCreationResult(result);
    }

    @Override
    protected SchemaCreationResult performWriteOperation(Consumer<JobProgress> progressCallback) throws Exception {
        updateProgress(progressCallback, 0, "Initializing", "Starting PostgreSQL schema creation process");

        // Get Oracle schemas from state
        List<String> oracleSchemas = getOracleSchemas();
        if (oracleSchemas.isEmpty()) {
            updateProgress(progressCallback, 100, "No schemas to process",
                          "No Oracle schemas found in state. Please extract Oracle schemas first.");
            log.warn("No Oracle schemas found in state for schema creation");
            return new SchemaCreationResult();
        }

        // Filter valid schemas (exclude system schemas)
        List<String> validOracleSchemas = filterValidSchemas(oracleSchemas);

        updateProgress(progressCallback, 10, "Analyzing schemas",
                      String.format("Found %d Oracle schemas, %d are valid for creation",
                                   oracleSchemas.size(), validOracleSchemas.size()));

        SchemaCreationResult result = new SchemaCreationResult();

        if (validOracleSchemas.isEmpty()) {
            updateProgress(progressCallback, 100, "No valid schemas", "No valid Oracle schemas to create in PostgreSQL");
            return result;
        }

        updateProgress(progressCallback, 20, "Connecting to PostgreSQL", "Establishing database connection");

        try (Connection postgresConnection = postgresConnectionService.getConnection()) {
            updateProgress(progressCallback, 25, "Connected", "Successfully connected to PostgreSQL database");

            // Get existing PostgreSQL schemas
            Set<String> existingPostgresSchemas = getExistingPostgresSchemas(postgresConnection);

            updateProgress(progressCallback, 30, "Checking existing schemas",
                          String.format("Found %d existing PostgreSQL schemas", existingPostgresSchemas.size()));

            // Determine which schemas need to be created
            List<String> schemasToCreate = validOracleSchemas.stream()
                    .filter(schema -> !existingPostgresSchemas.contains(schema.toLowerCase()))
                    .toList();

            List<String> schemasAlreadyExisting = validOracleSchemas.stream()
                    .filter(schema -> existingPostgresSchemas.contains(schema.toLowerCase()))
                    .toList();

            // Mark already existing schemas as skipped
            for (String schema : schemasAlreadyExisting) {
                result.addSkippedSchema(schema.toLowerCase());
                log.info("Schema '{}' already exists in PostgreSQL, skipping", schema.toLowerCase());
            }

            updateProgress(progressCallback, 40, "Planning creation",
                          String.format("%d schemas to create, %d already exist",
                                       schemasToCreate.size(), schemasAlreadyExisting.size()));

            if (schemasToCreate.isEmpty()) {
                updateProgress(progressCallback, 100, "All schemas exist",
                              "All Oracle schemas already exist in PostgreSQL");
                return result;
            }

            // Create missing schemas
            int totalSchemas = schemasToCreate.size();
            int processedSchemas = 0;

            for (String schemaName : schemasToCreate) {
                int progressPercentage = 40 + (processedSchemas * 50 / totalSchemas);
                updateProgress(progressCallback, progressPercentage,
                              String.format("Creating schema: %s", schemaName),
                              String.format("Schema %d of %d", processedSchemas + 1, totalSchemas));

                try {
                    createSchema(postgresConnection, schemaName);
                    result.addCreatedSchema(schemaName.toLowerCase());
                    log.info("Successfully created PostgreSQL schema: {}", schemaName.toLowerCase());
                } catch (SQLException e) {
                    String errorMessage = String.format("Failed to create schema '%s': %s", schemaName.toLowerCase(), e.getMessage());
                    String sqlStatement = String.format("CREATE SCHEMA IF NOT EXISTS %s", schemaName.toLowerCase());
                    result.addError(schemaName.toLowerCase(), errorMessage, sqlStatement);
                    log.error("Failed to create schema: {}", schemaName, e);
                }

                processedSchemas++;
            }

            updateProgress(progressCallback, 90, "Creation complete",
                          String.format("Created %d schemas, skipped %d existing, %d errors",
                                       result.getCreatedCount(), result.getSkippedCount(), result.getErrorCount()));

            return result;

        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed", "Schema creation failed: " + e.getMessage());
            throw e;
        }
    }

    private Set<String> getExistingPostgresSchemas(Connection connection) throws SQLException {
        Set<String> schemas = new HashSet<>();

        String sql = """
            SELECT schema_name
            FROM information_schema.schemata
            WHERE schema_name NOT IN ('information_schema', 'pg_catalog', 'pg_toast')
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String schemaName = rs.getString("schema_name");
                schemas.add(schemaName.toLowerCase()); // Store in lowercase for case-insensitive comparison
            }
        }

        log.debug("Found {} existing PostgreSQL schemas", schemas.size());
        return schemas;
    }

    private void createSchema(Connection connection, String schemaName) throws SQLException {
        // Use IF NOT EXISTS to avoid errors if schema exists
        // Use lowercase unquoted identifier - PostgreSQL will fold to lowercase automatically
        String sql = String.format("CREATE SCHEMA IF NOT EXISTS %s", schemaName.toLowerCase());

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.executeUpdate();
        }

        log.debug("Executed SQL: {}", sql);
    }

    @Override
    protected String generateSummaryMessage(SchemaCreationResult result) {
        StringBuilder summary = new StringBuilder();
        summary.append(String.format("Schema creation completed: %d created, %d skipped, %d errors",
                                    result.getCreatedCount(), result.getSkippedCount(), result.getErrorCount()));

        if (result.getCreatedCount() > 0) {
            summary.append(String.format(" | Created: %s", String.join(", ", result.getCreatedSchemas())));
        }

        if (result.getSkippedCount() > 0) {
            summary.append(String.format(" | Skipped: %s", String.join(", ", result.getSkippedSchemas())));
        }

        if (result.hasErrors()) {
            summary.append(String.format(" | %d errors occurred", result.getErrorCount()));
        }

        return summary.toString();
    }
}