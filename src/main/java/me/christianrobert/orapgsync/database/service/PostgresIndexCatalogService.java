package me.christianrobert.orapgsync.database.service;

import jakarta.enterprise.context.ApplicationScoped;
import me.christianrobert.orapgsync.core.job.model.index.IndexKeyPart;
import me.christianrobert.orapgsync.core.job.model.index.IndexMetadata;
import me.christianrobert.orapgsync.core.job.model.index.IndexSignature;
import me.christianrobert.orapgsync.core.job.model.index.PostgresIndexCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the existing PostgreSQL indexes into {@link IndexSignature}s.
 *
 * <p>Lives in {@code database/} rather than in a feature module because both index migration and
 * the FK gap-fill step need it, and feature modules must not depend on each other.</p>
 */
@ApplicationScoped
public class PostgresIndexCatalogService {

    private static final Logger log = LoggerFactory.getLogger(PostgresIndexCatalogService.class);

    /**
     * One row per index key column.
     *
     * <p>{@code pg_get_indexdef} with a column number renders each key uniformly, whether it is a
     * plain column or an expression, which keeps the Java side from having to split the
     * comma-separated {@code pg_index.indexprs} text itself. Sort direction is taken from
     * {@code indoption} bit 0 rather than parsed out of that text, because the text form varies
     * with server version and operator class.</p>
     *
     * <p>{@code indnkeyatts} excludes INCLUDEd columns, which are payload and not part of the
     * index's identity.</p>
     */
    private static final String INDEX_SQL = """
            SELECT n.nspname                                          AS schema_name,
                   t.relname                                          AS table_name,
                   i.relname                                          AS index_name,
                   idx.indisunique                                    AS is_unique,
                   am.amname                                          AS index_type,
                   k.ord                                              AS key_ordinal,
                   pg_get_indexdef(idx.indexrelid, k.ord::int, true)  AS key_def,
                   (idx.indoption[(k.ord - 1)::int] & 1) = 1          AS is_descending
            FROM pg_index idx
            JOIN pg_class i ON i.oid = idx.indexrelid
            JOIN pg_class t ON t.oid = idx.indrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            JOIN pg_am am ON am.oid = i.relam
            CROSS JOIN LATERAL generate_series(1, idx.indnkeyatts) AS k(ord)
            WHERE n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
              %s
            ORDER BY n.nspname, t.relname, i.relname, k.ord
            """;

    /**
     * Excludes indexes PostgreSQL created to back a primary key or unique constraint.
     *
     * <p>Applied only when listing indexes for the user, so the PostgreSQL side is comparable with
     * the Oracle side, which excludes the same class of index. It is deliberately <em>not</em>
     * applied when building the catalog: deciding whether an Oracle index is already present has
     * to see the constraint indexes, or every primary key would be migrated a second time.</p>
     */
    private static final String EXCLUDE_CONSTRAINT_BACKED =
            "AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = idx.indexrelid)";

    /** Every relation name, because PostgreSQL shares one namespace across relation kinds. */
    private static final String RELATION_NAME_SQL = """
            SELECT n.nspname AS schema_name,
                   c.relname AS relation_name
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
            """;

    public PostgresIndexCatalog load(Connection connection) throws SQLException {
        // Everything, constraint indexes included - see EXCLUDE_CONSTRAINT_BACKED for why.
        List<IndexMetadata> indexes = readIndexes(connection, false);

        Map<String, List<IndexSignature>> signaturesByTable = new HashMap<>();
        Map<String, Set<String>> indexNamesByTable = new HashMap<>();

        for (IndexMetadata index : indexes) {
            String qualifiedTable = index.getQualifiedTableName();
            signaturesByTable.computeIfAbsent(qualifiedTable, k -> new ArrayList<>())
                    .add(index.getSignature());
            indexNamesByTable.computeIfAbsent(qualifiedTable, k -> new HashSet<>())
                    .add(index.getIndexName());
        }

        PostgresIndexCatalog catalog = new PostgresIndexCatalog(
                signaturesByTable, indexNamesByTable, readRelationNames(connection));
        log.info("Loaded PostgreSQL index catalog: {} indexes across {} tables",
                catalog.getIndexCount(), catalog.getTableCount());
        return catalog;
    }

