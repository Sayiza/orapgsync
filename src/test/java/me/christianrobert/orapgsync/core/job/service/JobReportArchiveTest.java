package me.christianrobert.orapgsync.core.job.service;

import me.christianrobert.orapgsync.core.job.Job;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.JobStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the behaviour that makes a run's evidence outlive the run.
 *
 * <p>Written after the 2026-08-28 full run, where step 28's 54,093 per-object errors — each
 * holding the exact SQL PostgreSQL rejected — existed only in {@code StateService} and were lost
 * on restart, while log rotation had already overwritten most of the same information. See
 * {@code TYPE_METHOD_IMPLEMENTATION_PLAN.md} §13.4.
 */
class JobReportArchiveTest {

    /** Minimal stand-in: the archive only reads a job's id and type. */
    private record FakeJob(String getJobId, String getJobType) implements Job<Object> {
        @Override public String getDescription() { return "fake"; }
        @Override public CompletableFuture<Object> execute(Consumer<JobProgress> cb) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static JobService.JobExecution<Object> completed(String jobId, Object result) {
        JobService.JobExecution<Object> execution =
                new JobService.JobExecution<>(new FakeJob(jobId, "FUNCTION_IMPLEMENTATION"));
        execution.setStatus(JobStatus.COMPLETED);
        execution.setStartTime(LocalDateTime.of(2026, 8, 28, 9, 33, 6));
        execution.setEndTime(LocalDateTime.of(2026, 8, 28, 9, 34, 41));
        execution.setResult(result);
        return execution;
    }

    private static JobReportArchive archiveIn(Path dir, boolean enabled) {
        JobReportArchive archive = new JobReportArchive();
        archive.directory = dir.toString();
        archive.enabled = enabled;
        return archive;
    }

    /** The shape that matters: a failure carrying the statement that failed. */
    private static Map<String, Object> resultWithFailingSql() {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("functionName", "hr.emp_pkg.get_salary");
        error.put("error", "Failed to execute in PostgreSQL: ERROR: type \"bvars\" does not exist");
        error.put("sql", "CREATE OR REPLACE FUNCTION hr.emp_pkg__get_salary()\nRETURNS numeric AS $$\nDECLARE v bvars;\nBEGIN RETURN 1; END;\n$$ LANGUAGE plpgsql;");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("implementedCount", 16366);
        result.put("errorCount", 1);
        result.put("errors", List.of(error));
        return result;
    }

    @Test
    @DisplayName("A finished job's report is written to disk, including the failing SQL")
    void archivesReportWithFailingSql(@TempDir Path dir) throws Exception {
        archiveIn(dir, true).archive(completed("job-abc", resultWithFailingSql()));

        List<Path> files = Files.list(dir).toList();
        assertEquals(1, files.size(), "expected exactly one archived report, got: " + files);

        String report = Files.readString(files.get(0));
        assertTrue(report.contains("job-abc"), report);
        assertTrue(report.contains("FUNCTION_IMPLEMENTATION"), report);
        assertTrue(report.contains("implementedCount: 16366"), report);
        assertTrue(report.contains("type \"bvars\" does not exist"), report);
        assertTrue(report.contains("CREATE OR REPLACE FUNCTION hr.emp_pkg__get_salary()"),
                "the failing SQL is the reason this archive exists -- it must be in the file:\n"
                        + report);
    }

    @Test
    @DisplayName("The file name carries the run timestamp and the job id, so runs sort and are findable")
    void fileNameIsTimestampAndJobId(@TempDir Path dir) throws Exception {
        archiveIn(dir, true).archive(completed("job-abc", Map.of("errorCount", 0)));

        String name = Files.list(dir).findFirst().orElseThrow().getFileName().toString();
        assertEquals("20260828-093306-job-abc.txt", name);
    }

    @Test
    @DisplayName("An archived report is readable back after the job has left memory")
    void readsBackArchivedReport(@TempDir Path dir) {
        JobReportArchive archive = archiveIn(dir, true);
        archive.archive(completed("job-abc", resultWithFailingSql()));

        String report = archive.read("job-abc").orElseThrow(
                () -> new AssertionError("archived report must be readable by job id"));
        assertTrue(report.contains("type \"bvars\" does not exist"), report);
    }

    @Test
    @DisplayName("An unknown job id reads back as empty rather than failing")
    void unknownJobIdIsEmpty(@TempDir Path dir) {
        assertTrue(archiveIn(dir, true).read("job-does-not-exist").isEmpty());
    }

    @Test
    @DisplayName("read() cannot be steered outside the archive directory by its job id")
    void readCannotEscapeTheArchiveDirectory(@TempDir Path parent) throws Exception {
        // read()'s argument arrives from a URL path parameter, so it is attacker-shaped input.
        Path outside = parent.resolve("outside.txt");
        Files.writeString(outside, "SECRET - must never be served as a job report");

        Path dir = Files.createDirectory(parent.resolve("reports"));
        JobReportArchive archive = archiveIn(dir, true);

        for (String jobId : List.of("../outside", "..%2Foutside", "a/../outside",
                "/etc/passwd", "..", "")) {
            assertTrue(archive.read(jobId).isEmpty(),
                    "job id '" + jobId + "' must not reach a file outside " + dir);
        }
        assertTrue(archive.read(null).isEmpty());
    }

    @Test
    @DisplayName("An unsafe job id produces no file")
    void unsafeJobIdWritesNothing(@TempDir Path dir) throws Exception {
        archiveIn(dir, true).archive(completed("../escape", Map.of("errorCount", 0)));

        assertEquals(0, Files.list(dir).count(),
                "an unsafe job id must not produce a file");
    }

    @Test
    @DisplayName("Archiving is skipped entirely when disabled")
    void disabledWritesNothing(@TempDir Path dir) throws Exception {
        JobReportArchive archive = archiveIn(dir, false);
        archive.archive(completed("job-abc", resultWithFailingSql()));

        assertFalse(Files.exists(dir.resolve("20260828-093306-job-abc.txt")));
        assertTrue(archive.read("job-abc").isEmpty());
    }

    @Test
    @DisplayName("A job that failed is archived too, with its error in the header")
    void archivesFailedJob(@TempDir Path dir) throws Exception {
        JobService.JobExecution<Object> execution = completed("job-failed", Map.of("errorCount", 3));
        execution.setStatus(JobStatus.FAILED);
        execution.setError(new IllegalStateException("Oracle connection lost"));

        archiveIn(dir, true).archive(execution);

        String report = Files.readString(dir.resolve("20260828-093306-job-failed.txt"));
        assertTrue(report.contains("FAILED"), report);
        assertTrue(report.contains("Oracle connection lost"),
                "a failed job's partial result is the most valuable report of all:\n" + report);
    }

    @Test
    @DisplayName("An unwritable destination never propagates out of archiving")
    void archivingFailureIsSwallowed(@TempDir Path dir) throws Exception {
        // A regular file where the archive expects a directory.
        Path blocked = dir.resolve("blocked");
        Files.writeString(blocked, "not a directory");

        JobReportArchive archive = archiveIn(blocked, true);

        // Must not throw: a migration that succeeded cannot be reported as failed because a
        // disk write did not work.
        archive.archive(completed("job-abc", resultWithFailingSql()));
        assertTrue(archive.read("job-abc").isEmpty());
    }
}
