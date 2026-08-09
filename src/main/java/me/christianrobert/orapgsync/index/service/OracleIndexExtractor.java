package me.christianrobert.orapgsync.index.service;

import jakarta.enterprise.context.ApplicationScoped;
import me.christianrobert.orapgsync.core.job.model.index.IndexKeyPart;
import me.christianrobert.orapgsync.core.job.model.index.IndexMetadata;
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
import java.util.regex.Pattern;

/**
 * Extracts non-constraint indexes from the Oracle data dictionary.
 *
 * <h2>Constraint-backed indexes are excluded in SQL</h2>
 *
 * <p>PostgreSQL creates an index of its own whenever a primary key or unique constraint is
 * created, so migrating Oracle's equivalent as well would produce a duplicate on every key. The
 * exclusion joins {@code ALL_CONSTRAINTS.INDEX_NAME} rather than matching {@code SYS_C%} names,
 * because a constraint index named by hand - which is common - would pass a name pattern
 * untouched.</p>
 *
 * <h2>Descending indexes</h2>
 *
 * <p>Oracle has no true descending index: {@code CREATE INDEX ... (col DESC)} is stored as a
 * function-based index over a hidden {@code SYS_NC0000n$} column, and that hidden name is what
 * {@code ALL_IND_COLUMNS} reports. Emitting it verbatim would produce statements referencing
 * columns that do not exist. The real column is recovered from
 * {@code ALL_IND_EXPRESSIONS}, where the expression for such a key is just the quoted column
 * name, and the key is turned back into a plain descending column.</p>
 *
 * <h2>Why expressions are read in a separate query</h2>
 *
 * <p>{@code ALL_IND_EXPRESSIONS.COLUMN_EXPRESSION} is a {@code LONG}. The Oracle driver requires
 * a {@code LONG} to be read after the other columns of its row, so it is selected last and read
 * last. This mirrors how {@code OracleViewExtractionJob} reads {@code ALL_VIEWS.TEXT}.</p>
 */
@ApplicationScoped
public class OracleIndexExtractor {

    private static final Logger log = LoggerFactory.getLogger(OracleIndexExtractor.class);

    /** An unquoted Oracle identifier - used to tell a bare column apart from a real expression. */
    private static final Pattern SIMPLE_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_$#]*$");

    /** Oracle's hidden virtual columns backing function-based and descending indexes. */
    private static final Pattern HIDDEN_COLUMN = Pattern.compile("^SYS_NC\\d+\\$$", Pattern.CASE_INSENSITIVE);

    /**
     * Index types worth attempting. IOT and cluster indexes are structural parts of storage
     * organisations that do not exist in PostgreSQL, so they are excluded here rather than
     * reported. DOMAIN indexes are deliberately included so the creation job can report them as
     * unsupported instead of dropping them silently.
     */
    private static final String INDEX_SQL = """
            SELECT i.index_name,
                   i.table_name,
                   i.uniqueness,
                   i.index_type
            FROM all_indexes i
            WHERE i.table_owner = ?
              AND i.index_type IN ('NORMAL', 'NORMAL/REV', 'BITMAP',
                                   'FUNCTION-BASED NORMAL', 'FUNCTION-BASED BITMAP', 'DOMAIN')
              AND i.status IN ('VALID', 'N/A')
              AND i.index_name NOT LIKE 'BIN$%'
              AND i.table_name NOT LIKE 'BIN$%'
              AND NOT EXISTS (
                    SELECT 1
                    FROM all_constraints c
                    WHERE c.owner = i.table_owner
                      AND c.table_name = i.table_name
                      AND c.index_name = i.index_name
                      AND c.constraint_type IN ('P', 'U')
              )
            ORDER BY i.table_name, i.index_name
            """;

    private static final String INDEX_COLUMN_SQL = """
            SELECT ic.column_position, ic.descend, ic.column_name
            FROM all_ind_columns ic
            WHERE ic.index_owner = ? AND ic.index_name = ?
            ORDER BY ic.column_position
            """;

    /** {@code column_expression} is a LONG and must therefore be selected and read last. */
    private static final String INDEX_EXPRESSION_SQL = """
            SELECT ie.column_position, ie.column_expression
            FROM all_ind_expressions ie
            WHERE ie.index_owner = ? AND ie.index_name = ?
            ORDER BY ie.column_position
            """;

