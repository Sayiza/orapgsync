package me.christianrobert.orapgsync.synonym.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseExtractionJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.synonym.SynonymMetadata;
import me.christianrobert.orapgsync.core.tools.UserExcluder;
import me.christianrobert.orapgsync.database.service.OracleConnectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Dependent
public class OracleSynonymExtractionJob extends AbstractDatabaseExtractionJob<SynonymMetadata> {

    private static final Logger log = LoggerFactory.getLogger(OracleSynonymExtractionJob.class);

    @Inject
    private OracleConnectionService oracleConnectionService;

    @Override
    public String getSourceDatabase() {
        return "ORACLE";
    }

    @Override
    public String getExtractionType() {
        return "SYNONYM";
    }

    @Override
    public Class<SynonymMetadata> getResultType() {
        return SynonymMetadata.class;
    }

    @Override
    protected void saveResultsToState(List<SynonymMetadata> results) {
        stateService.setOracleSynonyms(results);
    }

    @Override
    protected List<SynonymMetadata> performExtraction(Consumer<JobProgress> progressCallback) throws Exception {
        updateProgress(progressCallback, 0, "Initializing", "Starting Oracle synonym extraction");

        if (!oracleConnectionService.isConfigured()) {
            updateProgress(progressCallback, -1, "Failed", "Oracle connection not configured");
            throw new IllegalStateException("Oracle connection not configured");
        }

        // Determine which schemas to process based on configuration
        List<String> schemasToProcess = determineSchemasToProcess(progressCallback);

        if (schemasToProcess.isEmpty()) {
            updateProgress(progressCallback, 100, "No schemas to process",
                          "No schemas available for synonym extraction based on current configuration");
            return new ArrayList<>();
        }

        // Always include PUBLIC schema for PUBLIC synonyms (globally accessible)
        if (!schemasToProcess.contains("public")) {
            schemasToProcess.add("public");
            log.info("Added PUBLIC schema for global synonym resolution");
        }

        updateProgress(progressCallback, 5, "Connecting to Oracle", "Establishing database connection");

        try (Connection connection = oracleConnectionService.getConnection()) {
            updateProgress(progressCallback, 10, "Connected", "Successfully connected to Oracle database");

            updateProgress(progressCallback, 15, "Building query",
                          String.format("Processing %d schema(s) for synonyms", schemasToProcess.size()));

            // Build SQL with IN clause for multiple schemas
            String schemaPlaceholders = schemasToProcess.stream()
                .map(s -> "?")
                .collect(java.util.stream.Collectors.joining(", "));

            String sql = """
                SELECT owner, synonym_name, table_owner, table_name, db_link
                FROM all_synonyms
                WHERE LOWER(owner) IN (%s)
                  AND table_owner IS NOT NULL
                ORDER BY owner, synonym_name
                """.formatted(schemaPlaceholders);

            updateProgress(progressCallback, 20, "Executing query", "Fetching synonyms from Oracle");

            Map<String, List<SynonymMetadata>> synonymsBySchema = new HashMap<>();
            int totalCount = 0;

            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                // Set schema parameters (using lowercase as already normalized)
                for (int i = 0; i < schemasToProcess.size(); i++) {
                    stmt.setString(i + 1, schemasToProcess.get(i));
                }

                try (ResultSet rs = stmt.executeQuery()) {
                    updateProgress(progressCallback, 30, "Processing results", "Extracting synonym metadata");

                    while (rs.next()) {
                        String owner = rs.getString("owner");
                        String synonymName = rs.getString("synonym_name");
                        String tableOwner = rs.getString("table_owner");
                        String tableName = rs.getString("table_name");
                        String dbLink = rs.getString("db_link");

                        // Apply UserExcluder logic for consistency
                        if (UserExcluder.is2BeExclueded(owner, "SYNONYM")) {
                            log.debug("Excluding Oracle schema for synonyms: {}", owner);
                            continue;
                        }

                        // Normalize all identifiers to lowercase
                        String normalizedOwner = owner.toLowerCase();
                        String normalizedSynonymName = synonymName.toLowerCase();
                        String normalizedTableOwner = tableOwner.toLowerCase();
                        String normalizedTableName = tableName.toLowerCase();
                        String normalizedDbLink = (dbLink != null) ? dbLink.toLowerCase() : null;

                        SynonymMetadata synonym = new SynonymMetadata(
                            normalizedOwner,
                            normalizedSynonymName,
                            normalizedTableOwner,
                            normalizedTableName,
                            normalizedDbLink
                        );

                        synonymsBySchema.computeIfAbsent(normalizedOwner, k -> new ArrayList<>()).add(synonym);
                        totalCount++;

                        if (totalCount % 20 == 0) {
                            int progress = 30 + Math.min(50, (totalCount * 50 / Math.max(1, totalCount + 100)));
                            updateProgress(progressCallback, progress, "Processing synonyms",
                                String.format("Processed %d synonyms across %d schemas",
                                    totalCount, synonymsBySchema.size()));
                        }
                    }

                    updateProgress(progressCallback, 80, "Processing completed",
                        String.format("Found %d synonyms across %d schemas", totalCount, synonymsBySchema.size()));
                }
            }

            // Flatten to single list
            List<SynonymMetadata> allSynonyms = synonymsBySchema.values().stream()
                    .flatMap(List::stream)
                    .toList();

            updateProgress(progressCallback, 90, "Saving to state", "Storing synonym metadata");

            return allSynonyms;

        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed", "Synonym extraction failed: " + e.getMessage());
            throw e;
        }
    }

    @Override
    protected String generateSummaryMessage(List<SynonymMetadata> results) {
        Map<String, Integer> schemaSynonymCounts = new HashMap<>();
        int remoteCount = 0;

        for (SynonymMetadata synonym : results) {
            String schema = synonym.getOwner();
            schemaSynonymCounts.put(schema, schemaSynonymCounts.getOrDefault(schema, 0) + 1);
            if (synonym.isRemote()) {
                remoteCount++;
            }
        }

        String message = String.format("Extraction completed: %d synonyms from %d schemas",
                           results.size(), schemaSynonymCounts.size());

        if (remoteCount > 0) {
            message += String.format(" (%d remote synonyms)", remoteCount);
        }

        return message;
    }
}
