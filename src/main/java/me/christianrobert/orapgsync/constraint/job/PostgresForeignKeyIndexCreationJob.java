package me.christianrobert.orapgsync.constraint.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseWriteJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.table.ConstraintMetadata;
import me.christianrobert.orapgsync.core.job.model.table.FKIndexCreationResult;
import me.christianrobert.orapgsync.core.job.model.index.PostgresIndexCatalog;
import me.christianrobert.orapgsync.core.job.model.table.TableMetadata;
import me.christianrobert.orapgsync.core.tools.PostgresIdentifierNormalizer;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.database.service.PostgresIndexCatalogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Consumer;

/**
 * Fills the gaps in foreign key index coverage.
 *
 * <p>Neither Oracle nor PostgreSQL indexes foreign key columns automatically - both create
 * indexes only for primary key and unique constraints. Unindexed FK columns make referential
 * lookups and cascading operations expensive and widen the locks taken during them, so this job
 * adds a B-tree index wherever nothing already covers the FK columns.</p>
 *
 * <h2>Gap-fill, not migration</h2>
 *
 * <p>These indexes are the migration's own invention: they need not have existed in Oracle. That
 * is why this job runs <em>after</em> {@code PostgresIndexCreationJob}, which reproduces the
 * indexes Oracle actually had. Many schemas already index their FK columns by hand; creating a
 * second index over the same columns under a generated name would cost write throughput and disk
 * for nothing.</p>
 *
 * <p>Coverage is therefore decided against {@link PostgresIndexCatalog} by signature rather than
 * by index name - an existing index called anything at all counts, and a wider index counts too
 * as long as the FK columns are a leading prefix of its keys, since that is exactly when a B-tree
 * can serve the lookup.</p>
 *
 * <p>Index Naming Convention: idx_fk_{table}_{column1}_{column2}_...</p>
 */
@Dependent
public class PostgresForeignKeyIndexCreationJob extends AbstractDatabaseWriteJob<FKIndexCreationResult> {

    private static final Logger log = LoggerFactory.getLogger(PostgresForeignKeyIndexCreationJob.class);

    @Inject
    private PostgresConnectionService postgresConnectionService;

    @Inject
    private PostgresIndexCatalogService postgresIndexCatalogService;

    @Override
    public String getTargetDatabase() {
        return "POSTGRES";
    }

    @Override
    public String getWriteOperationType() {
        return "FK_INDEX_CREATION";
    }

    @Override
    public Class<FKIndexCreationResult> getResultType() {
        return FKIndexCreationResult.class;
    }

    @Override
    protected void saveResultsToState(FKIndexCreationResult result) {
        stateService.setFkIndexCreationResult(result);
    }

