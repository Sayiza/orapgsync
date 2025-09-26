package me.christianrobert.orapgsync.objectdatatype.job;

import me.christianrobert.orapgsync.config.service.ConfigService;
import me.christianrobert.orapgsync.core.job.Job;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.objectdatatype.model.ObjectDataTypeMetaData;
import me.christianrobert.orapgsync.objectdatatype.model.ObjectDataTypeVariable;
import me.christianrobert.orapgsync.table.tools.UserExcluder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class PostgresObjectDataTypeExtractionJob implements Job<List<ObjectDataTypeMetaData>> {

    private static final Logger log = LoggerFactory.getLogger(PostgresObjectDataTypeExtractionJob.class);

    private final String jobId;

    private PostgresConnectionService postgresConnectionService;
    private ConfigService configService;

    public PostgresObjectDataTypeExtractionJob() {
        this.jobId = "postgres-object-datatype-extraction-" + UUID.randomUUID().toString();
    }

    public void setPostgresConnectionService(PostgresConnectionService postgresConnectionService) {
        this.postgresConnectionService = postgresConnectionService;
    }

    public void setConfigService(ConfigService configService) {
        this.configService = configService;
    }

    @Override
    public String getJobId() {
        return jobId;
    }

    @Override
    public String getJobType() {
        return "POSTGRES_OBJECT_DATATYPE_EXTRACTION";
    }

    @Override
    public String getDescription() {
        return "Extract object data types from PostgreSQL database";
    }

    @Override
    public CompletableFuture<List<ObjectDataTypeMetaData>> execute(Consumer<JobProgress> progressCallback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return performExtraction(progressCallback);
            } catch (Exception e) {
                log.error("PostgreSQL object data type extraction failed", e);
                throw new RuntimeException("PostgreSQL object data type extraction failed: " + e.getMessage(), e);
            }
        });
    }

    private List<ObjectDataTypeMetaData> performExtraction(Consumer<JobProgress> progressCallback) throws Exception {
        updateProgress(progressCallback, 0, "Initializing", "Starting PostgreSQL object data type extraction");

        if (!postgresConnectionService.isConfigured()) {
            updateProgress(progressCallback, -1, "Failed", "PostgreSQL connection not configured");
            throw new IllegalStateException("PostgreSQL connection not configured");
        }

        updateProgress(progressCallback, 5, "Connecting to PostgreSQL", "Establishing database connection");

        try (Connection connection = postgresConnectionService.getConnection()) {
            updateProgress(progressCallback, 10, "Connected", "Successfully connected to PostgreSQL database");

            // Check configuration settings
            boolean doAllSchemas = Boolean.TRUE.equals(configService.getConfigValueAsBoolean("do.all-schemas"));
            String testSchema = configService.getConfigValueAsString("do.only-test-schema");

            updateProgress(progressCallback, 15, "Building query", "Determining schemas to process based on configuration");

            // PostgreSQL composite types query - simplified to match Oracle approach
            String sql;
            if (doAllSchemas) {
                sql = """
                    SELECT DISTINCT
                        t.typnamespace::regnamespace::text as schema_name,
                        t.typname as type_name
                    FROM pg_type t
                    WHERE t.typtype = 'c'
                      AND t.typnamespace::regnamespace::text NOT IN ('information_schema', 'pg_catalog', 'pg_toast')
                      AND EXISTS (
                        SELECT 1 FROM information_schema.columns c
                        WHERE c.udt_name = t.typname
                          AND c.udt_schema = t.typnamespace::regnamespace::text
                      )
                    ORDER BY schema_name, type_name
                    """;
            } else {
                if (testSchema == null || testSchema.trim().isEmpty()) {
                    updateProgress(progressCallback, -1, "Configuration error", "Test schema not configured but do.all-schemas is false");
                    throw new IllegalStateException("Test schema not configured but do.all-schemas is false");
                }
                sql = """
                    SELECT DISTINCT
                        t.typnamespace::regnamespace::text as schema_name,
                        t.typname as type_name
                    FROM pg_type t
                    WHERE t.typtype = 'c'
                      AND t.typnamespace::regnamespace::text = ?
                      AND EXISTS (
                        SELECT 1 FROM information_schema.columns c
                        WHERE c.udt_name = t.typname
                          AND c.udt_schema = ?
                      )
                    ORDER BY schema_name, type_name
                    """;
            }

            updateProgress(progressCallback, 20, "Executing query", "Fetching object data types from PostgreSQL");

            Map<String, List<ObjectDataTypeMetaData>> objectDataTypesBySchema = new HashMap<>();

            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                if (!doAllSchemas && testSchema != null) {
                    stmt.setString(1, testSchema.toLowerCase()); // PostgreSQL typically uses lowercase
                    stmt.setString(2, testSchema.toLowerCase());
                }

                try (ResultSet rs = stmt.executeQuery()) {
                    int processedCount = 0;
                    updateProgress(progressCallback, 30, "Processing results", "Extracting object data type metadata");

                    while (rs.next()) {
                        String schemaName = rs.getString("schema_name");
                        String typeName = rs.getString("type_name");

                        // Apply UserExcluder logic for consistency (convert to uppercase for consistency)
                        if (UserExcluder.is2BeExclueded(schemaName.toUpperCase(), "TYPE")) {
                            log.debug("Excluding PostgreSQL schema for object data types: {}", schemaName);
                            continue;
                        }

                        // Create object data type metadata with empty variables list (since we're just getting basic info)
                        ObjectDataTypeMetaData objectDataType = createObjectDataTypeMetaData(schemaName, typeName, new ArrayList<>());
                        objectDataTypesBySchema.computeIfAbsent(schemaName, k -> new ArrayList<>()).add(objectDataType);

                        processedCount++;
                        if (processedCount % 10 == 0) {
                            int progress = 30 + (processedCount * 50 / Math.max(1, processedCount + 50)); // Estimate progress
                            updateProgress(progressCallback, progress, "Processing results",
                                String.format("Processed %d object data types", processedCount));
                        }
                    }

                    updateProgress(progressCallback, 80, "Processing completed",
                        String.format("Found %d object data types", processedCount));
                }
            }

            // Return flattened list
            List<ObjectDataTypeMetaData> allObjectDataTypes = objectDataTypesBySchema.values().stream()
                    .flatMap(List::stream)
                    .toList();

            updateProgress(progressCallback, 95, "Preparing summary", "Generating extraction summary");

            String summaryMessage = String.format(
                "Extraction completed: %d object data types from %d schemas",
                allObjectDataTypes.size(),
                objectDataTypesBySchema.size());

            updateProgress(progressCallback, 100, "Completed", summaryMessage);

            log.info("PostgreSQL object data type extraction completed successfully: {} object data types from {} schemas",
                    allObjectDataTypes.size(), objectDataTypesBySchema.size());

            return allObjectDataTypes;

        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed", "PostgreSQL object data type extraction failed: " + e.getMessage());
            throw e;
        }
    }

    private ObjectDataTypeMetaData createObjectDataTypeMetaData(String schema, String typeName, List<ObjectDataTypeVariable> variables) {
        // Using reflection to create ObjectDataTypeMetaData since it lacks setters
        // This is a workaround - ideally the model should have setters or a builder
        try {
            ObjectDataTypeMetaData objectDataType = new ObjectDataTypeMetaData();
            java.lang.reflect.Field nameField = ObjectDataTypeMetaData.class.getDeclaredField("name");
            java.lang.reflect.Field schemaField = ObjectDataTypeMetaData.class.getDeclaredField("schema");
            java.lang.reflect.Field variablesField = ObjectDataTypeMetaData.class.getDeclaredField("variables");

            nameField.setAccessible(true);
            schemaField.setAccessible(true);
            variablesField.setAccessible(true);

            nameField.set(objectDataType, typeName);
            schemaField.set(objectDataType, schema);
            variablesField.set(objectDataType, new ArrayList<>(variables));

            return objectDataType;
        } catch (Exception e) {
            log.error("Failed to create ObjectDataTypeMetaData for {}.{}", schema, typeName, e);
            throw new RuntimeException("Failed to create ObjectDataTypeMetaData", e);
        }
    }

}