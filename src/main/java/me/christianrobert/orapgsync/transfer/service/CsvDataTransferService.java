package me.christianrobert.orapgsync.transfer.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.model.table.ColumnMetadata;
import me.christianrobert.orapgsync.core.job.model.table.TableMetadata;
import me.christianrobert.orapgsync.core.tools.OracleTypeClassifier;
import me.christianrobert.orapgsync.core.tools.PostgresIdentifierNormalizer;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Service for transferring data from Oracle to PostgreSQL using CSV batch loading.
 * Uses PostgreSQL COPY command for high-performance data transfer.
 */
@ApplicationScoped
public class CsvDataTransferService {

    private static final Logger log = LoggerFactory.getLogger(CsvDataTransferService.class);

    // Batch sizes based on data complexity
    private static final int BATCH_SIZE_DEFAULT = 10_000;
    private static final int BATCH_SIZE_LOB = 50;  // Small batch for LOB tables to manage memory
    private static final int BATCH_SIZE_SIMPLE = 50_000;

    // Pipe buffer size for streaming CSV data
    private static final int PIPE_BUFFER_SIZE = 1024 * 1024; // 1MB

    // LOB handling configuration
    private static final long MAX_INLINE_LOB_SIZE = 20 * 1024 * 1024; // 20MB - LOBs larger than this will be skipped
    private static final int LOB_CHUNK_SIZE = 8192; // 8KB chunks for streaming LOB data
    private static final int LOB_FLUSH_FREQUENCY = 10; // Flush CSV to pipe every 10 rows for LOB tables

    @Inject
    private RowCountService rowCountService;

    @Inject
    private OracleComplexTypeSerializer complexTypeSerializer;

    /**
     * Transfers data for a single table from Oracle to PostgreSQL.
     *
     * @param oracleConn  Oracle database connection
     * @param postgresConn PostgreSQL database connection
     * @param table       Table metadata
     * @return Number of rows transferred, or -1 if error
     * @throws Exception if transfer fails
     */
    public long transferTable(Connection oracleConn, Connection postgresConn, TableMetadata table) throws Exception {
        String qualifiedOracleName = table.getSchema() + "." + table.getTableName();
        String qualifiedPostgresName = "\"" + table.getSchema() + "\".\"" + table.getTableName() + "\"";

        log.debug("Starting data transfer for table: {}", qualifiedOracleName);

        // Step 1: Check if transfer is needed (compare row counts)
        long oracleRowCount = rowCountService.getRowCount(oracleConn, table.getSchema(), table.getTableName());
        long postgresRowCount = rowCountService.getRowCount(postgresConn, table.getSchema(), table.getTableName());

        if (oracleRowCount == -1) {
            log.error("Failed to get Oracle row count for table: {}", qualifiedOracleName);
            throw new RuntimeException("Failed to get Oracle row count for table: " + qualifiedOracleName);
        }

        if (postgresRowCount == -1) {
            log.warn("PostgreSQL table does not exist or is not accessible: {}", qualifiedPostgresName);
            throw new RuntimeException("PostgreSQL table does not exist or is not accessible: " + qualifiedPostgresName);
        }

        if (oracleRowCount == 0) {
            log.debug("Table {} is empty in Oracle, skipping transfer", qualifiedOracleName);
            return 0;
        }

        if (oracleRowCount == postgresRowCount && postgresRowCount > 0) {
            log.debug("Table {} already has {} rows in PostgreSQL (matches Oracle), skipping transfer",
                    qualifiedOracleName, postgresRowCount);
            return postgresRowCount;
        }

        // Step 2: If PostgreSQL has data but counts don't match, truncate
        if (postgresRowCount > 0 && postgresRowCount != oracleRowCount) {
            log.debug("Table {} has {} rows in PostgreSQL but {} in Oracle, truncating before transfer",
                    qualifiedOracleName, postgresRowCount, oracleRowCount);
            truncateTable(postgresConn, table.getSchema(), table.getTableName());
        }

        // Step 3: Determine batch size based on table characteristics
        int batchSize = determineBatchSize(table);
        log.debug("Using batch size {} for table {}", batchSize, qualifiedOracleName);

        // Step 4: Perform the actual data transfer
        long transferredRows = performCsvTransfer(oracleConn, postgresConn, table, batchSize);

        log.debug("Data transfer completed for table {}: {} rows transferred", qualifiedOracleName, transferredRows);

        return transferredRows;
    }

