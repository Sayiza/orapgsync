package me.christianrobert.orapgsync.transformer.builder.outerjoin;

import java.util.List;

/**
 * One comma-separated item of a FROM clause, as far as Oracle (+) outer joins are concerned.
 *
 * <p>An item is identified by its position in the FROM list, which is also the position of its
 * transformed SQL in {@code VisitFromClause}. Its {@code keys} are the canonical names a column
 * reference can qualify it by: the alias if there is one, otherwise the table name and
 * {@code SCHEMA.TABLE}. An item with no usable name (an unaliased subquery) has no keys and so
 * can be neither the outer-joined side nor a dependency of one.
 *
 * <p>Examples:
 * <pre>
 * FROM employees e      → keys [E]
 * FROM hr.departments   → keys [DEPARTMENTS, HR.DEPARTMENTS]
 * </pre>
 */
public class TableInfo {

    private final int index;
    private final List<String> keys;

    public TableInfo(int index, List<String> keys) {
        this.index = index;
        this.keys = List.copyOf(keys);
    }

    /** Position in the FROM list. */
    public int getIndex() {
        return index;
    }

    /** Canonical names a qualified column reference can use for this item. */
    public List<String> getKeys() {
        return keys;
    }

    @Override
    public String toString() {
        return "TableInfo{index=" + index + ", keys=" + keys + "}";
    }
}
