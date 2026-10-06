package me.christianrobert.orapgsync.view.rest;

import me.christianrobert.orapgsync.core.job.model.view.ViewImplementationResult;
import me.christianrobert.orapgsync.core.job.model.view.ViewStubCreationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * View summaries survive an error that carries no SQL.
 *
 * <p>{@code PostgresViewImplementationJob} records one whenever an exception escapes
 * {@code implementView}, before any SQL exists. That had not happened in a measured run — 0 of the
 * 2,053 view errors on 2026-08-30 — so the {@code Map.of} in the projection had never been handed
 * a null. The identical shape in the function stub summary aborted a whole run the first time its
 * equivalent path fired, which is why this one is pinned rather than left to chance.
 */
class ViewSummaryNullSqlTest {

    @Test
    @DisplayName("A view implementation error with no SQL is projected, not thrown on")
    void implementationErrorWithoutSqlDoesNotThrow() {
        ViewImplementationResult result = new ViewImplementationResult();
        result.addError("hr.emp_v", "Implementation failed: unexpected", null);

        Map<String, Object> summary =
                assertDoesNotThrow(() -> ViewResource.generateViewImplementationSummary(result));

        @SuppressWarnings("unchecked")
        Map<String, Object> errors = (Map<String, Object>) summary.get("errors");
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) errors.get("hr.emp_v");

        assertEquals("error", error.get("status"));
        assertFalse(error.containsKey("sql"));
    }

    @Test
    @DisplayName("A view stub error with no SQL is projected, not thrown on")
    void stubErrorWithoutSqlDoesNotThrow() {
        ViewStubCreationResult result = new ViewStubCreationResult();
        result.addError("hr.emp_v", "Stub creation failed: unexpected", null);

        Map<String, Object> summary =
                assertDoesNotThrow(() -> ViewResource.generateViewStubCreationSummary(result));

        @SuppressWarnings("unchecked")
        Map<String, Object> errors = (Map<String, Object>) summary.get("errors");
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) errors.get("hr.emp_v");

        assertEquals("error", error.get("status"));
        assertFalse(error.containsKey("sql"));
    }

    @Test
    @DisplayName("An error that does have SQL still carries it")
    void errorWithSqlStillCarriesIt() {
        ViewImplementationResult result = new ViewImplementationResult();
        result.addError("hr.emp_v", "SQL execution failed", "CREATE OR REPLACE VIEW hr.emp_v AS ...");

        Map<String, Object> summary = ViewResource.generateViewImplementationSummary(result);

        @SuppressWarnings("unchecked")
        Map<String, Object> errors = (Map<String, Object>) summary.get("errors");
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) errors.get("hr.emp_v");

        assertTrue(((String) error.get("sql")).startsWith("CREATE OR REPLACE VIEW"));
    }
}
