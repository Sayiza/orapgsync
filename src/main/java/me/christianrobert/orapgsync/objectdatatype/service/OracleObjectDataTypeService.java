package me.christianrobert.orapgsync.objectdatatype.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.config.service.ConfigService;
import me.christianrobert.orapgsync.database.service.OracleConnectionService;
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

@ApplicationScoped
public class OracleObjectDataTypeService {

    private static final Logger log = LoggerFactory.getLogger(OracleObjectDataTypeService.class);

    @Inject
    OracleConnectionService oracleConnectionService;

    @Inject
    ConfigService configService;

    public Map<String, Object> getObjectDataTypes() {
        Map<String, Object> result = new HashMap<>();

        try {
            log.info("Fetching Oracle object data types...");

            if (!oracleConnectionService.isConfigured()) {
                throw new IllegalStateException("Oracle connection not configured");
            }

            Map<String, List<ObjectDataTypeMetaData>> objectDataTypesBySchema = fetchObjectDataTypes();

            result.put("status", "success");
            result.put("objectDataTypesBySchema", objectDataTypesBySchema);
            result.put("totalCount", objectDataTypesBySchema.values().stream().mapToInt(List::size).sum());
            result.put("message", "Successfully retrieved Oracle object data types");

            log.info("Found {} Oracle object data types across {} schemas",
                    objectDataTypesBySchema.values().stream().mapToInt(List::size).sum(),
                    objectDataTypesBySchema.size());

        } catch (SQLException e) {
            log.error("SQL error while fetching Oracle object data types", e);
            result.put("status", "error");
            result.put("objectDataTypesBySchema", new HashMap<>());
            result.put("totalCount", 0);
            result.put("message", "Database error: " + e.getMessage());
            result.put("errorCode", e.getErrorCode());
            result.put("sqlState", e.getSQLState());
        } catch (Exception e) {
            log.error("Error while fetching Oracle object data types", e);
            result.put("status", "error");
            result.put("objectDataTypesBySchema", new HashMap<>());
            result.put("totalCount", 0);
            result.put("message", "Error retrieving object data types: " + e.getMessage());
        }

        return result;
    }

    private Map<String, List<ObjectDataTypeMetaData>> fetchObjectDataTypes() throws SQLException {
        Map<String, List<ObjectDataTypeMetaData>> result = new HashMap<>();

        try (Connection connection = oracleConnectionService.getConnection()) {
            // Check configuration settings
            boolean doAllSchemas = Boolean.TRUE.equals(configService.getConfigValueAsBoolean("do.all-schemas"));
            String testSchema = configService.getConfigValueAsString("do.only-test-schema");

            String sql;
            if (doAllSchemas) {
                sql = """
                    SELECT x.owner, x.object_name
                    FROM all_objects x
                    WHERE x.object_type = 'TYPE'
                      AND x.owner NOT IN ('SYS', 'SYSTEM', 'CTXSYS', 'DBSNMP', 'EXFSYS', 'LBACSYS', 'MDSYS', 'MGMT_VIEW', 'OLAPSYS', 'ORDDATA', 'OWBSYS', 'ORDPLUGINS', 'ORDSYS', 'OUTLN', 'SI_INFORMTN_SCHEMA', 'SYS', 'SYSMAN', 'SYSTEM', 'TSMSYS', 'WK_TEST', 'WKPROXY', 'WMSYS', 'XDB', 'APEX_040000', 'APEX_PUBLIC_USER', 'DIP', 'FLOWS_30000', 'FLOWS_FILES', 'MDDATA', 'ORACLE_OCM', 'XS$NULL', 'SPATIAL_CSW_ADMIN_USR', 'SPATIAL_WFS_ADMIN_USR', 'PUBLIC')
                      AND x.object_name IN (
                        SELECT DISTINCT y.data_type
                        FROM all_tab_cols y
                        WHERE y.data_type_owner IS NOT NULL
                          AND y.data_type_owner NOT IN ('SYS', 'SYSTEM', 'CTXSYS', 'DBSNMP', 'EXFSYS', 'LBACSYS', 'MDSYS', 'MGMT_VIEW', 'OLAPSYS', 'ORDDATA', 'OWBSYS', 'ORDPLUGINS', 'ORDSYS', 'OUTLN', 'SI_INFORMTN_SCHEMA', 'SYS', 'SYSMAN', 'SYSTEM', 'TSMSYS', 'WK_TEST', 'WKPROXY', 'WMSYS', 'XDB', 'APEX_040000', 'APEX_PUBLIC_USER', 'DIP', 'FLOWS_30000', 'FLOWS_FILES', 'MDDATA', 'ORACLE_OCM', 'XS$NULL', 'SPATIAL_CSW_ADMIN_USR', 'SPATIAL_WFS_ADMIN_USR', 'PUBLIC')
                      )
                    ORDER BY x.owner, x.object_name
                    """;
            } else {
                if (testSchema == null || testSchema.trim().isEmpty()) {
                    throw new IllegalStateException("Test schema not configured but do.all-schemas is false");
                }
                sql = """
                    SELECT x.owner, x.object_name
                    FROM all_objects x
                    WHERE x.object_type = 'TYPE'
                      AND x.owner = ?
                      AND x.object_name IN (
                        SELECT DISTINCT y.data_type
                        FROM all_tab_cols y
                        WHERE y.data_type_owner = ?
                      )
                    ORDER BY x.owner, x.object_name
                    """;
            }

            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                if (!doAllSchemas && testSchema != null) {
                    stmt.setString(1, testSchema.toUpperCase());
                    stmt.setString(2, testSchema.toUpperCase());
                }

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String owner = rs.getString("owner");
                        String objectName = rs.getString("object_name");

                        // Apply UserExcluder logic for consistency
                        if (UserExcluder.is2BeExclueded(owner, "TYPE")) {
                            log.debug("Excluding Oracle schema for object data types: {}", owner);
                            continue;
                        }

                        // Create object data type metadata with empty variables list (since we're just getting basic info)
                        ObjectDataTypeMetaData objectDataType = createObjectDataTypeMetaData(owner, objectName, new ArrayList<>());
                        result.computeIfAbsent(owner, k -> new ArrayList<>()).add(objectDataType);
                    }
                }
            }
        }

        log.debug("Extracted {} object data types from {} schemas in Oracle",
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
            log.warn("Could not check for Oracle object data types", e);
            return false;
        }
    }
}