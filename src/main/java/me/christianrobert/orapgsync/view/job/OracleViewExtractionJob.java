package me.christianrobert.orapgsync.view.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseExtractionJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.table.ColumnMetadata;
import me.christianrobert.orapgsync.core.job.model.view.ViewMetadata;
import me.christianrobert.orapgsync.database.service.OracleConnectionService;
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
 * Extracts view definitions from Oracle database.
 * This job extracts both column metadata AND view SQL definitions.
 * The metadata is used for:
 * - Phase 1: Creating view stubs with correct column structure
 * - Phase 2: Implementing actual views with transformed SQL
 */
@Dependent
public class OracleViewExtractionJob extends AbstractDatabaseExtractionJob<ViewMetadata> {

    private static final Logger log = LoggerFactory.getLogger(OracleViewExtractionJob.class);

    @Inject
    private OracleConnectionService oracleConnectionService;

    @Override
    public String getSourceDatabase() {
        return "ORACLE";
    }

    @Override
    public String getExtractionType() {
        return "VIEW";
    }

    @Override
    public Class<ViewMetadata> getResultType() {
        return ViewMetadata.class;
    }

    @Override
    protected void saveResultsToState(List<ViewMetadata> results) {
        stateService.setOracleViewMetadata(results);
    }

    @Override
    protected List<ViewMetadata> performExtraction(Consumer<JobProgress> progressCallback) throws Exception {
        // Determine which schemas to process based on configuration
        List<String> schemasToProcess = determineSchemasToProcess(progressCallback);

        if (schemasToProcess.isEmpty()) {
            updateProgress(progressCallback, 100, "No schemas to process",
                    "No schemas available for view extraction based on current configuration");
            return new ArrayList<>();
        }

        updateProgress(progressCallback, 0, "Initializing",
                "Starting view definition extraction for " + schemasToProcess.size() + " schemas");

        List<String> validSchemas = filterValidSchemas(schemasToProcess);

        log.info("Starting view definition extraction for {} schemas (filtered from {} total)",
                validSchemas.size(), schemasToProcess.size());

        updateProgress(progressCallback, 5, "Connecting to Oracle", "Establishing database connection");

        List<ViewMetadata> allViewDefinitions = new ArrayList<>();

        try (Connection oracleConnection = oracleConnectionService.getConnection()) {
            updateProgress(progressCallback, 10, "Connected", "Successfully connected to Oracle database");

            int totalSchemas = validSchemas.size();
            int processedSchemas = 0;

            for (String schema : validSchemas) {
                updateProgress(progressCallback,
                        10 + (processedSchemas * 80 / totalSchemas),
                        "Processing schema: " + schema,
                        String.format("Schema %d of %d", processedSchemas + 1, totalSchemas));

                try {
                    List<ViewMetadata> schemaViewDefinitions = extractViewsForSchema(
                            oracleConnection, schema, progressCallback, processedSchemas, totalSchemas);

                    allViewDefinitions.addAll(schemaViewDefinitions);

                    log.info("Extracted {} views from schema {}", schemaViewDefinitions.size(), schema);

                } catch (Exception e) {
                    log.error("Failed to extract views for schema: " + schema, e);
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
                    "View definition extraction failed: " + e.getMessage());
            throw e;
        }
    }

    private List<ViewMetadata> extractViewsForSchema(Connection oracleConnection, String schema,
                                                               Consumer<JobProgress> progressCallback,
                                                               int currentSchemaIndex, int totalSchemas) throws SQLException {
        List<ViewMetadata> viewDefinitions = new ArrayList<>();

        // First, get all view names for this schema
        List<String> viewNames = fetchViewNames(oracleConnection, schema);

        for (String viewName : viewNames) {
            ViewMetadata viewDef = fetchViewDefinition(oracleConnection, schema, viewName);
            if (viewDef != null) {
                viewDefinitions.add(viewDef);
            }
        }

        return viewDefinitions;
    }

    private List<String> fetchViewNames(Connection oracleConnection, String owner) throws SQLException {
        List<String> result = new ArrayList<>();
        String sql = "SELECT view_name FROM all_views WHERE owner = ? ORDER BY view_name";

        try (PreparedStatement ps = oracleConnection.prepareStatement(sql)) {
            ps.setString(1, owner.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString("view_name"));
                }
            }
        }
        return result;
    }

    private ViewMetadata fetchViewDefinition(Connection oracleConnection, String owner, String viewName) throws SQLException {
        ViewMetadata viewDef = new ViewMetadata(owner.toLowerCase(), viewName.toLowerCase());

        // Fetch view SQL definition from ALL_VIEWS
        String sqlDefinitionSql = "SELECT text FROM all_views WHERE owner = ? AND view_name = ?";
        try (PreparedStatement ps = oracleConnection.prepareStatement(sqlDefinitionSql)) {
            ps.setString(1, owner.toUpperCase());
            ps.setString(2, viewName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String sqlText = rs.getString("text");
                    if (sqlText != null) {
                        viewDef.setSqlDefinition(sqlText.trim());
                    }
                }
            }
        }

        // Fetch column metadata from ALL_TAB_COLUMNS
        // This view contains columns for both tables and views
        String columnSql = "SELECT column_name, data_type, data_type_owner, char_length, data_precision, data_scale, nullable, data_default " +
                "FROM all_tab_columns WHERE owner = ? AND table_name = ? " +
                "AND data_type IS NOT NULL " +
                "ORDER BY column_id";

        try (PreparedStatement ps = oracleConnection.prepareStatement(columnSql)) {
            ps.setString(1, owner.toUpperCase());
            ps.setString(2, viewName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String columnName = rs.getString("column_name").toLowerCase();
                    String dataType = rs.getString("data_type");
                    String dataTypeOwner = rs.getString("data_type_owner");

                    // Keep all type owner information (including SYS) for proper type classification
                    if (dataTypeOwner != null && !dataTypeOwner.isEmpty()) {
                        dataTypeOwner = dataTypeOwner.toLowerCase();
                    } else {
                        dataTypeOwner = null;
                    }

                    Integer charLength = rs.getInt("char_length");
                    if (rs.wasNull()) charLength = null;
                    Integer precision = rs.getInt("data_precision");
                    if (rs.wasNull()) precision = null;
                    Integer scale = rs.getInt("data_scale");
                    if (rs.wasNull()) scale = null;
                    boolean nullable = "Y".equals(rs.getString("nullable"));
                    String defaultValue = rs.getString("data_default");
                    if (defaultValue != null) {
                        defaultValue = defaultValue.trim();
                    }

                    ColumnMetadata column = new ColumnMetadata(columnName, dataType, dataTypeOwner,
                            charLength, precision, scale, nullable, defaultValue);
                    viewDef.addColumn(column);
                }
            }
        }

        return viewDef;
    }

    @Override
    protected String generateSummaryMessage(List<ViewMetadata> results) {
        Map<String, Integer> schemaSummary = new HashMap<>();
        int totalColumns = 0;

        for (ViewMetadata view : results) {
            String schema = view.getSchema();
            schemaSummary.put(schema, schemaSummary.getOrDefault(schema, 0) + 1);
            totalColumns += view.getColumns().size();
        }

        return String.format("Extraction completed: %d views with %d columns from %d schemas",
                results.size(), totalColumns, schemaSummary.size());
    }
}
