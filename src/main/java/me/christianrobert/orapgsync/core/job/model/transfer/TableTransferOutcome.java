package me.christianrobert.orapgsync.core.job.model.transfer;

/**
 * Result of transferring one table, published by a transfer worker and aggregated by the
 * coordinating thread.
 *
 * <p>Exactly one outcome is produced per table, so the coordinator knows when the run is
 * complete without inspecting worker state.</p>
 *
 * @param qualifiedTableName schema-qualified Oracle table name
 * @param rowsTransferred    rows transferred; 0 means the table was skipped (empty in Oracle)
 * @param errorMessage       failure reason, or {@code null} if the table was transferred
 */
public record TableTransferOutcome(String qualifiedTableName, long rowsTransferred, String errorMessage) {

    public static TableTransferOutcome transferred(String qualifiedTableName, long rowsTransferred) {
        return new TableTransferOutcome(qualifiedTableName, rowsTransferred, null);
    }

    public static TableTransferOutcome failed(String qualifiedTableName, String errorMessage) {
        return new TableTransferOutcome(qualifiedTableName, 0, errorMessage);
    }

    public boolean isError() {
        return errorMessage != null;
    }

    /**
     * A table is skipped when it transferred without error but produced no rows (empty in Oracle).
     */
    public boolean isSkipped() {
        return !isError() && rowsTransferred == 0;
    }
}
