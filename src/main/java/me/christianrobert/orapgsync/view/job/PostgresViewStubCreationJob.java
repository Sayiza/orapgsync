package me.christianrobert.orapgsync.view.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseWriteJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.table.ColumnMetadata;
import me.christianrobert.orapgsync.core.job.model.view.ViewMetadata;
import me.christianrobert.orapgsync.core.job.model.view.ViewStubCreationResult;
import me.christianrobert.orapgsync.core.tools.OracleTypeClassifier;
import me.christianrobert.orapgsync.core.tools.PostgresIdentifierNormalizer;
import me.christianrobert.orapgsync.core.tools.TypeConverter;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Consumer;

/**
 * Creates view stubs in PostgreSQL database.
 *
 * View stubs are created with correct column structure but return empty result sets.
 * SQL pattern: CREATE VIEW schema.viewname AS SELECT NULL::type AS col1, ... WHERE false
 *
 * This allows functions and procedures to reference views during migration even though
 * the view logic hasn't been migrated yet.
 */
@Dependent
public class PostgresViewStubCreationJob extends AbstractDatabaseWriteJob<ViewStubCreationResult> {

    private static final Logger log = LoggerFactory.getLogger(PostgresViewStubCreationJob.class);

    @Inject
    private PostgresConnectionService postgresConnectionService;

    @Override
    public String getTargetDatabase() {
        return "POSTGRES";
    }

    @Override
    public String getWriteOperationType() {
        return "VIEW_STUB_CREATION";
    }

    @Override
    public Class<ViewStubCreationResult> getResultType() {
        return ViewStubCreationResult.class;
    }

    @Override
    protected void saveResultsToState(ViewStubCreationResult result) {
        stateService.setViewStubCreationResult(result);
    }

