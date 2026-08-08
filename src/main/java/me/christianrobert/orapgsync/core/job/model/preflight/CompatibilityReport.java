package me.christianrobert.orapgsync.core.job.model.preflight;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Aggregated pre-flight compatibility report: how many analysed objects transform, and which
 * Oracle constructs are responsible for the ones that do not.
 *
 * <p>Built by {@code CompatibilityReportAggregator} from the per-object
 * {@link CompatibilityFinding}s stored in state.</p>
 */
public class CompatibilityReport {

    private final LocalDateTime generatedAt = LocalDateTime.now();
    private final int analyzedObjectCount;
    private final Map<CompatibilityStatus, Integer> statusCounts;
    private final List<ConstructStat> constructs;
    private final List<FailureStat> failureGroups;

    public CompatibilityReport(int analyzedObjectCount,
                               Map<CompatibilityStatus, Integer> statusCounts,
                               List<ConstructStat> constructs,
                               List<FailureStat> failureGroups) {
        this.analyzedObjectCount = analyzedObjectCount;
        this.statusCounts = Map.copyOf(statusCounts);
        this.constructs = new ArrayList<>(constructs);
        this.failureGroups = new ArrayList<>(failureGroups);
    }

    public LocalDateTime getGeneratedAt() {
        return generatedAt;
    }

    public int getAnalyzedObjectCount() {
        return analyzedObjectCount;
    }

    public Map<CompatibilityStatus, Integer> getStatusCounts() {
        return statusCounts;
    }

    public int getCount(CompatibilityStatus status) {
        return statusCounts.getOrDefault(status, 0);
    }

    public int getFailureCount() {
        return getCount(CompatibilityStatus.PARSE_ERROR)
                + getCount(CompatibilityStatus.TRANSFORM_ERROR)
                + getCount(CompatibilityStatus.TRUNCATED_PARSE);
    }

    /** Constructs ranked by how many failing objects they appear in. */
    public List<ConstructStat> getConstructs() {
        return new ArrayList<>(constructs);
    }

    /**
     * Constructs that were dropped or unhandled in objects that transformed without an error.
     * These are the silent-corruption candidates.
     */
    public List<ConstructStat> getSilentLosses() {
        return constructs.stream().filter(ConstructStat::isSilentLoss).toList();
    }

    /** Failures grouped by status and normalized message, most frequent first. */
    public List<FailureStat> getFailureGroups() {
        return new ArrayList<>(failureGroups);
    }

    @Override
    public String toString() {
        return "CompatibilityReport{analyzed=" + analyzedObjectCount +
               ", failures=" + getFailureCount() +
               ", constructs=" + constructs.size() + "}";
    }
}
