package me.christianrobert.orapgsync.preflight.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseExtractionJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.preflight.CompatibilityFinding;
import me.christianrobert.orapgsync.core.job.model.preflight.CompatibilityStatus;
import me.christianrobert.orapgsync.core.job.model.view.ViewMetadata;
import me.christianrobert.orapgsync.preflight.service.ViewCompatibilityAnalyzer;
import me.christianrobert.orapgsync.transformer.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Pre-flight compatibility report: transforms every extracted Oracle view in memory and records
 * which Oracle constructs each one contains, without creating anything in PostgreSQL.
 *
 * <p>Purpose is to answer "what will fail, and why" <em>before</em> a migration run, and to rank
 * the causes by how often they occur in the actual codebase. It reads Oracle view metadata from
 * state, so no database connection is opened.</p>
 *
 * <p>Currently covers views. Functions and procedures need package context to transform and are
 * a follow-up.</p>
 */
@Dependent
public class OracleCompatibilityReportJob extends AbstractDatabaseExtractionJob<CompatibilityFinding> {

    private static final Logger log = LoggerFactory.getLogger(OracleCompatibilityReportJob.class);

    @Inject
    ViewCompatibilityAnalyzer viewAnalyzer;

    @Override
    public String getSourceDatabase() {
        return "ORACLE";
    }

    @Override
    public String getExtractionType() {
        return "COMPATIBILITY_REPORT";
    }

    @Override
    public Class<CompatibilityFinding> getResultType() {
        return CompatibilityFinding.class;
    }

    @Override
    public String getDescription() {
        return "Analyse extracted Oracle views for transformation compatibility (no database access)";
    }

    @Override
    protected void saveResultsToState(List<CompatibilityFinding> results) {
        stateService.setCompatibilityFindings(results);
    }

    @Override
    protected List<CompatibilityFinding> performExtraction(Consumer<JobProgress> progressCallback) {
        log.info("Starting pre-flight compatibility analysis");

        updateProgress(progressCallback, 0, "Initializing", "Starting compatibility analysis");

        List<ViewMetadata> oracleViews = stateService.getOracleViewMetadata();

        if (oracleViews == null || oracleViews.isEmpty()) {
            log.warn("No Oracle views in state - nothing to analyse");
            updateProgress(progressCallback, 100, "No views to analyse",
                    "No Oracle views found in state. Please extract Oracle views first.");
            return List.of();
        }

        List<ViewMetadata> views = filterAnalyzableViews(oracleViews);

        updateProgress(progressCallback, 10, "Building metadata indices",
                String.format("Analysing %d views", views.size()));

        List<String> schemas = stateService.getOracleSchemaNames();
        TransformationIndices indices = MetadataIndexBuilder.build(stateService, schemas);

        List<CompatibilityFinding> findings = new ArrayList<>();
        int total = views.size();
        int processed = 0;

        for (ViewMetadata view : views) {
            if (total > 0 && processed % Math.max(1, total / 20) == 0) {
                updateProgress(progressCallback, 10 + (processed * 85 / total),
                        "Analysing views",
                        String.format("View %d of %d", processed + 1, total));
            }

            findings.add(viewAnalyzer.analyze(view, indices));
            processed++;
        }

        // Deterministic ordering regardless of extraction order (project convention).
        findings.sort(Comparator
                .comparing(CompatibilityFinding::getSchema, Comparator.nullsFirst(String::compareTo))
                .thenComparing(CompatibilityFinding::getObjectName, Comparator.nullsFirst(String::compareTo)));

        long failures = findings.stream().filter(CompatibilityFinding::isFailure).count();
        long warnings = findings.stream()
                .filter(f -> f.getStatus() == CompatibilityStatus.OK_WITH_WARNINGS).count();

        log.info("Compatibility analysis completed: {} views analysed, {} failures, {} with warnings",
                findings.size(), failures, warnings);

        return findings;
    }

    /**
     * Keeps views in non-excluded schemas. Views without SQL are kept on purpose — they show up
     * as NO_SOURCE, which is itself a finding worth seeing.
     */
    private List<ViewMetadata> filterAnalyzableViews(List<ViewMetadata> views) {
        List<ViewMetadata> analyzable = new ArrayList<>();
        for (ViewMetadata view : views) {
            if (filterValidSchemas(List.of(view.getSchema())).isEmpty()) {
                log.debug("Skipping view {}.{} - schema excluded", view.getSchema(), view.getViewName());
                continue;
            }
            analyzable.add(view);
        }
        return analyzable;
    }

    @Override
    protected String generateSummaryMessage(List<CompatibilityFinding> results) {
        if (results.isEmpty()) {
            return "Compatibility analysis completed: no views analysed";
        }

        long ok = results.stream().filter(f -> f.getStatus() == CompatibilityStatus.OK).count();
        long warnings = results.stream()
                .filter(f -> f.getStatus() == CompatibilityStatus.OK_WITH_WARNINGS).count();
        long truncated = results.stream()
                .filter(f -> f.getStatus() == CompatibilityStatus.TRUNCATED_PARSE).count();
        long failures = results.stream().filter(CompatibilityFinding::isFailure).count();

        return String.format(
                "Compatibility analysis completed: %d views analysed, %d ok, %d with warnings, "
                        + "%d failing (of which %d silently truncated)",
                results.size(), ok, warnings, failures, truncated);
    }
}
