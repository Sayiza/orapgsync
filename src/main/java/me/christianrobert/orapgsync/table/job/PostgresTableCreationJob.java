package me.christianrobert.orapgsync.table.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseWriteJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.table.service.DefaultValueTransformer;
import me.christianrobert.orapgsync.core.tools.OracleTypeClassifier;
import me.christianrobert.orapgsync.core.tools.TypeConverter;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.core.job.model.table.ColumnMetadata;
import me.christianrobert.orapgsync.core.job.model.table.ConstraintMetadata;
import me.christianrobert.orapgsync.core.job.model.table.TableCreationResult;
import me.christianrobert.orapgsync.core.job.model.table.TableMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Consumer;

@Dependent
public class PostgresTableCreationJob extends AbstractDatabaseWriteJob<TableCreationResult> {

    private static final Logger log = LoggerFactory.getLogger(PostgresTableCreationJob.class);

    @Inject
    private PostgresConnectionService postgresConnectionService;

    @Override
    public String getTargetDatabase() {
        return "POSTGRES";
    }

    @Override
    public String getWriteOperationType() {
        return "TABLE_CREATION";
    }

    @Override
    public Class<TableCreationResult> getResultType() {
        return TableCreationResult.class;
    }

    @Override
    protected void saveResultsToState(TableCreationResult result) {
        stateService.setTableCreationResult(result);
    }

    @Override
    protected TableCreationResult performWriteOperation(Consumer<JobProgress> progressCallback) throws Exception {
        updateProgress(progressCallback, 0, "Initializing", "Starting PostgreSQL table creation process");

        // Get Oracle tables from state
        List<TableMetadata> oracleTables = getOracleTables();
        if (oracleTables.isEmpty()) {
            updateProgress(progressCallback, 100, "No tables to process",
                          "No Oracle tables found in state. Please extract Oracle table metadata first.");
            log.warn("No Oracle tables found in state for table creation");
            return new TableCreationResult();
        }

        // Filter valid tables (exclude system schemas)
        List<TableMetadata> validOracleTables = filterValidTables(oracleTables);

        updateProgress(progressCallback, 10, "Normalizing tables",
                      String.format("Found %d Oracle tables, %d are valid for creation",
                                   oracleTables.size(), validOracleTables.size()));

        // Normalize tables by resolving all synonym references in column types
        // This ensures table creation uses actual type references (not synonyms)
        List<TableMetadata> normalizedTables = normalizeTableMetadata(validOracleTables);

        updateProgress(progressCallback, 15, "Analyzing tables",
                      String.format("Normalized %d tables for creation", normalizedTables.size()));

        TableCreationResult result = new TableCreationResult();

        if (normalizedTables.isEmpty()) {
            updateProgress(progressCallback, 100, "No valid tables", "No valid Oracle tables to create in PostgreSQL");
            return result;
        }

        updateProgress(progressCallback, 20, "Connecting to PostgreSQL", "Establishing database connection");

        try (Connection postgresConnection = postgresConnectionService.getConnection()) {
            updateProgress(progressCallback, 25, "Connected", "Successfully connected to PostgreSQL database");

            // Get existing PostgreSQL tables
            Set<String> existingPostgresTables = getExistingPostgresTables(postgresConnection);

            updateProgress(progressCallback, 30, "Checking existing tables",
                          String.format("Found %d existing PostgreSQL tables", existingPostgresTables.size()));

            // Determine which tables need to be created (no sorting needed without constraints)
            List<TableMetadata> tablesToCreate = new ArrayList<>();
            List<TableMetadata> tablesAlreadyExisting = new ArrayList<>();

            for (TableMetadata table : normalizedTables) {
                String qualifiedTableName = getQualifiedTableName(table);
                if (existingPostgresTables.contains(qualifiedTableName.toLowerCase())) {
                    tablesAlreadyExisting.add(table);
                } else {
                    tablesToCreate.add(table);
                }
            }

            // Mark already existing tables as skipped
            for (TableMetadata table : tablesAlreadyExisting) {
                String qualifiedTableName = getQualifiedTableName(table);
                result.addSkippedTable(qualifiedTableName);
                log.info("Table '{}' already exists in PostgreSQL, skipping", qualifiedTableName);
            }

            updateProgress(progressCallback, 40, "Planning creation",
                          String.format("%d tables to create, %d already exist",
                                       tablesToCreate.size(), tablesAlreadyExisting.size()));

            if (tablesToCreate.isEmpty()) {
                updateProgress(progressCallback, 100, "All tables exist",
                              "All Oracle tables already exist in PostgreSQL");
                return result;
            }

            // Create tables without constraints (constraints will be added after data transfer)
            createTables(postgresConnection, tablesToCreate, result, progressCallback);

            updateProgress(progressCallback, 90, "Creation complete",
                          String.format("Created %d tables, skipped %d existing, %d errors",
                                       result.getCreatedCount(), result.getSkippedCount(), result.getErrorCount()));

            return result;

        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed", "Table creation failed: " + e.getMessage());
            throw e;
        }
    }

