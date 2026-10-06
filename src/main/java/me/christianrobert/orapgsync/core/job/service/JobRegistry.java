package me.christianrobert.orapgsync.core.job.service;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.DatabaseExtractionJob;
import me.christianrobert.orapgsync.core.job.DatabaseWriteJob;
import me.christianrobert.orapgsync.core.job.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry for database jobs using CDI-based discovery.
 * Automatically discovers and registers all DatabaseExtractionJob and DatabaseWriteJob implementations.
 */
@ApplicationScoped
public class JobRegistry {

    private static final Logger log = LoggerFactory.getLogger(JobRegistry.class);

    @Inject
    private Instance<DatabaseExtractionJob<?>> extractionJobInstances;

    @Inject
    private Instance<DatabaseWriteJob<?>> writeJobInstances;

    private final Map<String, Class<? extends Job<?>>> jobTypeMap = new HashMap<>();
    private final Map<String, Job<?>> jobProviders = new HashMap<>();

    @PostConstruct
    public void initialize() {
        log.info("Initializing JobRegistry and discovering database jobs (extraction and write)");

        int discoveredJobCount = 0;

        // Discover extraction jobs
        for (DatabaseExtractionJob<?> job : extractionJobInstances) {
            String jobTypeKey = createJobTypeKey(job.getSourceDatabase(), job.getExtractionType());
            registerJob(jobTypeKey, job, "extraction");
            discoveredJobCount++;
        }

        // Discover write jobs
        for (DatabaseWriteJob<?> job : writeJobInstances) {
            String jobTypeKey = createJobTypeKey(job.getTargetDatabase(), job.getWriteOperationType());
            registerJob(jobTypeKey, job, "write");
            discoveredJobCount++;
        }

        log.info("JobRegistry initialization completed. Discovered {} database jobs", discoveredJobCount);

        if (discoveredJobCount == 0) {
            log.warn("No database jobs were discovered. Check that job classes are properly annotated with CDI scopes.");
        }
    }

    private void registerJob(String jobTypeKey, Job<?> job, String jobCategory) {
        // Store the class type for later instantiation
        @SuppressWarnings("unchecked")
        Class<? extends Job<?>> jobClass = (Class<? extends Job<?>>) job.getClass();
        jobTypeMap.put(jobTypeKey, jobClass);

        // Store a provider instance for metadata queries
        jobProviders.put(jobTypeKey, job);

        log.info("Registered {} job: {} -> {} ({})",
                jobCategory, jobTypeKey, jobClass.getSimpleName(), job.getDescription());
    }

    /**
     * Creates a new job instance for the specified database and operation type.
     *
     * @param database The database ("ORACLE" or "POSTGRES")
     * @param operationType The operation type ("TABLE_METADATA", "SCHEMA_CREATION", etc.)
     * @return A new job instance, or empty if no matching job is found
     */
    public Optional<Job<?>> createJob(String database, String operationType) {
        String jobTypeKey = createJobTypeKey(database, operationType);

        Class<? extends Job<?>> jobClass = jobTypeMap.get(jobTypeKey);
        if (jobClass == null) {
            log.warn("No job registered for key: {}", jobTypeKey);
            return Optional.empty();
        }

        try {
            // Determine if it's an extraction job or write job and use appropriate CDI instance
            Job<?> jobInstance;
            if (DatabaseExtractionJob.class.isAssignableFrom(jobClass)) {
                @SuppressWarnings("unchecked")
                Class<? extends DatabaseExtractionJob<?>> extractionJobClass =
                    (Class<? extends DatabaseExtractionJob<?>>) jobClass;
                jobInstance = extractionJobInstances.select(extractionJobClass).get();
            } else if (DatabaseWriteJob.class.isAssignableFrom(jobClass)) {
                @SuppressWarnings("unchecked")
                Class<? extends DatabaseWriteJob<?>> writeJobClass =
                    (Class<? extends DatabaseWriteJob<?>>) jobClass;
                jobInstance = writeJobInstances.select(writeJobClass).get();
            } else {
                throw new IllegalStateException("Unknown job type: " + jobClass);
            }

            log.debug("Created new job instance: {} for key: {}", jobClass.getSimpleName(), jobTypeKey);
            return Optional.of(jobInstance);
        } catch (Exception e) {
            log.error("Failed to create job instance for key: {}", jobTypeKey, e);
            return Optional.empty();
        }
    }