    @Override
    protected ViewStubCreationResult performWriteOperation(Consumer<JobProgress> progressCallback) throws Exception {
        updateProgress(progressCallback, 0, "Initializing",
                "Starting PostgreSQL view stub creation process");

        // Get Oracle views from state
        List<ViewMetadata> oracleViews = getOracleViews();
        if (oracleViews.isEmpty()) {
            updateProgress(progressCallback, 100, "No views to process",
                    "No Oracle views found in state. Please extract Oracle view definitions first.");
            log.warn("No Oracle views found in state for view stub creation");
            return new ViewStubCreationResult();
        }

        // Filter valid views (exclude system schemas)
        List<ViewMetadata> validOracleViews = filterValidViews(oracleViews);

        updateProgress(progressCallback, 10, "Analyzing views",
                String.format("Found %d Oracle views, %d are valid for creation",
                        oracleViews.size(), validOracleViews.size()));

        ViewStubCreationResult result = new ViewStubCreationResult();

        if (validOracleViews.isEmpty()) {
            updateProgress(progressCallback, 100, "No valid views",
                    "No valid Oracle views to create in PostgreSQL");
            return result;
        }

        updateProgress(progressCallback, 20, "Connecting to PostgreSQL",
                "Establishing database connection");

        try (Connection postgresConnection = postgresConnectionService.getConnection()) {
            updateProgress(progressCallback, 25, "Connected",
                    "Successfully connected to PostgreSQL database");

            // Get existing PostgreSQL views
            Set<String> existingPostgresViews = getExistingPostgresViews(postgresConnection);

            updateProgress(progressCallback, 30, "Checking existing views",
                    String.format("Found %d existing PostgreSQL views", existingPostgresViews.size()));

            // Determine which views need to be created
            List<ViewMetadata> viewsToCreate = new ArrayList<>();
            List<ViewMetadata> viewsAlreadyExisting = new ArrayList<>();

            for (ViewMetadata view : validOracleViews) {
                String qualifiedViewName = getQualifiedViewName(view);
                if (existingPostgresViews.contains(qualifiedViewName.toLowerCase())) {
                    viewsAlreadyExisting.add(view);
                } else {
                    viewsToCreate.add(view);
                }
            }

            // Mark already existing views as skipped
            for (ViewMetadata view : viewsAlreadyExisting) {
                String qualifiedViewName = getQualifiedViewName(view);
                result.addSkippedView(qualifiedViewName);
                log.debug("View '{}' already exists in PostgreSQL, skipping", qualifiedViewName);
            }

            updateProgress(progressCallback, 40, "Planning creation",
                    String.format("%d views to create, %d already exist",
                            viewsToCreate.size(), viewsAlreadyExisting.size()));

            if (viewsToCreate.isEmpty()) {
                updateProgress(progressCallback, 100, "All views exist",
                        "All Oracle views already exist in PostgreSQL");
                return result;
            }

            // Create view stubs
            createViewStubs(postgresConnection, viewsToCreate, result, progressCallback);

            updateProgress(progressCallback, 90, "Creation complete",
                    String.format("Created %d view stubs, skipped %d existing, %d errors",
                            result.getCreatedCount(), result.getSkippedCount(), result.getErrorCount()));

            return result;

        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed",
                    "View stub creation failed: " + e.getMessage());
            throw e;
        }
    }

    private List<ViewMetadata> getOracleViews() {
        return stateService.getOracleViewMetadata();
    }

    private List<ViewMetadata> filterValidViews(List<ViewMetadata> views) {
        List<ViewMetadata> validViews = new ArrayList<>();
        for (ViewMetadata view : views) {
            if (!filterValidSchemas(List.of(view.getSchema())).isEmpty()) {
                validViews.add(view);
            }
        }
        return validViews;
    }

    private Set<String> getExistingPostgresViews(Connection connection) throws SQLException {
        Set<String> views = new HashSet<>();

        String sql = """
            SELECT table_schema, table_name
            FROM information_schema.views
            WHERE table_schema NOT IN ('information_schema', 'pg_catalog', 'pg_toast')
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String schemaName = rs.getString("table_schema");
                String viewName = rs.getString("table_name");
                String qualifiedName = String.format("%s.%s", schemaName, viewName).toLowerCase();
                views.add(qualifiedName);
            }
        }

        log.debug("Found {} existing PostgreSQL views", views.size());
        return views;
    }

    private String getQualifiedViewName(ViewMetadata view) {
        return String.format("%s.%s", view.getSchema(), view.getViewName());
    }

    private void createViewStubs(Connection connection, List<ViewMetadata> views,
                                  ViewStubCreationResult result, Consumer<JobProgress> progressCallback) throws SQLException {
        int totalViews = views.size();
        int processedViews = 0;

        for (ViewMetadata view : views) {
            int progressPercentage = 40 + (processedViews * 50 / totalViews);
            String qualifiedViewName = getQualifiedViewName(view);
            updateProgress(progressCallback, progressPercentage,
                    String.format("Creating view stub: %s", qualifiedViewName),
                    String.format("View %d of %d", processedViews + 1, totalViews));

            try {
                createViewStub(connection, view);
                result.addCreatedView(qualifiedViewName);
                log.debug("Successfully created PostgreSQL view stub: {}", qualifiedViewName);
            } catch (SQLException e) {
                String sqlStatement = generateCreateViewStubSQL(view);
                String errorMessage = String.format("Failed to create view stub '%s': %s",
                        qualifiedViewName, e.getMessage());
                result.addError(qualifiedViewName, errorMessage, sqlStatement);
                log.error("Failed to create view stub '{}': {}", qualifiedViewName, e.getMessage());
                log.error("Failed SQL statement: {}", sqlStatement);
            }

            processedViews++;
        }
    }

    private void createViewStub(Connection connection, ViewMetadata view) throws SQLException {
        String sql = generateCreateViewStubSQL(view);

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.executeUpdate();
        }

        log.debug("Executed SQL: {}", sql);
    }

    /**
     * Generates SQL for creating a view stub.
     * Pattern: CREATE VIEW schema.viewname AS SELECT NULL::type AS col1, NULL::type AS col2... WHERE false
     *
     * This creates a view with the correct structure but returns an empty result set.
     */
    private String generateCreateViewStubSQL(ViewMetadata view) {
        StringBuilder sql = new StringBuilder();

        sql.append("CREATE VIEW ");
        sql.append(view.getSchema().toLowerCase());
        sql.append(".");
        sql.append(view.getViewName().toLowerCase());
        sql.append(" AS SELECT ");

        List<String> columnDefinitions = new ArrayList<>();

        for (ColumnMetadata column : view.getColumns()) {
            String columnDef = generateViewStubColumnDefinition(column, view);
            columnDefinitions.add(columnDef);
        }

        sql.append(String.join(", ", columnDefinitions));
        sql.append(" WHERE false"); // Ensures empty result set

        return sql.toString();
    }

    /**
     * Generates a column definition for a view stub.
     * Pattern: NULL::type AS column_name
     */
    private String generateViewStubColumnDefinition(ColumnMetadata column, ViewMetadata view) {
        StringBuilder def = new StringBuilder();

        // Start with NULL cast to the appropriate type
        def.append("NULL::");

        // Determine PostgreSQL type
        String postgresType;
        if (column.isCustomDataType()) {
            String oracleType = column.getDataType().toLowerCase();
            String owner = column.getDataTypeOwner().toLowerCase();

            // Check if it's XMLTYPE - has direct PostgreSQL xml type mapping
            if (OracleTypeClassifier.isXmlType(owner, oracleType)) {
                postgresType = "xml";
                log.debug("Oracle XMLTYPE for column '{}' in view '{}' mapped to PostgreSQL xml type",
                        column.getColumnName(), getQualifiedViewName(view));
            }
            // Check if it's a complex Oracle system type that needs jsonb serialization
            else if (OracleTypeClassifier.isComplexOracleSystemType(owner, oracleType)) {
                postgresType = "jsonb";
                log.debug("Complex Oracle system type '{}.{}' for column '{}' in view '{}' will use jsonb",
                        owner, oracleType, column.getColumnName(), getQualifiedViewName(view));
            } else {
                // User-defined type - use the created PostgreSQL composite type
                postgresType = owner + "." + oracleType;
                log.debug("Using user-defined composite type '{}' for column '{}' in view '{}'",
                        postgresType, column.getColumnName(), getQualifiedViewName(view));
            }
        } else {
            // Convert Oracle built-in data type to PostgreSQL
            postgresType = TypeConverter.toPostgre(column.getDataType());
            if (postgresType == null) {
                postgresType = "text"; // Fallback for unknown types
                log.warn("Unknown data type '{}' for column '{}' in view '{}', using 'text' as fallback",
                        column.getDataType(), column.getColumnName(), getQualifiedViewName(view));
            }
        }

        def.append(postgresType);

        // Add column alias
        def.append(" AS ");
        String normalizedColumnName = PostgresIdentifierNormalizer.normalizeIdentifier(column.getColumnName());
        def.append(normalizedColumnName);

        return def.toString();
    }

    @Override
    protected String generateSummaryMessage(ViewStubCreationResult result) {
        StringBuilder summary = new StringBuilder();
        summary.append(String.format("View stub creation completed: %d created, %d skipped, %d errors",
                result.getCreatedCount(), result.getSkippedCount(), result.getErrorCount()));

        if (result.getCreatedCount() > 0) {
            summary.append(String.format(" | Created: %s",
                    String.join(", ", result.getCreatedViews())));
        }

        if (result.getSkippedCount() > 0) {
            summary.append(String.format(" | Skipped: %s",
                    String.join(", ", result.getSkippedViews())));
        }

        if (result.hasErrors()) {
            summary.append(String.format(" | %d errors occurred", result.getErrorCount()));
        }

        return summary.toString();
    }
}
