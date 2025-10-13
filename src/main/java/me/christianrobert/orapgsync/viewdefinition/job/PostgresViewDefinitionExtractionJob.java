package me.christianrobert.orapgsync.viewdefinition.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseExtractionJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.table.ColumnMetadata;
import me.christianrobert.orapgsync.core.job.model.viewdefinition.ViewDefinitionMetadata;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Extracts view definitions from PostgreSQL database for verification.
 * This job extracts column metadata for existing PostgreSQL views.
 */
@Dependent
public class PostgresViewDefinitionExtractionJob extends AbstractDatabaseExtractionJob<ViewDefinitionMetadata> {

    private static final Logger log = LoggerFactory.getLogger(PostgresViewDefinitionExtractionJob.class);

    @Inject
    private PostgresConnectionService postgresConnectionService;

    @Override
    public String getSourceDatabase() {
        return "POSTGRES";
    }

    @Override
    protected List<String> getAvailableSchemas() {
        // For PostgreSQL extraction, query the database directly instead of relying on state
        log.debug("Querying PostgreSQL database for available schemas");
        try (Connection connection = postgresConnectionService.getConnection()) {
            return fetchSchemasFromPostgres(connection);
        } catch (Exception e) {
            log.error("Failed to fetch schemas from PostgreSQL, falling back to state", e);
            return stateService.getPostgresSchemaNames();
        }
    }

    private List<String> fetchSchemasFromPostgres(Connection connection) throws Exception {
        List<String> schemas = new ArrayList<>();
        String sql = """
            SELECT schema_name
            FROM information_schema.schemata
            WHERE schema_name NOT IN ('information_schema', 'pg_catalog', 'pg_toast')
            ORDER BY schema_name
            """;

        try (var stmt = connection.createStatement();
             var rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                schemas.add(rs.getString("schema_name"));
            }
        }

