package me.christianrobert.orapgsync.transfer.service;

import me.christianrobert.orapgsync.core.job.model.table.TableMetadata;
import me.christianrobert.orapgsync.core.job.model.transfer.RowCountMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the largest-first scheduling order used by the parallel data transfer.
 */
class TransferOrderingTest {

    private TableMetadata table(String schema, String name) {
        return new TableMetadata(schema, name);
    }

    private List<String> namesOf(List<TableMetadata> tables) {
        return tables.stream().map(TableMetadata::getTableName).toList();
    }

    @Test
    @DisplayName("Orders tables by descending row count")
    void ordersByDescendingRowCount() {
        List<TableMetadata> tables = List.of(
                table("HR", "SMALL"),
                table("HR", "HUGE"),
                table("HR", "MEDIUM"));

        List<RowCountMetadata> rowCounts = List.of(
                new RowCountMetadata("HR", "SMALL", 10, 0),
                new RowCountMetadata("HR", "HUGE", 5_000_000, 0),
                new RowCountMetadata("HR", "MEDIUM", 1_000, 0));

        assertEquals(List.of("HUGE", "MEDIUM", "SMALL"),
                namesOf(TransferOrdering.largestFirst(tables, rowCounts)));
    }

    @Test
    @DisplayName("Tables with unknown row counts sort last")
    void unknownRowCountsSortLast() {
        List<TableMetadata> tables = List.of(
                table("HR", "UNKNOWN"),
                table("HR", "TINY"));

        List<RowCountMetadata> rowCounts = List.of(
                new RowCountMetadata("HR", "TINY", 1, 0));

        assertEquals(List.of("TINY", "UNKNOWN"),
                namesOf(TransferOrdering.largestFirst(tables, rowCounts)));
    }

    @Test
    @DisplayName("Empty tables sort before tables of unknown size")
    void knownEmptyBeatsUnknown() {
        List<TableMetadata> tables = List.of(
                table("HR", "UNKNOWN"),
                table("HR", "EMPTY"));

        List<RowCountMetadata> rowCounts = List.of(
                new RowCountMetadata("HR", "EMPTY", 0, 0));

        assertEquals(List.of("EMPTY", "UNKNOWN"),
                namesOf(TransferOrdering.largestFirst(tables, rowCounts)));
    }

    @Test
    @DisplayName("Equal row counts are ordered deterministically by qualified name")
    void breaksTiesByQualifiedName() {
        List<TableMetadata> tables = List.of(
                table("HR", "ZEBRA"),
                table("HR", "ALPHA"),
                table("FINANCE", "ALPHA"));

        List<RowCountMetadata> rowCounts = List.of(
                new RowCountMetadata("HR", "ZEBRA", 100, 0),
                new RowCountMetadata("HR", "ALPHA", 100, 0),
                new RowCountMetadata("FINANCE", "ALPHA", 100, 0));

        List<TableMetadata> ordered = TransferOrdering.largestFirst(tables, rowCounts);

        assertEquals(List.of("FINANCE.ALPHA", "HR.ALPHA", "HR.ZEBRA"),
                ordered.stream().map(t -> t.getSchema() + "." + t.getTableName()).toList());
    }

    @Test
    @DisplayName("Row counts match regardless of identifier case")
    void matchesRowCountsCaseInsensitively() {
        List<TableMetadata> tables = List.of(
                table("hr", "orders"),
                table("hr", "audit_log"));

        List<RowCountMetadata> rowCounts = List.of(
                new RowCountMetadata("HR", "AUDIT_LOG", 900_000, 0),
                new RowCountMetadata("HR", "ORDERS", 500, 0));

        assertEquals(List.of("audit_log", "orders"),
                namesOf(TransferOrdering.largestFirst(tables, rowCounts)));
    }

    @Test
    @DisplayName("Missing row count data keeps all tables, ordered by name")
    void handlesMissingRowCountData() {
        List<TableMetadata> tables = List.of(
                table("HR", "B"),
                table("HR", "A"));

        assertEquals(List.of("A", "B"), namesOf(TransferOrdering.largestFirst(tables, null)));
        assertEquals(List.of("A", "B"), namesOf(TransferOrdering.largestFirst(tables, List.of())));
    }

    @Test
    @DisplayName("Does not modify the input list")
    void doesNotModifyInput() {
        List<TableMetadata> tables = new java.util.ArrayList<>(List.of(
                table("HR", "SMALL"),
                table("HR", "HUGE")));

        List<RowCountMetadata> rowCounts = List.of(
                new RowCountMetadata("HR", "SMALL", 10, 0),
                new RowCountMetadata("HR", "HUGE", 999, 0));

        TransferOrdering.largestFirst(tables, rowCounts);

        assertEquals(List.of("SMALL", "HUGE"), namesOf(tables));
    }

    @Test
    @DisplayName("Handles empty and null input")
    void handlesEmptyInput() {
        assertTrue(TransferOrdering.largestFirst(List.of(), List.of()).isEmpty());
        assertTrue(TransferOrdering.largestFirst(null, List.of()).isEmpty());
    }
}
