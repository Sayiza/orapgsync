package me.christianrobert.orapgsync.core.job.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import me.christianrobert.orapgsync.core.job.model.table.ColumnMetadata;
import me.christianrobert.orapgsync.core.job.model.trigger.TriggerMetadata;
import me.christianrobert.orapgsync.core.job.model.view.ViewMetadata;
import me.christianrobert.orapgsync.trigger.rest.TriggerResource;
import me.christianrobert.orapgsync.view.rest.ViewResource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what the extraction summaries put on the wire.
 *
 * <p>These summaries are what `/api/jobs/{jobId}/result` returns for list jobs — the raw
 * metadata is deliberately not sent. The point of the projection is that the source text and
 * the nested collections stay out of it, so that is what these tests assert. Without them the
 * next person to add a field to the summary has nothing telling them the omissions are
 * intentional.
 */
class ExtractionSummaryPayloadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A view whose source is large enough that shipping it would be obvious. */
    private static ViewMetadata view(String schema, String name, int columns) {
        ViewMetadata view = new ViewMetadata(schema, name);
        view.setSqlDefinition("SELECT " + "x".repeat(2000) + " FROM dual");
        for (int i = 0; i < columns; i++) {
            view.addColumn(new ColumnMetadata("COL_" + i, "VARCHAR2", 100, null, null, true, null));
        }
        return view;
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("View summary carries name and column count, never the Oracle SQL")
    void viewSummaryOmitsSqlDefinition() {
        Map<String, Object> summary = ViewResource.generateViewDefinitionSummary(
                List.of(view("HR", "EMP_V", 3)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> views = (List<Map<String, Object>>) summary.get("views");

        assertEquals(1, views.size());
        assertEquals("HR", views.get(0).get("schema"));
        assertEquals("EMP_V", views.get(0).get("viewName"));
        assertEquals(3, views.get(0).get("columnCount"));

        String payload = json(summary);
        assertFalse(payload.contains("sqlDefinition"), "view SQL must not be serialized");
        assertFalse(payload.contains("xxxxx"), "view SQL body leaked into the payload");
        assertFalse(payload.contains("COL_0"), "column list must not be serialized");
    }

    @Test
    @DisplayName("View summary stays small as the view count grows")
    void viewSummaryScalesWithCountNotSourceSize() {
        List<ViewMetadata> views = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            views.add(view(i % 3 == 0 ? "HR" : "SALES", "V_" + i, 20));
        }

        int trimmed = json(ViewResource.generateViewDefinitionSummary(views)).length();
        int raw = json(views).length();

        assertTrue(trimmed * 20 < raw,
                "trimmed summary should be far smaller than the raw metadata; was "
                        + trimmed + " vs " + raw);
    }

    @Test
    @DisplayName("Trigger summary omits the body and both generated DDL fields")
    void triggerSummaryOmitsCode() {
        TriggerMetadata trigger = new TriggerMetadata("HR", "EMP_TRG", "EMP");
        trigger.setTriggerType("BEFORE");
        trigger.setTriggerLevel("ROW");
        trigger.setTriggerBody("BEGIN " + "y".repeat(2000) + " END;");
        trigger.setPostgresFunctionDdl("CREATE OR REPLACE FUNCTION " + "z".repeat(2000));
        trigger.setPostgresTriggerDdl("CREATE TRIGGER " + "w".repeat(2000));

        Map<String, Object> summary = TriggerResource.generateTriggerSummary(List.of(trigger));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> triggers = (List<Map<String, Object>>) summary.get("triggers");

        assertEquals(1, triggers.size());
        assertEquals("EMP_TRG", triggers.get(0).get("triggerName"));
        assertEquals("EMP", triggers.get(0).get("tableName"));
        assertEquals("BEFORE", triggers.get(0).get("triggerType"));
        assertEquals("ROW", triggers.get(0).get("triggerLevel"));

        String payload = json(summary);
        assertFalse(payload.contains("yyyyy"), "trigger body leaked into the payload");
        assertFalse(payload.contains("zzzzz"), "generated function DDL leaked into the payload");
        assertFalse(payload.contains("wwwww"), "generated trigger DDL leaked into the payload");
    }

    @Test
    @DisplayName("Counts in the summary still cover every extracted object")
    void summaryCountsAreComplete() {
        List<ViewMetadata> views = List.of(
                view("HR", "A", 2), view("HR", "B", 3), view("SALES", "C", 1));

        Map<String, Object> summary = ViewResource.generateViewDefinitionSummary(views);

        assertEquals(3, summary.get("totalViews"));
        assertEquals(6, summary.get("totalColumns"));

        @SuppressWarnings("unchecked")
        Map<String, Integer> perSchema = (Map<String, Integer>) summary.get("schemaViewCounts");
        assertEquals(2, perSchema.get("HR"));
        assertEquals(1, perSchema.get("SALES"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> projected = (List<Map<String, Object>>) summary.get("views");
        assertEquals(views.size(), projected.size(), "no view may be dropped from the projection");
    }
}
