package me.christianrobert.orapgsync.transfer.service;

import me.christianrobert.orapgsync.core.job.model.table.TableMetadata;
import me.christianrobert.orapgsync.core.job.model.transfer.RowCountMetadata;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orders tables for parallel transfer.
 *
 * <p>Largest tables are started first so the run does not end with a single worker still
 * transferring a huge table while the others idle — with N workers, a long tail costs up to the
 * duration of the largest table that was scheduled last.</p>
 *
 * <p>Row counts come from the Oracle row count extraction. Tables with no known row count sort
 * last (we cannot claim they are large), and ties break on the qualified name so the order is
 * deterministic across runs — the same principle the extraction jobs follow.</p>
 */
public final class TransferOrdering {

    private static final long UNKNOWN_ROW_COUNT = -1;

    private TransferOrdering() {
    }

    /**
     * Returns a new list ordered by descending row count, then by qualified name.
     *
     * @param tables    tables to transfer
     * @param rowCounts extracted Oracle row counts; may be null or incomplete
     */
    public static List<TableMetadata> largestFirst(List<TableMetadata> tables,
                                                   List<RowCountMetadata> rowCounts) {
        if (tables == null || tables.isEmpty()) {
            return List.of();
        }

        Map<String, Long> countsByTable = indexRowCounts(rowCounts);

        List<TableMetadata> ordered = new ArrayList<>(tables);
        ordered.sort(Comparator
                .comparingLong((TableMetadata table) ->
                        countsByTable.getOrDefault(keyOf(table.getSchema(), table.getTableName()), UNKNOWN_ROW_COUNT))
                .reversed()
                .thenComparing(table -> keyOf(table.getSchema(), table.getTableName())));

        return ordered;
    }

    private static Map<String, Long> indexRowCounts(List<RowCountMetadata> rowCounts) {
        Map<String, Long> countsByTable = new HashMap<>();
        if (rowCounts == null) {
            return countsByTable;
        }

        for (RowCountMetadata rowCount : rowCounts) {
            countsByTable.put(keyOf(rowCount.getSchema(), rowCount.getTableName()), rowCount.getRowCount());
        }
        return countsByTable;
    }

    private static String keyOf(String schema, String tableName) {
        return (schema + "." + tableName).toUpperCase();
    }
}