    @Override
    protected FKIndexCreationResult performWriteOperation(Consumer<JobProgress> progressCallback) throws Exception {
        updateProgress(progressCallback, 0, "Initializing", "Starting PostgreSQL FK index creation process");

        // Get Oracle tables from state
        List<TableMetadata> oracleTables = getOracleTables();
        if (oracleTables.isEmpty()) {
            updateProgress(progressCallback, 100, "No tables to process",
                    "No Oracle tables found in state. Please extract Oracle table metadata first.");
            log.warn("No Oracle tables found in state for FK index creation");
            return new FKIndexCreationResult();
        }

        // Filter valid tables (exclude system schemas)
        List<TableMetadata> validOracleTables = filterValidTables(oracleTables);

        updateProgress(progressCallback, 10, "Analyzing foreign keys",
                String.format("Found %d Oracle tables, %d are valid for FK index creation",
                        oracleTables.size(), validOracleTables.size()));

        FKIndexCreationResult result = new FKIndexCreationResult();

        if (validOracleTables.isEmpty()) {
            updateProgress(progressCallback, 100, "No valid tables",
                    "No valid Oracle tables to create FK indexes in PostgreSQL");
            return result;
        }

        // Extract all foreign key constraints
        List<FKIndexSpec> fkIndexSpecs = extractForeignKeyIndexSpecs(validOracleTables);

        if (fkIndexSpecs.isEmpty()) {
            updateProgress(progressCallback, 100, "No foreign keys found",
                    "No foreign key constraints found - no indexes to create");
            log.info("No foreign key constraints found in Oracle tables");
            return result;
        }

        updateProgress(progressCallback, 20, "Connecting to PostgreSQL",
                String.format("Will create %d FK indexes", fkIndexSpecs.size()));

        try (Connection postgresConnection = postgresConnectionService.getConnection()) {
            updateProgress(progressCallback, 25, "Connected", "Successfully connected to PostgreSQL database");

            // Read what PostgreSQL already has, by signature rather than by name - see the class
            // comment for why an index called something else still counts as coverage.
            PostgresIndexCatalog existingIndexes = postgresIndexCatalogService.load(postgresConnection);

            updateProgress(progressCallback, 30, "Checking existing indexes",
                    String.format("Found %d existing indexes across %d tables",
                            existingIndexes.getIndexCount(), existingIndexes.getTableCount()));

            // Create FK indexes
            createFKIndexes(postgresConnection, fkIndexSpecs, existingIndexes, result, progressCallback);

            updateProgress(progressCallback, 90, "Creation complete",
                    String.format("Created %d indexes, skipped %d existing, %d errors",
                            result.getCreatedCount(), result.getSkippedCount(), result.getErrorCount()));

            return result;

        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed", "FK index creation failed: " + e.getMessage());
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
     * Extracts foreign key index specifications from Oracle tables.
     * Each FK constraint results in one index specification.
     */
    private List<FKIndexSpec> extractForeignKeyIndexSpecs(List<TableMetadata> tables) {
        List<FKIndexSpec> specs = new ArrayList<>();

        for (TableMetadata table : tables) {
            for (ConstraintMetadata constraint : table.getConstraints()) {
                if (constraint.isForeignKey()) {
                    String schema = table.getSchema().toLowerCase();
                    String tableName = table.getTableName().toLowerCase();
                    List<String> columns = constraint.getColumnNames();

                    // Generate index name: idx_fk_{table}_{col1}_{col2}_...
                    String indexName = generateFKIndexName(tableName, columns);

                    specs.add(new FKIndexSpec(schema, tableName, indexName, columns, constraint.getConstraintName()));
                }
            }
        }

        log.info("Extracted {} foreign key index specifications from {} tables", specs.size(), tables.size());
        return specs;
    }

    /**
     * Generates an index name following the convention: idx_fk_{table}_{col1}_{col2}_...
     * Truncates if necessary to stay within PostgreSQL identifier limits (63 characters).
     */
    private String generateFKIndexName(String tableName, List<String> columns) {
        StringBuilder name = new StringBuilder("idx_fk_");
        name.append(tableName);

        for (String column : columns) {
            name.append("_").append(column.toLowerCase());
        }

        String fullName = name.toString();

        // PostgreSQL identifier limit is 63 characters
        if (fullName.length() > 63) {
            // Truncate and add hash suffix to ensure uniqueness
            String hash = Integer.toHexString(fullName.hashCode());
            fullName = fullName.substring(0, 63 - hash.length() - 1) + "_" + hash;
        }

        return PostgresIdentifierNormalizer.normalizeIdentifier(fullName);
    }

    private void createFKIndexes(Connection connection, List<FKIndexSpec> fkIndexSpecs,
                                 PostgresIndexCatalog existingIndexes,
                                 FKIndexCreationResult result, Consumer<JobProgress> progressCallback) throws SQLException {
        int totalIndexes = fkIndexSpecs.size();
        int processedIndexes = 0;

        for (FKIndexSpec spec : fkIndexSpecs) {
            int progressPercentage = 30 + (processedIndexes * 60 / totalIndexes);
            String qualifiedTableName = spec.getQualifiedTableName();

            updateProgress(progressCallback, progressPercentage,
                    String.format("Creating FK index: %s on %s",
                            spec.indexName, qualifiedTableName),
                    String.format("Index %d of %d", processedIndexes + 1, totalIndexes));

            // An existing index covers this FK when the FK columns are a leading prefix of its
            // keys - whatever that index is called, and whether or not Oracle had it.
            if (existingIndexes.coversLookup(qualifiedTableName, spec.columns)) {
                result.addSkippedIndex(qualifiedTableName, spec.indexName, spec.columns,
                        "Foreign key columns already covered by an existing index");
                log.info("FK columns {} on '{}' already covered by an existing index, skipping",
                        spec.columns, qualifiedTableName);
                processedIndexes++;
                continue;
            }

            try {
                createFKIndex(connection, spec);
                result.addCreatedIndex(qualifiedTableName, spec.indexName, spec.columns);
                log.info("Successfully created FK index '{}' on table '{}'",
                        spec.indexName, qualifiedTableName);
            } catch (SQLException e) {
                String sqlStatement = generateIndexSQL(spec);
                String errorMessage = String.format("Failed to create FK index '%s' on table '%s': %s",
                        spec.indexName, qualifiedTableName, e.getMessage());
                result.addError(qualifiedTableName, spec.indexName, spec.columns, errorMessage, sqlStatement);
                log.error("Failed to create FK index '{}' on table '{}': {}",
                        spec.indexName, qualifiedTableName, e.getMessage());
                log.error("Failed SQL statement: {}", sqlStatement);
            }

            processedIndexes++;
        }
    }

    private void createFKIndex(Connection connection, FKIndexSpec spec) throws SQLException {
        String sql = generateIndexSQL(spec);

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.executeUpdate();
        }

        log.debug("Executed SQL: {}", sql);
    }

    private String generateIndexSQL(FKIndexSpec spec) {
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE INDEX ");
        sql.append(spec.indexName);
        sql.append(" ON ");
        sql.append(spec.schema).append(".").append(spec.tableName);
        sql.append(" (");

        // Add columns
        for (int i = 0; i < spec.columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(PostgresIdentifierNormalizer.normalizeIdentifier(spec.columns.get(i)));
        }

        sql.append(")");

        return sql.toString();
    }

    @Override
    protected String generateSummaryMessage(FKIndexCreationResult result) {
        StringBuilder summary = new StringBuilder();
        summary.append(String.format("FK index creation completed: %d created, %d skipped, %d errors",
                result.getCreatedCount(), result.getSkippedCount(), result.getErrorCount()));

        if (result.hasErrors()) {
            summary.append(String.format(" | %d errors occurred", result.getErrorCount()));
        }

        return summary.toString();
    }

    /**
     * Specification for a foreign key index to be created.
     */
    private static class FKIndexSpec {
        final String schema;
        final String tableName;
        final String indexName;
        final List<String> columns;
        final String fkConstraintName;

        FKIndexSpec(String schema, String tableName, String indexName, List<String> columns, String fkConstraintName) {
            this.schema = schema;
            this.tableName = tableName;
            this.indexName = indexName;
            this.columns = new ArrayList<>(columns);
            this.fkConstraintName = fkConstraintName;
        }

        String getQualifiedTableName() {
            return schema + "." + tableName;
        }
    }
}
