package me.christianrobert.orapgsync.database.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sort direction is taken from {@code pg_index.indoption}, not parsed out of the rendered key
 * text. Any ordering keywords {@code pg_get_indexdef} appends must therefore be removed, or an
 * otherwise identical key would compare unequal and the index would be created a second time.
 */
class PostgresIndexCatalogServiceTest {

    @Test
    void plainColumnIsUnchanged() {
        assertEquals("dept_id", PostgresIndexCatalogService.stripOrderingSuffix("dept_id"));
    }

    @Test
    void stripsDescending() {
        assertEquals("dept_id", PostgresIndexCatalogService.stripOrderingSuffix("dept_id DESC"));
    }

    @Test
    void stripsAscending() {
        assertEquals("dept_id", PostgresIndexCatalogService.stripOrderingSuffix("dept_id ASC"));
    }

    @Test
    @DisplayName("strips a direction and a NULLS clause together")
    void stripsDirectionAndNullsClause() {
        assertEquals("dept_id", PostgresIndexCatalogService.stripOrderingSuffix("dept_id DESC NULLS FIRST"));
        assertEquals("dept_id", PostgresIndexCatalogService.stripOrderingSuffix("dept_id NULLS LAST"));
    }

    @Test
    void isCaseInsensitive() {
        assertEquals("dept_id", PostgresIndexCatalogService.stripOrderingSuffix("dept_id desc nulls first"));
    }

    @Test
    @DisplayName("an expression key survives intact")
    void expressionKeyIsPreserved() {
        assertEquals("upper((name)::text)",
                PostgresIndexCatalogService.stripOrderingSuffix("upper((name)::text) DESC"));
    }

    @Test
    @DisplayName("a column whose name merely ends in 'desc' is not truncated")
    void doesNotStripPartOfAnIdentifier() {
        assertEquals("item_desc", PostgresIndexCatalogService.stripOrderingSuffix("item_desc"));
    }

    @Test
    void handlesNull() {
        assertEquals("", PostgresIndexCatalogService.stripOrderingSuffix(null));
    }
}
