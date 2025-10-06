package me.christianrobert.orapgsync.core.job;

import jakarta.inject.Inject;
import me.christianrobert.orapgsync.config.service.ConfigService;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.service.StateService;
import me.christianrobert.orapgsync.core.tools.UserExcluder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Abstract base class for database extraction jobs providing common functionality.
 * This class handles schema determination, progress tracking, and error handling.
 *
 * @param <T> The type of metadata being extracted
 */
public abstract class AbstractDatabaseExtractionJob<T> implements DatabaseExtractionJob<T> {

    private static final Logger log = LoggerFactory.getLogger(AbstractDatabaseExtractionJob.class);

    protected final String jobId;

    @Inject
    protected ConfigService configService;

    @Inject
    protected StateService stateService;

    protected AbstractDatabaseExtractionJob() {
        this.jobId = generateJobId();
    }

    protected String generateJobId() {
        return getSourceDatabase().toLowerCase() + "-" +
               getExtractionType().toLowerCase().replace("_", "-") + "-" +
               UUID.randomUUID().toString();
    }

    @Override
    public String getJobId() {
        return jobId;
    }

    @Override
    public String getJobType() {
        return getJobTypeIdentifier();
    }

    @Override
    public String getDescription() {
        return String.format("Extract %s from %s database and store in global state",
                            getExtractionType().replace("_", " ").toLowerCase(),
                            getSourceDatabase());
    }

    @Override
    public CompletableFuture<List<T>> execute(Consumer<JobProgress> progressCallback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return performExtractionWithStateSaving(progressCallback);
            } catch (Exception e) {
                log.error("{} extraction failed", getExtractionType(), e);
                throw new RuntimeException(String.format("%s extraction failed: %s",
                                                       getExtractionType(), e.getMessage()), e);
            }
        });
    }

    /**
     * Template method for performing the actual extraction.
     * Subclasses must implement this method to provide extraction logic.
     */
    protected abstract List<T> performExtraction(Consumer<JobProgress> progressCallback) throws Exception;

    /**
     * Determines which schemas to process based on configuration.
     * This method provides common logic for schema determination.
     * Supports multiple schemas via comma-separated list in "do.only-test-schema" config.
     */
    protected List<String> determineSchemasToProcess(Consumer<JobProgress> progressCallback) {
        updateProgress(progressCallback, 0, "Checking configuration",
                      "Determining schemas to process based on configuration settings");

        boolean doAllSchemas = Boolean.TRUE.equals(configService.getConfigValueAsBoolean("do.all-schemas"));
        List<String> testSchemas = configService.getConfigValueAsStringList("do.only-test-schema");

        if (doAllSchemas) {
            updateProgress(progressCallback, 0, "Using all schemas",
                          "Configuration set to process all available schemas");
            log.info("Processing all schemas based on configuration (do.all-schemas=true)");
            return getAvailableSchemas();
        } else {
            if (testSchemas.isEmpty()) {
                updateProgress(progressCallback, 100, "Configuration error",
                              "Test schema(s) not configured but do.all-schemas is false");
                log.error("Test schema(s) not configured but do.all-schemas is false");
                throw new IllegalStateException("Test schema(s) not configured but do.all-schemas is false");
            }

            // Normalize all test schemas to lowercase
            List<String> normalizedTestSchemas = testSchemas.stream()
                    .map(String::toLowerCase)
                    .collect(java.util.stream.Collectors.toList());

            List<String> availableSchemas = getAvailableSchemas();
            List<String> validTestSchemas = new ArrayList<>();
            List<String> missingSchemas = new ArrayList<>();

            // Check which configured schemas are actually available
            for (String testSchema : normalizedTestSchemas) {
                if (availableSchemas.contains(testSchema)) {
                    validTestSchemas.add(testSchema);
                } else {
                    missingSchemas.add(testSchema);
                }
            }

            if (!missingSchemas.isEmpty()) {
                log.warn("Configured test schema(s) not found in available schemas: {}. Available: {}",
                        missingSchemas, availableSchemas);
            }

            if (validTestSchemas.isEmpty()) {
                updateProgress(progressCallback, 100, "No valid schemas",
                              "None of the configured test schemas were found in available schemas");
                log.warn("None of the configured test schemas {} found in available schemas: {}",
                        normalizedTestSchemas, availableSchemas);
                return new ArrayList<>();
            }

            String schemaList = String.join(", ", validTestSchemas);
            updateProgress(progressCallback, 0, "Using test schema(s)",
                          "Configuration set to process schema(s): " + schemaList);
            log.info("Processing test schema(s) '{}' based on configuration (do.all-schemas=false)",
                    schemaList);
            return validTestSchemas;
        }
    }

    /**
     * Get available schemas for the source database.
     * Default implementation delegates to StateService.
     */
    protected List<String> getAvailableSchemas() {
        if ("ORACLE".equals(getSourceDatabase())) {
            return stateService.getOracleSchemaNames();
        } else if ("POSTGRES".equals(getSourceDatabase())) {
            return stateService.getPostgresSchemaNames();
        } else {
            throw new IllegalStateException("Unknown source database: " + getSourceDatabase());
        }
    }

    /**
     * Filters schemas using UserExcluder logic.
     */
    protected List<String> filterValidSchemas(List<String> schemas) {
        List<String> validSchemas = new ArrayList<>();
        for (String schema : schemas) {
            if (!UserExcluder.is2BeExclueded(schema)) {
                validSchemas.add(schema);
            }
        }
        return validSchemas;
    }

    /**
     * Saves the extraction results to the appropriate state location.
     * This method must be implemented by subclasses to handle type-specific storage.
     */
    protected abstract void saveResultsToState(List<T> results);

    /**
     * Template method that provides common extraction flow with state saving.
     */
    protected final List<T> performExtractionWithStateSaving(Consumer<JobProgress> progressCallback) throws Exception {
        List<T> results = performExtraction(progressCallback);

        updateProgress(progressCallback, 90, "Storing results", "Saving extraction results to global state");
        saveResultsToState(results);

        updateProgress(progressCallback, 95, "Preparing summary", "Generating extraction summary");
        String summaryMessage = generateSummaryMessage(results);
        updateProgress(progressCallback, 100, "Completed", summaryMessage);

        log.info("{} extraction completed successfully: {}", getExtractionType(), summaryMessage);
        return results;
    }

    /**
     * Generates a summary message for the extraction results.
     * Default implementation provides count information.
     */
    protected String generateSummaryMessage(List<T> results) {
        return String.format("Extraction completed: %d %s extracted",
                           results.size(),
                           getExtractionType().replace("_", " ").toLowerCase());
    }
}