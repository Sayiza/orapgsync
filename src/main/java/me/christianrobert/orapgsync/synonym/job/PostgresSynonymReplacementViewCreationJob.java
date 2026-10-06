package me.christianrobert.orapgsync.synonym.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseWriteJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.synonym.SynonymMetadata;
import me.christianrobert.orapgsync.core.job.model.synonym.SynonymReplacementViewCreationResult;
import me.christianrobert.orapgsync.core.job.model.table.TableMetadata;
import me.christianrobert.orapgsync.core.job.model.view.ViewMetadata;
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
 * Creates synonym replacement views in PostgreSQL database.
 *
 * <p>Oracle synonyms provide alternative names for database objects. PostgreSQL doesn't have
 * synonyms, so external applications (Java/JDBC) that reference objects through synonyms
 * will fail after migration.</p>
 *
 * <p>This job creates PostgreSQL views that emulate synonym behavior:
 * CREATE VIEW synonym_schema.synonym_name AS SELECT * FROM target_schema.target_name</p>
 *
 * <p>These views are automatically updatable in PostgreSQL (simple single-table views),
 * so INSERT/UPDATE/DELETE operations through the view work correctly.</p>
 *
 * <h3>Processing Rules</h3>
 * <ul>
 *   <li>Only non-PUBLIC synonyms are processed (PUBLIC synonyms skipped)</li>
 *   <li>Synonym chains are resolved to the final target object</li>
 *   <li>Only synonyms pointing to migrated tables or views are processed</li>
 *   <li>Remote synonyms (with database links) are skipped</li>
 * </ul>
 */
@Dependent
public class PostgresSynonymReplacementViewCreationJob extends AbstractDatabaseWriteJob<SynonymReplacementViewCreationResult> {

    private static final Logger log = LoggerFactory.getLogger(PostgresSynonymReplacementViewCreationJob.class);

    @Inject
    private PostgresConnectionService postgresConnectionService;

    @Override
    public String getTargetDatabase() {
        return "POSTGRES";
    }

    @Override
    public String getWriteOperationType() {
        return "SYNONYM_REPLACEMENT_VIEW_CREATION";
    }

    @Override
    public Class<SynonymReplacementViewCreationResult> getResultType() {
        return SynonymReplacementViewCreationResult.class;
    }

    @Override
    protected void saveResultsToState(SynonymReplacementViewCreationResult result) {
        stateService.setSynonymReplacementViewCreationResult(result);
    }

