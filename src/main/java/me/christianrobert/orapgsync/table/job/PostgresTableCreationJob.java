package me.christianrobert.orapgsync.table.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseWriteJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.tools.TypeConverter;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.table.model.ColumnMetadata;
import me.christianrobert.orapgsync.table.model.ConstraintMetadata;
import me.christianrobert.orapgsync.table.model.TableCreationResult;
import me.christianrobert.orapgsync.table.model.TableMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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
        stateService.updateTableCreationResult(result);
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

            // Sort tables by dependencies to avoid foreign key issues
            List<TableMetadata> sortedTables = sortByDependencies(validOracleTables);

            // Determine which tables need to be created
            List<TableMetadata> tablesToCreate = new ArrayList<>();
            List<TableMetadata> tablesAlreadyExisting = new ArrayList<>();

            for (TableMetadata table : sortedTables) {
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

            // Create tables in two phases: tables first, then foreign key constraints
            createTablesAndConstraints(postgresConnection, tablesToCreate, result, progressCallback);

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

    private List<TableMetadata> sortByDependencies(List<TableMetadata> tables) {
        // Build dependency graph based on foreign key constraints
        Map<String, Set<String>> dependencies = new HashMap<>();
        Map<String, TableMetadata> tableMap = new HashMap<>();

        for (TableMetadata table : tables) {
            String tableName = getQualifiedTableName(table);
            tableMap.put(tableName, table);
            dependencies.put(tableName, new HashSet<>());

            // Find foreign key dependencies
            for (ConstraintMetadata constraint : table.getConstraints()) {
                if (constraint.isForeignKey() && constraint.getReferencedTable() != null) {
                    String referencedTable = String.format("%s.%s",
                        constraint.getReferencedSchema() != null ? constraint.getReferencedSchema() : table.getSchema(),
                        constraint.getReferencedTable());
                    dependencies.get(tableName).add(referencedTable);
                }
            }
        }

        // Perform topological sort
        List<TableMetadata> sortedTables = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();

        for (String tableName : dependencies.keySet()) {
            if (!visited.contains(tableName)) {
                topologicalSort(tableName, dependencies, tableMap, visited, visiting, sortedTables);
            }
        }

        return sortedTables;
    }

    private void topologicalSort(String tableName, Map<String, Set<String>> dependencies,
                                Map<String, TableMetadata> tableMap, Set<String> visited,
                                Set<String> visiting, List<TableMetadata> result) {
        if (visiting.contains(tableName)) {
            // Circular dependency detected, add table anyway (we'll handle FK constraints separately)
            log.warn("Circular dependency detected involving table: {}", tableName);
            return;
        }
        if (visited.contains(tableName)) {
            return;
        }

        visiting.add(tableName);

        // Visit dependencies first
        for (String dependency : dependencies.get(tableName)) {
            if (dependencies.containsKey(dependency)) {
                topologicalSort(dependency, dependencies, tableMap, visited, visiting, result);
            }
        }

        visiting.remove(tableName);
        visited.add(tableName);

        if (tableMap.containsKey(tableName)) {
            result.add(tableMap.get(tableName));
        }
    }

    private String getQualifiedTableName(TableMetadata table) {
        return String.format("%s.%s", table.getSchema(), table.getTableName());
    }

    private void createTablesAndConstraints(Connection connection, List<TableMetadata> tables,
                                          TableCreationResult result, Consumer<JobProgress> progressCallback) throws SQLException {
        int totalTables = tables.size();
        int processedTables = 0;

        // Phase 1: Create tables without foreign key constraints
        for (TableMetadata table : tables) {
            int progressPercentage = 40 + (processedTables * 25 / totalTables);
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

        // Phase 2: Add foreign key constraints
        updateProgress(progressCallback, 65, "Adding foreign key constraints", "Creating referential integrity constraints");

        for (TableMetadata table : tables) {
            if (result.getCreatedTables().contains(getQualifiedTableName(table))) {
                try {
                    addForeignKeyConstraints(connection, table);
                } catch (SQLException e) {
                    log.warn("Failed to add foreign key constraints for table {}: {}",
                            getQualifiedTableName(table), e.getMessage());
                    // Don't treat FK constraint failures as table creation failures
                }
            }
        }
    }

    private void createTable(Connection connection, TableMetadata table) throws SQLException {
        String sql = generateCreateTableSQL(table);

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.executeUpdate();
        }

        log.debug("Executed SQL: {}", sql);
    }

    private void addForeignKeyConstraints(Connection connection, TableMetadata table) throws SQLException {
        for (ConstraintMetadata constraint : table.getConstraints()) {
            if (constraint.isForeignKey()) {
                String sql = generateForeignKeyConstraintSQL(table, constraint);
                try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                    stmt.executeUpdate();
                }
                log.debug("Added foreign key constraint: {}", sql);
            }
        }
    }

    private String generateCreateTableSQL(TableMetadata table) {
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE TABLE ");
        sql.append(String.format("%s.%s (", table.getSchema().toLowerCase(), table.getTableName().toLowerCase()));

        List<String> columnDefinitions = new ArrayList<>();

        // Add column definitions
        for (ColumnMetadata column : table.getColumns()) {
            columnDefinitions.add(generateColumnDefinition(column));
        }

        // Add non-foreign key constraints
        for (ConstraintMetadata constraint : table.getConstraints()) {
            if (!constraint.isForeignKey()) {
                String constraintSQL = generateConstraintSQL(constraint);
                if (constraintSQL != null) {
                    columnDefinitions.add(constraintSQL);
                }
            }
        }

        sql.append(String.join(", ", columnDefinitions));
        sql.append(")");

        return sql.toString();
    }

    private String generateColumnDefinition(ColumnMetadata column) {
        StringBuilder def = new StringBuilder();
        def.append(column.getColumnName().toLowerCase());

        // Convert Oracle data type to PostgreSQL
        String postgresType = TypeConverter.toPostgre(column.getDataType());
        if (postgresType == null) {
            postgresType = "text"; // Fallback for unknown types
            log.warn("Unknown data type '{}' for column '{}', using 'text' as fallback",
                    column.getDataType(), column.getColumnName());
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

    private String generateConstraintSQL(ConstraintMetadata constraint) {
        StringBuilder sql = new StringBuilder();
        String constraintName = String.format("pg_%s", constraint.getConstraintName().toLowerCase());

        switch (constraint.getConstraintType()) {
            case ConstraintMetadata.PRIMARY_KEY:
                sql.append("CONSTRAINT ").append(constraintName)
                   .append(" PRIMARY KEY (")
                   .append(constraint.getColumnNames().stream()
                           .map(col -> col.toLowerCase())
                           .collect(Collectors.joining(", ")))
                   .append(")");
                break;

            case ConstraintMetadata.UNIQUE:
                sql.append("CONSTRAINT ").append(constraintName)
                   .append(" UNIQUE (")
                   .append(constraint.getColumnNames().stream()
                           .map(col -> col.toLowerCase())
                           .collect(Collectors.joining(", ")))
                   .append(")");
                break;

            case ConstraintMetadata.CHECK:
                if (constraint.getCheckCondition() != null) {
                    sql.append("CONSTRAINT ").append(constraintName)
                       .append(" CHECK (").append(constraint.getCheckCondition()).append(")");
                }
                break;

            default:
                return null; // Skip foreign keys and unknown constraints
        }

        return sql.toString();
    }

    private String generateForeignKeyConstraintSQL(TableMetadata table, ConstraintMetadata constraint) {
        StringBuilder sql = new StringBuilder();
        String constraintName = String.format("pg_%s", constraint.getConstraintName().toLowerCase());

        sql.append("ALTER TABLE ");
        sql.append(String.format("%s.%s", table.getSchema().toLowerCase(), table.getTableName().toLowerCase()));
        sql.append(" ADD CONSTRAINT ").append(constraintName);
        sql.append(" FOREIGN KEY (");
        sql.append(constraint.getColumnNames().stream()
                   .map(col -> col.toLowerCase())
                   .collect(Collectors.joining(", ")));
        sql.append(") REFERENCES ");

        String referencedSchema = constraint.getReferencedSchema() != null ?
                                 constraint.getReferencedSchema() : table.getSchema();
        sql.append(String.format("%s.%s", referencedSchema.toLowerCase(), constraint.getReferencedTable().toLowerCase()));

        if (!constraint.getReferencedColumns().isEmpty()) {
            sql.append(" (");
            sql.append(constraint.getReferencedColumns().stream()
                       .map(col -> col.toLowerCase())
                       .collect(Collectors.joining(", ")));
            sql.append(")");
        }

        // Add referential actions if specified
        if (constraint.getDeleteRule() != null && !constraint.getDeleteRule().equals("NO ACTION")) {
            sql.append(" ON DELETE ").append(constraint.getDeleteRule());
        }
        if (constraint.getUpdateRule() != null && !constraint.getUpdateRule().equals("NO ACTION")) {
            sql.append(" ON UPDATE ").append(constraint.getUpdateRule());
        }

        return sql.toString();
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