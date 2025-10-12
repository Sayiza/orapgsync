package me.christianrobert.orapgsync.constraint.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.constraint.service.ConstraintDependencyAnalyzer;
import me.christianrobert.orapgsync.constraint.service.ConstraintDependencyAnalyzer.TableConstraintPair;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseWriteJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.table.ConstraintCreationResult;
import me.christianrobert.orapgsync.core.job.model.table.ConstraintMetadata;
import me.christianrobert.orapgsync.core.job.model.table.TableMetadata;
import me.christianrobert.orapgsync.constraint.service.CheckConstraintTranslator;
import me.christianrobert.orapgsync.core.tools.PostgresIdentifierNormalizer;
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
 * Creates constraints in PostgreSQL from Oracle constraint metadata.
 *
 * Constraint Creation Order:
 * 1. Primary Keys (foundational, no dependencies)
 * 2. Unique Constraints (can be referenced by FKs)
 * 3. Foreign Keys (topologically sorted by table dependencies)
 * 4. Check Constraints (independent, failures won't block others)
 *
 * This job is meant to be run AFTER data transfer (Step C in the migration process).
 */
@Dependent
public class PostgresConstraintCreationJob extends AbstractDatabaseWriteJob<ConstraintCreationResult> {

    private static final Logger log = LoggerFactory.getLogger(PostgresConstraintCreationJob.class);

    @Inject
    private PostgresConnectionService postgresConnectionService;

    @Override
    public String getTargetDatabase() {
        return "POSTGRES";
    }

    @Override
    public String getWriteOperationType() {
        return "CONSTRAINT_CREATION";
    }

    @Override
    public Class<ConstraintCreationResult> getResultType() {
        return ConstraintCreationResult.class;
    }

    @Override
    protected void saveResultsToState(ConstraintCreationResult result) {
        stateService.setConstraintCreationResult(result);
    }

    @Override
    protected ConstraintCreationResult performWriteOperation(Consumer<JobProgress> progressCallback) throws Exception {
        updateProgress(progressCallback, 0, "Initializing", "Starting PostgreSQL constraint creation process");

        // Get Oracle tables from state
        List<TableMetadata> oracleTables = getOracleTables();
        if (oracleTables.isEmpty()) {
            updateProgress(progressCallback, 100, "No tables to process",
                    "No Oracle tables found in state. Please extract Oracle table metadata first.");
            log.warn("No Oracle tables found in state for constraint creation");
            return new ConstraintCreationResult();
        }

        // Filter valid tables (exclude system schemas)
        List<TableMetadata> validOracleTables = filterValidTables(oracleTables);

        updateProgress(progressCallback, 10, "Analyzing constraints",
                String.format("Found %d Oracle tables, %d are valid for constraint creation",
                        oracleTables.size(), validOracleTables.size()));

        ConstraintCreationResult result = new ConstraintCreationResult();

        if (validOracleTables.isEmpty()) {
            updateProgress(progressCallback, 100, "No valid tables",
                    "No valid Oracle tables to create constraints in PostgreSQL");
            return result;
        }

        // Sort constraints by dependency order
        List<TableConstraintPair> sortedConstraints = ConstraintDependencyAnalyzer.sortConstraintsByDependency(validOracleTables);

        // Filter out NOT NULL constraints (already applied during table creation)
        sortedConstraints.removeIf(pair -> pair.constraint.isNotNullConstraint());

        if (sortedConstraints.isEmpty()) {
            updateProgress(progressCallback, 100, "No constraints to create",
                    "No constraints found to create (NOT NULL constraints are already applied)");
            return result;
        }

        updateProgress(progressCallback, 20, "Connecting to PostgreSQL",
                String.format("Will create %d constraints in dependency order", sortedConstraints.size()));

        try (Connection postgresConnection = postgresConnectionService.getConnection()) {
            updateProgress(progressCallback, 25, "Connected", "Successfully connected to PostgreSQL database");

            // Get existing PostgreSQL constraints
            Map<String, Set<String>> existingPostgresConstraints = getExistingPostgresConstraints(postgresConnection);

            updateProgress(progressCallback, 30, "Checking existing constraints",
                    String.format("Found existing constraints on %d tables", existingPostgresConstraints.size()));

            // Create constraints in dependency order
            createConstraints(postgresConnection, sortedConstraints, existingPostgresConstraints, result, progressCallback);

            updateProgress(progressCallback, 90, "Creation complete",
                    String.format("Created %d constraints, skipped %d existing, %d errors",
                            result.getCreatedCount(), result.getSkippedCount(), result.getErrorCount()));

            return result;

        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed", "Constraint creation failed: " + e.getMessage());
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
     * Gets existing PostgreSQL constraints organized by table.
     * Returns a map: qualified_table_name -> Set of constraint_names
     */
    private Map<String, Set<String>> getExistingPostgresConstraints(Connection connection) throws SQLException {
        Map<String, Set<String>> constraints = new HashMap<>();

        String sql = """
            SELECT
                n.nspname AS schema_name,
                c.relname AS table_name,
                con.conname AS constraint_name
            FROM pg_constraint con
            JOIN pg_class c ON con.conrelid = c.oid
            JOIN pg_namespace n ON c.relnamespace = n.oid
            WHERE n.nspname NOT IN ('information_schema', 'pg_catalog', 'pg_toast')
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String schemaName = rs.getString("schema_name");
                String tableName = rs.getString("table_name");
                String constraintName = rs.getString("constraint_name");

                String qualifiedTableName = String.format("%s.%s", schemaName, tableName).toLowerCase();

                constraints.computeIfAbsent(qualifiedTableName, k -> new HashSet<>())
                          .add(constraintName.toLowerCase());
            }
        }

        log.debug("Found existing constraints on {} PostgreSQL tables", constraints.size());
        return constraints;
    }

    private void createConstraints(Connection connection, List<TableConstraintPair> sortedConstraints,
                                   Map<String, Set<String>> existingConstraints,
                                   ConstraintCreationResult result, Consumer<JobProgress> progressCallback) throws SQLException {
        int totalConstraints = sortedConstraints.size();
        int processedConstraints = 0;

        for (TableConstraintPair pair : sortedConstraints) {
            int progressPercentage = 30 + (processedConstraints * 60 / totalConstraints);
            String qualifiedTableName = pair.getQualifiedTableName().toLowerCase();
            String constraintName = pair.constraint.getConstraintName();
            String constraintType = pair.constraint.getConstraintType();

            updateProgress(progressCallback, progressPercentage,
                    String.format("Creating %s constraint: %s on %s",
                            getConstraintTypeDisplay(pair.constraint), constraintName, qualifiedTableName),
                    String.format("Constraint %d of %d", processedConstraints + 1, totalConstraints));

            // Check if constraint already exists
            Set<String> tableConstraints = existingConstraints.get(qualifiedTableName);
            if (tableConstraints != null && tableConstraints.contains(constraintName.toLowerCase())) {
                result.addSkippedConstraint(qualifiedTableName, constraintName, constraintType,
                        "Constraint already exists");
                log.info("Constraint '{}' already exists on table '{}', skipping", constraintName, qualifiedTableName);
                processedConstraints++;
                continue;
            }

            try {
                createConstraint(connection, pair.table, pair.constraint);
                result.addCreatedConstraint(qualifiedTableName, constraintName, constraintType);
                log.info("Successfully created {} constraint '{}' on table '{}'",
                        getConstraintTypeDisplay(pair.constraint), constraintName, qualifiedTableName);
            } catch (SQLException | IllegalArgumentException e) {
                String sqlStatement = generateConstraintSQL(pair.table, pair.constraint);
                String errorMessage = String.format("Failed to create %s constraint '%s' on table '%s': %s",
                        getConstraintTypeDisplay(pair.constraint), constraintName, qualifiedTableName, e.getMessage());
                result.addError(qualifiedTableName, constraintName, constraintType, errorMessage, sqlStatement);
                log.error("Failed to create constraint '{}' on table '{}': {}",
                        constraintName, qualifiedTableName, e.getMessage());
                log.error("Failed SQL statement: {}", sqlStatement);
            }

            processedConstraints++;
        }
    }

    private void createConstraint(Connection connection, TableMetadata table, ConstraintMetadata constraint) throws SQLException {
        String sql = generateConstraintSQL(table, constraint);

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.executeUpdate();
        }

        log.debug("Executed SQL: {}", sql);
    }

    private String generateConstraintSQL(TableMetadata table, ConstraintMetadata constraint) {
        String schemaName = table.getSchema().toLowerCase();
        String tableName = table.getTableName().toLowerCase();
        String constraintName = PostgresIdentifierNormalizer.normalizeIdentifier(constraint.getConstraintName());

        StringBuilder sql = new StringBuilder();
        sql.append("ALTER TABLE ").append(schemaName).append(".").append(tableName);
        sql.append(" ADD CONSTRAINT ").append(constraintName);

        if (constraint.isPrimaryKey()) {
            sql.append(" PRIMARY KEY (");
            sql.append(normalizeColumnNames(constraint.getColumnNames()));
            sql.append(")");
        } else if (constraint.isUniqueConstraint()) {
            sql.append(" UNIQUE (");
            sql.append(normalizeColumnNames(constraint.getColumnNames()));
            sql.append(")");
        } else if (constraint.isForeignKey()) {
            sql.append(" FOREIGN KEY (");
            sql.append(normalizeColumnNames(constraint.getColumnNames()));
            sql.append(") REFERENCES ");
            sql.append(constraint.getReferencedSchema().toLowerCase()).append(".");
            sql.append(constraint.getReferencedTable().toLowerCase());
            sql.append(" (");
            sql.append(normalizeColumnNames(constraint.getReferencedColumns()));
            sql.append(")");

            // Add ON DELETE rule if specified
            if (constraint.getDeleteRule() != null && !constraint.getDeleteRule().equals("NO ACTION")) {
                sql.append(" ON DELETE ").append(constraint.getDeleteRule());
            }
        } else if (constraint.isCheckConstraint()) {
            // For check constraints, translate Oracle syntax to PostgreSQL
            String checkCondition = constraint.getCheckCondition();
            if (checkCondition == null || checkCondition.trim().isEmpty()) {
                // Some Oracle check constraints (especially system-generated ones) have null/empty conditions
                // This should not happen for valid constraints, so we throw an exception
                throw new IllegalArgumentException(
                    String.format("Check constraint '%s' on table '%s.%s' has no check condition defined",
                        constraint.getConstraintName(), table.getSchema(), table.getTableName()));
            }

            // Translate Oracle functions to PostgreSQL equivalents
            String translatedCondition = CheckConstraintTranslator.translate(checkCondition);
            sql.append(" CHECK (").append(translatedCondition).append(")");
        } else {
            throw new IllegalArgumentException(
                "Unknown constraint type: " + constraint.getConstraintType());
        }

        return sql.toString();
    }

    /**
     * Normalizes a list of column names using PostgresIdentifierNormalizer.
     */
    private String normalizeColumnNames(List<String> columnList) {
        if (columnList == null || columnList.isEmpty()) {
            return "";
        }

        StringBuilder normalized = new StringBuilder();

        for (int i = 0; i < columnList.size(); i++) {
            if (i > 0) {
                normalized.append(", ");
            }
            normalized.append(PostgresIdentifierNormalizer.normalizeIdentifier(columnList.get(i).trim()));
        }

        return normalized.toString();
    }

    private String getConstraintTypeDisplay(ConstraintMetadata constraint) {
        if (constraint.isPrimaryKey()) return "PRIMARY KEY";
        if (constraint.isUniqueConstraint()) return "UNIQUE";
        if (constraint.isForeignKey()) return "FOREIGN KEY";
        if (constraint.isCheckConstraint()) return "CHECK";
        return "UNKNOWN";
    }

    @Override
    protected String generateSummaryMessage(ConstraintCreationResult result) {
        StringBuilder summary = new StringBuilder();
        summary.append(String.format("Constraint creation completed: %d created, %d skipped, %d errors",
                result.getCreatedCount(), result.getSkippedCount(), result.getErrorCount()));

        if (result.hasErrors()) {
            summary.append(String.format(" | %d errors occurred", result.getErrorCount()));
        }

        return summary.toString();
    }
}
