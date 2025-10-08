package me.christianrobert.orapgsync.table.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseWriteJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.tools.OracleTypeClassifier;
import me.christianrobert.orapgsync.core.tools.TypeConverter;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.core.job.model.table.ColumnMetadata;
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

        updateProgress(progressCallback, 10, "Analyzing tables",
                      String.format("Found %d Oracle tables, %d are valid for creation",
                                   oracleTables.size(), validOracleTables.size()));

        TableCreationResult result = new TableCreationResult();

        if (validOracleTables.isEmpty()) {
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

            for (TableMetadata table : validOracleTables) {
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
                createTable(connection, table);
                result.addCreatedTable(qualifiedTableName);
                log.info("Successfully created PostgreSQL table: {}", qualifiedTableName);
            } catch (SQLException e) {
                String errorMessage = String.format("Failed to create table '%s': %s", qualifiedTableName, e.getMessage());
                String sqlStatement = generateCreateTableSQL(table);
                result.addError(qualifiedTableName, errorMessage, sqlStatement);
                log.error("Failed to create table: {}", qualifiedTableName, e);
            }

            processedTables++;
        }
    }

    private void createTable(Connection connection, TableMetadata table) throws SQLException {
        String sql = generateCreateTableSQL(table);

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.executeUpdate();
        }

        log.debug("Executed SQL: {}", sql);
    }

    private String generateCreateTableSQL(TableMetadata table) {
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE TABLE ");
        sql.append(String.format("%s.%s (", table.getSchema().toLowerCase(), table.getTableName().toLowerCase()));

        List<String> columnDefinitions = new ArrayList<>();

        // Add column definitions only (no constraints - they will be added after data transfer)
        for (ColumnMetadata column : table.getColumns()) {
            columnDefinitions.add(generateColumnDefinition(column));
        }

        sql.append(String.join(", ", columnDefinitions));
        sql.append(")");

        return sql.toString();
    }

    private String generateColumnDefinition(ColumnMetadata column) {
        StringBuilder def = new StringBuilder();
        def.append(column.getColumnName().toLowerCase());

        // Check if this is a custom (user-defined) data type
        String postgresType;
        if (column.isCustomDataType()) {
            String oracleType = column.getDataType().toLowerCase();
            String owner = column.getDataTypeOwner().toLowerCase();

            // Check if it's a complex Oracle system type that needs jsonb serialization
            if (OracleTypeClassifier.isComplexOracleSystemType(owner, oracleType)) {
                postgresType = "jsonb";
                log.debug("Complex Oracle system type '{}.{}' for column '{}' will use jsonb (data transfer will preserve type metadata)",
                         owner, oracleType, column.getColumnName());
            } else {
                if (oracleType.equals("xmltype")) {
                    postgresType = "xml";
                    log.debug("Using xmltype");
                } else {
                    // User-defined type - use the created PostgreSQL composite type
                    postgresType = owner + "." + oracleType;
                    log.debug("Using user-defined composite type '{}' for column '{}'", postgresType, column.getColumnName());
                }
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

        // Add default value if specified
        if (column.getDefaultValue() != null && !column.getDefaultValue().trim().isEmpty()) {
            def.append(" DEFAULT ").append(column.getDefaultValue());
        }

        return def.toString();
    }


    @Override
    protected String generateSummaryMessage(TableCreationResult result) {
        StringBuilder summary = new StringBuilder();
        summary.append(String.format("Table creation completed: %d created, %d skipped, %d errors",
                                    result.getCreatedCount(), result.getSkippedCount(), result.getErrorCount()));

        if (result.getCreatedCount() > 0) {
            summary.append(String.format(" | Created: %s", String.join(", ", result.getCreatedTables())));
        }

        if (result.getSkippedCount() > 0) {
            summary.append(String.format(" | Skipped: %s", String.join(", ", result.getSkippedTables())));
        }

        if (result.hasErrors()) {
            summary.append(String.format(" | %d errors occurred", result.getErrorCount()));
        }

        return summary.toString();
    }
}