        log.debug("Found {} schemas in PostgreSQL database", schemas.size());
        return schemas;
    }

    @Override
    public String getExtractionType() {
        return "VIEW_DEFINITION";
    }

    @Override
    public Class<ViewDefinitionMetadata> getResultType() {
        return ViewDefinitionMetadata.class;
    }

    @Override
    protected void saveResultsToState(List<ViewDefinitionMetadata> results) {
        stateService.setPostgresViewDefinitionMetadata(results);
    }

    @Override
    protected List<ViewDefinitionMetadata> performExtraction(Consumer<JobProgress> progressCallback) throws Exception {
        // Determine which schemas to process based on configuration
        List<String> schemasToProcess = determineSchemasToProcess(progressCallback);

        if (schemasToProcess.isEmpty()) {
            updateProgress(progressCallback, 100, "No schemas to process",
                    "No schemas available for PostgreSQL view extraction based on current configuration");
            return new ArrayList<>();
        }

        updateProgress(progressCallback, 0, "Initializing",
                "Starting PostgreSQL view definition extraction for " + schemasToProcess.size() + " schemas");

        List<String> validSchemas = filterValidSchemas(schemasToProcess);

        log.info("Starting PostgreSQL view definition extraction for {} schemas (filtered from {} total)",
                validSchemas.size(), schemasToProcess.size());

        updateProgress(progressCallback, 5, "Connecting to PostgreSQL", "Establishing database connection");

        List<ViewDefinitionMetadata> allViewDefinitions = new ArrayList<>();

        try (Connection postgresConnection = postgresConnectionService.getConnection()) {
            updateProgress(progressCallback, 10, "Connected", "Successfully connected to PostgreSQL database");

            int totalSchemas = validSchemas.size();
            int processedSchemas = 0;

            for (String schema : validSchemas) {
                updateProgress(progressCallback,
                        10 + (processedSchemas * 80 / totalSchemas),
                        "Processing schema: " + schema,
                        String.format("Schema %d of %d", processedSchemas + 1, totalSchemas));

                try {
                    List<ViewDefinitionMetadata> schemaViewDefinitions = extractViewsForSchema(
                            postgresConnection, schema, progressCallback, processedSchemas, totalSchemas);

                    allViewDefinitions.addAll(schemaViewDefinitions);

                    log.info("Extracted {} views from PostgreSQL schema {}", schemaViewDefinitions.size(), schema);

                } catch (Exception e) {
                    log.error("Failed to extract views for PostgreSQL schema: " + schema, e);
                    updateProgress(progressCallback,
                            10 + (processedSchemas * 80 / totalSchemas),
                            "Error in schema: " + schema,
                            "Failed to process schema: " + e.getMessage());
                }

                processedSchemas++;
            }

            return allViewDefinitions;

        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed",
                    "PostgreSQL view definition extraction failed: " + e.getMessage());
            throw e;
        }
    }

    private List<ViewDefinitionMetadata> extractViewsForSchema(Connection postgresConnection, String schema,
                                                               Consumer<JobProgress> progressCallback,
                                                               int currentSchemaIndex, int totalSchemas) throws SQLException {
        List<ViewDefinitionMetadata> viewDefinitions = new ArrayList<>();

        // First, get all view names for this schema
        List<String> viewNames = fetchViewNames(postgresConnection, schema);

        for (String viewName : viewNames) {
            ViewDefinitionMetadata viewDef = fetchViewDefinition(postgresConnection, schema, viewName);
            if (viewDef != null) {
                viewDefinitions.add(viewDef);
            }
        }

        return viewDefinitions;
    }

    private List<String> fetchViewNames(Connection postgresConnection, String schemaName) throws SQLException {
        List<String> result = new ArrayList<>();
        String sql = "SELECT table_name FROM information_schema.views WHERE table_schema = ? ORDER BY table_name";

        try (PreparedStatement ps = postgresConnection.prepareStatement(sql)) {
            ps.setString(1, schemaName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString("table_name"));
                }
            }
        }
        return result;
    }

    private ViewDefinitionMetadata fetchViewDefinition(Connection postgresConnection, String schemaName, String viewName) throws SQLException {
        ViewDefinitionMetadata viewDef = new ViewDefinitionMetadata(schemaName, viewName);

        // Fetch column metadata from information_schema.columns
        String columnSql = """
            SELECT column_name, data_type, udt_schema, character_maximum_length,
                   numeric_precision, numeric_scale, is_nullable, column_default
            FROM information_schema.columns
            WHERE table_schema = ? AND table_name = ?
            ORDER BY ordinal_position
            """;

        try (PreparedStatement ps = postgresConnection.prepareStatement(columnSql)) {
            ps.setString(1, schemaName);
            ps.setString(2, viewName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String columnName = rs.getString("column_name");
                    String dataType = rs.getString("data_type");
                    String udtSchema = rs.getString("udt_schema");

                    // For user-defined types, udtSchema will contain the schema name
                    String dataTypeOwner = null;
                    if ("USER-DEFINED".equalsIgnoreCase(dataType) && udtSchema != null && !udtSchema.isEmpty()) {
                        dataTypeOwner = udtSchema.toLowerCase();
                        // Get the actual type name from UDT information
                        // This is simplified - in production you might need more UDT details
                    }

                    Integer charLength = rs.getInt("character_maximum_length");
                    if (rs.wasNull()) charLength = null;
                    Integer precision = rs.getInt("numeric_precision");
                    if (rs.wasNull()) precision = null;
                    Integer scale = rs.getInt("numeric_scale");
                    if (rs.wasNull()) scale = null;
                    boolean nullable = "YES".equals(rs.getString("is_nullable"));
                    String defaultValue = rs.getString("column_default");

                    ColumnMetadata column = new ColumnMetadata(columnName, dataType, dataTypeOwner,
                            charLength, precision, scale, nullable, defaultValue);
                    viewDef.addColumn(column);
                }
            }
        }

        return viewDef;
    }

    @Override
    protected String generateSummaryMessage(List<ViewDefinitionMetadata> results) {
        Map<String, Integer> schemaSummary = new HashMap<>();
        int totalColumns = 0;

        for (ViewDefinitionMetadata view : results) {
            String schema = view.getSchema();
            schemaSummary.put(schema, schemaSummary.getOrDefault(schema, 0) + 1);
            totalColumns += view.getColumns().size();
        }

        return String.format("Extraction completed: %d views with %d columns from %d schemas",
                results.size(), totalColumns, schemaSummary.size());
    }
}
