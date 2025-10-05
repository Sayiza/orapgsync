package me.christianrobert.orapgsync.transfer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import me.christianrobert.orapgsync.core.job.model.table.ColumnMetadata;
import oracle.sql.ANYDATA;
import oracle.sql.Datum;
import oracle.sql.STRUCT;
import oracle.sql.TypeDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for serializing complex Oracle data types to JSON format.
 * This service handles types like ANYDATA, XMLTYPE, SDO_GEOMETRY, AQ$_* types, etc.
 *
 * The serialization format preserves Oracle type metadata:
 * {
 *   "oracleType": "SCHEMA.TYPE_NAME",
 *   "value": { ... actual data ... }
 * }
 */
@ApplicationScoped
public class OracleComplexTypeSerializer {

    private static final Logger log = LoggerFactory.getLogger(OracleComplexTypeSerializer.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Serializes a complex Oracle type to JSON string for storage in PostgreSQL jsonb.
     *
     * @param connection Oracle database connection (needed for ANYDATA extraction)
     * @param rs ResultSet positioned at current row
     * @param columnIndex Column index (1-based)
     * @param column Column metadata
     * @return JSON string representation, or null if the value is NULL
     * @throws SQLException if database access fails
     */
    public String serializeToJson(Connection connection, ResultSet rs, int columnIndex, ColumnMetadata column) throws SQLException {
        String dataType = column.getDataType();

        try {
            if ("ANYDATA".equals(dataType)) {
                return serializeAnyData(connection, rs, columnIndex, column);
            } else if ("ANYTYPE".equals(dataType)) {
                return serializeAnyType(rs, columnIndex, column);
            } else if ("XMLTYPE".equals(dataType)) {
                return serializeXmlType(rs, columnIndex, column);
            } else if (dataType != null && dataType.startsWith("AQ$_")) {
                return serializeAqType(rs, columnIndex, column);
            } else if (dataType != null && dataType.startsWith("SDO_")) {
                return serializeSdoType(rs, columnIndex, column);
            } else {
                log.warn("Unknown complex type {} for column {} - treating as NULL",
                        dataType, column.getColumnName());
                return null;
            }
        } catch (Exception e) {
            log.error("Failed to serialize complex type {} for column {}: {}",
                    dataType, column.getColumnName(), e.getMessage(), e);
            // Return error information as JSON instead of failing the entire transfer
            return buildErrorJson(dataType, column.getColumnName(), e.getMessage());
        }
    }

    /**
     * Serializes Oracle ANYDATA type to JSON.
     * ANYDATA is a self-describing wrapper that can contain any Oracle type.
     * Uses PL/SQL to extract the actual value since JDBC doesn't expose ANYDATA methods directly.
     */
    private String serializeAnyData(Connection connection, ResultSet rs, int columnIndex, ColumnMetadata column) throws SQLException {
        ANYDATA anydataObj = (ANYDATA) rs.getObject(columnIndex);

        if (anydataObj == null) {
            return null; // NULL ANYDATA → NULL in PostgreSQL
        }

        try {
            // Use PL/SQL to extract type name and value from ANYDATA
            // We need to pass the ANYDATA object and get back the type and value
            AnydataExtraction extraction = extractAnydataViaPLSQL(connection, anydataObj);

            if (extraction == null) {
                log.warn("Failed to extract ANYDATA for column {}", column.getColumnName());
                return buildJsonWrapper("SYS.ANYDATA", "<<extraction failed>>");
            }

            log.debug("ANYDATA column {} contains type {}: {}",
                    column.getColumnName(), extraction.typeName, extraction.value);

            // Build JSON wrapper with the extracted type and value
            return buildJsonWrapper(extraction.typeName, extraction.value);

        } catch (Exception e) {
            log.error("Failed to extract ANYDATA value from column {}: {}",
                    column.getColumnName(), e.getMessage(), e);
            return buildErrorJson("SYS.ANYDATA", column.getColumnName(), e.getMessage());
        }
    }

    /**
     * Helper class to hold extracted ANYDATA information.
     */
    private static class AnydataExtraction {
        String typeName;
        String value;

        AnydataExtraction(String typeName, String value) {
            this.typeName = typeName;
            this.value = value;
        }
    }

    /**
     * Extracts ANYDATA type and value using PL/SQL block.
     * This is necessary because JDBC doesn't expose ANYDATA access methods.
     */
    private AnydataExtraction extractAnydataViaPLSQL(Connection connection, ANYDATA anydata) throws SQLException {
        // PL/SQL block that extracts type name and converts value to string
        String plsql =
            "DECLARE " +
            "  v_anydata ANYDATA := ?; " +
            "  v_type_name VARCHAR2(200); " +
            "  v_value VARCHAR2(4000); " +
            "  v_number NUMBER; " +
            "  v_varchar VARCHAR2(4000); " +
            "  v_date DATE; " +
            "  v_timestamp TIMESTAMP; " +
            "  v_res NUMBER; " +
            "BEGIN " +
            "  IF v_anydata IS NULL THEN " +
            "    ? := NULL; " +
            "    ? := NULL; " +
            "    RETURN; " +
            "  END IF; " +
            "  " +
            "  v_type_name := ANYDATA.GetTypeName(v_anydata); " +
            "  " +
            "  CASE v_type_name " +
            "    WHEN 'SYS.NUMBER' THEN " +
            "      v_res := ANYDATA.AccessNumber(v_anydata, v_number); " +
            "      v_value := TO_CHAR(v_number); " +
            "    WHEN 'SYS.VARCHAR2' THEN " +
            "      v_res := ANYDATA.AccessVarchar2(v_anydata, v_varchar); " +
            "      v_value := v_varchar; " +
            "    WHEN 'SYS.DATE' THEN " +
            "      v_res := ANYDATA.AccessDate(v_anydata, v_date); " +
            "      v_value := TO_CHAR(v_date, 'YYYY-MM-DD HH24:MI:SS'); " +
            "    WHEN 'SYS.TIMESTAMP' THEN " +
            "      v_res := ANYDATA.AccessTimestamp(v_anydata, v_timestamp); " +
            "      v_value := TO_CHAR(v_timestamp, 'YYYY-MM-DD HH24:MI:SS.FF'); " +
            "    ELSE " +
            "      v_value := '<<Unsupported ANYDATA type: ' || v_type_name || '>>'; " +
            "  END CASE; " +
            "  " +
            "  ? := v_type_name; " +
            "  ? := v_value; " +
            "END;";

        try (CallableStatement stmt = connection.prepareCall(plsql)) {
            // Input parameter: the ANYDATA object
            stmt.setObject(1, anydata);

            // Output parameters: type name and value
            stmt.registerOutParameter(2, java.sql.Types.VARCHAR);
            stmt.registerOutParameter(3, java.sql.Types.VARCHAR);
            stmt.registerOutParameter(4, java.sql.Types.VARCHAR);
            stmt.registerOutParameter(5, java.sql.Types.VARCHAR);

            stmt.execute();

            String typeName = stmt.getString(4);
            String value = stmt.getString(5);

            if (typeName == null) {
                return null;
            }

            return new AnydataExtraction(typeName, value);

        } catch (SQLException e) {
            log.error("PL/SQL extraction failed: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Extracts value from an ANYDATA object using a generic approach.
     * ANYDATA in Oracle JDBC is often represented as STRUCT or other Oracle types.
     */
    private Object extractAnydataValueGeneric(Object anydataObj) throws SQLException {
        try {
            // Check if it's a STRUCT (common representation)
            if (anydataObj instanceof STRUCT) {
                STRUCT struct = (STRUCT) anydataObj;
                return extractStructData(struct);
            }

            // Check if it's a Datum (Oracle's generic data holder)
            if (anydataObj instanceof Datum) {
                Datum datum = (Datum) anydataObj;
                // Try to convert to string representation
                return datum.stringValue();
            }

            // Fallback: use toString()
            return anydataObj.toString();

        } catch (Exception e) {
            log.error("Failed to extract ANYDATA value: {}", e.getMessage(), e);
            return "<<Extraction error: " + e.getMessage() + ">>";
        }
    }

    /**
     * Extracts data from an Oracle STRUCT (custom object type).
     * Returns a map of attribute names to values.
     */
    private Map<String, Object> extractStructData(STRUCT struct) throws SQLException {
        Map<String, Object> data = new HashMap<>();

        if (struct == null) {
            return data;
        }

        try {
            Object[] attributes = struct.getAttributes();

            // Get type name for context
            TypeDescriptor typeDesc = struct.getDescriptor();
            String typeName = typeDesc.getName();
            data.put("_oracleStructType", typeName);

            // Extract attributes as array since we can't easily get attribute names
            // without StructMetaData which may not be available in all Oracle JDBC versions
            for (int i = 0; i < attributes.length; i++) {
                Object attrValue = attributes[i];

                // Convert Oracle-specific types to Java types
                if (attrValue instanceof Datum) {
                    attrValue = ((Datum) attrValue).stringValue();
                } else if (attrValue instanceof STRUCT) {
                    // Nested object - recurse
                    attrValue = extractStructData((STRUCT) attrValue);
                }

                data.put("attr_" + i, attrValue);
            }
        } catch (Exception e) {
            log.error("Failed to extract STRUCT data: {}", e.getMessage(), e);
            data.put("error", "Failed to extract: " + e.getMessage());
        }

        return data;
    }

    /**
     * Builds a JSON wrapper that preserves Oracle type information.
     * Format: {"oracleType": "TYPE_NAME", "value": {...}}
     */
    private String buildJsonWrapper(String oracleType, Object value) {
        try {
            ObjectNode wrapper = objectMapper.createObjectNode();
            wrapper.put("oracleType", oracleType);
            wrapper.putPOJO("value", value);
            return objectMapper.writeValueAsString(wrapper);
        } catch (Exception e) {
            log.error("Failed to build JSON wrapper for type {}: {}", oracleType, e.getMessage());
            return String.format("{\"oracleType\":\"%s\",\"value\":null,\"error\":\"%s\"}",
                    oracleType, e.getMessage());
        }
    }

    /**
     * Builds an error JSON object when serialization fails.
     */
    private String buildErrorJson(String dataType, String columnName, String errorMessage) {
        try {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("oracleType", dataType);
            error.put("column", columnName);
            error.put("error", errorMessage);
            error.putNull("value");
            return objectMapper.writeValueAsString(error);
        } catch (Exception e) {
            return String.format("{\"error\":\"Serialization failed\",\"column\":\"%s\"}", columnName);
        }
    }

    /**
     * Serializes ANYTYPE - similar to ANYDATA but represents type definitions.
     * TODO: Implement when needed
     */
    private String serializeAnyType(ResultSet rs, int columnIndex, ColumnMetadata column) throws SQLException {
        log.warn("ANYTYPE serialization not yet implemented for column {}", column.getColumnName());
        return buildJsonWrapper("SYS.ANYTYPE", "<<ANYTYPE not yet implemented>>");
    }

    /**
     * Serializes Oracle XMLTYPE to JSON.
     * TODO: Implement when needed
     */
    private String serializeXmlType(ResultSet rs, int columnIndex, ColumnMetadata column) throws SQLException {
        log.warn("XMLTYPE serialization not yet implemented for column {}", column.getColumnName());
        // Future implementation: extract XML string and embed in JSON
        return buildJsonWrapper("SYS.XMLTYPE", "<<XMLTYPE not yet implemented>>");
    }

    /**
     * Serializes Oracle Advanced Queuing (AQ) types.
     * TODO: Implement when needed
     */
    private String serializeAqType(ResultSet rs, int columnIndex, ColumnMetadata column) throws SQLException {
        String dataType = column.getDataType();
        log.warn("AQ type {} serialization not yet implemented for column {}", dataType, column.getColumnName());
        return buildJsonWrapper("SYS." + dataType, "<<AQ type not yet implemented>>");
    }

    /**
     * Serializes Oracle Spatial (SDO) types like SDO_GEOMETRY.
     * TODO: Implement when needed (could convert to GeoJSON)
     */
    private String serializeSdoType(ResultSet rs, int columnIndex, ColumnMetadata column) throws SQLException {
        String dataType = column.getDataType();
        log.warn("SDO type {} serialization not yet implemented for column {}", dataType, column.getColumnName());
        return buildJsonWrapper("MDSYS." + dataType, "<<SDO type not yet implemented>>");
    }
}
