package me.christianrobert.orapgsync.view.job;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import me.christianrobert.orapgsync.core.job.AbstractDatabaseExtractionJob;
import me.christianrobert.orapgsync.core.job.model.JobProgress;
import me.christianrobert.orapgsync.core.job.model.view.ViewImplementationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * Creates PostgreSQL view implementations (Phase 2) by replacing stubs with actual SQL.
 * This job transforms Oracle view SQL to PostgreSQL and creates the actual views.
 *
 * Current status: STUB - Returns empty result (awaiting ANTLR transformation implementation)
 *
 * Future implementation will:
 * 1. Read Oracle view SQL from ViewMetadata.sqlDefinition
 * 2. Transform SQL using ANTLR parser (Oracle → PostgreSQL)
 * 3. Drop existing stub views
 * 4. Create views with transformed SQL
 * 5. Handle dependencies and circular references
 */
@Dependent
public class PostgresViewImplementationJob extends AbstractDatabaseExtractionJob<ViewImplementationResult> {

    private static final Logger log = LoggerFactory.getLogger(PostgresViewImplementationJob.class);

    @Override
    public String getSourceDatabase() {
        return "POSTGRES";
    }

    @Override
    public String getExtractionType() {
        return "VIEW_IMPLEMENTATION";
    }

    @Override
    public Class<ViewImplementationResult> getResultType() {
        return ViewImplementationResult.class;
    }

    @Override
    protected void saveResultsToState(List<ViewImplementationResult> results) {
        // Since this returns a single result object, extract it from the list
        if (results != null && !results.isEmpty()) {
            stateService.setViewImplementationResult(results.get(0));
        }
    }

    @Override
    protected List<ViewImplementationResult> performExtraction(Consumer<JobProgress> progressCallback) throws Exception {
        log.info("PostgresViewImplementationJob is currently a STUB");
        log.info("View implementation (SQL transformation) will be implemented in a future step using ANTLR");

        updateProgress(progressCallback, 0, "Starting view implementation",
                "NOTE: This is a stub job - actual implementation awaiting ANTLR transformation");

        // Create empty result indicating no work was done
        ViewImplementationResult result = new ViewImplementationResult();

        updateProgress(progressCallback, 50, "Stub job running",
                "View implementation placeholder - SQL transformation not yet implemented");

        log.info("ViewImplementationResult: {}", result);

        updateProgress(progressCallback, 100, "Stub job completed",
                "View implementation awaiting SQL transformation implementation (ANTLR)");

        return List.of(result);
    }

    @Override
    protected String generateSummaryMessage(List<ViewImplementationResult> results) {
        if (results == null || results.isEmpty()) {
            return "View implementation stub completed: No views implemented (awaiting ANTLR transformation)";
        }

        ViewImplementationResult result = results.get(0);
        return String.format("View implementation stub completed: %d implemented, %d skipped, %d errors (STUB - awaiting implementation)",
                result.getImplementedCount(), result.getSkippedCount(), result.getErrorCount());
    }
}
