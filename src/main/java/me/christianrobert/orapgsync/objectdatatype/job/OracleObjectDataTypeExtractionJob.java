package me.christianrobert.orapgsync.objectdatatype.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseExtractionJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.database.service.OracleConnectionService;
import me.christianrobert.orapgsync.core.job.model.objectdatatype.ObjectDataTypeMetaData;
import me.christianrobert.orapgsync.core.job.model.objectdatatype.ObjectDataTypeVariable;
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
import java.util.function.Consumer;

@Dependent
public class OracleObjectDataTypeExtractionJob extends AbstractDatabaseExtractionJob<ObjectDataTypeMetaData> {

    private static final Logger log = LoggerFactory.getLogger(OracleObjectDataTypeExtractionJob.class);

    @Inject
    private OracleConnectionService oracleConnectionService;

    @Override
    public String getSourceDatabase() {
        return "ORACLE";
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
        stateService.setOracleObjectDataTypeMetaData(results);
    }

    @Override
    protected List<ObjectDataTypeMetaData> performExtraction(Consumer<JobProgress> progressCallback) throws Exception {
        updateProgress(progressCallback, 0, "Initializing", "Starting Oracle object data type extraction");

        if (!oracleConnectionService.isConfigured()) {
            updateProgress(progressCallback, -1, "Failed", "Oracle connection not configured");
            throw new IllegalStateException("Oracle connection not configured");
        }

        // Determine which schemas to process based on configuration
        List<String> schemasToProcess = determineSchemasToProcess(progressCallback);

        if (schemasToProcess.isEmpty()) {
            updateProgress(progressCallback, 100, "No schemas to process",
                          "No schemas available for object data type extraction based on current configuration");
            return new ArrayList<>();
        }

        updateProgress(progressCallback, 5, "Connecting to Oracle", "Establishing database connection");

        try (Connection connection = oracleConnectionService.getConnection()) {
            updateProgress(progressCallback, 10, "Connected", "Successfully connected to Oracle database");

            updateProgress(progressCallback, 15, "Building query",
                          String.format("Processing %d schema(s) for object data types", schemasToProcess.size()));

            // Build SQL with IN clause for multiple schemas
            String schemaPlaceholders = schemasToProcess.stream()
                .map(s -> "?")
                .collect(java.util.stream.Collectors.joining(", "));

            String sql = """
                SELECT DISTINCT o.owner, o.object_name
                FROM all_objects o
                JOIN all_tab_cols c
                  ON c.data_type = o.object_name
                  AND c.data_type_owner = o.owner
                WHERE o.object_type = 'TYPE'
                  AND LOWER(o.owner) IN (%s)
                ORDER BY o.owner, o.object_name
                """.formatted(schemaPlaceholders);

            updateProgress(progressCallback, 20, "Executing query", "Fetching object data types from Oracle");

            Map<String, List<ObjectDataTypeMetaData>> objectDataTypesBySchema = new HashMap<>();

            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                // Set schema parameters (using lowercase as already normalized)
                for (int i = 0; i < schemasToProcess.size(); i++) {
                    stmt.setString(i + 1, schemasToProcess.get(i));
                }

                try (ResultSet rs = stmt.executeQuery()) {
                    int processedCount = 0;
                    updateProgress(progressCallback, 30, "Processing results", "Extracting object data type metadata");

                    while (rs.next()) {
                        String owner = rs.getString("owner").toLowerCase();
                        String objectName = rs.getString("object_name").toLowerCase();

                        // Apply UserExcluder logic for consistency (check with original case)
                        if (UserExcluder.is2BeExclueded(rs.getString("owner"), "TYPE")) {
                            log.debug("Excluding Oracle schema for object data types: {}", owner);
                            continue;
                        }

                        // Extract detailed attributes for this object type
                        updateProgress(progressCallback, 30 + (processedCount * 40 / Math.max(1, processedCount + 50)),
                            "Extracting attributes", String.format("Getting attributes for %s.%s", owner, objectName));

                        List<ObjectDataTypeVariable> attributes = extractObjectTypeAttributes(connection, owner, objectName);

                        // Create object data type metadata with detailed attributes
                        ObjectDataTypeMetaData objectDataType = createObjectDataTypeMetaData(owner, objectName, attributes);
                        objectDataTypesBySchema.computeIfAbsent(owner, k -> new ArrayList<>()).add(objectDataType);

                        processedCount++;
                        if (processedCount % 5 == 0) {
                            int progress = 30 + (processedCount * 50 / Math.max(1, processedCount + 25)); // Better progress estimation
                            updateProgress(progressCallback, progress, "Processing object types",
                                String.format("Processed %d object data types with %d total attributes",
                                    processedCount, countTotalAttributes(objectDataTypesBySchema)));
                        }
                    }

                    updateProgress(progressCallback, 80, "Processing completed",
                        String.format("Found %d object data types", processedCount));
                }
            }

            // Save to global state - flatten the map to a single list
            List<ObjectDataTypeMetaData> allObjectDataTypes = objectDataTypesBySchema.values().stream()
                    .flatMap(List::stream)
                    .toList();

            return allObjectDataTypes;

        } catch (Exception e) {
            updateProgress(progressCallback, -1, "Failed", "Object data type extraction failed: " + e.getMessage());
            throw e;
        }
    }

    private List<ObjectDataTypeVariable> extractObjectTypeAttributes(Connection connection, String owner, String typeName) throws Exception {
        List<ObjectDataTypeVariable> attributes = new ArrayList<>();

        String attributeQuery = """
            SELECT
                attr_name,
                attr_type_name,
                length,
                precision,
                scale,
                attr_no
            FROM all_type_attrs
            WHERE owner = ?
              AND type_name = ?
            ORDER BY attr_no
            """;

        try (PreparedStatement stmt = connection.prepareStatement(attributeQuery)) {
            stmt.setString(1, owner.toUpperCase());
            stmt.setString(2, typeName.toUpperCase());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String attrName = rs.getString("attr_name").toLowerCase();
                    String attrTypeName = rs.getString("attr_type_name");
                    Integer length = rs.getObject("length", Integer.class);
                    Integer precision = rs.getObject("precision", Integer.class);
                    Integer scale = rs.getObject("scale", Integer.class);
                    int attrNo = rs.getInt("attr_no");

                    // Format the data type with size information if available
                    String formattedDataType = formatDataType(attrTypeName, length, precision, scale);

                    ObjectDataTypeVariable variable = new ObjectDataTypeVariable(attrName, formattedDataType);
                    attributes.add(variable);

                    log.debug("Found attribute {} for {}.{}: {} (formatted as: {})",
                        attrName, owner, typeName, attrTypeName, formattedDataType);
                }
            }
        }

        log.debug("Extracted {} attributes for object type {}.{}", attributes.size(), owner, typeName);
        return attributes;
    }

    private int countTotalAttributes(Map<String, List<ObjectDataTypeMetaData>> objectDataTypesBySchema) {
        return objectDataTypesBySchema.values().stream()
                .flatMap(List::stream)
                .mapToInt(objectType -> objectType.getVariables().size())
                .sum();
    }

    private String formatDataType(String typeName, Integer length, Integer precision, Integer scale) {
        if (typeName == null) {
            return "UNKNOWN";
        }

        // Handle different Oracle data types with their size specifications
        switch (typeName.toUpperCase()) {
            case "VARCHAR2":
            case "CHAR":
            case "NVARCHAR2":
            case "NCHAR":
                return length != null ? String.format("%s(%d)", typeName, length) : typeName;

            case "NUMBER":
                if (precision != null && scale != null) {
                    return String.format("%s(%d,%d)", typeName, precision, scale);
                } else if (precision != null) {
                    return String.format("%s(%d)", typeName, precision);
                } else {
                    return typeName;
                }

            case "DECIMAL":
            case "NUMERIC":
                if (precision != null && scale != null) {
                    return String.format("%s(%d,%d)", typeName, precision, scale);
                } else if (precision != null) {
                    return String.format("%s(%d)", typeName, precision);
                } else {
                    return typeName;
                }

            case "FLOAT":
                return precision != null ? String.format("%s(%d)", typeName, precision) : typeName;

            case "RAW":
                return length != null ? String.format("%s(%d)", typeName, length) : typeName;

            default:
                // For other types (DATE, TIMESTAMP, CLOB, BLOB, etc.), return as-is
                return typeName;
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

    @Override
    protected String generateSummaryMessage(List<ObjectDataTypeMetaData> results) {
        Map<String, Integer> schemaObjectCounts = new HashMap<>();
        int totalAttributes = 0;

        for (ObjectDataTypeMetaData objectType : results) {
            String schema = objectType.getSchema();
            schemaObjectCounts.put(schema, schemaObjectCounts.getOrDefault(schema, 0) + 1);
            totalAttributes += objectType.getVariables().size();
        }

        return String.format("Extraction completed: %d object data types from %d schemas with %d total attributes",
                           results.size(), schemaObjectCounts.size(), totalAttributes);
    }
}