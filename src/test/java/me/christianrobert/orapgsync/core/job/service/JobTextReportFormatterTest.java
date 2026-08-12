package me.christianrobert.orapgsync.core.job.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the plain-text job report format.
 *
 * <p>The formatter is generic on purpose (it walks the Jackson tree rather than switching on
 * the result type), so these tests use ad-hoc shapes: what matters is that any shape a job
 * result can take comes out complete and readable.
 */
class JobTextReportFormatterTest {

    private static Map<String, String> header() {
        Map<String, String> header = new LinkedHashMap<>();
        header.put("Job", "job-42");
        header.put("Type", "VIEW_IMPLEMENTATION");
        header.put("Status", "COMPLETED");
        return header;
    }

    @Test
    @DisplayName("Header fields are rendered in insertion order")
    void rendersHeaderInOrder() {
        String report = JobTextReportFormatter.format(header(), null);

        int job = report.indexOf("Job:");
        int type = report.indexOf("Type:");
        int status = report.indexOf("Status:");

        assertTrue(job >= 0 && type > job && status > type,
                "header fields must keep insertion order:\n" + report);
        assertTrue(report.contains("job-42"));
        assertTrue(report.contains("(no result)"));
    }

    @Test
    @DisplayName("Scalar fields are rendered as key: value")
    void rendersScalars() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("createdCount", 12);
        result.put("errorCount", 0);
        result.put("successful", true);

        String report = JobTextReportFormatter.format(header(), result);

        assertTrue(report.contains("createdCount: 12"), report);
        assertTrue(report.contains("errorCount: 0"), report);
        assertTrue(report.contains("successful: true"), report);
    }

    @Test
    @DisplayName("Every entry of a large list appears - the report is the uncapped view")
    void rendersEveryListEntry() {
        List<Map<String, Object>> views = new java.util.ArrayList<>();
        for (int i = 1; i <= 500; i++) {
            views.add(Map.of("viewName", "VIEW_" + i));
        }
        Map<String, Object> result = Map.of("views", views);

        String report = JobTextReportFormatter.format(header(), result);

        assertTrue(report.contains("views: (500)"), "list size must be stated:\n" + firstLines(report));
        assertTrue(report.contains("VIEW_1\n"), "first entry missing");
        assertTrue(report.contains("VIEW_250"), "middle entry missing");
        assertTrue(report.contains("VIEW_500"), "last entry missing");
    }

    @Test
    @DisplayName("Multi-line SQL is rendered as an indented block, not one long line")
    void rendersSqlAsBlock() {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("viewName", "EMP_V");
        failure.put("error", "unsupported construct");
        failure.put("sql", "SELECT a,\n       b\n  FROM emp");

        String report = JobTextReportFormatter.format(header(), Map.of("errors", List.of(failure)));

        assertTrue(report.contains("sql:"), report);
        assertTrue(report.contains("SELECT a,"), report);
        assertTrue(report.contains("FROM emp"), report);
        assertFalse(report.contains("SELECT a,\\n"), "newlines must not be escaped");
    }

    @Test
    @DisplayName("Empty and null collections are stated explicitly rather than omitted")
    void rendersEmptyValuesExplicitly() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("errors", List.of());
        result.put("skipped", java.util.Collections.emptyMap());

        String report = JobTextReportFormatter.format(header(), result);

        assertTrue(report.contains("errors: (none)"), report);
        assertTrue(report.contains("skipped: (none)"), report);
    }

    @Test
    @DisplayName("A list of scalars stays on one line")
    void rendersScalarListInline() {
        String report = JobTextReportFormatter.format(header(), Map.of("schemas", List.of("HR", "SALES")));

        assertTrue(report.contains("schemas: HR, SALES"), report);
    }

    @Test
    @DisplayName("A result that cannot be serialized is reported, not thrown")
    void unserializableResultIsReported() {
        Object unserializable = new Object() {
            @SuppressWarnings("unused")
            public String getBoom() {
                throw new IllegalStateException("kaboom");
            }
        };

        String report = JobTextReportFormatter.format(header(), unserializable);

        assertTrue(report.contains("Could not render result"), report);
        assertTrue(report.contains("kaboom"), report);
    }

    private static String firstLines(String report) {
        String[] lines = report.split("\n");
        return String.join("\n", java.util.Arrays.copyOfRange(lines, 0, Math.min(15, lines.length)));
    }
}
