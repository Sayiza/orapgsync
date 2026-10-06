package me.christianrobert.orapgsync.index.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseExtractionJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.index.IndexMetadata;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.database.service.PostgresIndexCatalogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Reads the indexes that currently exist in PostgreSQL, so the migrated state can be inspected
 * the same way as every other object type.
 *
 * <p>Constraint-backed indexes are excluded, matching what the Oracle side reports. PostgreSQL
 * creates one for every primary key and unique constraint, so including them would make the two
 * counts incomparable and suggest indexes were created that this step never touched.</p>
 *
 * <p>Schemas are discovered from the database rather than from state, so this works before any
 * extraction has been run.</p>
 */
@Dependent
public class PostgresIndexExtractionJob extends AbstractDatabaseExtractionJob<IndexMetadata> {

    private static final Logger log = LoggerFactory.getLogger(PostgresIndexExtractionJob.class);

    @Inject
    private PostgresConnectionService postgresConnectionService;

    @Inject
    private PostgresIndexCatalogService postgresIndexCatalogService;

    @Override
    public String getSourceDatabase() {
        return "POSTGRES";
    }

    @Override
    public String getExtractionType() {
        return "INDEX";
    }

    @Override
    public Class<IndexMetadata> getResultType() {
        return IndexMetadata.class;
    }

    @Override
    protected void saveResultsToState(List<IndexMetadata> results) {
        stateService.setPostgresIndexMetadata(results);
    }

    @Override
    protected List<String> getAvailableSchemas() {
        // Query the database directly: indexes can be inspected before anything has been extracted
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
        return schemas;
    }

    @Override
    protected List<IndexMetadata> performExtraction(Consumer<JobProgress> progressCallback) throws Exception {
        updateProgress(progressCallback, 0, "Initializing", "Reading existing PostgreSQL indexes");

        List<String> schemasToProcess = filterValidSchemas(determineSchemasToProcess(progressCallback));
        if (schemasToProcess.isEmpty()) {
            updateProgress(progressCallback, 100, "No schemas to process",
                    "No PostgreSQL schemas available for index extraction");
            return new ArrayList<>();
        }

        updateProgress(progressCallback, 20, "Connecting to PostgreSQL", "Establishing database connection");

        List<IndexMetadata> indexes;
        try (Connection connection = postgresConnectionService.getConnection()) {
            updateProgress(progressCallback, 40, "Reading indexes", "Querying PostgreSQL system catalogs");
            indexes = postgresIndexCatalogService.readIndexes(connection, true);
        }

        // Keep only the schemas in scope for this migration
        List<IndexMetadata> inScope = new ArrayList<>();
        for (IndexMetadata index : indexes) {
            if (index.getSchema() != null && schemasToProcess.contains(index.getSchema().toLowerCase())) {
                inScope.add(index);
            }
        }

        // Deterministic ordering regardless of execution plan (project convention)
        inScope.sort(Comparator
                .comparing(IndexMetadata::getSchema, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(IndexMetadata::getTableName, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(IndexMetadata::getIndexName, Comparator.nullsLast(Comparator.naturalOrder())));

        log.info("PostgreSQL index extraction completed: {} non-constraint indexes across {} schemas",
                inScope.size(), schemasToProcess.size());

        return inScope;
    }

    @Override
    protected String generateSummaryMessage(List<IndexMetadata> results) {
        long unique = results.stream().filter(IndexMetadata::isUnique).count();

        return String.format("PostgreSQL index extraction completed: %d non-constraint indexes (%d unique)",
                results.size(), unique);
    }
}
