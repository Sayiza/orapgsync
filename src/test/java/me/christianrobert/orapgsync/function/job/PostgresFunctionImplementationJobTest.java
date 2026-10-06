package me.christianrobert.orapgsync.function.job;

import me.christianrobert.orapgsync.core.job.model.function.FunctionImplementationResult;
import me.christianrobert.orapgsync.core.job.model.function.FunctionMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the one invariant that the 2026-08-28 full run showed was not holding:
 * <b>a failed transformation is an error, not a skip.</b>
 *
 * <p>That run reported "16,366 implemented, 3,965 skipped, 54,093 errors" for orchestration step
 * 28. The 3,965 were transformation failures presented as a neutral count — the same mechanism
 * that let the type method job report success while implementing nothing
 * ({@code TYPE_METHOD_IMPLEMENTATION_PLAN.md} §1), fixed there and left standing here.
 *
 * <p>These tests drive {@code recordTransformationFailure} directly, which is the real routing
 * decision, so restoring {@code addSkippedFunction} in it fails them. Verified by mutation.
 */
class PostgresFunctionImplementationJobTest {

    private static FunctionMetadata function() {
        FunctionMetadata function = new FunctionMetadata("hr", "get_salary", "FUNCTION");
        function.setPackageName("emp_pkg");
        return function;
    }

    @Test
    @DisplayName("A failed transformation is counted as an error, never as a skip")
    void failedTransformationIsAnError() {
        FunctionImplementationResult result = new FunctionImplementationResult();

        PostgresFunctionImplementationJob.recordTransformationFailure(
                function(), "BULK COLLECT not supported", result);

        assertEquals(1, result.getErrorCount(),
                "a transformation failure must be counted as an error");
        assertEquals(0, result.getSkippedCount(),
                "skip must mean 'nothing to do'; a failure counted as skipped disappears from "
                        + "every summary and badge");
        assertTrue(result.getSkippedFunctions().isEmpty(),
                "the skipped list must stay empty for this job");
    }

    @Test
    @DisplayName("The recorded error names the function and carries the transformation message")
    void errorCarriesTheReason() {
        FunctionImplementationResult result = new FunctionImplementationResult();

        PostgresFunctionImplementationJob.recordTransformationFailure(
                function(), "BULK COLLECT not supported", result);

        String key = function().getDisplayName();
        assertTrue(result.getErrors().containsKey(key),
                "error must be keyed by the function's display name, was: "
                        + result.getErrors().keySet());

        String message = result.getErrors().get(key).getError();
        assertTrue(message.contains("Transformation failed"), message);
        assertTrue(message.contains("BULK COLLECT not supported"),
                "the transformer's own reason must survive into the report, or the error says "
                        + "nothing about what to fix: " + message);
    }

    @Test
    @DisplayName("A result with failures is not reported as successful")
    void resultIsNotSuccessful() {
        FunctionImplementationResult result = new FunctionImplementationResult();
        assertTrue(result.isSuccessful(), "empty result is successful");

        PostgresFunctionImplementationJob.recordTransformationFailure(
                function(), "some reason", result);

        assertTrue(!result.isSuccessful(),
                "a run with transformation failures must not report success -- the skip path did");
    }

    // ===== Package context failures =====
    //
    // The 2026-08-28 run recorded 56,338 function failures, of which 44,693 (79%) were one
    // package's context failure copied onto each of its member functions, with the package never
    // named. 5,913 identical `schema "type" does not exist` entries came from 300 packages.

    private static FunctionMetadata member(String name) {
        FunctionMetadata function = new FunctionMetadata("hr", name, "FUNCTION");
        function.setPackageName("emp_pkg");
        return function;
    }

    @Test
    @DisplayName("A package context failure is recorded once against the package, not once per member")
    void packageErrorIsRecordedOncePerPackage() {
        FunctionImplementationResult result = new FunctionImplementationResult();

        result.addPackageError("hr", "emp_pkg", "type \"salary_tab\" does not exist", "CREATE FUNCTION ...");
        result.addPackageError("hr", "emp_pkg", "type \"salary_tab\" does not exist", "CREATE FUNCTION ...");
        result.addPackageError("hr", "emp_pkg", "type \"salary_tab\" does not exist", "CREATE FUNCTION ...");

        assertEquals(1, result.getPackageErrorCount(),
                "one broken package is one error, however many members it has -- recording it per "
                        + "member is what turned a few hundred root causes into 44,693 entries");
        assertEquals(1, result.getPackageErrors().size(),
                "the count must not exceed the evidence behind it");
    }

