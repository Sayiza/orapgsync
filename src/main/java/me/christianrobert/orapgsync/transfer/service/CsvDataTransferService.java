package me.christianrobert.orapgsync.transfer.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.model.table.ColumnMetadata;
import me.christianrobert.orapgsync.core.job.model.table.TableMetadata;
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
    private static final int BATCH_SIZE_LOB = 1_000;
    private static final int BATCH_SIZE_SIMPLE = 50_000;

    // Pipe buffer size for streaming CSV data
    private static final int PIPE_BUFFER_SIZE = 1024 * 1024; // 1MB

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

        log.info("Starting data transfer for table: {}", qualifiedOracleName);

        // Step 1: Check if transfer is needed (compare row counts)
        long oracleRowCount = rowCountService.getRowCount(oracleConn, table.getSchema(), table.getTableName());
        long postgresRowCount = rowCountService.getRowCount(postgresConn, table.getSchema(), table.getTableName());

        if (oracleRowCount == -1) {
            log.error("Failed to get Oracle row count for table: {}", qualifiedOracleName);
            throw new RuntimeException("Failed to get Oracle row count for table: " + qualifiedOracleName);
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
        String columnList = buildColumnList(table);
        String quotedColumnList = buildQuotedColumnList(table);

        // Oracle SELECT query
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
                    return produceOracleCsv(selectStmt, table, pipedOutput);
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
     */
    private long produceOracleCsv(PreparedStatement selectStmt, TableMetadata table,
                                 PipedOutputStream outputStream) throws SQLException, IOException {
        long rowCount = 0;

        CSVFormat csvFormat = CSVFormat.DEFAULT
                .builder()
                .setNullString("\\N")  // PostgreSQL NULL representation
                .build();

        try (ResultSet rs = selectStmt.executeQuery();
             StringWriter stringWriter = new StringWriter();
             CSVPrinter csvPrinter = new CSVPrinter(stringWriter, csvFormat)) {

            int columnCount = table.getColumns().size();

            while (rs.next()) {
                // Check for complex types and LOBs
                for (int i = 0; i < columnCount; i++) {
                    ColumnMetadata column = table.getColumns().get(i);
                    String dataType = column.getDataType();

                    if (isLobType(dataType)) {
                        // TODO: Handle BLOB/CLOB with hex encoding
                        // For now, skip LOB columns (this is a placeholder)
                        log.warn("LOB column {} in table {}.{} - LOB handling not yet implemented",
                                column.getColumnName(), table.getSchema(), table.getTableName());
                        csvPrinter.print(null);
                    } else if (isComplexOracleSystemType(column)) {
                        // Handle complex Oracle system types (ANYDATA, XMLTYPE, etc.)
                        // Serialize to JSON format for storage in PostgreSQL jsonb
                        String jsonValue = complexTypeSerializer.serializeToJson(rs, i + 1, column);
                        csvPrinter.print(jsonValue);
                    } else {
                        // Simple type: use standard JDBC getString
                        String value = rs.getString(i + 1);
                        csvPrinter.print(value);
                    }
                }

                csvPrinter.println();
                rowCount++;

                // Flush to pipe periodically
                if (rowCount % 1000 == 0) {
                    stringWriter.flush();
                    outputStream.write(stringWriter.toString().getBytes("UTF-8"));
                    stringWriter.getBuffer().setLength(0); // Clear buffer
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
     * Checks if a column is a complex Oracle system type requiring JSON serialization.
     */
    private boolean isComplexOracleSystemType(ColumnMetadata column) {
        // TODO: Detect complex Oracle system types (ANYDATA, XMLTYPE, AQ$_*, SDO_GEOMETRY, etc.)
        // For now, return false (only handle simple types in this iteration)
        String owner = column.getDataTypeOwner();
        String dataType = column.getDataType();

        // Check for known complex types with SYS or PUBLIC owner
        if (owner != null && (owner.equalsIgnoreCase("SYS") || owner.equalsIgnoreCase("PUBLIC"))) {
            return dataType != null && dataType.matches("ANYDATA|ANYTYPE|XMLTYPE|AQ\\$_.*|SDO_GEOMETRY|SDO_.*");
        }

        return false;
    }

    /**
     * Builds a comma-separated list of column names (unquoted, for Oracle).
     */
    private String buildColumnList(TableMetadata table) {
        return table.getColumns().stream()
                .map(ColumnMetadata::getColumnName)
                .reduce((a, b) -> a + ", " + b)
                .orElse("*");
    }

    /**
     * Builds a comma-separated list of quoted column names (for PostgreSQL).
     */
    private String buildQuotedColumnList(TableMetadata table) {
        return table.getColumns().stream()
                .map(col -> "\"" + col.getColumnName() + "\"")
                .reduce((a, b) -> a + ", " + b)
                .orElse("*");
    }
}
