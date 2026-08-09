package me.christianrobert.orapgsync.core.job.model.index;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Result of PostgreSQL index creation.
 *
 * <p>Holds one flat list of {@link IndexOutcome}s rather than separate created/skipped/error
 * lists, so the counts can never disagree with the detail and the UI can filter one collection.</p>
 *
 * <p>Not thread-safe by design. Index creation runs on a worker pool, but workers only publish
 * outcomes to a queue and all aggregation happens on the calling thread - the same thread
 * confinement used by the parallel data transfer.</p>
 */
public class IndexCreationResult {

    private final List<IndexOutcome> outcomes = new ArrayList<>();
    private final LocalDateTime executionDateTime = LocalDateTime.now();

    public void add(IndexOutcome outcome) {
        outcomes.add(outcome);
    }

    public List<IndexOutcome> getOutcomes() {
        return new ArrayList<>(outcomes);
    }

    public List<IndexOutcome> getByStatus(IndexOutcome.Status status) {
        return outcomes.stream().filter(o -> o.status() == status).toList();
    }

    public List<IndexOutcome> getCreatedIndexes() {
        return getByStatus(IndexOutcome.Status.CREATED);
    }

    public List<IndexOutcome> getSkippedIndexes() {
        return getByStatus(IndexOutcome.Status.SKIPPED);
    }

    public List<IndexOutcome> getUnsupportedIndexes() {
        return getByStatus(IndexOutcome.Status.UNSUPPORTED);
    }

    public List<IndexOutcome> getErrors() {
        return getByStatus(IndexOutcome.Status.ERROR);
    }

    public int getCreatedCount() {
        return countOf(IndexOutcome.Status.CREATED);
    }

    public int getSkippedCount() {
        return countOf(IndexOutcome.Status.SKIPPED);
    }

    public int getUnsupportedCount() {
        return countOf(IndexOutcome.Status.UNSUPPORTED);
    }

    public int getErrorCount() {
        return countOf(IndexOutcome.Status.ERROR);
    }

    public int getTotalProcessed() {
        return outcomes.size();
    }

    private int countOf(IndexOutcome.Status status) {
        return (int) outcomes.stream().filter(o -> o.status() == status).count();
    }

    public boolean hasErrors() {
        return getErrorCount() > 0;
    }

    public boolean isSuccessful() {
        return !hasErrors();
    }

    public LocalDateTime getExecutionDateTime() {
        return executionDateTime;
    }

    @Override
    public String toString() {
        return String.format("IndexCreationResult{created=%d, skipped=%d, unsupported=%d, errors=%d}",
                getCreatedCount(), getSkippedCount(), getUnsupportedCount(), getErrorCount());
    }
}
