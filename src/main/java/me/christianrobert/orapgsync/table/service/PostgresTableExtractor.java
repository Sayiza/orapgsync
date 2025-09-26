package me.christianrobert.orapgsync.table.service;

import me.christianrobert.orapgsync.table.model.ColumnMetadata;
import me.christianrobert.orapgsync.table.model.ConstraintMetadata;
import me.christianrobert.orapgsync.table.model.TableMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class PostgresTableExtractor {

    private static final Logger log = LoggerFactory.getLogger(PostgresTableExtractor.class);

    public static List<TableMetadata> extractAllTables(Connection postgresConn, List<String> schemas) throws SQLException {
        List<TableMetadata> tableMetadataList = new ArrayList<>();

        for (String schema : schemas) {
            // Skip system schemas
            if (isSystemSchema(schema)) {
                log.debug("Skipping PostgreSQL system schema: {}", schema);
                continue;
            }

            List<String> tables = fetchTableNames(postgresConn, schema);

            for (String table : tables) {
                TableMetadata tableMetadata = fetchTableMetadata(postgresConn, schema, table);
                tableMetadataList.add(tableMetadata);
            }
            log.info("Extracted tables from PostgreSQL schema {}", schema);
        }
        return tableMetadataList;
    }

    private static boolean isSystemSchema(String schema) {
        return schema != null && (
                schema.equals("information_schema") ||
                schema.equals("pg_catalog") ||
                schema.equals("pg_toast") ||
                schema.startsWith("pg_temp_") ||
                schema.startsWith("pg_toast_temp_")
        );
    }

    private static List<String> fetchTableNames(Connection postgresConn, String schema) throws SQLException {
        List<String> result = new ArrayList<>();
        String sql = """
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = ?
              AND table_type = 'BASE TABLE'
            ORDER BY table_name
            """;

        try (PreparedStatement ps = postgresConn.prepareStatement(sql)) {
            ps.setString(1, schema.toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString("table_name"));
                }
            }
        }
        return result;
    }

    private static TableMetadata fetchTableMetadata(Connection postgresConn, String schema, String table) throws SQLException {
        TableMetadata tableMetadata = new TableMetadata(schema, table);

        // Fetch column metadata
        String columnSql = """
            SELECT column_name, data_type, character_maximum_length, numeric_precision, numeric_scale,
                   is_nullable, column_default
            FROM information_schema.columns
            WHERE table_schema = ? AND table_name = ?
            ORDER BY ordinal_position
            """;

        try (PreparedStatement ps = postgresConn.prepareStatement(columnSql)) {
            ps.setString(1, schema.toLowerCase());
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String columnName = rs.getString("column_name");
                    String dataType = rs.getString("data_type");
                    Integer charLength = rs.getInt("character_maximum_length");
                    if (rs.wasNull()) charLength = null;
                    Integer precision = rs.getInt("numeric_precision");
                    if (rs.wasNull()) precision = null;
                    Integer scale = rs.getInt("numeric_scale");
                    if (rs.wasNull()) scale = null;
                    boolean nullable = "YES".equals(rs.getString("is_nullable"));
                    String defaultValue = rs.getString("column_default");
                    if (defaultValue != null) {
                        defaultValue = defaultValue.trim();
                    }

                    ColumnMetadata column = new ColumnMetadata(columnName, dataType, charLength, precision, scale, nullable, defaultValue);
                    tableMetadata.addColumn(column);
                }
            }
        }

        // Fetch constraints
        fetchConstraints(postgresConn, schema, table, tableMetadata);

        return tableMetadata;
    }

    private static void fetchConstraints(Connection postgresConn, String schema, String table,
                                       TableMetadata tableMetadata) throws SQLException {
        String constraintSql = """
            SELECT tc.constraint_name, tc.constraint_type,
                   kcu.column_name,
                   ccu.table_schema AS referenced_table_schema,
                   ccu.table_name AS referenced_table_name,
                   ccu.column_name AS referenced_column_name,
                   rc.update_rule, rc.delete_rule,
                   cc.check_clause
            FROM information_schema.table_constraints tc
            LEFT JOIN information_schema.key_column_usage kcu
              ON tc.constraint_catalog = kcu.constraint_catalog
              AND tc.constraint_schema = kcu.constraint_schema
              AND tc.constraint_name = kcu.constraint_name
            LEFT JOIN information_schema.referential_constraints rc
              ON tc.constraint_catalog = rc.constraint_catalog
              AND tc.constraint_schema = rc.constraint_schema
              AND tc.constraint_name = rc.constraint_name
            LEFT JOIN information_schema.constraint_column_usage ccu
              ON rc.unique_constraint_catalog = ccu.constraint_catalog
              AND rc.unique_constraint_schema = ccu.constraint_schema
              AND rc.unique_constraint_name = ccu.constraint_name
            LEFT JOIN information_schema.check_constraints cc
              ON tc.constraint_catalog = cc.constraint_catalog
              AND tc.constraint_schema = cc.constraint_schema
              AND tc.constraint_name = cc.constraint_name
            WHERE tc.table_schema = ? AND tc.table_name = ?
            ORDER BY tc.constraint_type, tc.constraint_name, kcu.ordinal_position
            """;

        try (PreparedStatement ps = postgresConn.prepareStatement(constraintSql)) {
            ps.setString(1, schema.toLowerCase());
            ps.setString(2, table);

            try (ResultSet rs = ps.executeQuery()) {
                String currentConstraintName = null;
                ConstraintMetadata currentConstraint = null;

                while (rs.next()) {
                    String constraintName = rs.getString("constraint_name");
                    String constraintType = rs.getString("constraint_type");
                    String columnName = rs.getString("column_name");
                    String referencedSchema = rs.getString("referenced_table_schema");
                    String referencedTable = rs.getString("referenced_table_name");
                    String referencedColumn = rs.getString("referenced_column_name");
                    String updateRule = rs.getString("update_rule");
                    String deleteRule = rs.getString("delete_rule");
                    String checkClause = rs.getString("check_clause");

                    // New constraint or same constraint name?
                    if (!constraintName.equals(currentConstraintName)) {
                        // Save previous constraint if exists
                        if (currentConstraint != null && currentConstraint.isValid()) {
                            tableMetadata.addConstraint(currentConstraint);
                        }

                        // Create new constraint
                        String mappedType = mapConstraintType(constraintType);
                        if ("R".equals(mappedType) && referencedSchema != null && referencedTable != null) {
                            currentConstraint = new ConstraintMetadata(constraintName, mappedType,
                                                                     referencedSchema, referencedTable);
                            currentConstraint.setUpdateRule(updateRule);
                            currentConstraint.setDeleteRule(deleteRule);
                        } else {
                            currentConstraint = new ConstraintMetadata(constraintName, mappedType);
                        }

                        if ("C".equals(mappedType)) {
                            currentConstraint.setCheckCondition(checkClause);
                        }

                        currentConstraintName = constraintName;
                    }

                    // Add column to current constraint
                    if (currentConstraint != null && columnName != null) {
                        currentConstraint.addColumnName(columnName);

                        // For foreign keys, also add referenced column
                        if ("R".equals(currentConstraint.getConstraintType()) && referencedColumn != null) {
                            currentConstraint.addReferencedColumnName(referencedColumn);
                        }
                    }
                }

                // Don't forget the last constraint
                if (currentConstraint != null && currentConstraint.isValid()) {
                    tableMetadata.addConstraint(currentConstraint);
                }
            }
        }
    }

    private static String mapConstraintType(String postgresType) {
        return switch (postgresType) {
            case "PRIMARY KEY" -> "P";
            case "FOREIGN KEY" -> "R";
            case "UNIQUE" -> "U";
            case "CHECK" -> "C";
            default -> postgresType;
        };
    }
}