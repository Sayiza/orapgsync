package me.christianrobert.orapgsync.core.job.model.index;

/**
 * The result of attempting to migrate one index. Every extracted index produces exactly one
 * outcome, so an index can never silently vanish between extraction and the report.
 *
 * @param qualifiedTableName the table the index belongs to ({@code schema.table})
 * @param indexName          the PostgreSQL index name that was used or would have been used
 * @param keyDisplay         the index keys, for display
 * @param status             what happened
 * @param reason             why, for every status except {@link Status#CREATED} (where it may
 *                           still carry a note, e.g. a bitmap index downgraded to a B-tree)
 * @param sqlStatement       the statement that was executed or attempted; {@code null} when none
 *                           was generated
 */
public record IndexOutcome(String qualifiedTableName, String indexName, String keyDisplay,
                           Status status, String reason, String sqlStatement) {

    public enum Status {
        /** The index was created. */
        CREATED,
        /** PostgreSQL already had an equivalent index; see {@link #reason()}. */
        SKIPPED,
        /** The index has no PostgreSQL equivalent and was not attempted. */
        UNSUPPORTED,
        /** Creation was attempted and failed. */
        ERROR
    }

    public static IndexOutcome created(String qualifiedTableName, String indexName, String keyDisplay,
                                       String sqlStatement, String note) {
        return new IndexOutcome(qualifiedTableName, indexName, keyDisplay, Status.CREATED, note, sqlStatement);
    }

    public static IndexOutcome skipped(String qualifiedTableName, String indexName, String keyDisplay,
                                       String reason) {
        return new IndexOutcome(qualifiedTableName, indexName, keyDisplay, Status.SKIPPED, reason, null);
    }

    public static IndexOutcome unsupported(String qualifiedTableName, String indexName, String keyDisplay,
                                           String reason) {
        return new IndexOutcome(qualifiedTableName, indexName, keyDisplay, Status.UNSUPPORTED, reason, null);
    }

    public static IndexOutcome error(String qualifiedTableName, String indexName, String keyDisplay,
                                     String reason, String sqlStatement) {
        return new IndexOutcome(qualifiedTableName, indexName, keyDisplay, Status.ERROR, reason, sqlStatement);
    }

    public boolean isError() {
        return status == Status.ERROR;
    }
}