    /**
     * Extracts all migratable indexes for one schema.
     *
     * @param connection  open Oracle connection
     * @param schema      schema to extract from (case-insensitive)
     * @param knownTables lowercase table names that were migrated; indexes on anything else are
     *                    skipped, since their table will not exist in PostgreSQL
     */
    public List<IndexMetadata> extractIndexes(Connection connection, String schema,
                                              java.util.Set<String> knownTables) throws SQLException {
        List<IndexMetadata> indexes = new ArrayList<>();

        try (PreparedStatement stmt = connection.prepareStatement(INDEX_SQL)) {
            stmt.setString(1, schema.toUpperCase());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String indexName = rs.getString("index_name");
                    String tableName = rs.getString("table_name");
                    String uniqueness = rs.getString("uniqueness");
                    String indexType = rs.getString("index_type");

                    if (knownTables != null && !knownTables.isEmpty()
                            && !knownTables.contains(tableName.toLowerCase())) {
                        log.debug("Skipping index {}: table {}.{} was not migrated",
                                indexName, schema, tableName);
                        continue;
                    }

                    List<IndexKeyPart> keyParts = readKeyParts(connection, schema, indexName);
                    if (keyParts.isEmpty()) {
                        log.warn("Skipping index {}.{}: no usable key columns could be resolved",
                                schema, indexName);
                        continue;
                    }

                    indexes.add(new IndexMetadata(schema, tableName, indexName,
                            "UNIQUE".equalsIgnoreCase(uniqueness), indexType, keyParts));
                }
            }
        }

        log.info("Extracted {} non-constraint indexes from schema {}", indexes.size(), schema);
        return indexes;
    }

    /**
     * Resolves the ordered key parts of one index, folding hidden virtual columns back into the
     * plain columns or expressions they stand for.
     */
    private List<IndexKeyPart> readKeyParts(Connection connection, String schema, String indexName)
            throws SQLException {

        Map<Integer, String> expressions = readExpressions(connection, schema, indexName);
        List<IndexKeyPart> keyParts = new ArrayList<>();

        try (PreparedStatement stmt = connection.prepareStatement(INDEX_COLUMN_SQL)) {
            stmt.setString(1, schema.toUpperCase());
            stmt.setString(2, indexName);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int position = rs.getInt("column_position");
                    boolean descending = "DESC".equalsIgnoreCase(rs.getString("descend"));
                    String columnName = rs.getString("column_name");

                    String expression = expressions.get(position);
                    if (expression != null) {
                        keyParts.add(keyPartFromExpression(expression, descending));
                    } else if (HIDDEN_COLUMN.matcher(columnName).matches()) {
                        // A virtual column with no expression cannot be reproduced; dropping just
                        // this key would silently change what the index does, so drop the index.
                        log.warn("Index {}.{} references hidden column {} with no expression - "
                                + "index cannot be migrated", schema, indexName, columnName);
                        return List.of();
                    } else {
                        keyParts.add(IndexKeyPart.ofColumn(columnName, descending));
                    }
                }
            }
        }

        return keyParts;
    }

    /**
     * Turns an Oracle index expression into a key part. A descending index stores the plain
     * column name as its "expression", so an expression that is nothing but a quoted identifier
     * becomes a column key again rather than an expression key.
     */
    static IndexKeyPart keyPartFromExpression(String expression, boolean descending) {
        String trimmed = expression.trim();
        String unquoted = trimmed;
        if (unquoted.length() > 1 && unquoted.startsWith("\"") && unquoted.endsWith("\"")) {
            unquoted = unquoted.substring(1, unquoted.length() - 1);
        }

        if (SIMPLE_IDENTIFIER.matcher(unquoted).matches()) {
            return IndexKeyPart.ofColumn(unquoted, descending);
        }
        return IndexKeyPart.ofExpression(trimmed, descending);
    }

    private Map<Integer, String> readExpressions(Connection connection, String schema, String indexName)
            throws SQLException {
        Map<Integer, String> expressions = new HashMap<>();

        try (PreparedStatement stmt = connection.prepareStatement(INDEX_EXPRESSION_SQL)) {
            stmt.setString(1, schema.toUpperCase());
            stmt.setString(2, indexName);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    // Read in select order: the LONG expression must come after the position.
                    int position = rs.getInt("column_position");
                    String expression = rs.getString("column_expression");
                    if (expression != null) {
                        expressions.put(position, expression);
                    }
                }
            }
        }

        return expressions;
    }
}
