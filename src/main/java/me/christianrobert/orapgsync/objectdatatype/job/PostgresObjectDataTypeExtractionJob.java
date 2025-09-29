package me.christianrobert.orapgsync.objectdatatype.job;

import me.christianrobert.orapgsync.config.service.ConfigService;
import me.christianrobert.orapgsync.core.job.Job;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.service.StateService;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.objectdatatype.model.ObjectDataTypeMetaData;
import me.christianrobert.orapgsync.objectdatatype.model.ObjectDataTypeVariable;
import me.christianrobert.orapgsync.core.tools.UserExcluder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

    private StateService stateService;
    private PostgresConnectionService postgresConnectionService;
    private ConfigService configService;

    public PostgresObjectDataTypeExtractionJob() {
        this.jobId = "postgres-object-datatype-extraction-" + UUID.randomUUID().toString();
    }

    public void setStateService(StateService stateService) {
        this.stateService = stateService;
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

                        // Extract detailed attributes for this composite type
                        updateProgress(progressCallback, 30 + (processedCount * 40 / Math.max(1, processedCount + 50)),
                            "Extracting attributes", String.format("Getting attributes for %s.%s", schemaName, typeName));

                        List<ObjectDataTypeVariable> attributes = extractCompositeTypeAttributes(connection, schemaName, typeName);

                        // Create object data type metadata with detailed attributes
                        ObjectDataTypeMetaData objectDataType = createObjectDataTypeMetaData(schemaName, typeName, attributes);
                        objectDataTypesBySchema.computeIfAbsent(schemaName, k -> new ArrayList<>()).add(objectDataType);

                        processedCount++;
                        if (processedCount % 5 == 0) {
                            int progress = 30 + (processedCount * 50 / Math.max(1, processedCount + 25)); // Better progress estimation
                            updateProgress(progressCallback, progress, "Processing composite types",
                                String.format("Processed %d object data types with %d total attributes",
                                    processedCount, countTotalAttributes(objectDataTypesBySchema)));
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

            updateProgress(progressCallback, 90, "Storing results", "Saving object data types to global state");
            stateService.updatePostgresObjectDataTypeMetaData(allObjectDataTypes);

            updateProgress(progressCallback, 95, "Preparing summary", "Generating extraction summary");

            int totalAttributes = allObjectDataTypes.stream()
                    .mapToInt(objectType -> objectType.getVariables().size())
                    .sum();

            String summaryMessage = String.format(
                "Extraction completed: %d object data types from %d schemas with %d total attributes",
                allObjectDataTypes.size(),
                objectDataTypesBySchema.size(),
                totalAttributes);

            updateProgress(progressCallback, 100, "Completed", summaryMessage);

            log.info("PostgreSQL object data type extraction completed successfully: {} object data types from {} schemas with {} total attributes",
                    allObjectDataTypes.size(), objectDataTypesBySchema.size(), totalAttributes);

            return allObjectDataTypes;

        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed", "PostgreSQL object data type extraction failed: " + e.getMessage());
            throw e;
        }
    }

    private List<ObjectDataTypeVariable> extractCompositeTypeAttributes(Connection connection, String schemaName, String typeName) throws Exception {
        List<ObjectDataTypeVariable> attributes = new ArrayList<>();

        String attributeQuery = """
            SELECT
                a.attname as attr_name,
                t.typname as attr_type_name,
                CASE
                    WHEN a.atttypmod > 0 AND t.typname IN ('varchar', 'char', 'bpchar')
                    THEN a.atttypmod - 4
                    WHEN t.typname = 'numeric' AND a.atttypmod > 0
                    THEN ((a.atttypmod - 4) >> 16) & 65535
                    ELSE NULL
                END as length,
                CASE
                    WHEN t.typname = 'numeric' AND a.atttypmod > 0
                    THEN ((a.atttypmod - 4) >> 16) & 65535
                    ELSE NULL
                END as precision,
                CASE
                    WHEN t.typname = 'numeric' AND a.atttypmod > 0
                    THEN (a.atttypmod - 4) & 65535
                    ELSE NULL
                END as scale,
                a.attnum as attr_no
            FROM pg_attribute a
            JOIN pg_type pt ON pt.typname = ?
            JOIN pg_namespace pn ON pn.nspname = ? AND pt.typnamespace = pn.oid
            JOIN pg_type t ON t.oid = a.atttypid
            WHERE a.attrelid = pt.typrelid
              AND a.attnum > 0
              AND NOT a.attisdropped
            ORDER BY a.attnum
            """;

        try (PreparedStatement stmt = connection.prepareStatement(attributeQuery)) {
            stmt.setString(1, typeName);
            stmt.setString(2, schemaName);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String attrName = rs.getString("attr_name");
                    String attrTypeName = rs.getString("attr_type_name");
                    Integer length = rs.getObject("length", Integer.class);
                    Integer precision = rs.getObject("precision", Integer.class);
                    Integer scale = rs.getObject("scale", Integer.class);
                    int attrNo = rs.getInt("attr_no");

                    // Format the data type with size information if available
                    String formattedDataType = formatPostgresDataType(attrTypeName, length, precision, scale);

                    ObjectDataTypeVariable variable = new ObjectDataTypeVariable(attrName, formattedDataType);
                    attributes.add(variable);

                    log.debug("Found attribute {} for {}.{}: {} (formatted as: {})",
                        attrName, schemaName, typeName, attrTypeName, formattedDataType);
                }
            }
        }

        log.debug("Extracted {} attributes for composite type {}.{}", attributes.size(), schemaName, typeName);
        return attributes;
    }

    private int countTotalAttributes(Map<String, List<ObjectDataTypeMetaData>> objectDataTypesBySchema) {
        return objectDataTypesBySchema.values().stream()
                .flatMap(List::stream)
                .mapToInt(objectType -> objectType.getVariables().size())
                .sum();
    }

    private String formatPostgresDataType(String typeName, Integer length, Integer precision, Integer scale) {
        if (typeName == null) {
            return "UNKNOWN";
        }

        // Handle different PostgreSQL data types with their size specifications
        switch (typeName.toLowerCase()) {
            case "varchar":
            case "character varying":
                return length != null ? String.format("VARCHAR(%d)", length) : "VARCHAR";

            case "char":
            case "bpchar":
            case "character":
                return length != null ? String.format("CHAR(%d)", length) : "CHAR";

            case "numeric":
            case "decimal":
                if (precision != null && scale != null) {
                    return String.format("NUMERIC(%d,%d)", precision, scale);
                } else if (precision != null) {
                    return String.format("NUMERIC(%d)", precision);
                } else {
                    return "NUMERIC";
                }

            case "real":
                return "REAL";

            case "double precision":
                return "DOUBLE PRECISION";

            case "text":
                return "TEXT";

            case "bytea":
                return "BYTEA";

            case "boolean":
                return "BOOLEAN";

            case "date":
                return "DATE";

            case "timestamp":
                return "TIMESTAMP";

            case "timestamptz":
                return "TIMESTAMP WITH TIME ZONE";

            default:
                // For other types, return as-is but uppercase for consistency
                return typeName.toUpperCase();
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