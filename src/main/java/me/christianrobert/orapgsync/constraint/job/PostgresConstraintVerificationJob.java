package me.christianrobert.orapgsync.constraint.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseExtractionJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.table.ConstraintMetadata;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * PostgreSQL Constraint Verification Job.
 *
 * This job extracts constraint metadata from the actual PostgreSQL database
 * to verify what constraints have been successfully created.
 *
 * Unlike OracleConstraintSourceStateJob (which reads from state),
 * this job queries the live PostgreSQL database to get the actual constraint state.
 *
 * The job:
 * 1. Queries PostgreSQL system catalogs (pg_constraint, pg_class, pg_namespace)
 * 2. Extracts all constraint metadata (PK, FK, Unique, Check)
 * 3. Filters out system schemas
 * 4. Returns the list for frontend display and comparison
 */
@Dependent
public class PostgresConstraintVerificationJob extends AbstractDatabaseExtractionJob<ConstraintMetadata> {

    private static final Logger log = LoggerFactory.getLogger(PostgresConstraintVerificationJob.class);

    @Inject
    private PostgresConnectionService postgresConnectionService;

    @Override
    public String getSourceDatabase() {
        return "POSTGRES";
    }

    @Override
    public String getExtractionType() {
        return "CONSTRAINT_VERIFICATION";
    }

    @Override
    public Class<ConstraintMetadata> getResultType() {
        return ConstraintMetadata.class;
    }

    @Override
    protected void saveResultsToState(List<ConstraintMetadata> results) {
        // Do NOT save to state - this is display-only
        // Constraints are already stored as part of table metadata
    }

