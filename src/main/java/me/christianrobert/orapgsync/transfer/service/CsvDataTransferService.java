package me.christianrobert.orapgsync.transfer.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.model.table.ColumnMetadata;
import me.christianrobert.orapgsync.core.job.model.table.TableMetadata;
import me.christianrobert.orapgsync.core.tools.OracleTypeClassifier;
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

    @Inject
    private me.christianrobert.orapgsync.config.service.ConfigService configService;

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

        log.info("Starting data transfer for table: {}", qualifiedOracleName);

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
            log.info("Table {} is empty in Oracle, skipping transfer", qualifiedOracleName);
            return 0;
        }

        if (oracleRowCount == postgresRowCount && postgresRowCount > 0) {
            log.info("Table {} already has {} rows in PostgreSQL (matches Oracle), skipping transfer",
                    qualifiedOracleName, postgresRowCount);
            return postgresRowCount;
        }

        // Step 2: If PostgreSQL has data but counts don't match, truncate
        if (postgresRowCount > 0 && postgresRowCount != oracleRowCount) {
            log.info("Table {} has {} rows in PostgreSQL but {} in Oracle, truncating before transfer",
                    qualifiedOracleName, postgresRowCount, oracleRowCount);
            truncateTable(postgresConn, table.getSchema(), table.getTableName());
        }

        // Step 3: Determine batch size based on table characteristics
        int batchSize = determineBatchSize(table);
        log.debug("Using batch size {} for table {}", batchSize, qualifiedOracleName);

        // Step 4: Perform the actual data transfer
        long transferredRows = performCsvTransfer(oracleConn, postgresConn, table, batchSize);

        log.info("Data transfer completed for table {}: {} rows transferred", qualifiedOracleName, transferredRows);

        return transferredRows;
    }

    /**
     * Truncates a PostgreSQL table.
     */
    private void truncateTable(Connection postgresConn, String schema, String tableName) throws SQLException {
        String sql = "TRUNCATE TABLE \"" + schema + "\".\"" + tableName + "\"";
        try (PreparedStatement stmt = postgresConn.prepareStatement(sql)) {
            stmt.execute();
            log.info("Truncated table: {}.{}", schema, tableName);
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

        // Build column list for SELECT and COPY
        // The SELECT includes extra columns for ANYDATA extraction
        String columnList = buildOracleSelectColumnList(table);
        String quotedColumnList = buildQuotedColumnList(table);

        // Oracle SELECT query with ANYDATA extraction
        String selectSql = "SELECT " + columnList + " FROM " + qualifiedOracleName;

        // PostgreSQL COPY command
        String copySql = "COPY " + qualifiedPostgresName + " (" + quotedColumnList + ") FROM STDIN WITH (FORMAT csv, NULL '\\N', ENCODING 'UTF8')";

        log.debug("Oracle query: {}", selectSql);
        log.debug("PostgreSQL COPY: {}", copySql);

        long totalTransferred = 0;

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

                            // Check if LOB data should be excluded (config flag)
                            Boolean excludeLobData = configService.getConfigValueAsBoolean("exclude.lob-data");
                            if (excludeLobData != null && excludeLobData) {
                                // Skip LOB serialization - insert minimal dummy value to satisfy NOT NULL constraints
                                if (dataType.matches("BLOB|BFILE|LONG RAW")) {
                                    // Binary LOB - use empty bytea in PostgreSQL hex format
                                    // Format: \x (PostgreSQL's representation of empty bytea in CSV)
                                    lobValue = "\\x";
                                    log.trace("Using empty bytea dummy value for BLOB column {} (exclude.lob-data=true)",
                                             column.getColumnName());
                                } else if (dataType.matches("CLOB|NCLOB|LONG")) {
                                    // Character LOB - use empty string
                                    lobValue = "";
                                    log.trace("Using empty string dummy value for CLOB column {} (exclude.lob-data=true)",
                                             column.getColumnName());
                                } else {
                                    log.warn("Unknown LOB type {} for column {}, using empty string",
                                            dataType, column.getColumnName());
                                    lobValue = "";
                                }
                            } else {
                                // Normal LOB serialization
                                if (dataType.matches("BLOB|BFILE|LONG RAW")) {
                                    // Binary LOB - serialize to hex format
                                    lobValue = complexTypeSerializer.serializeBlobToHex(rs, rsColumnIndex, column);
                                } else if (dataType.matches("CLOB|NCLOB|LONG")) {
                                    // Character LOB - serialize to text
                                    lobValue = complexTypeSerializer.serializeClobToText(rs, rsColumnIndex, column);
                                } else {
                                    log.warn("Unknown LOB type {} for column {}", dataType, column.getColumnName());
                                }
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

                        String value = rs.getString(rsColumnIndex++); // Read _VALUE
                        String typeName = rs.getString(rsColumnIndex++); // Read _TYPE

                        // Build JSON wrapper
                        String jsonValue = buildAnydataJson(typeName, value);
                        csvPrinter.print(jsonValue);
                    } else if (isUserDefinedObjectType(column)) {
                        // User-defined object type - serialize to PostgreSQL ROW format
                        try {
                            String rowValue = complexTypeSerializer.serializeToPostgresRow(
                                    oracleConn, rs, rsColumnIndex, column);
                            csvPrinter.print(rowValue);
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
                            csvPrinter.print(jsonValue);
                        } catch (Exception e) {
                            log.error("Failed to serialize complex type {} for column {}: {}",
                                    dataType, column.getColumnName(), e.getMessage());
                            csvPrinter.print(null);
                        }
                        rsColumnIndex++;
                    } else {
                        // Simple type: use standard JDBC getString
                        String value = rs.getString(rsColumnIndex++);
                        csvPrinter.print(value);
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
     * Builds a comma-separated list of quoted column names (for PostgreSQL COPY).
     * This only includes the actual table columns, not the extraction helper columns.
     */
    private String buildQuotedColumnList(TableMetadata table) {
        return table.getColumns().stream()
                .map(col -> "\"" + col.getColumnName() + "\"")
                .reduce((a, b) -> a + ", " + b)
                .orElse("*");
    }
}
