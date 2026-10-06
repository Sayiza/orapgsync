package me.christianrobert.orapgsync.core.job.model.index;

/**
 * One key of an index: either a plain column or an expression, plus its sort direction.
 *
 * <p>Oracle implements descending indexes as function-based indexes, so a descending column
 * arrives from the data dictionary as a hidden {@code SYS_NC0000n$} column with the real column
 * name only present in {@code ALL_IND_EXPRESSIONS}. Extraction resolves that back into a plain
 * column part with {@code descending = true}; see {@code OracleIndexExtractor}.</p>
 *
 * @param expression  the column name (for {@code column = true}) or the raw expression text
 * @param column      {@code true} if this part is a plain column reference
 * @param descending  {@code true} for a DESC key
 */
public record IndexKeyPart(String expression, boolean column, boolean descending) {

    public static IndexKeyPart ofColumn(String columnName, boolean descending) {
        return new IndexKeyPart(columnName == null ? null : columnName.toLowerCase(), true, descending);
    }

    public static IndexKeyPart ofExpression(String expression, boolean descending) {
        return new IndexKeyPart(expression, false, descending);
    }

    public boolean isExpression() {
        return !column;
    }

    @Override
    public String toString() {
        return expression + (descending ? " DESC" : "");
    }
}
