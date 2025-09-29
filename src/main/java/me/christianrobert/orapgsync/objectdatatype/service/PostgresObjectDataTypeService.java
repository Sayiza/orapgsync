package me.christianrobert.orapgsync.objectdatatype.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.config.service.ConfigService;
import me.christianrobert.orapgsync.database.service.PostgresConnectionService;
import me.christianrobert.orapgsync.objectdatatype.model.ObjectDataTypeMetaData;
import me.christianrobert.orapgsync.objectdatatype.model.ObjectDataTypeVariable;
import me.christianrobert.orapgsync.core.tools.UserExcluder;
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

@ApplicationScoped
public class PostgresObjectDataTypeService {

    private static final Logger log = LoggerFactory.getLogger(PostgresObjectDataTypeService.class);

    @Inject
    PostgresConnectionService postgresConnectionService;

    @Inject
    ConfigService configService;

    public Map<String, Object> getObjectDataTypes() {
        Map<String, Object> result = new HashMap<>();

        try {
            log.info("Fetching PostgreSQL object data types...");

            if (!postgresConnectionService.isConfigured()) {
                throw new IllegalStateException("PostgreSQL connection not configured");
            }

            Map<String, List<ObjectDataTypeMetaData>> objectDataTypesBySchema = fetchObjectDataTypes();

            result.put("status", "success");
            result.put("objectDataTypesBySchema", objectDataTypesBySchema);
            result.put("totalCount", objectDataTypesBySchema.values().stream().mapToInt(List::size).sum());
            result.put("message", "Successfully retrieved PostgreSQL object data types");

            log.info("Found {} PostgreSQL object data types across {} schemas",
                    objectDataTypesBySchema.values().stream().mapToInt(List::size).sum(),
                    objectDataTypesBySchema.size());

        } catch (SQLException e) {
            log.error("SQL error while fetching PostgreSQL object data types", e);
            result.put("status", "error");
            result.put("objectDataTypesBySchema", new HashMap<>());
            result.put("totalCount", 0);
            result.put("message", "Database error: " + e.getMessage());
            result.put("errorCode", e.getErrorCode());
            result.put("sqlState", e.getSQLState());
        } catch (Exception e) {
            log.error("Error while fetching PostgreSQL object data types", e);
            result.put("status", "error");
            result.put("objectDataTypesBySchema", new HashMap<>());
            result.put("totalCount", 0);
            result.put("message", "Error retrieving object data types: " + e.getMessage());
        }

        return result;
    }

    private Map<String, List<ObjectDataTypeMetaData>> fetchObjectDataTypes() throws SQLException {
        Map<String, List<ObjectDataTypeMetaData>> result = new HashMap<>();

        try (Connection connection = postgresConnectionService.getConnection()) {
            // Check configuration settings
            boolean doAllSchemas = Boolean.TRUE.equals(configService.getConfigValueAsBoolean("do.all-schemas"));
            String testSchema = configService.getConfigValueAsString("do.only-test-schema");

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

            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                if (!doAllSchemas && testSchema != null) {
                    stmt.setString(1, testSchema.toLowerCase()); // PostgreSQL typically uses lowercase
                    stmt.setString(2, testSchema.toLowerCase());
                }

                try (ResultSet rs = stmt.executeQuery()) {
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
                        result.computeIfAbsent(schemaName, k -> new ArrayList<>()).add(objectDataType);
                    }
                }
            }
        }

        log.debug("Extracted {} object data types from {} schemas in PostgreSQL",
                result.values().stream().mapToInt(List::size).sum(), result.size());
        return result;
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

    public boolean hasObjectDataTypes() {
        try {
            Map<String, List<ObjectDataTypeMetaData>> objectDataTypes = fetchObjectDataTypes();
            return !objectDataTypes.isEmpty() && objectDataTypes.values().stream().anyMatch(list -> !list.isEmpty());
        } catch (Exception e) {
            log.warn("Could not check for PostgreSQL object data types", e);
            return false;
        }
    }
}