    @Override
    protected SynonymReplacementViewCreationResult performWriteOperation(Consumer<JobProgress> progressCallback) throws Exception {
        updateProgress(progressCallback, 0, "Initializing",
                "Starting PostgreSQL synonym replacement view creation process");

        SynonymReplacementViewCreationResult result = new SynonymReplacementViewCreationResult();

        // Get all synonyms from state
        Map<String, Map<String, SynonymMetadata>> synonymsByOwner = stateService.getOracleSynonymsByOwnerAndName();
        if (synonymsByOwner.isEmpty()) {
            updateProgress(progressCallback, 100, "No synonyms to process",
                    "No Oracle synonyms found in state. Please extract Oracle synonyms first.");
            log.warn("No Oracle synonyms found in state for synonym replacement view creation");
            return result;
        }

        // Collect all non-PUBLIC synonyms from valid schemas
        List<SynonymMetadata> synonymsToProcess = collectSynonymsToProcess(synonymsByOwner);

        updateProgress(progressCallback, 10, "Analyzing synonyms",
                String.format("Found %d non-PUBLIC synonyms to process", synonymsToProcess.size()));

        if (synonymsToProcess.isEmpty()) {
            updateProgress(progressCallback, 100, "No valid synonyms",
                    "No non-PUBLIC synonyms found in configured schemas");
            return result;
        }

        // Build index of existing tables and views in PostgreSQL
        updateProgress(progressCallback, 15, "Building target index",
                "Indexing migrated tables and views for target validation");

        Set<String> migratedObjects = buildMigratedObjectsIndex();
        log.info("Built index of {} migrated objects (tables + views)", migratedObjects.size());

        updateProgress(progressCallback, 20, "Connecting to PostgreSQL",
                "Establishing database connection");

        try (Connection postgresConnection = postgresConnectionService.getConnection()) {
            updateProgress(progressCallback, 25, "Connected",
                    "Successfully connected to PostgreSQL database");

            // Get existing PostgreSQL views to avoid conflicts
            Set<String> existingViews = getExistingPostgresViews(postgresConnection);

            updateProgress(progressCallback, 30, "Checking existing views",
                    String.format("Found %d existing PostgreSQL views", existingViews.size()));

            // Process each synonym
            int totalSynonyms = synonymsToProcess.size();
            int processedSynonyms = 0;

            for (SynonymMetadata synonym : synonymsToProcess) {
                int progressPercentage = 30 + (processedSynonyms * 60 / totalSynonyms);
                String qualifiedSynonym = synonym.getQualifiedSynonym().toLowerCase();

                updateProgress(progressCallback, progressPercentage,
                        String.format("Processing synonym: %s", qualifiedSynonym),
                        String.format("Synonym %d of %d", processedSynonyms + 1, totalSynonyms));

                processSynonym(postgresConnection, synonym, existingViews, migratedObjects, result);
                processedSynonyms++;
            }

            updateProgress(progressCallback, 90, "Creation complete",
                    String.format("Created %d synonym replacement views, skipped %d, %d errors",
                            result.getCreatedCount(), result.getSkippedCount(), result.getErrorCount()));

            return result;

        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed",
                    "Synonym replacement view creation failed: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Collects non-PUBLIC synonyms from valid schemas.
     */
    private List<SynonymMetadata> collectSynonymsToProcess(Map<String, Map<String, SynonymMetadata>> synonymsByOwner) {
        List<SynonymMetadata> result = new ArrayList<>();

        for (Map.Entry<String, Map<String, SynonymMetadata>> entry : synonymsByOwner.entrySet()) {
            String owner = entry.getKey();

            // Skip PUBLIC synonyms
            if ("public".equalsIgnoreCase(owner)) {
                log.debug("Skipping PUBLIC schema synonyms (not processed per configuration)");
                continue;
            }

            // Check if schema is in valid schemas list
            if (filterValidSchemas(List.of(owner)).isEmpty()) {
                log.debug("Skipping synonyms from excluded schema: {}", owner);
                continue;
            }

            result.addAll(entry.getValue().values());
        }

        // Sort by owner and synonym name for deterministic ordering
        result.sort(Comparator.comparing(SynonymMetadata::getOwner)
                .thenComparing(SynonymMetadata::getSynonymName));

        log.info("Collected {} non-PUBLIC synonyms from valid schemas", result.size());
        return result;
    }

    /**
     * Builds an index of all migrated tables and views for target validation.
     */
    private Set<String> buildMigratedObjectsIndex() {
        Set<String> migratedObjects = new HashSet<>();

        // Add all Oracle tables (they should be migrated to PostgreSQL)
        for (TableMetadata table : stateService.getOracleTableMetadata()) {
            String qualifiedName = (table.getSchema() + "." + table.getTableName()).toLowerCase();
            migratedObjects.add(qualifiedName);
        }

        // Add all Oracle views (they should be migrated to PostgreSQL)
        for (ViewMetadata view : stateService.getOracleViewMetadata()) {
            String qualifiedName = (view.getSchema() + "." + view.getViewName()).toLowerCase();
            migratedObjects.add(qualifiedName);
        }

        return migratedObjects;
    }

    /**
     * Gets existing PostgreSQL views to avoid creating duplicates.
     */
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

    /**
     * Processes a single synonym: resolves chain, validates target, creates view.
     */
    private void processSynonym(Connection connection, SynonymMetadata synonym,
                                 Set<String> existingViews, Set<String> migratedObjects,
                                 SynonymReplacementViewCreationResult result) {

        String qualifiedSynonym = synonym.getQualifiedSynonym().toLowerCase();

        // Skip remote synonyms (with database links)
        if (synonym.isRemote()) {
            result.addSkippedSynonym(qualifiedSynonym, "remote synonym with DB link: " + synonym.getDbLink());
            log.debug("Skipping remote synonym: {} (DB link: {})", qualifiedSynonym, synonym.getDbLink());
            return;
        }

        // Resolve synonym chain to final target
        String finalTarget = resolveToFinalTarget(synonym);
        if (finalTarget == null) {
            result.addSkippedSynonym(qualifiedSynonym, "circular synonym chain detected");
            log.warn("Circular synonym chain detected for: {}", qualifiedSynonym);
            return;
        }

        // Check if target exists in migrated objects
        if (!migratedObjects.contains(finalTarget.toLowerCase())) {
            result.addSkippedSynonym(qualifiedSynonym, "target not migrated: " + finalTarget);
            log.debug("Skipping synonym {} - target {} not found in migrated objects", qualifiedSynonym, finalTarget);
            return;
        }

        // Check if view already exists
        if (existingViews.contains(qualifiedSynonym)) {
            result.addSkippedSynonym(qualifiedSynonym, "view already exists");
            log.debug("Synonym replacement view already exists: {}", qualifiedSynonym);
            return;
        }

        // Create the synonym replacement view
        String sql = generateCreateViewSQL(synonym, finalTarget);

        try {
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.executeUpdate();
            }

            result.addCreatedView(qualifiedSynonym, finalTarget);
            existingViews.add(qualifiedSynonym); // Track as existing for subsequent synonyms
            log.debug("Created synonym replacement view: {} -> {}", qualifiedSynonym, finalTarget);

        } catch (SQLException e) {
            String errorMessage = String.format("Failed to create synonym replacement view '%s': %s",
                    qualifiedSynonym, e.getMessage());
            result.addError(qualifiedSynonym, errorMessage, sql);
            log.error("Failed to create synonym replacement view '{}': {}", qualifiedSynonym, e.getMessage());
            log.error("Failed SQL: {}", sql);
        }
    }

    /**
     * Resolves a synonym chain to the final target object.
     * Handles chains like: synA -> synB -> synC -> actualTable
     *
     * @param synonym The starting synonym
     * @return The final target as "schema.name", or null if circular chain detected
     */
    private String resolveToFinalTarget(SynonymMetadata synonym) {
        Set<String> visited = new HashSet<>();
        String currentSchema = synonym.getTableOwner().toLowerCase();
        String currentName = synonym.getTableName().toLowerCase();
        String current = currentSchema + "." + currentName;

        while (true) {
            if (visited.contains(current)) {
                log.warn("Circular synonym chain detected at: {}", current);
                return null; // Circular reference
            }
            visited.add(current);

            // Try to resolve as synonym
            String resolved = stateService.resolveSynonym(currentSchema, currentName);

            if (resolved == null) {
                // Not a synonym - this is the final target
                return current;
            }

            // Parse resolved target and continue chain
            String[] parts = resolved.toLowerCase().split("\\.", 2);
            if (parts.length != 2) {
                log.warn("Invalid resolved synonym format: {}", resolved);
                return current;
            }
            currentSchema = parts[0];
            currentName = parts[1];
            current = resolved.toLowerCase();
        }
    }

    /**
     * Generates SQL for creating a synonym replacement view.
     * Pattern: CREATE VIEW schema.synonym AS SELECT * FROM target_schema.target_name
     */
    private String generateCreateViewSQL(SynonymMetadata synonym, String finalTarget) {
        String viewSchema = synonym.getOwner().toLowerCase();
        String viewName = synonym.getSynonymName().toLowerCase();

        return String.format("CREATE VIEW %s.%s AS SELECT * FROM %s",
                viewSchema, viewName, finalTarget);
    }

    @Override
    protected String generateSummaryMessage(SynonymReplacementViewCreationResult result) {
        StringBuilder summary = new StringBuilder();
        summary.append(String.format("Synonym replacement view creation completed: %d created, %d skipped, %d errors",
                result.getCreatedCount(), result.getSkippedCount(), result.getErrorCount()));

        if (result.getCreatedCount() > 0 && result.getCreatedCount() <= 10) {
            summary.append(String.format(" | Created: %s",
                    String.join(", ", result.getCreatedViews())));
        } else if (result.getCreatedCount() > 10) {
            summary.append(String.format(" | Created %d views (too many to list)", result.getCreatedCount()));
        }

        if (result.hasErrors()) {
            summary.append(String.format(" | %d errors occurred", result.getErrorCount()));
        }

        return summary.toString();
    }
}