    @Override
    protected List<ConstraintMetadata> performExtraction(Consumer<JobProgress> progressCallback) throws Exception {
        updateProgress(progressCallback, 0, "Initializing",
                "Starting PostgreSQL constraint verification");

        updateProgress(progressCallback, 10, "Connecting to PostgreSQL",
                "Establishing database connection");

        try (Connection postgresConnection = postgresConnectionService.getConnection()) {
            updateProgress(progressCallback, 20, "Connected",
                    "Successfully connected to PostgreSQL database");

            List<ConstraintMetadata> allConstraints = new ArrayList<>();

            // Extract Primary Keys
            updateProgress(progressCallback, 30, "Extracting primary keys",
                    "Querying PostgreSQL for primary key constraints");
            allConstraints.addAll(extractPrimaryKeys(postgresConnection));

            // Extract Foreign Keys
            updateProgress(progressCallback, 50, "Extracting foreign keys",
                    "Querying PostgreSQL for foreign key constraints");
            allConstraints.addAll(extractForeignKeys(postgresConnection));

            // Extract Unique Constraints
            updateProgress(progressCallback, 70, "Extracting unique constraints",
                    "Querying PostgreSQL for unique constraints");
            allConstraints.addAll(extractUniqueConstraints(postgresConnection));

            // Extract Check Constraints
            updateProgress(progressCallback, 85, "Extracting check constraints",
                    "Querying PostgreSQL for check constraints");
            allConstraints.addAll(extractCheckConstraints(postgresConnection));

            // Count by type for summary
            long pkCount = allConstraints.stream().filter(ConstraintMetadata::isPrimaryKey).count();
            long fkCount = allConstraints.stream().filter(ConstraintMetadata::isForeignKey).count();
            long uniqueCount = allConstraints.stream().filter(ConstraintMetadata::isUniqueConstraint).count();
            long checkCount = allConstraints.stream().filter(ConstraintMetadata::isCheckConstraint).count();

            log.info("Verified {} constraints in PostgreSQL: {} PK, {} FK, {} Unique, {} Check",
                    allConstraints.size(), pkCount, fkCount, uniqueCount, checkCount);

            updateProgress(progressCallback, 100, "Complete",
                    String.format("Verified %d constraints: %d PK, %d FK, %d Unique, %d Check",
                            allConstraints.size(), pkCount, fkCount, uniqueCount, checkCount));

            return allConstraints;

        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed",
                    "PostgreSQL constraint verification failed: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Extracts Primary Key constraints from PostgreSQL.
     */
    private List<ConstraintMetadata> extractPrimaryKeys(Connection connection) throws Exception {
        List<ConstraintMetadata> constraints = new ArrayList<>();

        String sql = """
            SELECT
                n.nspname AS schema_name,
                c.relname AS table_name,
                con.conname AS constraint_name,
                pg_get_constraintdef(con.oid) AS constraint_def,
                array_to_string(array_agg(a.attname ORDER BY u.attposition), ',') AS columns
            FROM pg_constraint con
            JOIN pg_class c ON con.conrelid = c.oid
            JOIN pg_namespace n ON c.relnamespace = n.oid
            JOIN LATERAL unnest(con.conkey) WITH ORDINALITY AS u(attnum, attposition) ON true
            JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = u.attnum
            WHERE con.contype = 'p'
              AND n.nspname NOT IN ('information_schema', 'pg_catalog', 'pg_toast')
            GROUP BY n.nspname, c.relname, con.conname, con.oid
            ORDER BY n.nspname, c.relname, con.conname
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String schema = rs.getString("schema_name");
                String tableName = rs.getString("table_name");
                String constraintName = rs.getString("constraint_name");
                String columns = rs.getString("columns");

                // Filter valid schemas (exclude system schemas)
                if (filterValidSchemas(List.of(schema)).isEmpty()) {
                    continue;
                }

                ConstraintMetadata constraint = new ConstraintMetadata(constraintName, "P");
                constraint.setSchema(schema);
                constraint.setTableName(tableName);
                // Parse comma-separated columns into list
                if (columns != null && !columns.isEmpty()) {
                    for (String col : columns.split(",")) {
                        constraint.addColumnName(col.trim());
                    }
                }

                constraints.add(constraint);
            }
        }

        log.debug("Extracted {} primary key constraints from PostgreSQL", constraints.size());
        return constraints;
    }

    /**
     * Extracts Foreign Key constraints from PostgreSQL.
     */
    private List<ConstraintMetadata> extractForeignKeys(Connection connection) throws Exception {
        List<ConstraintMetadata> constraints = new ArrayList<>();

        String sql = """
            SELECT
                n.nspname AS schema_name,
                c.relname AS table_name,
                con.conname AS constraint_name,
                rn.nspname AS referenced_schema,
                rc.relname AS referenced_table,
                array_to_string(array_agg(a.attname ORDER BY u.attposition), ',') AS columns,
                array_to_string(array_agg(ra.attname ORDER BY u.attposition), ',') AS referenced_columns,
                CASE con.confdeltype
                    WHEN 'a' THEN 'NO ACTION'
                    WHEN 'r' THEN 'RESTRICT'
                    WHEN 'c' THEN 'CASCADE'
                    WHEN 'n' THEN 'SET NULL'
                    WHEN 'd' THEN 'SET DEFAULT'
                    ELSE 'NO ACTION'
                END AS delete_rule
            FROM pg_constraint con
            JOIN pg_class c ON con.conrelid = c.oid
            JOIN pg_namespace n ON c.relnamespace = n.oid
            JOIN pg_class rc ON con.confrelid = rc.oid
            JOIN pg_namespace rn ON rc.relnamespace = rn.oid
            JOIN LATERAL unnest(con.conkey, con.confkey) WITH ORDINALITY AS u(attnum, refattnum, attposition) ON true
            JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = u.attnum
            JOIN pg_attribute ra ON ra.attrelid = rc.oid AND ra.attnum = u.refattnum
            WHERE con.contype = 'f'
              AND n.nspname NOT IN ('information_schema', 'pg_catalog', 'pg_toast')
            GROUP BY n.nspname, c.relname, con.conname, rn.nspname, rc.relname, con.confdeltype
            ORDER BY n.nspname, c.relname, con.conname
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String schema = rs.getString("schema_name");
                String tableName = rs.getString("table_name");
                String constraintName = rs.getString("constraint_name");
                String referencedSchema = rs.getString("referenced_schema");
                String referencedTable = rs.getString("referenced_table");
                String columns = rs.getString("columns");
                String referencedColumns = rs.getString("referenced_columns");
                String deleteRule = rs.getString("delete_rule");

                // Filter valid schemas (exclude system schemas)
                if (filterValidSchemas(List.of(schema)).isEmpty()) {
                    continue;
                }

                ConstraintMetadata constraint = new ConstraintMetadata(constraintName, "R",
                                                                               referencedSchema, referencedTable);
                constraint.setSchema(schema);
                constraint.setTableName(tableName);
                constraint.setDeleteRule(deleteRule);

                // Parse comma-separated columns into list
                if (columns != null && !columns.isEmpty()) {
                    for (String col : columns.split(",")) {
                        constraint.addColumnName(col.trim());
                    }
                }

                // Parse comma-separated referenced columns into list
                if (referencedColumns != null && !referencedColumns.isEmpty()) {
                    for (String col : referencedColumns.split(",")) {
                        constraint.addReferencedColumnName(col.trim());
                    }
                }

                constraints.add(constraint);
            }
        }

        log.debug("Extracted {} foreign key constraints from PostgreSQL", constraints.size());
        return constraints;
    }

