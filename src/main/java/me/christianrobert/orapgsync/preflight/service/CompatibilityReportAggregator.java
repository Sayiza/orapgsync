package me.christianrobert.orapgsync.preflight.service;

import jakarta.enterprise.context.ApplicationScoped;
import me.christianrobert.orapgsync.core.job.model.preflight.CompatibilityFinding;
import me.christianrobert.orapgsync.core.job.model.preflight.CompatibilityReport;
import me.christianrobert.orapgsync.core.job.model.preflight.CompatibilityStatus;
import me.christianrobert.orapgsync.core.job.model.preflight.ConstructStat;
import me.christianrobert.orapgsync.core.job.model.preflight.FailureStat;
import me.christianrobert.orapgsync.transformer.analysis.DetectedConstruct;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns per-object {@link CompatibilityFinding}s into the ranked {@link CompatibilityReport}.
 *
 * <p>Ranking is by number of <em>failing</em> objects a construct appears in, because that is the
 * number that decides what is worth implementing next. Occurrence count only breaks ties.</p>
 */
@ApplicationScoped
public class CompatibilityReportAggregator {

    private static final int MAX_SIGNATURE_LENGTH = 100;

    public CompatibilityReport aggregate(List<CompatibilityFinding> findings) {
        List<CompatibilityFinding> input = findings != null ? findings : List.of();

        Map<CompatibilityStatus, Integer> statusCounts = new EnumMap<>(CompatibilityStatus.class);
        Map<String, ConstructStat> constructStats = new LinkedHashMap<>();
        Map<String, FailureStat> failureStats = new LinkedHashMap<>();

        for (CompatibilityFinding finding : input) {
            statusCounts.merge(finding.getStatus(), 1, Integer::sum);

            for (DetectedConstruct construct : finding.getConstructs()) {
                constructStats
                        .computeIfAbsent(construct.id(), id -> new ConstructStat(
                                id, construct.displayName(), construct.support(), construct.note()))
                        .record(finding.getQualifiedName(), finding.isFailure());
            }

            if (finding.isFailure()) {
                String signature = normalizeFailureMessage(finding.getErrorMessage());
                failureStats
                        .computeIfAbsent(finding.getStatus().name() + "|" + signature,
                                key -> new FailureStat(finding.getStatus(), signature))
                        .record(finding.getQualifiedName(), finding.getErrorMessage());
            }
        }

        List<ConstructStat> rankedConstructs = new ArrayList<>(constructStats.values());
        rankedConstructs.sort(Comparator
                .comparingInt(ConstructStat::getFailingObjectCount).reversed()
                .thenComparing(Comparator.comparingInt(ConstructStat::getAffectedObjectCount).reversed())
                .thenComparing(ConstructStat::getConstructId));

        List<FailureStat> rankedFailures = new ArrayList<>(failureStats.values());
        rankedFailures.sort(Comparator
                .comparingInt(FailureStat::getObjectCount).reversed()
                .thenComparing(FailureStat::getSignature));

        return new CompatibilityReport(input.size(), statusCounts, rankedConstructs, rankedFailures);
    }

    /**
     * Reduces a failure message to a signature that groups equivalent failures.
     *
     * <p>Drops the {@code Line n:m -} prefix (the position differs per object), cuts everything
     * after the first {@code expecting } (ANTLR's expected-token set is long and adds nothing to
     * the grouping) or after {@code  | } (the object specific detail the analyzer appends), and
     * truncates the remainder.</p>
     */
    public String normalizeFailureMessage(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "unknown failure";
        }

        // Only the first line matters; the rest is cascade noise from the same position.
        String firstLine = errorMessage.split("\\R", 2)[0];

        String signature = firstLine.replaceFirst("^Line \\d+:\\d+ - ", "");
        signature = cutAt(signature, " expecting ");
        signature = cutAt(signature, " | ");
        signature = signature.trim();

        return signature.length() > MAX_SIGNATURE_LENGTH
                ? signature.substring(0, MAX_SIGNATURE_LENGTH) + " ..."
                : signature;
    }

    private String cutAt(String text, String separator) {
        int index = text.indexOf(separator);
        return index >= 0 ? text.substring(0, index) : text;
    }
}
