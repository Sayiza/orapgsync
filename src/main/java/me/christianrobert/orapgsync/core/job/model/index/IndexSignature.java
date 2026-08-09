package me.christianrobert.orapgsync.core.job.model.index;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What an index <em>does</em>, independent of what it is called: the table it sits on, whether it
 * is unique, and its ordered key parts.
 *
 * <p>Index names cannot be compared across the two databases - Oracle's {@code PK_EMP} becomes
 * PostgreSQL's {@code emp_pkey}, and a hand-written FK index has whatever name its author chose.
 * Every "does this already exist?" question in the migration is therefore answered against a
 * signature, not a name. The same component backs three callers:</p>
 *
 * <ul>
 *   <li>index creation - skip Oracle indexes that PostgreSQL already has</li>
 *   <li>FK gap-fill - skip FK columns already served by an existing index</li>
 *   <li>verification - compare Oracle index coverage against PostgreSQL</li>
 * </ul>
 *
 * <h2>Two different questions, two different rules</h2>
 *
 * <p>{@link #makesRedundant(IndexSignature)} answers "has this Oracle index already been
 * reproduced?" and demands an <em>exact</em> key match. We reproduce Oracle faithfully; a wider
 * index that merely happens to cover the same lookups is not the same index and would leave the
 * migrated schema quietly different from the source.</p>
 *
 * <p>{@link #coversLookup(List)} answers "would a lookup on these columns already be served?" and
 * accepts a leading prefix. That laxer rule is only used for the FK indexes the migration invents
 * on its own, where the goal is lookup performance rather than fidelity.</p>
 *
 * <h2>Sort direction</h2>
 *
 * <p>A B-tree can be scanned in either direction, so {@code (a DESC)} and {@code (a ASC)} are
 * interchangeable - but {@code (a ASC, b DESC)} and {@code (a ASC, b ASC)} are not. Direction is
 * therefore compatible when all directions match or all are exactly inverted, which is precisely
 * the condition under which one index can substitute for the other.</p>
 */
public final class IndexSignature {

    private final String qualifiedTable;
    private final boolean unique;
    private final List<String> keys;
    private final List<Boolean> descending;

    private IndexSignature(String qualifiedTable, boolean unique, List<String> keys, List<Boolean> descending) {
        this.qualifiedTable = qualifiedTable;
        this.unique = unique;
        this.keys = List.copyOf(keys);
        this.descending = List.copyOf(descending);
    }

    /**
     * Builds a signature from index key parts.
     *
     * @param schema    owning schema (case-insensitive)
     * @param table     table name (case-insensitive)
     * @param unique    whether the index enforces uniqueness
     * @param keyParts  ordered key parts; must not be empty
     */
    public static IndexSignature of(String schema, String table, boolean unique, List<IndexKeyPart> keyParts) {
        Objects.requireNonNull(keyParts, "keyParts");
        List<String> normalizedKeys = new ArrayList<>(keyParts.size());
        List<Boolean> directions = new ArrayList<>(keyParts.size());
        for (IndexKeyPart part : keyParts) {
            normalizedKeys.add(normalizeKey(part.expression()));
            directions.add(part.descending());
        }
        return new IndexSignature(qualifiedName(schema, table), unique, normalizedKeys, directions);
    }

    /**
     * Normalizes key text so the same key written by Oracle and by PostgreSQL compares equal:
     * lowercased, unquoted, with runs of whitespace collapsed and whitespace around punctuation
     * removed.
     *
     * <p>For plain columns this is exact. For expressions it is a best effort - {@code UPPER("NAME")}
     * and {@code upper(name)} do reduce to the same text, but there is no guarantee in general,
     * which is why callers pair signature matching with a name check.</p>
     */
    static String normalizeKey(String key) {
        if (key == null) {
            return "";
        }
        String normalized = key.toLowerCase()
                .replace("\"", "")
                .replaceAll("\\s+", " ")
                .trim();
        // Drop whitespace that only exists for readability, so "upper( name )" == "upper(name)"
        normalized = normalized.replaceAll("\\s*([(),])\\s*", "$1");
        // A fully parenthesised expression means the same as its contents
        while (normalized.length() > 1 && normalized.startsWith("(") && normalized.endsWith(")")
                && parenthesesBalancedWithin(normalized)) {
            normalized = normalized.substring(1, normalized.length() - 1).trim();
        }
        return normalized;
    }

    /** True when the outermost parentheses of {@code text} enclose the whole expression. */
    private static boolean parenthesesBalancedWithin(String text) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0 && i < text.length() - 1) {
                    return false;
                }
            }
        }
        return depth == 0;
    }

    public static String qualifiedName(String schema, String table) {
        return (schema + "." + table).toLowerCase();
    }

    /**
     * Whether this (existing) index makes {@code desired} unnecessary.
     *
     * <p>Requires the same table, an exact key match, compatible directions, and - when
     * {@code desired} is unique - that this index is unique too. A non-unique existing index can
     * never stand in for a unique one, because uniqueness is a constraint and not just an access
     * path.</p>
     */
    public boolean makesRedundant(IndexSignature desired) {
        if (desired == null || !qualifiedTable.equals(desired.qualifiedTable)) {
            return false;
        }
        if (!keys.equals(desired.keys)) {
            return false;
        }
        if (desired.unique && !unique) {
            return false;
        }
        return directionsCompatible(desired);
    }

    /** All directions identical, or all exactly inverted - the two cases a B-tree can serve. */
    private boolean directionsCompatible(IndexSignature other) {
        if (descending.size() != other.descending.size()) {
            return false;
        }
        boolean allSame = true;
        boolean allInverted = true;
        for (int i = 0; i < descending.size(); i++) {
            if (descending.get(i).equals(other.descending.get(i))) {
                allInverted = false;
            } else {
                allSame = false;
            }
        }
        return allSame || allInverted;
    }

    /**
     * Whether an equality lookup on {@code lookupColumns} would already be served by this index,
     * i.e. whether those columns are a leading prefix of its keys. Direction is irrelevant for
     * equality lookups, so it is ignored.
     *
     * <p>Used only for FK gap-fill. See the class comment for why the exact-match rule applies
     * everywhere else.</p>
     */
    public boolean coversLookup(List<String> lookupColumns) {
        if (lookupColumns == null || lookupColumns.isEmpty() || lookupColumns.size() > keys.size()) {
            return false;
        }
        for (int i = 0; i < lookupColumns.size(); i++) {
            if (!keys.get(i).equals(normalizeKey(lookupColumns.get(i)))) {
                return false;
            }
        }
        return true;
    }

    public String getQualifiedTable() {
        return qualifiedTable;
    }

    public boolean isUnique() {
        return unique;
    }

    public List<String> getKeys() {
        return keys;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof IndexSignature other)) {
            return false;
        }
        return unique == other.unique
                && qualifiedTable.equals(other.qualifiedTable)
                && keys.equals(other.keys)
                && descending.equals(other.descending);
    }

    @Override
    public int hashCode() {
        return Objects.hash(qualifiedTable, unique, keys, descending);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(unique ? "UNIQUE " : "").append(qualifiedTable).append(" (");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(keys.get(i));
            if (descending.get(i)) {
                sb.append(" DESC");
            }
        }
        return sb.append(")").toString();
    }
}
