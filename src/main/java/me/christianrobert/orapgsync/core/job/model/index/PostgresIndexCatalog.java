package me.christianrobert.orapgsync.core.job.model.index;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A snapshot of the indexes PostgreSQL already has, plus the relation names already in use.
 *
 * <p>Immutable. Index creation reads it during a single-threaded planning pass and the workers
 * then only execute statements, so no shared state needs synchronizing.</p>
 *
 * <p>The occupied-name set covers <em>all</em> relations, not just indexes, because PostgreSQL
 * puts tables, views, sequences and indexes in one namespace per schema while Oracle gives
 * indexes a namespace of their own. An Oracle index named after a table is legal at the source
 * and a collision here.</p>
 */
public class PostgresIndexCatalog {

    private final Map<String, List<IndexSignature>> signaturesByTable;
    private final Map<String, Set<String>> indexNamesByTable;
    private final Map<String, Set<String>> relationNamesBySchema;

    public PostgresIndexCatalog(Map<String, List<IndexSignature>> signaturesByTable,
                                Map<String, Set<String>> indexNamesByTable,
                                Map<String, Set<String>> relationNamesBySchema) {
        this.signaturesByTable = deepCopySignatures(signaturesByTable);
        this.indexNamesByTable = deepCopyNames(indexNamesByTable);
        this.relationNamesBySchema = deepCopyNames(relationNamesBySchema);
    }

    public static PostgresIndexCatalog empty() {
        return new PostgresIndexCatalog(Map.of(), Map.of(), Map.of());
    }

    /**
     * Whether {@code qualifiedTable} already carries an index of this name.
     *
     * <p>Used as a re-run guard for function-based indexes. Expression text cannot be compared
     * reliably between the two databases, so an index this migration created on a previous run
     * may not match by signature; because index names are preserved from Oracle, matching the
     * name on the same table identifies it instead.</p>
     */
    public boolean hasIndexNamed(String qualifiedTable, String indexName) {
        if (qualifiedTable == null || indexName == null) {
            return false;
        }
        return indexNamesByTable.getOrDefault(qualifiedTable.toLowerCase(), Set.of())
                .contains(indexName.toLowerCase());
    }

    /**
     * Whether PostgreSQL already has an index that makes {@code desired} unnecessary.
     * Uses the exact-match rule - see {@link IndexSignature#makesRedundant(IndexSignature)}.
     */
    public boolean hasEquivalentOf(IndexSignature desired) {
        return findEquivalentOf(desired) != null;
    }

    /** The existing signature that makes {@code desired} unnecessary, or {@code null}. */
    public IndexSignature findEquivalentOf(IndexSignature desired) {
        if (desired == null) {
            return null;
        }
        for (IndexSignature existing : signaturesFor(desired.getQualifiedTable())) {
            if (existing.makesRedundant(desired)) {
                return existing;
            }
        }
        return null;
    }

    /**
     * Whether an equality lookup on {@code columns} of {@code qualifiedTable} is already served
     * by some index. Prefix match - used only for FK gap-fill.
     */
    public boolean coversLookup(String qualifiedTable, List<String> columns) {
        for (IndexSignature existing : signaturesFor(qualifiedTable)) {
            if (existing.coversLookup(columns)) {
                return true;
            }
        }
        return false;
    }

    public List<IndexSignature> signaturesFor(String qualifiedTable) {
        if (qualifiedTable == null) {
            return List.of();
        }
        return signaturesByTable.getOrDefault(qualifiedTable.toLowerCase(), List.of());
    }

    /** Whether {@code name} is already taken by any relation in {@code schema}. */
    public boolean isNameTaken(String schema, String name) {
        if (schema == null || name == null) {
            return false;
        }
        return relationNamesBySchema.getOrDefault(schema.toLowerCase(), Set.of())
                .contains(name.toLowerCase());
    }

    public int getIndexCount() {
        return signaturesByTable.values().stream().mapToInt(List::size).sum();
    }

    public int getTableCount() {
        return signaturesByTable.size();
    }

    private static Map<String, List<IndexSignature>> deepCopySignatures(Map<String, List<IndexSignature>> source) {
        Map<String, List<IndexSignature>> copy = new HashMap<>();
        if (source != null) {
            source.forEach((table, signatures) ->
                    copy.put(table.toLowerCase(), Collections.unmodifiableList(new ArrayList<>(signatures))));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<String, Set<String>> deepCopyNames(Map<String, Set<String>> source) {
        Map<String, Set<String>> copy = new HashMap<>();
        if (source != null) {
            source.forEach((schema, names) ->
                    copy.put(schema.toLowerCase(), Collections.unmodifiableSet(new HashSet<>(names))));
        }
        return Collections.unmodifiableMap(copy);
    }
}
