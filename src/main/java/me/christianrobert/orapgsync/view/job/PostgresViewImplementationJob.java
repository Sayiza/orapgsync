package me.christianrobert.orapgsync.view.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseWriteJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.table.ColumnMetadata;
import me.christianrobert.orapgsync.core.job.model.view.ViewImplementationResult;
import me.christianrobert.orapgsync.core.job.model.view.ViewMetadata;
import me.christianrobert.orapgsync.core.tools.OracleTypeClassifier;
import me.christianrobert.orapgsync.core.tools.PostgresIdentifierNormalizer;
import me.christianrobert.orapgsync.core.tools.TypeConverter;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.transformer.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import me.christianrobert.orapgsync.transformer.service.TransformationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Creates PostgreSQL view implementations (Phase 2) by replacing stubs with actual SQL.
 * This job transforms Oracle view SQL to PostgreSQL and replaces stub views.
 *
 * CRITICAL: Uses CREATE OR REPLACE VIEW to preserve dependencies!
 * The two-phase architecture depends on this:
 * - Phase 1 (stubs): Functions/procedures are created with references to stub views
 * - Phase 2 (implementation): Replace stubs WITHOUT dropping dependent objects
 *
 * Implementation:
 * 1. Reads Oracle view SQL from ViewMetadata.sqlDefinition
 * 2. Transforms SQL using ANTLR parser (Oracle → PostgreSQL)
 * 3. Replaces stub views using CREATE OR REPLACE VIEW
 * 4. Preserves all dependencies (functions, procedures, other views)
 * 5. Tracks success/failures in ViewImplementationResult
 */
@Dependent
public class PostgresViewImplementationJob extends AbstractDatabaseWriteJob<ViewImplementationResult> {

    private static final Logger log = LoggerFactory.getLogger(PostgresViewImplementationJob.class);

    @Inject
    private PostgresConnectionService postgresConnectionService;

    @Inject
    private TransformationService transformationService;

    @Override
    public String getTargetDatabase() {
        return "POSTGRES";
    }

    @Override
    public String getWriteOperationType() {
        return "VIEW_IMPLEMENTATION";
    }

    @Override
    public Class<ViewImplementationResult> getResultType() {
        return ViewImplementationResult.class;
    }

    @Override
    protected void saveResultsToState(ViewImplementationResult result) {
        stateService.setViewImplementationResult(result);
    }