    /**
     * Reads the existing PostgreSQL indexes.
     *
     * @param excludeConstraintBacked when true, omits indexes backing a primary key or unique
     *                                constraint, giving a list comparable with the Oracle side
     */
    public List<IndexMetadata> readIndexes(Connection connection, boolean excludeConstraintBacked)
            throws SQLException {

        String sql = String.format(INDEX_SQL, excludeConstraintBacked ? EXCLUDE_CONSTRAINT_BACKED : "");

        // Keyed by table then index, so the ordered key rows can be reassembled per index.
        Map<String, Map<String, IndexBuilder>> byTable = new LinkedHashMap<>();

        try (PreparedStatement stmt = connection.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String schema = rs.getString("schema_name");
                String table = rs.getString("table_name");
                String indexName = rs.getString("index_name");
                boolean unique = rs.getBoolean("is_unique");
                String indexType = rs.getString("index_type");
                String keyDef = rs.getString("key_def");
                boolean descending = rs.getBoolean("is_descending");

                String qualifiedTable = IndexSignature.qualifiedName(schema, table);
                IndexBuilder builder = byTable
                        .computeIfAbsent(qualifiedTable, k -> new LinkedHashMap<>())
                        .computeIfAbsent(indexName,
                                k -> new IndexBuilder(schema, table, indexName, unique, indexType));
                builder.addKey(stripOrderingSuffix(keyDef), descending);
            }
        }

        List<IndexMetadata> indexes = new ArrayList<>();
        byTable.values().forEach(indexesOfTable ->
                indexesOfTable.values().forEach(builder -> indexes.add(builder.build())));
        return indexes;
    }

    /**
     * Removes any ordering keywords {@code pg_get_indexdef} appended to the key text. Direction
     * comes from {@code indoption}; leaving the suffix in place would make an otherwise identical
     * key compare unequal.
     */
    static String stripOrderingSuffix(String keyDef) {
        if (keyDef == null) {
            return "";
        }
        return keyDef
                .replaceAll("(?i)\\s+nulls\\s+(first|last)\\s*$", "")
                .replaceAll("(?i)\\s+(asc|desc)\\s*$", "")
                .replaceAll("(?i)\\s+nulls\\s+(first|last)\\s*$", "")
                .trim();
    }

    private Map<String, Set<String>> readRelationNames(Connection connection) throws SQLException {
        Map<String, Set<String>> names = new HashMap<>();
        try (PreparedStatement stmt = connection.prepareStatement(RELATION_NAME_SQL);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String schema = rs.getString("schema_name").toLowerCase();
                String relation = rs.getString("relation_name").toLowerCase();
                names.computeIfAbsent(schema, k -> new HashSet<>()).add(relation);
            }
        }
        return names;
    }

    /** Accumulates the key rows of one index until it can be built. */
    private static final class IndexBuilder {
        private final String schema;
        private final String table;
        private final String indexName;
        private final boolean unique;
        private final String indexType;
        private final List<IndexKeyPart> keyParts = new ArrayList<>();

        IndexBuilder(String schema, String table, String indexName, boolean unique, String indexType) {
            this.schema = schema;
            this.table = table;
            this.indexName = indexName;
            this.unique = unique;
            this.indexType = indexType;
        }

        void addKey(String keyText, boolean descending) {
            // Keys are read back from PostgreSQL as rendered text, so they are treated as
            // expressions; a plain column simply renders as its own name and compares equal.
            keyParts.add(new IndexKeyPart(keyText, false, descending));
        }

        IndexMetadata build() {
            return new IndexMetadata(schema, table, indexName, unique, indexType, keyParts);
        }
    }
}