    @Test
    @DisplayName("The package error carries the failing SQL, which belongs to the package")
    void packageErrorCarriesTheSql() {
        FunctionImplementationResult result = new FunctionImplementationResult();

        result.addPackageError("hr", "emp_pkg", "boom", "CREATE FUNCTION hr.emp_pkg__get_x() ...");

        FunctionImplementationResult.PackageErrorInfo error =
                result.getPackageErrors().get("hr.emp_pkg");
        assertEquals("emp_pkg", error.getPackageName());
        assertTrue(error.getSql().contains("CREATE FUNCTION"),
                "without the rejected SQL the error cannot be root-caused; step 28 passed null for "
                        + "it on every one of the 2026-08-28 run's entries");
    }

    @Test
    @DisplayName("A function blocked by its package is a counted failure, never a skip")
    void blockedFunctionIsAFailureNotASkip() {
        FunctionImplementationResult result = new FunctionImplementationResult();

        result.addBlockedFunction(member("get_salary"), "emp_pkg", "3 of 7 helpers failed");

        assertEquals(1, result.getBlockedCount(), "a blocked function must be counted");
        assertEquals(0, result.getSkippedCount(),
                "skip must mean 'nothing to do'; a blocked function has a body and is broken");
        assertTrue(result.getSkippedFunctions().isEmpty(), "the skipped list must stay empty");
        assertTrue(!result.isSuccessful(),
                "a run that implemented nothing because every package context failed must not "
                        + "report success");
    }

    @Test
    @DisplayName("A blocked function names the package to fix, not just its own name")
    void blockedFunctionNamesThePackage() {
        FunctionImplementationResult result = new FunctionImplementationResult();

        result.addBlockedFunction(member("get_salary"), "emp_pkg", "3 of 7 helpers failed");

        FunctionImplementationResult.BlockedFunctionInfo blocked =
                result.getBlockedFunctions().get("hr.emp_pkg.get_salary");
        assertEquals("emp_pkg", blocked.getPackageName(),
                "the fix is in the package; an error naming only the member sends the reader to "
                        + "the wrong file");
        assertTrue(blocked.getReason().contains("helpers failed"), blocked.getReason());
    }

    @Test
    @DisplayName("Package errors alone make the result unsuccessful")
    void packageErrorsAloneFailTheRun() {
        FunctionImplementationResult result = new FunctionImplementationResult();

        result.addPackageError("hr", "emp_pkg", "boom", null);

        assertTrue(!result.isSuccessful(),
                "a failed package context is a failure even before any member is counted");
    }

    @Test
    @DisplayName("A package already known to have failed is never retried")
    void knownFailedPackageIsNotRetried() {
        PostgresFunctionImplementationJob job = new PostgresFunctionImplementationJob();
        FunctionImplementationResult result = new FunctionImplementationResult();
        job.packageContextFailures.put("hr.emp_pkg", "type \"salary_tab\" does not exist");

        // Null connections: reaching Oracle or PostgreSQL at all would throw. Before the negative
        // cache existed, a failing package was rebuilt from scratch for every one of its members --
        // spec re-queried, body re-parsed, helpers re-executed and re-failed, ~16 times over.
        String reason = job.ensurePackageContext(null, null, "hr", "emp_pkg", result);

        assertEquals("type \"salary_tab\" does not exist", reason,
                "the cached reason must be returned so the member can be recorded as blocked");
        assertEquals(0, result.getPackageErrorCount(),
                "the package error was already recorded on the first attempt; recording it again "
                        + "per member is the bug this replaces");
    }

    @Test
    @DisplayName("Overloads do not overwrite each other, and counts match the evidence")
    void overloadsAreCountedSeparately() {
        FunctionImplementationResult result = new FunctionImplementationResult();

        FunctionMetadata first = member("get_salary");
        FunctionMetadata second = member("get_salary");
        second.setOverloadNumber(2);

        result.addError(first, "boom", null);
        result.addError(second, "boom", null);

        assertEquals(2, result.getErrors().size(),
                "two Oracle overloads share a display name; keying on it alone drops one");
        assertEquals(2, result.getErrorCount(),
                "the 2026-08-28 run reported errorCount 58,062 against a map of 56,338 -- 2,044 "
                        + "records overwritten while the counter still incremented");
    }

    @Test
    @DisplayName("A repeated failure for one function is counted once")
    void countNeverExceedsTheMap() {
        FunctionImplementationResult result = new FunctionImplementationResult();

        result.addError(member("get_salary"), "boom", null);
        result.addError(member("get_salary"), "boom again", null);

        assertEquals(1, result.getErrors().size());
        assertEquals(1, result.getErrorCount(),
                "a count that outruns its map advertises evidence that is not there");
    }
}
