package me.christianrobert.orapgsync.core.job.service;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.DatabaseExtractionJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry for database extraction jobs using CDI-based discovery.
 * Automatically discovers and registers all DatabaseExtractionJob implementations.
 */
@ApplicationScoped
public class JobRegistry {

    private static final Logger log = LoggerFactory.getLogger(JobRegistry.class);

    @Inject
    private Instance<DatabaseExtractionJob<?>> jobInstances;

    private final Map<String, Class<? extends DatabaseExtractionJob<?>>> jobTypeMap = new HashMap<>();
    private final Map<String, DatabaseExtractionJob<?>> jobProviders = new HashMap<>();

    @PostConstruct
    public void initialize() {
        log.info("Initializing JobRegistry and discovering database extraction jobs");

        int discoveredJobCount = 0;
        for (DatabaseExtractionJob<?> job : jobInstances) {
            String jobTypeKey = createJobTypeKey(job.getSourceDatabase(), job.getExtractionType());

            // Store the class type for later instantiation
            @SuppressWarnings("unchecked")
            Class<? extends DatabaseExtractionJob<?>> jobClass = (Class<? extends DatabaseExtractionJob<?>>) job.getClass();
            jobTypeMap.put(jobTypeKey, jobClass);

            // Store a provider instance for metadata queries
            jobProviders.put(jobTypeKey, job);

            log.info("Registered job: {} -> {} ({})",
                    jobTypeKey,
                    jobClass.getSimpleName(),
                    job.getDescription());

            discoveredJobCount++;
        }

        log.info("JobRegistry initialization completed. Discovered {} database extraction jobs", discoveredJobCount);

        if (discoveredJobCount == 0) {
            log.warn("No database extraction jobs were discovered. Check that job classes are properly annotated with CDI scopes.");
        }
    }

    /**
     * Creates a new job instance for the specified database and extraction type.
     *
     * @param sourceDatabase The source database ("ORACLE" or "POSTGRES")
     * @param extractionType The extraction type ("TABLE_METADATA", "OBJECT_DATATYPE", etc.)
     * @return A new job instance, or empty if no matching job is found
     */
    public Optional<DatabaseExtractionJob<?>> createJob(String sourceDatabase, String extractionType) {
        String jobTypeKey = createJobTypeKey(sourceDatabase, extractionType);

        Class<? extends DatabaseExtractionJob<?>> jobClass = jobTypeMap.get(jobTypeKey);
        if (jobClass == null) {
            log.warn("No job registered for key: {}", jobTypeKey);
            return Optional.empty();
        }

        try {
            // Use CDI to create a new instance - this ensures proper dependency injection
            DatabaseExtractionJob<?> jobInstance = jobInstances.select(jobClass).get();
            log.debug("Created new job instance: {} for key: {}", jobClass.getSimpleName(), jobTypeKey);
            return Optional.of(jobInstance);
        } catch (Exception e) {
            log.error("Failed to create job instance for key: {}", jobTypeKey, e);
            return Optional.empty();
        }
    }

    /**
     * Gets metadata about a job type without creating an instance.
     *
     * @param sourceDatabase The source database
     * @param extractionType The extraction type
     * @return Job metadata, or empty if no matching job is found
     */
    public Optional<JobMetadata> getJobMetadata(String sourceDatabase, String extractionType) {
        String jobTypeKey = createJobTypeKey(sourceDatabase, extractionType);

        DatabaseExtractionJob<?> provider = jobProviders.get(jobTypeKey);
        if (provider == null) {
            return Optional.empty();
        }

        return Optional.of(new JobMetadata(
                sourceDatabase,
                extractionType,
                provider.getJobTypeIdentifier(),
                provider.getDescription(),
                provider.getResultType()
        ));
    }

    /**
     * Gets all available job type keys.
     *
     * @return Map of job type keys to their class names
     */
    public Map<String, String> getAvailableJobTypes() {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, Class<? extends DatabaseExtractionJob<?>>> entry : jobTypeMap.entrySet()) {
            result.put(entry.getKey(), entry.getValue().getSimpleName());
        }
        return result;
    }

    /**
     * Checks if a job type is supported.
     *
     * @param sourceDatabase The source database
     * @param extractionType The extraction type
     * @return true if the job type is supported
     */
    public boolean isJobTypeSupported(String sourceDatabase, String extractionType) {
        String jobTypeKey = createJobTypeKey(sourceDatabase, extractionType);
        return jobTypeMap.containsKey(jobTypeKey);
    }

    private String createJobTypeKey(String sourceDatabase, String extractionType) {
        return sourceDatabase.toUpperCase() + "_" + extractionType.toUpperCase();
    }

    /**
     * Metadata about a registered job type.
     */
    public static class JobMetadata {
        private final String sourceDatabase;
        private final String extractionType;
        private final String jobTypeIdentifier;
        private final String description;
        private final Class<?> resultType;

        public JobMetadata(String sourceDatabase, String extractionType, String jobTypeIdentifier,
                          String description, Class<?> resultType) {
            this.sourceDatabase = sourceDatabase;
            this.extractionType = extractionType;
            this.jobTypeIdentifier = jobTypeIdentifier;
            this.description = description;
            this.resultType = resultType;
        }

        public String getSourceDatabase() { return sourceDatabase; }
        public String getExtractionType() { return extractionType; }
        public String getJobTypeIdentifier() { return jobTypeIdentifier; }
        public String getDescription() { return description; }
        public Class<?> getResultType() { return resultType; }
    }
}