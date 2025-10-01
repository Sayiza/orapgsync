package me.christianrobert.orapgsync.objectdatatype.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseExtractionJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.objectdatatype.model.ObjectDataTypeMetaData;
import me.christianrobert.orapgsync.objectdatatype.model.ObjectDataTypeVariable;
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
public class PostgresObjectDataTypeExtractionJob extends AbstractDatabaseExtractionJob<ObjectDataTypeMetaData> {

    private static final Logger log = LoggerFactory.getLogger(PostgresObjectDataTypeExtractionJob.class);

    @Inject
    private PostgresConnectionService postgresConnectionService;

    @Override
    public String getSourceDatabase() {
        return "POSTGRES";
    }

    @Override
    public String getExtractionType() {
        return "OBJECT_DATATYPE";
    }

    @Override
    public Class<ObjectDataTypeMetaData> getResultType() {
        return ObjectDataTypeMetaData.class;
    }

    @Override
    protected void saveResultsToState(List<ObjectDataTypeMetaData> results) {
        stateService.updatePostgresObjectDataTypeMetaData(results);
    }

    @Override
    protected List<ObjectDataTypeMetaData> performExtraction(Consumer<JobProgress> progressCallback) throws Exception {
        updateProgress(progressCallback, 0, "Initializing", "Starting PostgreSQL object data type extraction");

        if (!postgresConnectionService.isConfigured()) {
            updateProgress(progressCallback, -1, "Failed", "PostgreSQL connection not configured");
            throw new IllegalStateException("PostgreSQL connection not configured");
        }

        updateProgress(progressCallback, 5, "Connecting to PostgreSQL", "Establishing database connection");

        try (Connection connection = postgresConnectionService.getConnection()) {
            updateProgress(progressCallback, 10, "Connected", "Successfully connected to PostgreSQL database");

            // Extract composite types that are actually used as column data types
            // This matches Oracle's approach: only extract types that are referenced in table columns
            // This filters out implicit table row types (which PostgreSQL creates automatically)
            updateProgress(progressCallback, 20, "Executing query", "Fetching user-defined composite types from PostgreSQL");

            List<ObjectDataTypeMetaData> allObjectDataTypes = new ArrayList<>();

            // Query for composite types that are used in table columns
            // Similar to Oracle's strategy: JOIN with columns to find only types that are actually used
            String sql = """
                SELECT DISTINCT
                    udt_n.nspname as schema_name,
                    udt_t.typname as type_name
                FROM information_schema.columns c
                JOIN pg_namespace udt_n ON c.udt_schema = udt_n.nspname
                JOIN pg_type udt_t ON c.udt_name = udt_t.typname AND udt_t.typnamespace = udt_n.oid
                WHERE c.data_type = 'USER-DEFINED'
                  AND udt_n.nspname NOT IN ('information_schema', 'pg_catalog', 'pg_toast')
                  AND udt_t.typtype = 'c'  -- composite types only
                ORDER BY udt_n.nspname, udt_t.typname
                """;

            try (PreparedStatement stmt = connection.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {

                int processedCount = 0;
                updateProgress(progressCallback, 30, "Processing results", "Extracting PostgreSQL composite type metadata");

                while (rs.next()) {
                    String schemaName = rs.getString("schema_name");
                    String typeName = rs.getString("type_name");

                    // Extract attributes for this composite type
                    List<ObjectDataTypeVariable> attributes = extractCompositeTypeAttributes(connection, schemaName, typeName);

                    // Create object data type metadata
                    ObjectDataTypeMetaData objectDataType = createObjectDataTypeMetaData(schemaName, typeName, attributes);
                    allObjectDataTypes.add(objectDataType);

                    processedCount++;
                    if (processedCount % 5 == 0) {
                        int progress = 30 + (processedCount * 50 / Math.max(1, processedCount + 25));
                        updateProgress(progressCallback, progress, "Processing composite types",
                            String.format("Processed %d composite types", processedCount));
                    }
                }

                updateProgress(progressCallback, 80, "Processing completed",
                    String.format("Found %d user-defined composite types (used in table columns)", processedCount));
            }

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
                a.attname as attribute_name,
                format_type(a.atttypid, a.atttypmod) as attribute_type
            FROM pg_attribute a
            JOIN pg_type t ON a.attrelid = t.typrelid
            JOIN pg_namespace n ON t.typnamespace = n.oid
            WHERE n.nspname = ?
              AND t.typname = ?
              AND a.attnum > 0
              AND NOT a.attisdropped
            ORDER BY a.attnum
            """;

        try (PreparedStatement stmt = connection.prepareStatement(attributeQuery)) {
            stmt.setString(1, schemaName);
            stmt.setString(2, typeName);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String attrName = rs.getString("attribute_name");
                    String attrType = rs.getString("attribute_type");

                    ObjectDataTypeVariable variable = new ObjectDataTypeVariable(attrName, attrType);
                    attributes.add(variable);

                    log.debug("Found attribute {} for {}.{}: {}",
                        attrName, schemaName, typeName, attrType);
                }
            }
        }

        log.debug("Extracted {} attributes for composite type {}.{}", attributes.size(), schemaName, typeName);
        return attributes;
    }

    private ObjectDataTypeMetaData createObjectDataTypeMetaData(String schema, String typeName, List<ObjectDataTypeVariable> variables) {
        // Using reflection to create ObjectDataTypeMetaData since it lacks setters
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

    @Override
    protected String generateSummaryMessage(List<ObjectDataTypeMetaData> results) {
        Map<String, Integer> schemaObjectCounts = new HashMap<>();
        int totalAttributes = 0;

        for (ObjectDataTypeMetaData objectType : results) {
            String schema = objectType.getSchema();
            schemaObjectCounts.put(schema, schemaObjectCounts.getOrDefault(schema, 0) + 1);
            totalAttributes += objectType.getVariables().size();
        }

        return String.format("Extraction completed: %d user-defined composite types from %d schemas with %d total attributes",
                           results.size(), schemaObjectCounts.size(), totalAttributes);
    }
}