    /**
     * Truncates a PostgreSQL table.
     */
    private void truncateTable(Connection postgresConn, String schema, String tableName) throws SQLException {
        String sql = "TRUNCATE TABLE \"" + schema + "\".\"" + tableName + "\"";
        try (PreparedStatement stmt = postgresConn.prepareStatement(sql)) {
            stmt.execute();
            log.debug("Truncated table: {}.{}", schema, tableName);
        }
    }

    /**
     * Determines the appropriate batch size based on table characteristics.
     */
    private int determineBatchSize(TableMetadata table) {
        // Check for LOB columns
        boolean hasLobs = table.getColumns().stream()
                .anyMatch(col -> isLobType(col.getDataType()));

        if (hasLobs) {
            return BATCH_SIZE_LOB;
        }

        // Check if all columns are simple types
        boolean onlySimpleTypes = table.getColumns().stream()
                .allMatch(col -> isSimpleType(col.getDataType()));

        if (onlySimpleTypes) {
            return BATCH_SIZE_SIMPLE;
        }

        return BATCH_SIZE_DEFAULT;
    }

    /**
     * Checks if a data type is a LOB type.
     */
    private boolean isLobType(String dataType) {
        // TODO: Add binary data handling (BLOB, CLOB, BFILE)
        // For now, only handle simple types
        return dataType != null && dataType.matches("BLOB|CLOB|BFILE|NCLOB|LONG RAW|LONG");
    }

    /**
     * Checks if a data type is a simple (non-complex) type.
     */
    private boolean isSimpleType(String dataType) {
        if (dataType == null) {
            return false;
        }
        // Simple types: VARCHAR, CHAR, NUMBER, DATE, TIMESTAMP, etc.
        return dataType.matches("VARCHAR2?|CHAR|NVARCHAR2?|NCHAR|NUMBER|NUMERIC|INTEGER|INT|FLOAT|" +
                "DATE|TIMESTAMP.*|BOOLEAN|REAL|DOUBLE PRECISION|SMALLINT|BIGINT|DECIMAL");
    }

