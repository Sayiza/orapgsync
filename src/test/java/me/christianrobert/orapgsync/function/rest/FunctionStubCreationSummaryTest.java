package me.christianrobert.orapgsync.function.rest;

import me.christianrobert.orapgsync.core.job.model.function.FunctionStubCreationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The stub creation job's REST projection.
 *
 * <p>Written after it aborted a full run. The job gained a failure mode that records an error with
 * <b>no SQL</b> — a stub refused because a type could not be placed never gets as far as generating
 * any — and this projection built each error with {@code Map.of}, which throws
 * {@link NullPointerException} on a null value. The whole run failed at the point the frontend
 * asked for the result, so a job that had completed reported nothing at all.
 *
 * <p>The lesson is narrower than "check for nulls": <b>a new nullable value in a result reaches
 * every projection of that result</b>, and the job tests cannot see them. The sibling projection in
 * this same file had already been fixed for exactly this, for package errors.
 */
class FunctionStubCreationSummaryTest {

    @Test
    @DisplayName("An error with no SQL is projected, not thrown on")
    void errorWithoutSqlDoesNotThrow() {
        FunctionStubCreationResult result = new FunctionStubCreationResult();
        result.addError("hr.get_icon",
                "Failed to create function stub 'hr.get_icon': cannot resolve Oracle type "
                        + "'uicomponent.icon' for return type",
                null);

        Map<String, Object> summary =
                assertDoesNotThrow(() -> FunctionResource.generateFunctionStubCreationSummary(result));

        @SuppressWarnings("unchecked")
        Map<String, Object> errors = (Map<String, Object>) summary.get("errors");
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) errors.get("hr.get_icon");

        assertNotNull(error);
        assertEquals("error", error.get("status"));
        assertTrue(((String) error.get("error")).contains("uicomponent.icon"),
                "the message naming the unplaceable type is the whole value of the error");
        assertFalse(error.containsKey("sql"),
                "there is no SQL, and saying so by omission is what results-panel.js already "
                        + "expects: it reads item.sql || item.statement");
    }

    @Test
    @DisplayName("An error that does have SQL still carries it")
    void errorWithSqlStillCarriesIt() {
        FunctionStubCreationResult result = new FunctionStubCreationResult();
        result.addError("hr.get_salary", "Failed: syntax error",
                "CREATE OR REPLACE FUNCTION hr.get_salary() RETURNS numeric AS $$ ... $$");

        Map<String, Object> summary = FunctionResource.generateFunctionStubCreationSummary(result);

        @SuppressWarnings("unchecked")
        Map<String, Object> errors = (Map<String, Object>) summary.get("errors");
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) errors.get("hr.get_salary");

        assertTrue(((String) error.get("sql")).startsWith("CREATE OR REPLACE FUNCTION"));
    }

    @Test
    @DisplayName("A result mixing both kinds of error projects both")
    void mixedErrorsBothProject() {
        FunctionStubCreationResult result = new FunctionStubCreationResult();
        result.addError("hr.a", "no type", null);
        result.addError("hr.b", "bad sql", "CREATE ...");
        result.addCreatedFunction("hr.c");

        Map<String, Object> summary = FunctionResource.generateFunctionStubCreationSummary(result);

        @SuppressWarnings("unchecked")
        Map<String, Object> errors = (Map<String, Object>) summary.get("errors");
        assertEquals(2, errors.size());
        assertEquals(2, summary.get("errorCount"));
        assertEquals(1, summary.get("createdCount"));
    }
}