    @Override
    protected ViewImplementationResult performWriteOperation(Consumer<JobProgress> progressCallback) throws Exception {
        log.info("Starting PostgreSQL view implementation with SQL transformation");

        updateProgress(progressCallback, 0, "Initializing",
                "Starting view implementation process");

        ViewImplementationResult result = new ViewImplementationResult();

        // Get Oracle views from state
        List<ViewMetadata> oracleViews = stateService.getOracleViewMetadata();

        if (oracleViews == null || oracleViews.isEmpty()) {
            log.warn("No Oracle views found in state for implementation");
            updateProgress(progressCallback, 100, "No views to process",
                    "No Oracle views found in state. Please extract Oracle views first.");
            return result;
        }

        // Filter valid views (exclude system schemas and views without SQL)
        List<ViewMetadata> validViews = filterValidViews(oracleViews);

        if (validViews.isEmpty()) {
            log.warn("No valid Oracle views found for implementation");
            updateProgress(progressCallback, 100, "No valid views",
                    "No valid Oracle views with SQL definitions found");
            return result;
        }

        log.info("Found {} valid Oracle views to implement", validViews.size());
        updateProgress(progressCallback, 10, "Building metadata indices",
                String.format("Processing %d views", validViews.size()));

        // Build transformation indices once at the start
        List<String> schemas = stateService.getOracleSchemaNames();
        TransformationIndices indices = MetadataIndexBuilder.build(stateService, schemas);

        updateProgress(progressCallback, 15, "Metadata indices built",
                "Ready to transform view SQL");

        updateProgress(progressCallback, 20, "Connecting to PostgreSQL",
                "Establishing database connection");

        try (Connection pgConnection = postgresConnectionService.getConnection()) {
            updateProgress(progressCallback, 25, "Connected",
                    "Successfully connected to PostgreSQL");

            int totalViews = validViews.size();
            int processedViews = 0;

            for (ViewMetadata view : validViews) {
                String qualifiedName = view.getSchema() + "." + view.getViewName();
                int progressPercentage = 25 + (processedViews * 70 / totalViews);

                updateProgress(progressCallback, progressPercentage,
                        "Implementing view: " + qualifiedName,
                        String.format("View %d of %d", processedViews + 1, totalViews));

                try {
                    implementView(pgConnection, view, indices, result);
                } catch (Exception e) {
                    log.error("Failed to implement view: " + qualifiedName, e);
                    result.addError(qualifiedName, "Implementation failed: " + e.getMessage(), null);
                }

                processedViews++;
            }

            updateProgress(progressCallback, 95, "Finalizing",
                    "View implementation completed");

            log.info("View implementation completed: {} implemented, {} skipped, {} errors",
                    result.getImplementedCount(), result.getSkippedCount(), result.getErrorCount());

            updateProgress(progressCallback, 100, "Completed",
                    String.format("Implemented %d views: %d succeeded, %d skipped, %d errors",
                            result.getTotalProcessed(), result.getImplementedCount(),
                            result.getSkippedCount(), result.getErrorCount()));

            return result;

        } catch (Exception e) {
            log.error("View implementation failed", e);
            updateProgress(progressCallback, -1, "Failed",
                    "View implementation failed: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Implements a single view by transforming Oracle SQL to PostgreSQL.
     * Catches and logs SQL errors to prevent job crashes - individual view failures
     * should not stop processing of other views.
     */
    private void implementView(Connection pgConnection, ViewMetadata view,
                               TransformationIndices indices,
                               ViewImplementationResult result) {

        String schema = view.getSchema();
        String viewName = view.getViewName();
        String qualifiedName = schema + "." + viewName;
        String oracleSql = view.getSqlDefinition();

        // Check if Oracle SQL is available
        if (oracleSql == null || oracleSql.trim().isEmpty()) {
            log.warn("No SQL definition for view: {}", qualifiedName);
            result.addSkippedView(qualifiedName);
            return;
        }

        log.debug("Transforming view: {}", qualifiedName);
        log.trace("Oracle SQL: {}", oracleSql);

        // Transform Oracle SQL to PostgreSQL (pure translation, no type casting)
        TransformationResult transformationResult = transformationService.transformSql(
                oracleSql, schema, indices);

        if (transformationResult.isFailure()) {
            log.error("Transformation failed for view {}: {}", qualifiedName,
                    transformationResult.getErrorMessage());
            result.addError(qualifiedName, transformationResult.getErrorMessage(), oracleSql);
            return;
        }

        String transformedSql = transformationResult.getPostgresSql();
        log.debug("Transformed SQL for {}: {}", qualifiedName, transformedSql);

        // Wrap transformed SQL with type-casting outer SELECT
        // This ensures CREATE OR REPLACE VIEW succeeds by matching stub column types exactly
        String wrappedSql = wrapWithTypeCasts(transformedSql, view);
        log.debug("Wrapped SQL for {}: {}", qualifiedName, wrappedSql);

        // Replace stub view with actual implementation
        // IMPORTANT: Use CREATE OR REPLACE to preserve dependencies (functions/procedures)
        // This is the whole point of the two-phase stub architecture!
        try {
            replaceView(pgConnection, schema, viewName, wrappedSql);
            result.addImplementedView(qualifiedName);
            log.info("Successfully implemented view: {}", qualifiedName);
        } catch (SQLException e) {
            // Log SQL error but continue processing other views
            log.error("SQL error creating view {}: {}", qualifiedName, e.getMessage());
            log.debug("Failed SQL: CREATE OR REPLACE VIEW {}.{} AS {}",
                    schema.toLowerCase(), viewName.toLowerCase(), wrappedSql);

            // Add error with the wrapped SQL (shows what we tried to execute)
            String errorMsg = "SQL execution failed: " + e.getMessage();
            result.addError(qualifiedName, errorMsg, wrappedSql);
        }
    }

    /**
     * Wraps transformed SQL with type-casting outer SELECT.
     * This ensures CREATE OR REPLACE VIEW succeeds by matching stub column types exactly.
     *
     * <p><strong>Architecture:</strong> Type reconciliation is handled at the view creation layer
     * (wrapper SELECT approach), NOT during SQL transformation. This provides:</p>
     * <ul>
     *   <li>Cleaner separation of concerns (transformation vs type reconciliation)</li>
     *   <li>Position-based casting by design (no name matching needed)</li>
     *   <li>Simpler transformation code (no view column metadata in transformation layer)</li>
     *   <li>More robust (works regardless of SELECT aliases or column name conventions)</li>
     * </ul>
     *
     * <p><strong>Example:</strong></p>
     * <pre>
     * Input (transformed SQL): SELECT empno, COUNT(*) FROM hr.emp GROUP BY empno
     * Output (wrapped SQL):
     *   SELECT c0::numeric AS emp_id, c1::bigint AS emp_count
     *   FROM (
     *     SELECT empno, COUNT(*) FROM hr.emp GROUP BY empno
     *   ) AS subq(c0, c1)
     * </pre>
     *
     * @param transformedSql PostgreSQL SQL from transformation (pure translation, no casts)
     * @param view View metadata containing column definitions with types
     * @return Wrapped SQL with type-casting outer SELECT
     */
    private String wrapWithTypeCasts(String transformedSql, ViewMetadata view) {
        int columnCount = view.getColumns().size();

        // Build generic subquery column list: c0, c1, c2, ...
        List<String> subqColumns = new ArrayList<>();
        for (int i = 0; i < columnCount; i++) {
            subqColumns.add("c" + i);
        }

        // Build outer SELECT with casts: c0::type AS view_col, c1::type AS view_col, ...
        List<String> selectExprs = new ArrayList<>();
        for (int i = 0; i < columnCount; i++) {
            ColumnMetadata column = view.getColumns().get(i);
            String columnName = PostgresIdentifierNormalizer.normalizeIdentifier(column.getColumnName());

            // Determine PostgreSQL type (same logic as stub creation)
            String postgresType = determinePostgresType(column, view.getSchema() + "." + view.getViewName());

            // Build cast expression: c0::numeric AS emp_id
            selectExprs.add(String.format("c%d::%s AS %s", i, postgresType, columnName));
        }

        // Build final wrapped SQL
        return String.format("SELECT %s FROM ( %s ) AS subq(%s)",
                String.join(", ", selectExprs),
                transformedSql,
                String.join(", ", subqColumns));
    }

    /**
     * Determines PostgreSQL type for a column (same logic as stub creation).
     *
     * @param column Column metadata
     * @param qualifiedViewName Qualified view name for logging (schema.view)
     * @return PostgreSQL type string
     */
    private String determinePostgresType(ColumnMetadata column, String qualifiedViewName) {
        if (column.isCustomDataType()) {
            String oracleType = column.getDataType().toLowerCase();
            String owner = column.getDataTypeOwner().toLowerCase();

            // Check if it's XMLTYPE - has direct PostgreSQL xml type mapping
            if (OracleTypeClassifier.isXmlType(owner, oracleType)) {
                return "xml";
            }
            // Check if it's a complex Oracle system type that needs jsonb serialization
            else if (OracleTypeClassifier.isComplexOracleSystemType(owner, oracleType)) {
                return "jsonb";
            } else {
                // User-defined type - use the created PostgreSQL composite type
                return owner + "." + oracleType;
            }
        } else {
            // Convert Oracle built-in data type to PostgreSQL
            String postgresType = TypeConverter.toPostgre(column.getDataType());
            if (postgresType == null) {
                postgresType = "text"; // Fallback for unknown types
                log.warn("Unknown data type '{}' for column '{}' in view '{}', using 'text' as fallback",
                        column.getDataType(), column.getColumnName(), qualifiedViewName);
            }
            return postgresType;
        }
    }

    /**
     * Replaces an existing view with new SQL definition.
     * Uses CREATE OR REPLACE VIEW to preserve dependencies on the view.
     *
     * <p>This is critical for the two-phase architecture:</p>
     * <ul>
     *   <li>Phase 1: Stubs allow functions/procedures to be created with references to views</li>
     *   <li>Phase 2: Replace stubs WITHOUT breaking those dependencies</li>
     * </ul>
     *
     * <p><strong>Note:</strong> No explicit column list needed - the outer SELECT wrapper
     * provides column names with type casts (wrapper SELECT approach).</p>
     *
     * @param pgConnection PostgreSQL database connection
     * @param schema Schema name
     * @param viewName View name
     * @param wrappedSql Wrapped SQL with type-casting outer SELECT
     */
    private void replaceView(Connection pgConnection, String schema, String viewName,
                            String wrappedSql) throws SQLException {
        String createOrReplaceSql = String.format("CREATE OR REPLACE VIEW %s.%s AS %s",
                schema.toLowerCase(), viewName.toLowerCase(), wrappedSql);

        log.debug("Replacing view with SQL: {}", createOrReplaceSql);

        try (PreparedStatement ps = pgConnection.prepareStatement(createOrReplaceSql)) {
            ps.executeUpdate();
        }
    }

    /**
     * Filters views to only include valid ones (with SQL definitions and in valid schemas).
     */
    private List<ViewMetadata> filterValidViews(List<ViewMetadata> views) {
        List<ViewMetadata> validViews = new ArrayList<>();

        for (ViewMetadata view : views) {
            // Check if schema is valid
            if (filterValidSchemas(List.of(view.getSchema())).isEmpty()) {
                log.debug("Skipping view {} - schema excluded", view.getSchema() + "." + view.getViewName());
                continue;
            }

            // Check if SQL definition exists
            if (view.getSqlDefinition() == null || view.getSqlDefinition().trim().isEmpty()) {
                log.debug("Skipping view {} - no SQL definition", view.getSchema() + "." + view.getViewName());
                continue;
            }

            validViews.add(view);
        }

        return validViews;
    }

    @Override
    protected String generateSummaryMessage(ViewImplementationResult result) {
        if (result == null) {
            return "View implementation completed: No views processed";
        }

        return String.format("View implementation completed: %d implemented, %d skipped, %d errors",
                result.getImplementedCount(), result.getSkippedCount(), result.getErrorCount());
    }
}
