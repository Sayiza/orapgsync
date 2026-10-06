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
 * Abstract base class for database write jobs providing common functionality.
 * This class handles progress tracking, error handling, and provides utilities for write operations.
 *
 * @param <T> The type of result being produced by the write operation
 */
public abstract class AbstractDatabaseWriteJob<T> implements DatabaseWriteJob<T> {

    private static final Logger log = LoggerFactory.getLogger(AbstractDatabaseWriteJob.class);

    protected final String jobId;

    @Inject
    protected ConfigService configService;

    @Inject
    protected StateService stateService;

    protected AbstractDatabaseWriteJob() {
        this.jobId = generateJobId();
    }

    protected String generateJobId() {
        return getTargetDatabase().toLowerCase() + "-" +
               getWriteOperationType().toLowerCase().replace("_", "-") + "-" +
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
        return String.format("Perform %s operation on %s database",
                            getWriteOperationType().replace("_", " ").toLowerCase(),
                            getTargetDatabase());
    }

    @Override
    public CompletableFuture<T> execute(Consumer<JobProgress> progressCallback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return performWriteOperationWithStateUpdating(progressCallback);
            } catch (Exception e) {
                log.error("{} operation failed", getWriteOperationType(), e);
                throw new RuntimeException(String.format("%s operation failed: %s",
                                                       getWriteOperationType(), e.getMessage()), e);
            }
        });
    }

    /**
     * Template method for performing the actual write operation.
     * Subclasses must implement this method to provide write operation logic.
     */
    protected abstract T performWriteOperation(Consumer<JobProgress> progressCallback) throws Exception;

    /**
     * Saves the write operation results to the appropriate state location.
     * This method must be implemented by subclasses to handle type-specific storage.
     */
    protected abstract void saveResultsToState(T result);

    /**
     * Template method that provides common write operation flow with state saving.
     */
    protected final T performWriteOperationWithStateUpdating(Consumer<JobProgress> progressCallback) throws Exception {
        T result = performWriteOperation(progressCallback);

        updateProgress(progressCallback, 90, "Storing results", "Saving write operation results to global state");
        saveResultsToState(result);

        updateProgress(progressCallback, 95, "Preparing summary", "Generating operation summary");
        String summaryMessage = generateSummaryMessage(result);
        updateProgress(progressCallback, 100, "Completed", summaryMessage);

        log.info("{} operation completed successfully: {}", getWriteOperationType(), summaryMessage);
        return result;
    }

    /**
     * Get available Oracle schemas from state.
     */
    protected List<String> getOracleSchemas() {
        return stateService.getOracleSchemaNames();
    }

    /**
     * Get available PostgreSQL schemas from state.
     */
    protected List<String> getPostgresSchemas() {
        return stateService.getPostgresSchemaNames();
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
     * Utility method for updating job progress.
     */
    public void updateProgress(Consumer<JobProgress> progressCallback, int percentage,
                                String currentTask, String details) {
        if (progressCallback != null) {
            JobProgress progress = new JobProgress(percentage, currentTask, details);
            progressCallback.accept(progress);
        }
    }

    /**
     * Generates a summary message for the write operation results.
     * Default implementation provides basic information.
     */
    protected String generateSummaryMessage(T result) {
        return String.format("Write operation completed: %s",
                           getWriteOperationType().replace("_", " ").toLowerCase());
    }
}