    /**
     * Extracts Unique constraints from PostgreSQL.
     */
    private List<ConstraintMetadata> extractUniqueConstraints(Connection connection) throws Exception {
        List<ConstraintMetadata> constraints = new ArrayList<>();

        String sql = """
            SELECT
                n.nspname AS schema_name,
                c.relname AS table_name,
                con.conname AS constraint_name,
                array_to_string(array_agg(a.attname ORDER BY u.attposition), ',') AS columns
            FROM pg_constraint con
            JOIN pg_class c ON con.conrelid = c.oid
            JOIN pg_namespace n ON c.relnamespace = n.oid
            JOIN LATERAL unnest(con.conkey) WITH ORDINALITY AS u(attnum, attposition) ON true
            JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = u.attnum
            WHERE con.contype = 'u'
              AND n.nspname NOT IN ('information_schema', 'pg_catalog', 'pg_toast')
            GROUP BY n.nspname, c.relname, con.conname
            ORDER BY n.nspname, c.relname, con.conname
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String schema = rs.getString("schema_name");
                String tableName = rs.getString("table_name");
                String constraintName = rs.getString("constraint_name");
                String columns = rs.getString("columns");

                // Filter valid schemas (exclude system schemas)
                if (filterValidSchemas(List.of(schema)).isEmpty()) {
                    continue;
                }

                ConstraintMetadata constraint = new ConstraintMetadata(constraintName, "U");
                constraint.setSchema(schema);
                constraint.setTableName(tableName);

                // Parse comma-separated columns into list
                if (columns != null && !columns.isEmpty()) {
                    for (String col : columns.split(",")) {
                        constraint.addColumnName(col.trim());
                    }
                }

                constraints.add(constraint);
            }
        }

        log.debug("Extracted {} unique constraints from PostgreSQL", constraints.size());
        return constraints;
    }

    /**
     * Extracts Check constraints from PostgreSQL.
     */
    private List<ConstraintMetadata> extractCheckConstraints(Connection connection) throws Exception {
        List<ConstraintMetadata> constraints = new ArrayList<>();

        String sql = """
            SELECT
                n.nspname AS schema_name,
                c.relname AS table_name,
                con.conname AS constraint_name,
                pg_get_constraintdef(con.oid) AS search_condition
            FROM pg_constraint con
            JOIN pg_class c ON con.conrelid = c.oid
            JOIN pg_namespace n ON c.relnamespace = n.oid
            WHERE con.contype = 'c'
              AND n.nspname NOT IN ('information_schema', 'pg_catalog', 'pg_toast')
            ORDER BY n.nspname, c.relname, con.conname
            """;

        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String schema = rs.getString("schema_name");
                String tableName = rs.getString("table_name");
                String constraintName = rs.getString("constraint_name");
                String searchCondition = rs.getString("search_condition");

                // Filter valid schemas (exclude system schemas)
                if (filterValidSchemas(List.of(schema)).isEmpty()) {
                    continue;
                }

                // Remove "CHECK " prefix from search condition if present
                if (searchCondition != null && searchCondition.startsWith("CHECK (")) {
                    searchCondition = searchCondition.substring(7, searchCondition.length() - 1);
                }

                ConstraintMetadata constraint = new ConstraintMetadata(constraintName, "C");
                constraint.setSchema(schema);
                constraint.setTableName(tableName);
                constraint.setCheckCondition(searchCondition);

                constraints.add(constraint);
            }
        }

        log.debug("Extracted {} check constraints from PostgreSQL", constraints.size());
        return constraints;
    }

    @Override
    protected String generateSummaryMessage(List<ConstraintMetadata> result) {
        long pkCount = result.stream().filter(ConstraintMetadata::isPrimaryKey).count();
        long fkCount = result.stream().filter(ConstraintMetadata::isForeignKey).count();
        long uniqueCount = result.stream().filter(ConstraintMetadata::isUniqueConstraint).count();
        long checkCount = result.stream().filter(ConstraintMetadata::isCheckConstraint).count();

        return String.format("Verified %d PostgreSQL constraints: %d PK, %d FK, %d Unique, %d Check",
                result.size(), pkCount, fkCount, uniqueCount, checkCount);
    }
}
