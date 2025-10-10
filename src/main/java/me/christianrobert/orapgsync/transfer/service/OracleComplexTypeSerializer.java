package me.christianrobert.orapgsync.transfer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import me.christianrobert.orapgsync.core.job.model.table.ColumnMetadata;
import oracle.sql.ANYDATA;
import oracle.sql.BLOB;
import oracle.sql.CLOB;
import oracle.sql.Datum;
import oracle.sql.STRUCT;
import oracle.sql.TypeDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.Reader;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
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

    // LOB handling configuration
    private static final long MAX_INLINE_LOB_SIZE = 20 * 1024 * 1024; // 20MB
    private static final int LOB_CHUNK_SIZE = 8192; // 8KB chunks

    // Hex encoding lookup table for performance
    private static final char[] HEX_ARRAY = "0123456789abcdef".toCharArray();

    /**
     * Serializes a user-defined Oracle STRUCT/OBJECT type to PostgreSQL composite type format for CSV COPY.
     * This is used for custom object types that have been mapped to PostgreSQL composite types.
     *
     * Format for CSV COPY: (value1,value2,value3) or ("text with spaces",123,NULL)
     * For nested objects: (value1,"(nested1,nested2)",value3)
     *
     * Note: This is NOT the ROW() constructor format - PostgreSQL COPY expects raw composite literal syntax.
     *
     * @param connection Oracle database connection
     * @param rs ResultSet positioned at current row
     * @param columnIndex Column index (1-based)
     * @param column Column metadata
     * @return PostgreSQL composite literal string, or null if the value is NULL
     * @throws SQLException if database access fails
     */
    public String serializeToPostgresRow(Connection connection, ResultSet rs, int columnIndex, ColumnMetadata column) throws SQLException {
        Object obj = rs.getObject(columnIndex);

        if (obj == null) {
            return null; // NULL object → NULL in PostgreSQL
        }

        if (!(obj instanceof STRUCT)) {
            log.warn("Expected STRUCT for user-defined type {} in column {}, got: {}",
                    column.getDataType(), column.getColumnName(), obj.getClass().getName());
            return null;
        }

        STRUCT struct = (STRUCT) obj;
        return serializeStructToCompositeLiteral(struct);
    }

    /**
     * Recursively serializes an Oracle STRUCT to PostgreSQL composite literal format for CSV COPY.
     * Format: (value1,value2,value3) - parentheses with comma-separated values
     * Nested composites are quoted: (value1,"(nested1,nested2)",value3)
     */
    private String serializeStructToCompositeLiteral(STRUCT struct) throws SQLException {
        if (struct == null) {
            return null;
        }

        Object[] attributes = struct.getAttributes();

        if (attributes == null || attributes.length == 0) {
            return "()"; // Empty composite
        }

        StringBuilder literalBuilder = new StringBuilder("(");

        for (int i = 0; i < attributes.length; i++) {
            if (i > 0) {
                literalBuilder.append(",");
            }

            Object attrValue = attributes[i];
            literalBuilder.append(serializeAttributeValueForComposite(attrValue, false));
        }

        literalBuilder.append(")");
        return literalBuilder.toString();
    }

    /**
     * Serializes a single attribute value for use in PostgreSQL composite literal.
     *
     * PostgreSQL composite literal rules for CSV COPY:
     * - NULL values: unquoted NULL or empty position
     * - Numbers: unquoted (123, 45.67)
     * - Strings without special chars: can be unquoted (abc) or quoted ("abc")
     * - Strings with special chars (spaces, commas, quotes, backslashes, parens): MUST be quoted
     * - Nested composites: quoted composite literals: "(val1,val2)"
     * - Quotes inside strings: double the quote ("O""Brien")
     * - Backslashes: double the backslash ("path\\to\\file")
     *
     * @param value The attribute value
     * @param isNested True if this is a nested composite (needs extra quoting)
     */
    private String serializeAttributeValueForComposite(Object value, boolean isNested) throws SQLException {
        if (value == null) {
            return ""; // In composite literals, NULL is represented as empty string or omitted
        }

        // Handle nested STRUCT (recursive case)
        if (value instanceof STRUCT) {
            String nestedLiteral = serializeStructToCompositeLiteral((STRUCT) value);
            // Nested composites must be quoted and the quotes inside must be escaped
            return quoteCompositeValue(nestedLiteral);
        }

        // Handle Oracle Datum types
        if (value instanceof Datum) {
            Datum datum = (Datum) value;
            try {
                Object javaValue = datum.toJdbc();
                return serializeAttributeValueForComposite(javaValue, isNested);
            } catch (Exception e) {
                log.warn("Failed to convert Datum to JDBC type: {}", e.getMessage());
                String strValue = datum.stringValue();
                return quoteCompositeValue(strValue);
            }
        }

        // Handle standard Java types
        if (value instanceof String) {
            String strValue = (String) value;
            // Check if quoting is needed
            if (needsQuoting(strValue)) {
                return quoteCompositeValue(strValue);
            } else {
                return strValue; // Simple strings can be unquoted
            }
        }

        if (value instanceof Number) {
            return value.toString(); // Numbers are always unquoted
        }

        if (value instanceof Boolean) {
            return value.toString(); // Boolean: true/false unquoted
        }

        if (value instanceof Date || value instanceof Timestamp) {
            // Dates need quoting
            return quoteCompositeValue(value.toString());
        }

        // Fallback: convert to string and quote if needed
        log.debug("Unhandled attribute type {}: using toString()", value.getClass().getName());
        String strValue = value.toString();
        return needsQuoting(strValue) ? quoteCompositeValue(strValue) : strValue;
    }

    /**
     * Checks if a string value needs quoting in a composite literal.
     * Strings need quoting if they contain: spaces, commas, quotes, backslashes, parens, or are empty.
     */
    private boolean needsQuoting(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        // Check for special characters that require quoting
        return value.contains(" ") || value.contains(",") || value.contains("\"") ||
               value.contains("\\") || value.contains("(") || value.contains(")") ||
               value.contains("\n") || value.contains("\r") || value.contains("\t");
    }

    /**
     * Quotes and escapes a value for use in PostgreSQL composite literal.
     * Rules:
     * - Wrap in double quotes
     * - Double any internal quotes: " → ""
     * - Double any backslashes: \ → \\
     */
    private String quoteCompositeValue(String value) {
        if (value == null) {
            return "";
        }

        // Escape backslashes first (must be done before quotes)
        String escaped = value.replace("\\", "\\\\");
        // Escape double quotes
        escaped = escaped.replace("\"", "\"\"");

        return "\"" + escaped + "\"";
    }

    /**
     * Serializes an Oracle BLOB to PostgreSQL bytea hex format for CSV COPY.
     * Format: \\x48656c6c6f (hex encoding with \\x prefix, escaped for CSV)
     *
     * @param rs ResultSet positioned at current row
     * @param columnIndex Column index (1-based)
     * @param column Column metadata
     * @return Hex-encoded string for PostgreSQL bytea, or null if NULL/too large
     * @throws SQLException if database access fails
     */
    public String serializeBlobToHex(ResultSet rs, int columnIndex, ColumnMetadata column) throws SQLException {
        java.sql.Blob sqlBlob = rs.getBlob(columnIndex);

        if (sqlBlob == null) {
            return null; // NULL BLOB → NULL in PostgreSQL
        }

        // Convert to Oracle BLOB for better API support
        BLOB blob = (BLOB) sqlBlob;

        try {
            long size = blob.length();

            // Check size limit
            if (size > MAX_INLINE_LOB_SIZE) {
                log.warn("BLOB size {} bytes exceeds limit {} in column {}, skipping (will insert NULL)",
                        size, MAX_INLINE_LOB_SIZE, column.getColumnName());
                return null;
            }

            if (size == 0) {
                return "\\\\x"; // Empty BLOB → \x (escaped for CSV)
            }

            log.debug("Serializing BLOB of {} bytes in column {}", size, column.getColumnName());

            // Stream BLOB data in chunks and encode to hex
            return streamBlobToHex(blob, size);

        } catch (Exception e) {
            log.error("Failed to serialize BLOB in column {}: {}", column.getColumnName(), e.getMessage(), e);
            return null; // Insert NULL on error
        }
    }

    /**
     * Streams BLOB data in chunks and encodes to hex format.
     * This avoids loading the entire BLOB into memory at once.
     */
    private String streamBlobToHex(BLOB blob, long size) throws SQLException {
        // Pre-allocate StringBuilder with known size: \\x + 2 chars per byte
        // Add some buffer for safety
        int capacity = (int) (4 + (size * 2));
        StringBuilder hex = new StringBuilder(capacity);
        hex.append("\\\\x"); // Escaped \x prefix for CSV

        try (InputStream stream = blob.getBinaryStream()) {
            byte[] buffer = new byte[LOB_CHUNK_SIZE];
            int bytesRead;

            while ((bytesRead = stream.read(buffer)) != -1) {
                // Convert chunk to hex
                appendBytesAsHex(hex, buffer, 0, bytesRead);
            }

            return hex.toString();

        } catch (Exception e) {
            log.error("Failed to stream BLOB data: {}", e.getMessage(), e);
            throw new SQLException("BLOB streaming failed", e);
        }
    }

    /**
     * Converts bytes to hex characters and appends to StringBuilder.
     * High-performance implementation using lookup table.
     */
    private void appendBytesAsHex(StringBuilder sb, byte[] bytes, int offset, int length) {
        for (int i = 0; i < length; i++) {
            int v = bytes[offset + i] & 0xFF;
            sb.append(HEX_ARRAY[v >>> 4]);   // High nibble
            sb.append(HEX_ARRAY[v & 0x0F]);  // Low nibble
        }
    }

    /**
     * Serializes an Oracle CLOB to text format for CSV COPY.
     * For PostgreSQL text columns, the text is escaped according to CSV rules.
     *
     * @param rs ResultSet positioned at current row
     * @param columnIndex Column index (1-based)
     * @param column Column metadata
     * @return CLOB text content, or null if NULL/too large
     * @throws SQLException if database access fails
     */
    public String serializeClobToText(ResultSet rs, int columnIndex, ColumnMetadata column) throws SQLException {
        java.sql.Clob sqlClob = rs.getClob(columnIndex);

        if (sqlClob == null) {
            return null; // NULL CLOB → NULL in PostgreSQL
        }

        // Convert to Oracle CLOB for better API support
        CLOB clob = (CLOB) sqlClob;

        try {
            long size = clob.length();

            // Check size limit
            if (size > MAX_INLINE_LOB_SIZE) {
                log.warn("CLOB size {} characters exceeds limit {} in column {}, skipping (will insert NULL)",
                        size, MAX_INLINE_LOB_SIZE, column.getColumnName());
                return null;
            }

            if (size == 0) {
                return ""; // Empty CLOB → empty string
            }

            log.debug("Serializing CLOB of {} characters in column {}", size, column.getColumnName());

            // Stream CLOB data in chunks
            return streamClobToString(clob, size);

        } catch (Exception e) {
            log.error("Failed to serialize CLOB in column {}: {}", column.getColumnName(), e.getMessage(), e);
            return null; // Insert NULL on error
        }
    }

    /**
     * Streams CLOB data in chunks to avoid loading entire CLOB into memory.
     */
    private String streamClobToString(CLOB clob, long size) throws SQLException {
        // Pre-allocate StringBuilder with known size
        StringBuilder text = new StringBuilder((int) size);

        try (Reader reader = clob.getCharacterStream()) {
            char[] buffer = new char[LOB_CHUNK_SIZE];
            int charsRead;

            while ((charsRead = reader.read(buffer)) != -1) {
                text.append(buffer, 0, charsRead);
            }

            return text.toString();

        } catch (Exception e) {
            log.error("Failed to stream CLOB data: {}", e.getMessage(), e);
            throw new SQLException("CLOB streaming failed", e);
        }
    }

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
     * Serializes Oracle XMLTYPE to string.
     * XMLTYPE is mapped to PostgreSQL's native xml type, so we extract the XML string directly.
     *
     * Note: This method is not used in the standard data transfer flow since XMLTYPE is no longer
     * treated as a complex type. It's kept for backward compatibility and potential future use.
     */
    private String serializeXmlType(ResultSet rs, int columnIndex, ColumnMetadata column) throws SQLException {
        Object xmlObj = rs.getObject(columnIndex);

        if (xmlObj == null) {
            return null; // NULL XMLTYPE → NULL in PostgreSQL
        }

        try {
            // Try to use standard JDBC getString which works for XMLTYPE
            String xmlString = rs.getString(columnIndex);

            if (xmlString == null) {
                log.warn("XMLTYPE column {} returned null string", column.getColumnName());
                return null;
            }

            log.debug("Extracted XML from column {}, length: {} characters",
                    column.getColumnName(), xmlString.length());

            return xmlString;

        } catch (Exception e) {
            log.error("Failed to extract XMLTYPE from column {}: {}",
                    column.getColumnName(), e.getMessage(), e);
            throw new SQLException("Failed to extract XMLTYPE: " + e.getMessage(), e);
        }
    }

    /**
     * Serializes Oracle Advanced Queuing (AQ) types to JSON.
     * AQ types are Oracle STRUCT objects that represent JMS messages and related data structures.
     *
     * Supported AQ types:
     * - AQ$_JMS_TEXT_MESSAGE: JMS text messages
     * - AQ$_SIG_PROP: Message properties
     * - AQ$_RECIPIENTS: Message recipient lists
     *
     * The serialization extracts all attributes from the STRUCT and builds a structured JSON object.
     */
    private String serializeAqType(ResultSet rs, int columnIndex, ColumnMetadata column) throws SQLException {
        String dataType = column.getDataType();
        Object aqObject = rs.getObject(columnIndex);

        if (aqObject == null) {
            return null; // NULL AQ type → NULL in PostgreSQL
        }

        // AQ types are Oracle STRUCTs
        if (!(aqObject instanceof STRUCT)) {
            log.warn("Expected STRUCT for AQ type {} in column {}, got: {}",
                    dataType, column.getColumnName(), aqObject.getClass().getName());
            return buildJsonWrapper("SYS." + dataType, "<<Unexpected object type>>");
        }

        STRUCT struct = (STRUCT) aqObject;

        try {
            // Extract struct data based on AQ type
            Map<String, Object> aqData;

            if ("AQ$_JMS_TEXT_MESSAGE".equals(dataType)) {
                aqData = extractJmsTextMessage(struct);
            } else if ("AQ$_SIG_PROP".equals(dataType)) {
                aqData = extractSigProp(struct);
            } else if ("AQ$_RECIPIENTS".equals(dataType)) {
                aqData = extractRecipients(struct);
            } else {
                // Generic AQ type extraction
                log.debug("Using generic extraction for AQ type: {}", dataType);
                aqData = extractStructData(struct);
            }

            log.debug("Serialized AQ type {} for column {}: {} attributes",
                    dataType, column.getColumnName(), aqData.size());

            // Build JSON wrapper with Oracle type metadata
            return buildJsonWrapper("SYS." + dataType, aqData);

        } catch (Exception e) {
            log.error("Failed to serialize AQ type {} for column {}: {}",
                    dataType, column.getColumnName(), e.getMessage(), e);
            return buildErrorJson("SYS." + dataType, column.getColumnName(), e.getMessage());
        }
    }

    /**
     * Extracts AQ$_JMS_TEXT_MESSAGE structure.
     *
     * Oracle AQ$_JMS_TEXT_MESSAGE structure typically contains:
     * - Text content
     * - JMS headers (message ID, timestamp, correlation ID, etc.)
     * - Custom properties
     * - Message metadata
     *
     * The exact attribute structure may vary by Oracle version, so we extract
     * all attributes generically and add known field names for common attributes.
     */
    private Map<String, Object> extractJmsTextMessage(STRUCT struct) throws SQLException {
        Map<String, Object> messageData = new HashMap<>();

        Object[] attributes = struct.getAttributes();
        if (attributes == null || attributes.length == 0) {
            log.warn("JMS text message struct has no attributes");
            return messageData;
        }

        // Oracle AQ JMS message structure (typical layout - may vary by version):
        // The exact attribute positions may differ, so we extract all attributes
        // and provide meaningful names based on common patterns

        for (int i = 0; i < attributes.length; i++) {
            Object attr = attributes[i];

            // Convert Oracle types to Java types for JSON serialization
            Object convertedValue = convertOracleTypeToJava(attr);

            // Use generic attribute names since exact structure may vary
            String attributeName = "attr_" + i;

            // Try to identify common JMS message attributes by type and position
            // This is a best-effort approach since Oracle doesn't expose attribute names easily
            if (i == 0 && convertedValue instanceof String) {
                // First attribute is often the text content
                messageData.put("text_content", convertedValue);
                messageData.put(attributeName, convertedValue);
            } else if (convertedValue instanceof Map) {
                // Nested structures (headers, properties)
                messageData.put("nested_" + attributeName, convertedValue);
                messageData.put(attributeName, convertedValue);
            } else {
                messageData.put(attributeName, convertedValue);
            }
        }

        messageData.put("message_type", "JMS_TEXT_MESSAGE");
        messageData.put("attribute_count", attributes.length);

        return messageData;
    }

    /**
     * Extracts AQ$_SIG_PROP structure (message signature properties).
     */
    private Map<String, Object> extractSigProp(STRUCT struct) throws SQLException {
        Map<String, Object> propData = new HashMap<>();

        Object[] attributes = struct.getAttributes();
        if (attributes == null || attributes.length == 0) {
            return propData;
        }

        for (int i = 0; i < attributes.length; i++) {
            Object attr = attributes[i];
            Object convertedValue = convertOracleTypeToJava(attr);
            propData.put("attr_" + i, convertedValue);
        }

        propData.put("type", "SIG_PROP");
        propData.put("attribute_count", attributes.length);

        return propData;
    }

    /**
     * Extracts AQ$_RECIPIENTS structure (message recipients list).
     */
    private Map<String, Object> extractRecipients(STRUCT struct) throws SQLException {
        Map<String, Object> recipientsData = new HashMap<>();

        Object[] attributes = struct.getAttributes();
        if (attributes == null || attributes.length == 0) {
            return recipientsData;
        }

        for (int i = 0; i < attributes.length; i++) {
            Object attr = attributes[i];
            Object convertedValue = convertOracleTypeToJava(attr);
            recipientsData.put("attr_" + i, convertedValue);
        }

        recipientsData.put("type", "RECIPIENTS");
        recipientsData.put("attribute_count", attributes.length);

        return recipientsData;
    }

    /**
     * Converts Oracle-specific types to Java types suitable for JSON serialization.
     */
    private Object convertOracleTypeToJava(Object oracleValue) throws SQLException {
        if (oracleValue == null) {
            return null;
        }

        // Handle Oracle Datum types
        if (oracleValue instanceof Datum) {
            Datum datum = (Datum) oracleValue;
            try {
                return datum.toJdbc();
            } catch (Exception e) {
                log.debug("Failed to convert Datum to JDBC, using stringValue: {}", e.getMessage());
                return datum.stringValue();
            }
        }

        // Handle nested STRUCTs (recursive)
        if (oracleValue instanceof STRUCT) {
            return extractStructData((STRUCT) oracleValue);
        }

        // Handle arrays (Oracle ARRAY types)
        if (oracleValue instanceof java.sql.Array) {
            try {
                Object[] arrayElements = (Object[]) ((java.sql.Array) oracleValue).getArray();
                Object[] convertedArray = new Object[arrayElements.length];
                for (int i = 0; i < arrayElements.length; i++) {
                    convertedArray[i] = convertOracleTypeToJava(arrayElements[i]);
                }
                return convertedArray;
            } catch (Exception e) {
                log.warn("Failed to extract array elements: {}", e.getMessage());
                return "<<Array extraction failed>>";
            }
        }

        // Standard Java types (String, Number, Date, etc.) are already JSON-compatible
        return oracleValue;
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