    private List<TableMetadata> getOracleTables() {
        return stateService.getOracleTableMetadata();
    }

    private List<TableMetadata> filterValidTables(List<TableMetadata> tables) {
        List<TableMetadata> validTables = new ArrayList<>();
        for (TableMetadata table : tables) {
            if (!filterValidSchemas(List.of(table.getSchema())).isEmpty()) {
                validTables.add(table);
            }
        }
        return validTables;
    }

    /**
     * Normalizes table metadata by resolving all synonym references in column data types.
     * This preprocessing step ensures that:
     * 1. Table creation SQL uses the correct target types (not synonyms)
     * 2. Synonym resolution happens only once per type reference
     *
     * @param tables The original tables with potential synonym references in column types
     * @return Normalized copies with all synonyms resolved to their targets
     */
    private List<TableMetadata> normalizeTableMetadata(List<TableMetadata> tables) {
        log.info("Normalizing {} tables by resolving synonym references in column types", tables.size());

        List<TableMetadata> normalizedTables = new ArrayList<>();
        int synonymsResolved = 0;

        for (TableMetadata originalTable : tables) {
            try {
                // Create a new table with normalized column references
                TableMetadata normalizedTable = new TableMetadata(
                    originalTable.getSchema(),
                    originalTable.getTableName()
                );

                // Normalize each column's data type
                for (ColumnMetadata originalColumn : originalTable.getColumns()) {
                    ColumnMetadata normalizedColumn;

                    // Only resolve synonyms for custom (user-defined) types
                    if (originalColumn.isCustomDataType()) {
                        String owner = originalColumn.getDataTypeOwner();
                        String typeName = originalColumn.getDataType();

                        // Extract the base type name without size/precision info
                        String baseTypeName = extractBaseTypeName(typeName);

                        // Try to resolve as a synonym
                        String resolvedTarget = stateService.resolveSynonym(owner, baseTypeName);

                        if (resolvedTarget != null) {
                            // Parse the resolved target (format: "schema.typename")
                            String[] parts = resolvedTarget.split("\\.");
                            if (parts.length == 2) {
                                String resolvedOwner = parts[0];
                                String resolvedTypeName = parts[1];

                                // Preserve any size/precision info from the original type
                                String typeWithSize = typeName.substring(baseTypeName.length());
                                String fullResolvedType = resolvedTypeName + typeWithSize;

                                normalizedColumn = new ColumnMetadata(
                                    originalColumn.getColumnName(),
                                    fullResolvedType,
                                    resolvedOwner,
                                    originalColumn.getCharacterLength(),
                                    originalColumn.getNumericPrecision(),
                                    originalColumn.getNumericScale(),
                                    originalColumn.isNullable(),
                                    originalColumn.getDefaultValue()
                                );

                                synonymsResolved++;
                                log.debug("Resolved synonym {}.{} -> {}.{} for column '{}' in table {}.{}",
                                    owner, baseTypeName, resolvedOwner, resolvedTypeName,
                                    originalColumn.getColumnName(), originalTable.getSchema(), originalTable.getTableName());
                            } else {
                                // Malformed resolution result, use original
                                normalizedColumn = createColumnCopy(originalColumn);
                                log.warn("Malformed synonym resolution result: {}", resolvedTarget);
                            }
                        } else {
                            // Not a synonym, use original column
                            normalizedColumn = createColumnCopy(originalColumn);
                        }
                    } else {
                        // Built-in type, use original column
                        normalizedColumn = createColumnCopy(originalColumn);
                    }

                    normalizedTable.addColumn(normalizedColumn);
                }

                // Copy constraints as-is (no type references in constraints at this stage)
                for (ConstraintMetadata constraint : originalTable.getConstraints()) {
                    normalizedTable.addConstraint(constraint);
                }

                normalizedTables.add(normalizedTable);

            } catch (Exception e) {
                log.error("Failed to normalize table {}.{}, using original",
                    originalTable.getSchema(), originalTable.getTableName(), e);
                normalizedTables.add(originalTable); // Fall back to original on error
            }
        }

        log.info("Normalization complete: {} tables processed, {} synonym references resolved",
            normalizedTables.size(), synonymsResolved);

        return normalizedTables;
    }

