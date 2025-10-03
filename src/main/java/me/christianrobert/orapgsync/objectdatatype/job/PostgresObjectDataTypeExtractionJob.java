package me.christianrobert.orapgsync.objectdatatype.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseExtractionJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.core.job.model.objectdatatype.ObjectDataTypeMetaData;
import me.christianrobert.orapgsync.core.job.model.objectdatatype.ObjectDataTypeVariable;
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
        stateService.setPostgresObjectDataTypeMetaData(results);
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

            // Extract all composite types, including those not yet used in tables
            // This differs from Oracle's approach to show newly created types before table creation
            updateProgress(progressCallback, 20, "Executing query", "Fetching user-defined composite types from PostgreSQL");

            List<ObjectDataTypeMetaData> allObjectDataTypes = new ArrayList<>();

            // Query for all composite types, not just those used in table columns
            // This allows visibility of newly created types before tables are created
            String sql = """
                SELECT
                  n.nspname AS schema_name,
                  t.typname AS type_name
                FROM pg_type t
                JOIN pg_namespace n ON t.typnamespace = n.oid
                LEFT JOIN pg_class c ON t.typrelid = c.oid
                WHERE t.typtype = 'c'  -- composite types only
                  AND n.nspname NOT IN ('information_schema', 'pg_catalog', 'pg_toast')
                  AND t.typrelid != 0  -- has attributes
                  AND (c.oid IS NULL OR c.relkind != 'r')  -- exclude types tied to tables
                ORDER BY n.nspname, t.typname;
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
                    String.format("Found %d user-defined composite types", processedCount));
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