    /**
     * Performs the actual CSV-based data transfer using PostgreSQL COPY.
     */
    private long performCsvTransfer(Connection oracleConn, Connection postgresConn,
                                   TableMetadata table, int batchSize) throws Exception {
        String qualifiedOracleName = table.getSchema() + "." + table.getTableName();
        String qualifiedPostgresName = "\"" + table.getSchema() + "\".\"" + table.getTableName() + "\"";

        // Step 1: Detect oid columns (needed for COPY column list and staging workflow)
        List<String> oidColumns = detectOidColumns(postgresConn, table);
        if (!oidColumns.isEmpty()) {
            log.debug("Table {} has {} oid columns requiring staging: {}",
                      table.getTableName(), oidColumns.size(), oidColumns);
        }

        // Step 2: Build column lists
        // The SELECT includes extra columns for ANYDATA extraction
        // For oid columns, COPY targets staging columns
        String columnList = buildOracleSelectColumnList(table);
        String quotedColumnList = buildQuotedColumnList(table, oidColumns);  // Pass oidColumns for staging substitution

        // Step 3: Build SQL commands
        // Oracle SELECT query with ANYDATA extraction
        String selectSql = "SELECT " + columnList + " FROM " + qualifiedOracleName;

        // PostgreSQL COPY command
        String copySql = "COPY " + qualifiedPostgresName + " (" + quotedColumnList + ") FROM STDIN WITH (FORMAT csv, NULL '\\N', ENCODING 'UTF8')";

        log.debug("Oracle query: {}", selectSql);
        log.debug("PostgreSQL COPY: {}", copySql);

        // Step 4: Add staging columns (before COPY)
        if (!oidColumns.isEmpty()) {
            addStagingColumns(postgresConn, table, oidColumns);
        }

        long totalTransferred = 0;

        // Step 5: Perform COPY (unchanged)
        // Use piped streams for in-memory CSV transfer
        try (PipedOutputStream pipedOutput = new PipedOutputStream();
             PipedInputStream pipedInput = new PipedInputStream(pipedOutput, PIPE_BUFFER_SIZE);
             PreparedStatement selectStmt = oracleConn.prepareStatement(selectSql)) {

            // Configure streaming ResultSet
            selectStmt.setFetchSize(batchSize);

            // Get CopyManager from PostgreSQL connection
            CopyManager copyManager = new CopyManager(postgresConn.unwrap(BaseConnection.class));

            // Producer: Read from Oracle and write CSV to pipe
            CompletableFuture<Long> producerFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return produceOracleCsv(oracleConn, selectStmt, table, pipedOutput);
                } catch (Exception e) {
                    log.error("Producer failed", e);
                    throw new RuntimeException("CSV production failed", e);
                }
            });

            // Consumer: Read CSV from pipe and COPY to PostgreSQL
            CompletableFuture<Long> consumerFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return copyManager.copyIn(copySql, pipedInput);
                } catch (Exception e) {
                    log.error("Consumer failed", e);
                    throw new RuntimeException("PostgreSQL COPY failed", e);
                }
            });

            // Wait for both to complete
            try {
                long produced = producerFuture.get();
                long consumed = consumerFuture.get();

                if (produced != consumed) {
                    log.warn("Mismatch: produced {} rows, consumed {} rows", produced, consumed);
                }

                totalTransferred = consumed;

            } catch (InterruptedException | ExecutionException e) {
                log.error("Data transfer failed for table {}", qualifiedOracleName, e);
                throw new RuntimeException("Data transfer failed", e);
            }
        }

        // Step 6: Convert staging columns to Large Objects and cleanup (after COPY succeeds)
        if (!oidColumns.isEmpty()) {
            log.debug("Converting staging columns to Large Objects for table {}", qualifiedOracleName);
            convertStagingToLargeObjects(postgresConn, table, oidColumns);
            dropStagingColumns(postgresConn, table, oidColumns);
        }

        // Commit the transaction
        postgresConn.commit();

        return totalTransferred;
    }

    /**
     * Produces CSV data from Oracle ResultSet and writes to output stream.
     * The ResultSet may have extra columns for ANYDATA extraction (_VALUE and _TYPE).
     */
    private long produceOracleCsv(Connection oracleConn, PreparedStatement selectStmt, TableMetadata table,
                                 PipedOutputStream outputStream) throws SQLException, IOException {
        long rowCount = 0;

        CSVFormat csvFormat = CSVFormat.DEFAULT
                .builder()
                .setNullString("\\N")  // PostgreSQL NULL representation
                .build();

        // Detect if table has LOB columns for enhanced flushing
        boolean hasLobColumns = table.getColumns().stream()
                .anyMatch(col -> isLobType(col.getDataType()));
        int flushFrequency = hasLobColumns ? LOB_FLUSH_FREQUENCY : 1000;

        log.debug("Table {} has LOB columns: {}, flush frequency: {}",
                table.getTableName(), hasLobColumns, flushFrequency);

        try (ResultSet rs = selectStmt.executeQuery();
             StringWriter stringWriter = new StringWriter();
             CSVPrinter csvPrinter = new CSVPrinter(stringWriter, csvFormat)) {

            // Build column index mapping
            // ResultSet has extra columns for ANYDATA: original, _VALUE, _TYPE
            int rsColumnIndex = 1; // ResultSet columns are 1-based

            while (rs.next()) {
                rsColumnIndex = 1; // Reset for each row

                // Process each table column
                for (ColumnMetadata column : table.getColumns()) {
                    String dataType = column.getDataType();

                    if (isLobType(dataType)) {
                        // Handle BLOB/CLOB with streaming and hex encoding
                        try {
                            String lobValue = null;

                            // Serialize LOB data
                            if (dataType.matches("BLOB|BFILE|LONG RAW")) {
                                // Binary LOB - serialize to hex format
                                lobValue = complexTypeSerializer.serializeBlobToHex(rs, rsColumnIndex, column);
                            } else if (dataType.matches("CLOB|NCLOB|LONG")) {
                                // Character LOB - serialize to text
                                lobValue = complexTypeSerializer.serializeClobToText(rs, rsColumnIndex, column);
                            } else {
                                log.warn("Unknown LOB type {} for column {}", dataType, column.getColumnName());
                            }

                            csvPrinter.print(lobValue);

                        } catch (Exception e) {
                            log.error("Failed to serialize LOB type {} for column {}: {}",
                                    dataType, column.getColumnName(), e.getMessage());
                            csvPrinter.print(null); // Insert NULL on error
                        }
                        rsColumnIndex++;
                    } else if (OracleTypeClassifier.isComplexOracleSystemType(column.getDataTypeOwner(), dataType)
                            && "ANYDATA".equals(dataType)) {
                        // For ANYDATA: skip original column, read _VALUE and _TYPE columns
                        rsColumnIndex++; // Skip original ANYDATA column

                        String value = stripNullBytes(rs.getString(rsColumnIndex++)); // Read _VALUE
                        String typeName = stripNullBytes(rs.getString(rsColumnIndex++)); // Read _TYPE

                        // Build JSON wrapper
                        String jsonValue = buildAnydataJson(typeName, value);
                        csvPrinter.print(jsonValue);
                    } else if (isUserDefinedObjectType(column)) {
                        // User-defined object type - serialize to PostgreSQL ROW format
                        try {
                            String rowValue = complexTypeSerializer.serializeToPostgresRow(
                                    oracleConn, rs, rsColumnIndex, column);
                            // Row values are already processed by the serializer, just strip NULL bytes
                            csvPrinter.print(stripNullBytes(rowValue));
                        } catch (Exception e) {
                            log.error("Failed to serialize user-defined type {} for column {}: {}",
                                    dataType, column.getColumnName(), e.getMessage());
                            csvPrinter.print(null);
                        }
                        rsColumnIndex++;
                    } else if (OracleTypeClassifier.isComplexOracleSystemType(column.getDataTypeOwner(), dataType)) {
                        // Other complex Oracle system types - serialize to JSON (for jsonb columns)
                        try {
                            String jsonValue = complexTypeSerializer.serializeToJson(
                                    oracleConn, rs, rsColumnIndex, column);
                            // JSON values are already processed by the serializer, just strip NULL bytes
                            csvPrinter.print(stripNullBytes(jsonValue));
                        } catch (Exception e) {
                            log.error("Failed to serialize complex type {} for column {}: {}",
                                    dataType, column.getColumnName(), e.getMessage());
                            csvPrinter.print(null);
                        }
                        rsColumnIndex++;
                    } else {
                        // Simple type: use standard JDBC getString
                        String value = rs.getString(rsColumnIndex++);
                        // Strip NULL bytes from simple string values to ensure PostgreSQL UTF-8 compatibility
                        csvPrinter.print(stripNullBytes(value));
                    }
                }

                csvPrinter.println();
                rowCount++;

                // Flush to pipe periodically (more frequently for LOB tables)
                if (rowCount % flushFrequency == 0) {
                    stringWriter.flush();
                    outputStream.write(stringWriter.toString().getBytes("UTF-8"));
                    stringWriter.getBuffer().setLength(0); // Clear buffer

                    if (hasLobColumns && rowCount % 50 == 0) {
                        log.debug("Flushed {} rows with LOB data for table {}", rowCount, table.getTableName());
                    }
                }
            }

            // Final flush
            csvPrinter.flush();
            stringWriter.flush();
            if (stringWriter.getBuffer().length() > 0) {
                outputStream.write(stringWriter.toString().getBytes("UTF-8"));
            }

        } finally {
            outputStream.close();
        }

        return rowCount;
    }

    /**
     * Checks if a column is a user-defined object type (not a system type, not a simple type).
     * User-defined types have been mapped to PostgreSQL composite types.
     * Uses OracleTypeClassifier for consistent type classification across the application.
     */
    private boolean isUserDefinedObjectType(ColumnMetadata column) {
        String owner = column.getDataTypeOwner();
        String dataType = column.getDataType();

        // Must have an owner (schema) and data type
        if (owner == null || dataType == null) {
            return false;
        }

        // Exclude system-owned types
        if (owner.equalsIgnoreCase("SYS") || owner.equalsIgnoreCase("PUBLIC")) {
            return false;
        }

        // Exclude simple built-in types
        if (isSimpleType(dataType)) {
            return false;
        }

        // Exclude LOB types
        if (isLobType(dataType)) {
            return false;
        }

        // If it has a custom owner and is not a simple type or LOB, it's a user-defined type
        return true;
    }

    /**
     * Builds JSON wrapper for ANYDATA extracted value.
     * Format: {"oracleType": "SYS.NUMBER", "value": "123"}
     */
    private String buildAnydataJson(String typeName, String value) {
        if (typeName == null) {
            return null;
        }

        try {
            // Simple JSON construction (could use Jackson for more complex cases)
            StringBuilder json = new StringBuilder();
            json.append("{\"oracleType\":\"");
            json.append(escapeJson(typeName));
            json.append("\",\"value\":");

            if (value == null) {
                json.append("null");
            } else {
                json.append("\"");
                json.append(escapeJson(value));
                json.append("\"");
            }

            json.append("}");
            return json.toString();
        } catch (Exception e) {
            log.error("Failed to build JSON for ANYDATA: {}", e.getMessage());
            return "{\"error\":\"JSON construction failed\"}";
        }
    }

    /**
     * Escapes special characters for JSON strings.
     */
    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Strips NULL bytes (0x00) from a string to make it PostgreSQL UTF-8 compatible.
     * PostgreSQL's UTF-8 encoding does not allow NULL bytes, which can cause
     * "invalid byte sequence for encoding UTF8: 0x00" errors during COPY operations.
     *
     * NULL bytes are often found in Oracle data when:
     * - Data was imported from binary sources
     * - Application code explicitly inserted NULL bytes
     * - Binary data was stored in text columns
     *
     * @param value The string that may contain NULL bytes
     * @return The string with NULL bytes removed, or null if input is null
     */
    private String stripNullBytes(String value) {
        if (value == null) {
            return null;
        }

        // Check if string contains NULL bytes before doing expensive replace
        if (value.indexOf('\0') == -1) {
            return value; // No NULL bytes, return as-is for performance
        }

        // Remove all NULL bytes
        String stripped = value.replace("\0", "");

        // Log warning if NULL bytes were found (only for first occurrence to avoid log spam)
        if (stripped.length() != value.length()) {
            int nullByteCount = value.length() - stripped.length();
            log.debug("Stripped {} NULL byte(s) from string value (length: {} -> {})",
                    nullByteCount, value.length(), stripped.length());
        }

        return stripped;
    }

    /**
     * Builds Oracle SELECT column list with ANYDATA extraction.
     * For ANYDATA columns, adds extra columns: {column}_VALUE and {column}_TYPE
     */
    private String buildOracleSelectColumnList(TableMetadata table) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;

        for (ColumnMetadata column : table.getColumns()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;

            String columnName = column.getColumnName();

            if ("ANYDATA".equals(column.getDataType())
                    && OracleTypeClassifier.isComplexOracleSystemType(column.getDataTypeOwner(), column.getDataType())) {
                // For ANYDATA: add extraction columns
                // Original column (for debugging, but we won't use it in CSV)
                sb.append(columnName);

                // Add extracted value column
                sb.append(", CASE ANYDATA.GetTypeName(").append(columnName).append(") ");
                sb.append("WHEN 'SYS.NUMBER' THEN TO_CHAR(ANYDATA.AccessNumber(").append(columnName).append(")) ");
                sb.append("WHEN 'SYS.VARCHAR2' THEN ANYDATA.AccessVarchar2(").append(columnName).append(") ");
                sb.append("WHEN 'SYS.DATE' THEN TO_CHAR(ANYDATA.AccessDate(").append(columnName).append("), 'YYYY-MM-DD HH24:MI:SS') ");
                sb.append("WHEN 'SYS.TIMESTAMP' THEN TO_CHAR(ANYDATA.AccessTimestamp(").append(columnName).append("), 'YYYY-MM-DD HH24:MI:SS.FF6') ");
                sb.append("ELSE 'Unsupported Type' END AS ").append(columnName).append("_VALUE");

                // Add type name column
                sb.append(", ANYDATA.GetTypeName(").append(columnName).append(") AS ").append(columnName).append("_TYPE");
            } else {
                // User-defined types, other complex types, and simple columns - just select the column
                sb.append(columnName);
            }
        }

        return sb.toString();
    }

    /**
     * Builds a comma-separated list of normalized column names (for PostgreSQL COPY).
     * This only includes the actual table columns, not the extraction helper columns.
     * Column names are normalized to handle reserved words and special characters.
     *
     * For oid columns (BLOB/CLOB), substitutes staging column names:
     * - Regular column: "doc_content"
     * - OID column: "doc_content_staging" (COPY target for hex data)
     *
     * @param table Table metadata
     * @param oidColumns List of oid column names (use staging columns for these)
     * @return Comma-separated quoted column list for COPY command
     */
    private String buildQuotedColumnList(TableMetadata table, List<String> oidColumns) {
        return table.getColumns().stream()
                .map(col -> {
                    String columnName = col.getColumnName();
                    // If this is an oid column, use staging column for COPY
                    if (oidColumns.contains(columnName)) {
                        return PostgresIdentifierNormalizer.normalizeIdentifier(columnName + "_staging");
                    } else {
                        return PostgresIdentifierNormalizer.normalizeIdentifier(columnName);
                    }
                })
                .reduce((a, b) -> a + ", " + b)
                .orElse("*");
    }

    // ========== LOB→OID Migration Support (Staging Column Lifecycle) ==========

    /**
     * Detects columns with oid type in PostgreSQL table.
     * These columns need staging columns for COPY + conversion to Large Objects.
     *
     * Background: PostgreSQL oid columns store Large Object references (integer OIDs),
     * not binary data directly. We cannot COPY hex-encoded BLOB data into oid columns.
     * Solution: COPY into temporary bytea staging columns, then convert to Large Objects.
     *
     * @param postgresConn PostgreSQL connection
     * @param table Table metadata
     * @return List of column names with oid type
     * @throws SQLException if metadata query fails
     */
    private List<String> detectOidColumns(Connection postgresConn, TableMetadata table) throws SQLException {
        List<String> oidColumns = new java.util.ArrayList<>();

        String sql = "SELECT column_name " +
                     "FROM information_schema.columns " +
                     "WHERE table_schema = ? " +
                     "AND table_name = ? " +
                     "AND data_type = 'oid'";

        try (PreparedStatement stmt = postgresConn.prepareStatement(sql)) {
            stmt.setString(1, table.getSchema());
            stmt.setString(2, table.getTableName());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    oidColumns.add(rs.getString("column_name"));
                }
            }
        }

        log.debug("Found {} oid columns in table {}.{}: {}",
                  oidColumns.size(), table.getSchema(), table.getTableName(), oidColumns);

        return oidColumns;
    }

    /**
     * Adds staging columns for oid columns.
     * Staging columns are temporary bytea columns used during COPY.
     * Format: {original_column}_staging bytea
     *
     * These staging columns are:
     * - Created before COPY operation
     * - Used as COPY target for hex-encoded BLOB/CLOB data
     * - Converted to Large Objects after COPY completes
     * - Dropped after successful conversion
     *
     * @param postgresConn PostgreSQL connection
     * @param table Table metadata
     * @param oidColumns List of oid column names
     * @throws SQLException if ALTER TABLE fails
     */
    private void addStagingColumns(Connection postgresConn, TableMetadata table,
                                    List<String> oidColumns) throws SQLException {
        if (oidColumns.isEmpty()) {
            return;
        }

        String qualifiedTableName = "\"" + table.getSchema() + "\".\"" + table.getTableName() + "\"";

        for (String oidColumn : oidColumns) {
            String stagingColumn = oidColumn + "_staging";
            String sql = "ALTER TABLE " + qualifiedTableName +
                         " ADD COLUMN \"" + stagingColumn + "\" bytea";

            log.debug("Adding staging column: {} to {}", stagingColumn, qualifiedTableName);

            try (PreparedStatement stmt = postgresConn.prepareStatement(sql)) {
                stmt.execute();
            }
        }

        log.debug("Added {} staging columns to {}", oidColumns.size(), qualifiedTableName);
    }

    /**
     * Converts staging column data to Large Objects using lo_from_bytea().
     *
     * PostgreSQL function lo_from_bytea(loid oid, data bytea) creates a Large Object
     * from bytea data and returns its OID reference. Using loid=0 means PostgreSQL
     * auto-generates a unique OID.
     *
     * Process:
     * 1. For each oid column with staging data:
     *    UPDATE table SET doc_content = lo_from_bytea(0, doc_content_staging)
     *    WHERE doc_content_staging IS NOT NULL
     * 2. NULL staging values → NULL oid values (no Large Object created)
     * 3. Each non-NULL row gets a unique Large Object with unique OID
     *
     * Error Handling:
     * - Conversion failure → SQLException → Transaction rollback
     * - Staging columns remain for debugging
     *
     * @param postgresConn PostgreSQL connection
     * @param table Table metadata
     * @param oidColumns List of oid column names
     * @throws SQLException if conversion fails
     */
    private void convertStagingToLargeObjects(Connection postgresConn, TableMetadata table,
                                              List<String> oidColumns) throws SQLException {
        if (oidColumns.isEmpty()) {
            return;
        }

        String qualifiedTableName = "\"" + table.getSchema() + "\".\"" + table.getTableName() + "\"";

        for (String oidColumn : oidColumns) {
            String stagingColumn = oidColumn + "_staging";

            // UPDATE table SET doc_content = lo_from_bytea(0, doc_content_staging)
            // WHERE doc_content_staging IS NOT NULL
            String sql = "UPDATE " + qualifiedTableName +
                         " SET \"" + oidColumn + "\" = lo_from_bytea(0, \"" + stagingColumn + "\") " +
                         " WHERE \"" + stagingColumn + "\" IS NOT NULL";

            log.debug("Converting staging column {} to Large Objects for column {} in {}",
                      stagingColumn, oidColumn, qualifiedTableName);

            try (PreparedStatement stmt = postgresConn.prepareStatement(sql)) {
                int rowsUpdated = stmt.executeUpdate();
                log.debug("Converted {} rows for column {} (NULL rows remain NULL)",
                          rowsUpdated, oidColumn);
            }
        }

        log.debug("Completed Large Object conversion for {} columns in {}",
                  oidColumns.size(), qualifiedTableName);
    }

    /**
     * Drops staging columns after successful Large Object conversion.
     *
     * Staging columns are temporary and should be removed after:
     * 1. COPY completes successfully
     * 2. Staging data converted to Large Objects
     * 3. Transaction committed
     *
     * Error Handling:
     * - Drop failure is logged but does not fail the transaction
     * - Orphaned staging columns can be manually cleaned later
     * - This is non-critical cleanup (data already converted)
     *
     * @param postgresConn PostgreSQL connection
     * @param table Table metadata
     * @param oidColumns List of oid column names
     */
    private void dropStagingColumns(Connection postgresConn, TableMetadata table,
                                    List<String> oidColumns) {
        if (oidColumns.isEmpty()) {
            return;
        }

        String qualifiedTableName = "\"" + table.getSchema() + "\".\"" + table.getTableName() + "\"";

        for (String oidColumn : oidColumns) {
            String stagingColumn = oidColumn + "_staging";
            String sql = "ALTER TABLE " + qualifiedTableName +
                         " DROP COLUMN \"" + stagingColumn + "\"";

            log.debug("Dropping staging column: {} from {}", stagingColumn, qualifiedTableName);

            try (PreparedStatement stmt = postgresConn.prepareStatement(sql)) {
                stmt.execute();
            } catch (SQLException e) {
                // Log error but don't fail transaction (data already converted)
                log.warn("Failed to drop staging column {} from {}: {}",
                         stagingColumn, qualifiedTableName, e.getMessage());
            }
        }

        log.debug("Dropped {} staging columns from {}", oidColumns.size(), qualifiedTableName);
    }
}
