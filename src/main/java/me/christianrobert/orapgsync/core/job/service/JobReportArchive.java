package me.christianrobert.orapgsync.core.job.service;

import jakarta.enterprise.context.ApplicationScoped;
import me.christianrobert.orapgsync.core.job.model.JobStatus;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Writes each finished job's full text report to disk, so it survives the process.
 *
 * <h2>Why this exists</h2>
 * Every job faithfully records per-object failures — {@code addError(name, message, sql)} keeps the
 * exact SQL that PostgreSQL rejected. All of it lived only in {@code StateService}, which is memory,
 * and {@code /api/jobs/{id}/report} could only render a job the current process still held.
 *
 * <p>On the 2026-08-28 full run that cost the evidence for the largest failure in the pipeline.
 * Step 28 reported <b>54,093 errors</b>; the application was restarted afterwards, and with it went
 * every failing statement. What remained was {@code logs/migration.log}, whose rotation had already
 * overwritten roughly 95% of those errors while the run was still going. 255 type-method failures
 * could be classified and 54,093 function failures could not — an asymmetry produced entirely by
 * which errors happened to survive, not by which mattered.
 *
 * <p>So the archive is not a convenience feature. Until a failure taxonomy can be built from a
 * finished run, every judgement about what to fix next is a guess. See
 * {@code TYPE_METHOD_IMPLEMENTATION_PLAN.md} §13.4.
 *
 * <h2>Design</h2>
 * <ul>
 *   <li><b>One hook, in {@link JobService}</b>, not per job type. Every job reaches exactly one
 *       terminal point there, so a job added later is archived without anyone remembering to do it
 *       — the same reasoning that made {@link JobTextReportFormatter} walk the Jackson tree instead
 *       of switching on the result type.</li>
 *   <li><b>Streamed, never built in memory.</b> A step-28 report is hundreds of megabytes. Holding
 *       it as one {@code String} would fail precisely on the run worth capturing.</li>
 *   <li><b>Never fails the job.</b> A migration that succeeded must not be reported as failed
 *       because a disk write did not work. Archiving problems are logged and swallowed.</li>
 * </ul>
 */
@ApplicationScoped
public class JobReportArchive {

    private static final Logger log = LoggerFactory.getLogger(JobReportArchive.class);

    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * Job ids are generated internally, but {@link #read} is reachable from a URL path parameter,
     * so it is attacker-shaped input.
     *
     * <p><b>This is defence in depth, not the primary protection.</b> What actually keeps a job id
     * from escaping the archive directory is that neither method ever builds a path out of it
     * directly: {@link #read} scans the directory's own entries and matches on a name suffix, and
     * {@link #archive} always prefixes a timestamp. Removing this pattern alone changes no
     * observable behaviour — verified by mutation. It earns its place by catching the refactor that
     * would matter, a switch to {@code dir.resolve(jobId + ".txt")}, which
     * {@code readCannotEscapeTheArchiveDirectory} pins.
     */
    private static final Pattern SAFE_JOB_ID = Pattern.compile("[A-Za-z0-9._-]{1,200}");

    @ConfigProperty(name = "job.report.archive.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "job.report.archive.directory", defaultValue = "logs/reports")
    String directory;

    /**
     * Writes the report for a finished job. Does nothing if archiving is disabled, and never
     * throws: the job has already produced its result, and losing the archive must not change it.
     */
    public void archive(JobService.JobExecution<?> execution) {
        if (!enabled || execution == null) {
            return;
        }

        String jobId = execution.getJob().getJobId();
        try {
            if (!SAFE_JOB_ID.matcher(jobId).matches()) {
                log.warn("Not archiving job report: job id is not a safe file name: {}", jobId);
                return;
            }

            Path dir = Path.of(directory);
            Files.createDirectories(dir);

            LocalDateTime stamp = execution.getStartTime() != null
                    ? execution.getStartTime()
                    : LocalDateTime.now();
            Path file = dir.resolve(stamp.format(FILE_TIMESTAMP) + "-" + jobId + ".txt");

            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                JobTextReportFormatter.write(writer, headerFor(execution), execution.getResult());
            }

            log.info("Archived job report: {} ({} bytes)", file, Files.size(file));

        } catch (Exception e) {
            // Deliberately swallowed -- see the class note.
            log.warn("Could not archive job report for {}: {}", jobId, e.toString());
        }
    }

    /**
     * Reads back a previously archived report, so {@code /api/jobs/{id}/report} keeps working for
     * a job this process no longer holds in memory.
     *
     * @return the report text, or empty if archiving is off, the id is unsafe, or no file exists
     */
    public Optional<String> read(String jobId) {
        if (!enabled || jobId == null || !SAFE_JOB_ID.matcher(jobId).matches()) {
            return Optional.empty();
        }

        Path dir = Path.of(directory);
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }

        // The file name carries a run timestamp in front of the id, so it is found by suffix.
        // Newest wins if the same job id was somehow archived twice.
        String suffix = "-" + jobId + ".txt";
        try (Stream<Path> files = Files.list(dir)) {
            Optional<Path> match = files
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .max(Path::compareTo);
            if (match.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(Files.readString(match.get(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("Could not read archived job report for {}: {}", jobId, e.toString());
            return Optional.empty();
        }
    }

    /**
     * The report header, built once here so the archived file and the live REST response cannot
     * disagree about what a job report says.
     */
    public static Map<String, String> headerFor(JobService.JobExecution<?> execution) {
        Map<String, String> header = new LinkedHashMap<>();
        header.put("Job", execution.getJob().getJobId());
        header.put("Type", execution.getJob().getJobType());
        header.put("Status", execution.getStatus().name());
        if (execution.getStartTime() != null) {
            header.put("Started", execution.getStartTime().toString());
        }
        if (execution.getEndTime() != null) {
            header.put("Finished", execution.getEndTime().toString());
        }
        if (execution.getStatus() == JobStatus.FAILED) {
            Exception error = execution.getError();
            header.put("Error", error != null ? error.getMessage() : "unknown error");
        }
        return header;
    }
}
