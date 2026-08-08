package me.christianrobert.orapgsync.preflight.service;

import me.christianrobert.orapgsync.core.job.model.preflight.CompatibilityFinding;
import me.christianrobert.orapgsync.core.job.model.preflight.CompatibilityReport;
import me.christianrobert.orapgsync.core.job.model.preflight.CompatibilityStatus;
import me.christianrobert.orapgsync.core.job.model.preflight.ConstructStat;
import me.christianrobert.orapgsync.core.job.model.preflight.FailureStat;
import me.christianrobert.orapgsync.transformer.analysis.ConstructSupport;
import me.christianrobert.orapgsync.transformer.analysis.DetectedConstruct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the ranking that drives the demand-driven fix order: constructs are ranked by how many
 * <em>failing</em> objects they appear in, not by how often they occur.
 */
class CompatibilityReportAggregatorTest {

    private CompatibilityReportAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new CompatibilityReportAggregator();
    }

    @Test
    void ranksConstructsByFailingObjectCount() {
        // MODEL blocks one view, PIVOT blocks three - PIVOT is the better investment even though
        // MODEL occurs more often in total.
        List<CompatibilityFinding> findings = List.of(
                failing("V1", construct("PIVOT")),
                failing("V2", construct("PIVOT")),
                failing("V3", construct("PIVOT")),
                failing("V4", construct("MODEL"), construct("MODEL"), construct("MODEL"),
                        construct("MODEL"), construct("MODEL")));

        List<ConstructStat> ranked = aggregator.aggregate(findings).getConstructs();

        assertEquals("PIVOT", ranked.get(0).getConstructId());
        assertEquals(3, ranked.get(0).getFailingObjectCount());
        assertEquals("MODEL", ranked.get(1).getConstructId());
        assertEquals(5, ranked.get(1).getOccurrences(), "Occurrences are still counted");
    }

    @Test
    void separatesFailingFromPassingObjects() {
        List<CompatibilityFinding> findings = List.of(
                failing("V1", construct("PIVOT")),
                passingWithWarnings("V2", construct("PIVOT")));

        ConstructStat pivot = aggregator.aggregate(findings).getConstructs().get(0);

        assertEquals(1, pivot.getFailingObjectCount());
        assertEquals(1, pivot.getPassingObjectCount());
        assertEquals(List.of("HR.V1"), pivot.getFailingExamples());
        assertEquals(List.of("HR.V2"), pivot.getPassingExamples());
    }

    @Test
    void flagsUnhandledConstructsInPassingObjectsAsSilentLoss() {
        // A view that transformed without an error but contains a construct nothing handles:
        // the generated view is missing something and nobody would notice.
        CompatibilityReport report = aggregator.aggregate(List.of(
                passingWithWarnings("V1", construct("PIVOT"))));

        assertEquals(List.of("PIVOT"),
                report.getSilentLosses().stream().map(ConstructStat::getConstructId).toList());
    }

    @Test
    void handledConstructInPassingObjectIsNotASilentLoss() {
        CompatibilityReport report = aggregator.aggregate(List.of(
                passing("V1", new DetectedConstruct("ROLLUP_CUBE", "ROLLUP / CUBE",
                        ConstructSupport.HANDLED, 1, "ROLLUP(a)", "note"))));

        assertTrue(report.getSilentLosses().isEmpty());
    }

    @Test
    void countsTruncatedParseAsFailure() {
        CompatibilityReport report = aggregator.aggregate(List.of(
                finding("V1", CompatibilityStatus.TRUNCATED_PARSE,
                        "Parser stopped before the end of the statement at 'PARTITION' | unread source: x"),
                finding("V2", CompatibilityStatus.OK, null)));

        assertEquals(1, report.getFailureCount());
        assertEquals(1, report.getCount(CompatibilityStatus.TRUNCATED_PARSE));
    }

    @Test
    void groupsFailuresBySignatureAndStatus() {
        CompatibilityReport report = aggregator.aggregate(List.of(
                finding("V1", CompatibilityStatus.PARSE_ERROR,
                        "Line 3:12 - mismatched input 'PIVOT' expecting {AND, OR, ')'}"),
                finding("V2", CompatibilityStatus.PARSE_ERROR,
                        "Line 9:44 - mismatched input 'PIVOT' expecting {')'}"),
                finding("V3", CompatibilityStatus.TRANSFORM_ERROR,
                        "Unsupported string function: SOUNDEX")));

        List<FailureStat> groups = report.getFailureGroups();

        assertEquals(2, groups.size(), "The two parse errors differ only in position and expected set");
        assertEquals("mismatched input 'PIVOT'", groups.get(0).getSignature());
        assertEquals(2, groups.get(0).getObjectCount());
        assertEquals(CompatibilityStatus.PARSE_ERROR, groups.get(0).getStatus());
        assertEquals(List.of("HR.V1", "HR.V2"), groups.get(0).getExamples());
    }

    @Test
    void doesNotGroupDifferentStatusesTogether() {
        CompatibilityReport report = aggregator.aggregate(List.of(
                finding("V1", CompatibilityStatus.PARSE_ERROR, "same message"),
                finding("V2", CompatibilityStatus.TRANSFORM_ERROR, "same message")));

        assertEquals(2, report.getFailureGroups().size());
    }

    @Test
    void passingObjectsProduceNoFailureGroups() {
        CompatibilityReport report = aggregator.aggregate(List.of(
                finding("V1", CompatibilityStatus.OK, null)));

        assertTrue(report.getFailureGroups().isEmpty());
        assertEquals(0, report.getFailureCount());
        assertEquals(1, report.getAnalyzedObjectCount());
    }

    @Test
    void emptyAndNullInputProduceAnEmptyReport() {
        assertEquals(0, aggregator.aggregate(List.of()).getAnalyzedObjectCount());
        assertEquals(0, aggregator.aggregate(null).getAnalyzedObjectCount());
    }

    @Test
    void normalizesFailureMessages() {
        assertEquals("mismatched input 'PIVOT'",
                aggregator.normalizeFailureMessage("Line 3:12 - mismatched input 'PIVOT' expecting {')'}"));
        assertEquals("Parser stopped at 'MODEL'",
                aggregator.normalizeFailureMessage("Parser stopped at 'MODEL' | unread source: PARTITION BY"));
        assertEquals("only the first line matters",
                aggregator.normalizeFailureMessage("only the first line matters\ncascade noise\nmore noise"));
        assertEquals("unknown failure", aggregator.normalizeFailureMessage(null));
        assertEquals("unknown failure", aggregator.normalizeFailureMessage("   "));
    }

    @Test
    void truncatesVeryLongSignatures() {
        String signature = aggregator.normalizeFailureMessage("x".repeat(500));

        assertTrue(signature.length() < 200, "Signature should be truncated, was " + signature.length());
        assertTrue(signature.endsWith(" ..."));
    }

    // ========== HELPERS ==========

    private DetectedConstruct construct(String id) {
        return new DetectedConstruct(id, id, ConstructSupport.NO_VISITOR, 1, id + " (...)", "note");
    }

    private CompatibilityFinding failing(String name, DetectedConstruct... constructs) {
        return new CompatibilityFinding("VIEW", "HR", name, CompatibilityStatus.TRANSFORM_ERROR,
                "transformation failed", List.of(constructs));
    }

    private CompatibilityFinding passingWithWarnings(String name, DetectedConstruct... constructs) {
        return new CompatibilityFinding("VIEW", "HR", name, CompatibilityStatus.OK_WITH_WARNINGS,
                null, List.of(constructs));
    }

    private CompatibilityFinding passing(String name, DetectedConstruct... constructs) {
        return new CompatibilityFinding("VIEW", "HR", name, CompatibilityStatus.OK,
                null, List.of(constructs));
    }

    private CompatibilityFinding finding(String name, CompatibilityStatus status, String message) {
        return new CompatibilityFinding("VIEW", "HR", name, status, message, List.of());
    }
}
