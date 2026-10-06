package me.christianrobert.orapgsync.core.job.model.index;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The catalog is the snapshot every skip decision is taken against, so its lookups are pinned
 * independently of the SQL that fills it.
 */
class PostgresIndexCatalogTest {

    private static IndexSignature signature(String table, boolean unique, String... columns) {
        List<IndexKeyPart> parts = java.util.Arrays.stream(columns)
                .map(column -> IndexKeyPart.ofColumn(column, false))
                .toList();
        return IndexSignature.of("hr", table, unique, parts);
    }

    private static PostgresIndexCatalog catalog() {
        return new PostgresIndexCatalog(
                Map.of("hr.emp", List.of(
                        signature("emp", false, "dept_id", "hire_date"),
                        signature("emp", true, "email"))),
                Map.of("hr.emp", Set.of("emp_dept_hire_ix", "emp_email_uix")),
                Map.of("hr", Set.of("emp", "emp_dept_hire_ix", "emp_email_uix", "emp_archive")));
    }

    @Test
    void findsAnEquivalentIndex() {
        assertTrue(catalog().hasEquivalentOf(signature("emp", false, "dept_id", "hire_date")));
    }

    @Test
    @DisplayName("a narrower index is not considered present")
    void narrowerIndexIsNotEquivalent() {
        assertFalse(catalog().hasEquivalentOf(signature("emp", false, "dept_id")));
    }

    @Test
    void unknownTableHasNothing() {
        assertFalse(catalog().hasEquivalentOf(signature("dept", false, "dept_id")));
        assertEquals(List.of(), catalog().signaturesFor("hr.dept"));
    }

    @Test
    @DisplayName("a lookup on a leading column is covered by the wider index")
    void coversLookupUsesPrefixMatch() {
        assertTrue(catalog().coversLookup("hr.emp", List.of("dept_id")));
        assertFalse(catalog().coversLookup("hr.emp", List.of("hire_date")));
    }

    @Test
    void lookupOnUnknownTableIsNotCovered() {
        assertFalse(catalog().coversLookup("hr.dept", List.of("dept_id")));
    }

    @Test
    @DisplayName("names are taken across relation kinds, not just indexes")
    void tableNameCountsAsTaken() {
        // PostgreSQL shares one namespace per schema; Oracle gives indexes their own.
        assertTrue(catalog().isNameTaken("hr", "emp"));
        assertTrue(catalog().isNameTaken("hr", "emp_archive"));
        assertFalse(catalog().isNameTaken("hr", "emp_new_ix"));
    }

    @Test
    void nameLookupsAreCaseInsensitive() {
        assertTrue(catalog().isNameTaken("HR", "EMP_ARCHIVE"));
        assertTrue(catalog().hasIndexNamed("HR.EMP", "EMP_EMAIL_UIX"));
    }

    @Test
    @DisplayName("the re-run guard only matches indexes on the same table")
    void hasIndexNamedIsScopedToTheTable() {
        assertTrue(catalog().hasIndexNamed("hr.emp", "emp_email_uix"));
        assertFalse(catalog().hasIndexNamed("hr.dept", "emp_email_uix"));
    }

    @Test
    void emptyCatalogAnswersNothing() {
        PostgresIndexCatalog empty = PostgresIndexCatalog.empty();

        assertFalse(empty.hasEquivalentOf(signature("emp", false, "dept_id")));
        assertFalse(empty.coversLookup("hr.emp", List.of("dept_id")));
        assertFalse(empty.isNameTaken("hr", "anything"));
        assertEquals(0, empty.getIndexCount());
        assertEquals(0, empty.getTableCount());
    }

    @Test
    void countsReflectContents() {
        assertEquals(2, catalog().getIndexCount());
        assertEquals(1, catalog().getTableCount());
    }

    @Test
    void nullArgumentsAreSafe() {
        PostgresIndexCatalog catalog = catalog();

        assertFalse(catalog.hasEquivalentOf(null));
        assertFalse(catalog.isNameTaken(null, "x"));
        assertFalse(catalog.isNameTaken("hr", null));
        assertFalse(catalog.hasIndexNamed(null, "x"));
        assertEquals(List.of(), catalog.signaturesFor(null));
    }
}