    /**
     * Extracts the base type name without size/precision information.
     * For example: "VARCHAR2(100)" -> "VARCHAR2", "NUMBER(10,2)" -> "NUMBER"
     */
    private String extractBaseTypeName(String dataType) {
        int parenIndex = dataType.indexOf('(');
        if (parenIndex > 0) {
            return dataType.substring(0, parenIndex);
        }
        return dataType;
    }

    /**
     * Creates a copy of a ColumnMetadata object.
     */
    private ColumnMetadata createColumnCopy(ColumnMetadata original) {
        return new ColumnMetadata(
            original.getColumnName(),
            original.getDataType(),
            original.getDataTypeOwner(),
            original.getCharacterLength(),
            original.getNumericPrecision(),
            original.getNumericScale(),
            original.isNullable(),
            original.getDefaultValue()
        );
    }

    private Set<String> getExistingPostgresTables(Connection connection) throws SQLException {
        Set<String> tables = new HashSet<>();

        String sql = """
            SELECT schemaname as schema_name, tablename as table_name
            FROM pg_tables
            WHERE schemaname NOT IN ('information_schema', 'pg_catalog', 'pg_toast')
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String schemaName = rs.getString("schema_name");
                String tableName = rs.getString("table_name");
                String qualifiedName = String.format("%s.%s", schemaName, tableName).toLowerCase();
                tables.add(qualifiedName);
            }
        }

        log.debug("Found {} existing PostgreSQL tables", tables.size());
        return tables;
    }

    private String getQualifiedTableName(TableMetadata table) {
        return String.format("%s.%s", table.getSchema(), table.getTableName());
    }

    private void createTables(Connection connection, List<TableMetadata> tables,
                             TableCreationResult result, Consumer<JobProgress> progressCallback) throws SQLException {
        int totalTables = tables.size();
        int processedTables = 0;

        // Create tables without any constraints (constraints will be added after data transfer)
        for (TableMetadata table : tables) {
            int progressPercentage = 40 + (processedTables * 50 / totalTables);
            String qualifiedTableName = getQualifiedTableName(table);
            updateProgress(progressCallback, progressPercentage,
                          String.format("Creating table: %s", qualifiedTableName),
                          String.format("Table %d of %d", processedTables + 1, totalTables));

            try {
                createTable(connection, table, result);
                result.addCreatedTable(qualifiedTableName);
                log.info("Successfully created PostgreSQL table: {}", qualifiedTableName);
            } catch (SQLException e) {
                String errorMessage = String.format("Failed to create table '%s': %s", qualifiedTableName, e.getMessage());
                String sqlStatement = generateCreateTableSQL(table, result);
                result.addError(qualifiedTableName, errorMessage, sqlStatement);
                log.error("Failed to create table: {}", qualifiedTableName, e);
            }

            processedTables++;
        }
    }

    private void createTable(Connection connection, TableMetadata table, TableCreationResult result) throws SQLException {
        String sql = generateCreateTableSQL(table, result);

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.executeUpdate();
        }

        log.debug("Executed SQL: {}", sql);
    }

    private String generateCreateTableSQL(TableMetadata table, TableCreationResult result) {
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE TABLE ");
        sql.append(String.format("%s.%s (", table.getSchema().toLowerCase(), table.getTableName().toLowerCase()));

        List<String> columnDefinitions = new ArrayList<>();

        // Add column definitions only (no constraints - they will be added after data transfer)
        for (ColumnMetadata column : table.getColumns()) {
            columnDefinitions.add(generateColumnDefinition(column, table, result));
        }

        sql.append(String.join(", ", columnDefinitions));
        sql.append(")");

        return sql.toString();
    }

    private String generateColumnDefinition(ColumnMetadata column, TableMetadata table, TableCreationResult result) {
        StringBuilder def = new StringBuilder();
        def.append(column.getColumnName().toLowerCase());

        // Check if this is a custom (user-defined) data type
        String postgresType;
        if (column.isCustomDataType()) {
            String oracleType = column.getDataType().toLowerCase();
            String owner = column.getDataTypeOwner().toLowerCase();

            // Check if it's XMLTYPE - has direct PostgreSQL xml type mapping
            if (OracleTypeClassifier.isXmlType(owner, oracleType)) {
                postgresType = "xml";
                log.debug("Oracle XMLTYPE for column '{}' mapped to PostgreSQL xml type", column.getColumnName());
            }
            // Check if it's a complex Oracle system type that needs jsonb serialization
            else if (OracleTypeClassifier.isComplexOracleSystemType(owner, oracleType)) {
                postgresType = "jsonb";
                log.debug("Complex Oracle system type '{}.{}' for column '{}' will use jsonb (data transfer will preserve type metadata)",
                         owner, oracleType, column.getColumnName());
            } else {
                // User-defined type - use the created PostgreSQL composite type
                postgresType = owner + "." + oracleType;
                log.debug("Using user-defined composite type '{}' for column '{}'", postgresType, column.getColumnName());
            }
        } else {
            // Convert Oracle built-in data type to PostgreSQL
            postgresType = TypeConverter.toPostgre(column.getDataType());
            if (postgresType == null) {
                postgresType = "text"; // Fallback for unknown types
                log.warn("Unknown data type '{}' for column '{}', using 'text' as fallback",
                        column.getDataType(), column.getColumnName());
            }
        }

        def.append(" ").append(postgresType);

        // Add NOT NULL constraint if applicable
        if (!column.isNullable()) {
            def.append(" NOT NULL");
        }

        // Add default value if specified - transform Oracle defaults to PostgreSQL
        if (column.getDefaultValue() != null && !column.getDefaultValue().trim().isEmpty()) {
            String qualifiedTableName = getQualifiedTableName(table);
            DefaultValueTransformer.TransformationResult transformResult =
                DefaultValueTransformer.transform(column.getDefaultValue(), column.getColumnName(), qualifiedTableName);

            if (transformResult.isSkipped()) {
                // Complex default that couldn't be mapped - log warning and track for manual review
                result.addUnmappedDefault(qualifiedTableName, column.getColumnName(),
                    transformResult.getOriginalValue(), transformResult.getTransformationNote());
                log.info("Skipped default value for {}.{}: {} ({})",
                    qualifiedTableName, column.getColumnName(),
                    transformResult.getOriginalValue(), transformResult.getTransformationNote());
            } else {
                // Simple default or successfully transformed - apply it
                def.append(" DEFAULT ").append(transformResult.getTransformedValue());
                if (transformResult.wasTransformed()) {
                    log.debug("Applied transformed default for {}.{}: {} -> {}",
                        qualifiedTableName, column.getColumnName(),
                        transformResult.getOriginalValue(), transformResult.getTransformedValue());
                }
            }
        }

        return def.toString();
    }


    @Override
    protected String generateSummaryMessage(TableCreationResult result) {
        StringBuilder summary = new StringBuilder();
        summary.append(String.format("Table creation completed: %d created, %d skipped, %d errors",
                                    result.getCreatedCount(), result.getSkippedCount(), result.getErrorCount()));

        if (result.getUnmappedDefaultCount() > 0) {
            summary.append(String.format(", %d unmapped defaults", result.getUnmappedDefaultCount()));
        }

        if (result.getCreatedCount() > 0) {
            summary.append(String.format(" | Created: %s", String.join(", ", result.getCreatedTables())));
        }

        if (result.getSkippedCount() > 0) {
            summary.append(String.format(" | Skipped: %s", String.join(", ", result.getSkippedTables())));
        }

        if (result.hasErrors()) {
            summary.append(String.format(" | %d errors occurred", result.getErrorCount()));
        }

        if (result.getUnmappedDefaultCount() > 0) {
            summary.append(String.format(" | %d column defaults require manual review", result.getUnmappedDefaultCount()));
        }

        return summary.toString();
    }
}