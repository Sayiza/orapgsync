package me.christianrobert.orapgsync.transfer.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Service for extracting row counts from database tables.
 * This service is used by both row count extraction jobs and data transfer jobs
 * to determine the number of rows in a table.
 */
@ApplicationScoped
public class RowCountService {

    private static final Logger log = LoggerFactory.getLogger(RowCountService.class);

    /**
     * Gets the row count for a specific table.
     *
     * @param connection Database connection
     * @param schema     Schema name
     * @param tableName  Table name
     * @return Row count, or -1 if an error occurs
     */
    public long getRowCount(Connection connection, String schema, String tableName) {
        try {
            return getRowCountForTable(connection, schema, tableName);
        } catch (Exception e) {
            log.error("Failed to get row count for table: {}.{}", schema, tableName, e);
            return -1;
        }
    }

    /**
     * Gets the row count for a table, throwing exceptions on failure.
     * Handles both Oracle (unquoted) and PostgreSQL (quoted) identifier syntax.
     *
     * @param connection Database connection
     * @param schema     Schema name
     * @param tableName  Table name
     * @return Row count
     * @throws Exception if the query fails
     */
    private long getRowCountForTable(Connection connection, String schema, String tableName) throws Exception {
        // Detect database type from connection metadata
        String dbProductName = connection.getMetaData().getDatabaseProductName();
        boolean isPostgres = dbProductName != null && dbProductName.toLowerCase().contains("postgres");

        // Build SQL based on database type
        String sql;
        String countColumn;
        if (isPostgres) {
            // PostgreSQL: use quoted identifiers and lowercase column alias
            sql = "SELECT COUNT(*) as row_count FROM \"" + schema + "\".\"" + tableName + "\"";
            countColumn = "row_count";
        } else {
            // Oracle: use unquoted identifiers and uppercase column alias
            sql = "SELECT COUNT(*) as ROW_COUNT FROM " + schema + "." + tableName;
            countColumn = "ROW_COUNT";
        }

        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            if (rs.next()) {
                return rs.getLong(countColumn);
            } else {
                throw new RuntimeException("No result returned from count query");
            }
        }
    }
}