    /**
     * @deprecated Use {@link #createJob(String, String)} instead
     */
    @Deprecated
    public Optional<DatabaseExtractionJob<?>> createExtractionJob(String sourceDatabase, String extractionType) {
        Optional<Job<?>> job = createJob(sourceDatabase, extractionType);
        if (job.isPresent() && job.get() instanceof DatabaseExtractionJob<?>) {
            return Optional.of((DatabaseExtractionJob<?>) job.get());
        }
        return Optional.empty();
    }

    /**
     * Gets metadata about a job type without creating an instance.
     *
     * @param database The database
     * @param operationType The operation type
     * @return Job metadata, or empty if no matching job is found
     */
    public Optional<JobMetadata> getJobMetadata(String database, String operationType) {
        String jobTypeKey = createJobTypeKey(database, operationType);

        Job<?> provider = jobProviders.get(jobTypeKey);
        if (provider == null) {
            return Optional.empty();
        }

        String jobCategory = "unknown";
        String resultTypeName = "Object";

        if (provider instanceof DatabaseExtractionJob<?> extractionJob) {
            jobCategory = "extraction";
            resultTypeName = extractionJob.getResultType().getSimpleName();
        } else if (provider instanceof DatabaseWriteJob<?> writeJob) {
            jobCategory = "write";
            resultTypeName = writeJob.getResultType().getSimpleName();
        }

        return Optional.of(new JobMetadata(
                database,
                operationType,
                provider.getJobType(),
                provider.getDescription(),
                provider.getClass(), // Use the job class instead of result type
                jobCategory,
                resultTypeName
        ));
    }

    /**
     * Gets all available job type keys.
     *
     * @return Map of job type keys to their class names
     */
    public Map<String, String> getAvailableJobTypes() {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, Class<? extends Job<?>>> entry : jobTypeMap.entrySet()) {
            result.put(entry.getKey(), entry.getValue().getSimpleName());
        }
        return result;
    }

    /**
     * Checks if a job type is supported.
     *
     * @param database The database
     * @param operationType The operation type
     * @return true if the job type is supported
     */
    public boolean isJobTypeSupported(String database, String operationType) {
        String jobTypeKey = createJobTypeKey(database, operationType);
        return jobTypeMap.containsKey(jobTypeKey);
    }

    private String createJobTypeKey(String database, String operationType) {
        return database.toUpperCase() + "_" + operationType.toUpperCase();
    }

    /**
     * Metadata about a registered job type.
     */
    public static class JobMetadata {
        private final String database;
        private final String operationType;
        private final String jobTypeIdentifier;
        private final String description;
        private final Class<?> jobClass;
        private final String jobCategory;
        private final String resultTypeName;

        public JobMetadata(String database, String operationType, String jobTypeIdentifier,
                          String description, Class<?> jobClass, String jobCategory, String resultTypeName) {
            this.database = database;
            this.operationType = operationType;
            this.jobTypeIdentifier = jobTypeIdentifier;
            this.description = description;
            this.jobClass = jobClass;
            this.jobCategory = jobCategory;
            this.resultTypeName = resultTypeName;
        }

        public String getDatabase() { return database; }
        public String getOperationType() { return operationType; }
        public String getJobTypeIdentifier() { return jobTypeIdentifier; }
        public String getDescription() { return description; }
        public Class<?> getJobClass() { return jobClass; }
        public String getJobCategory() { return jobCategory; }
        public String getResultTypeName() { return resultTypeName; }

        @Override
        public String toString() {
            return String.format("JobMetadata{database='%s', operationType='%s', category='%s', class='%s'}",
                               database, operationType, jobCategory, jobClass.getSimpleName());
        }
    }
}