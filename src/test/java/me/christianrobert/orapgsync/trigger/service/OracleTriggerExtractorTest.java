package me.christianrobert.orapgsync.trigger.service;

import me.christianrobert.orapgsync.core.job.model.trigger.TriggerMetadata;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for OracleTriggerExtractor.
 * Tests the parsing logic for Oracle trigger metadata.
 */
class OracleTriggerExtractorTest {

    /**
     * Helper to create a mock ResultSet with trigger data.
     */
    private ResultSet createMockResultSet(
            String owner, String triggerName, String tableName,
            String triggerType, String triggeringEvent, String status,
            String triggerBody, String whenClause, String description) throws SQLException {

        ResultSet rs = mock(ResultSet.class);

        when(rs.next()).thenReturn(true, false); // One row, then done
        when(rs.getString("owner")).thenReturn(owner);
        when(rs.getString("trigger_name")).thenReturn(triggerName);
        when(rs.getString("table_owner")).thenReturn(owner);
        when(rs.getString("table_name")).thenReturn(tableName);
        when(rs.getString("trigger_type")).thenReturn(triggerType);
        when(rs.getString("triggering_event")).thenReturn(triggeringEvent);
        when(rs.getString("status")).thenReturn(status);
        when(rs.getString("trigger_body")).thenReturn(triggerBody);
        when(rs.getString("when_clause")).thenReturn(whenClause);
        when(rs.getString("description")).thenReturn(description);

        return rs;
    }

    /**
     * Helper to create a mock connection that returns the mock result set.
     */
    private Connection createMockConnection(ResultSet rs) throws SQLException {
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);

        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);

        return conn;
    }

    @Test
    void testBeforeEachRowTrigger() throws SQLException {
        String triggerBody = "BEGIN\n  INSERT INTO audit_log VALUES (:NEW.id);\nEND;";

        ResultSet rs = createMockResultSet(
                "HR", "AUDIT_INSERT_TRG", "EMPLOYEES",
                "BEFORE EACH ROW", "INSERT", "ENABLED",
                triggerBody, null, null);

        Connection conn = createMockConnection(rs);
        List<TriggerMetadata> triggers = OracleTriggerExtractor.extractAllTriggers(conn, List.of("HR"));

        assertEquals(1, triggers.size());
        TriggerMetadata trigger = triggers.get(0);
        assertEquals("hr", trigger.getSchema());
        assertEquals("audit_insert_trg", trigger.getTriggerName());
        assertEquals("employees", trigger.getTableName());
        assertEquals("BEFORE", trigger.getTriggerType());
        assertEquals("ROW", trigger.getTriggerLevel());
        assertEquals("INSERT", trigger.getTriggerEvent());
        assertEquals("ENABLED", trigger.getStatus());
        assertNotNull(trigger.getTriggerBody());
        assertTrue(trigger.getTriggerBody().contains("BEGIN"));
    }

    @Test
    void testAfterStatementTrigger() throws SQLException {
        String triggerBody = "BEGIN\n  UPDATE stats SET count = count + 1;\nEND;";

        ResultSet rs = createMockResultSet(
                "HR", "UPDATE_STATS_TRG", "EMPLOYEES",
                "AFTER STATEMENT", "UPDATE", "ENABLED",
                triggerBody, null, null);

        Connection conn = createMockConnection(rs);
        List<TriggerMetadata> triggers = OracleTriggerExtractor.extractAllTriggers(conn, List.of("HR"));

        assertEquals(1, triggers.size());
        TriggerMetadata trigger = triggers.get(0);
        assertEquals("AFTER", trigger.getTriggerType());
        assertEquals("STATEMENT", trigger.getTriggerLevel());
        assertEquals("UPDATE", trigger.getTriggerEvent());
    }

    @Test
    void testInsteadOfTrigger() throws SQLException {
        String triggerBody = "BEGIN\n  INSERT INTO base_table VALUES (:NEW.id);\nEND;";

        ResultSet rs = createMockResultSet(
                "HR", "VIEW_INSERT_TRG", "EMP_VIEW",
                "INSTEAD OF", "INSERT", "ENABLED",
                triggerBody, null, null);

        Connection conn = createMockConnection(rs);
        List<TriggerMetadata> triggers = OracleTriggerExtractor.extractAllTriggers(conn, List.of("HR"));

        assertEquals(1, triggers.size());
        TriggerMetadata trigger = triggers.get(0);
        assertEquals("INSTEAD OF", trigger.getTriggerType());
        assertEquals("ROW", trigger.getTriggerLevel()); // INSTEAD OF is always row-level
        assertEquals("INSERT", trigger.getTriggerEvent());
    }

    @Test
    void testCompoundTrigger() throws SQLException {
        String triggerBody = "COMPOUND TRIGGER\n  BEFORE STATEMENT IS BEGIN NULL; END;\nEND;";

        ResultSet rs = createMockResultSet(
                "HR", "COMPOUND_TRG", "EMPLOYEES",
                "COMPOUND", "INSERT", "ENABLED",
                triggerBody, null, null);

        Connection conn = createMockConnection(rs);
        List<TriggerMetadata> triggers = OracleTriggerExtractor.extractAllTriggers(conn, List.of("HR"));

        assertEquals(1, triggers.size());
        TriggerMetadata trigger = triggers.get(0);
        assertEquals("COMPOUND", trigger.getTriggerType());
        // Compound triggers need special handling - log warning is expected
    }

    @Test
    void testTriggerWithMultipleEvents() throws SQLException {
        String triggerBody = "BEGIN\n  NULL;\nEND;";

        ResultSet rs = createMockResultSet(
                "HR", "MULTI_EVENT_TRG", "EMPLOYEES",
                "BEFORE EACH ROW", "INSERT OR UPDATE", "ENABLED",
                triggerBody, null, null);

        Connection conn = createMockConnection(rs);
        List<TriggerMetadata> triggers = OracleTriggerExtractor.extractAllTriggers(conn, List.of("HR"));

        assertEquals(1, triggers.size());
        TriggerMetadata trigger = triggers.get(0);
        assertEquals("INSERT OR UPDATE", trigger.getTriggerEvent());
    }

    @Test
    void testTriggerWithAllEvents() throws SQLException {
        String triggerBody = "BEGIN\n  NULL;\nEND;";

        ResultSet rs = createMockResultSet(
                "HR", "ALL_EVENT_TRG", "EMPLOYEES",
                "AFTER EACH ROW", "INSERT OR UPDATE OR DELETE", "ENABLED",
                triggerBody, null, null);

        Connection conn = createMockConnection(rs);
        List<TriggerMetadata> triggers = OracleTriggerExtractor.extractAllTriggers(conn, List.of("HR"));

        assertEquals(1, triggers.size());
        TriggerMetadata trigger = triggers.get(0);
        assertEquals("INSERT OR UPDATE OR DELETE", trigger.getTriggerEvent());
    }

    @Test
    void testTriggerWithWhenClause_Simple() throws SQLException {
        String triggerBody = "BEGIN\n  NULL;\nEND;";
        String whenClause = "NEW.salary > 1000";

        ResultSet rs = createMockResultSet(
                "HR", "SALARY_CHECK_TRG", "EMPLOYEES",
                "BEFORE EACH ROW", "UPDATE", "ENABLED",
                triggerBody, whenClause, null);

        Connection conn = createMockConnection(rs);
        List<TriggerMetadata> triggers = OracleTriggerExtractor.extractAllTriggers(conn, List.of("HR"));

        assertEquals(1, triggers.size());
        TriggerMetadata trigger = triggers.get(0);
        assertEquals("NEW.salary > 1000", trigger.getWhenClause());
    }

    @Test
    void testTriggerWithWhenClause_WithParentheses() throws SQLException {
        String triggerBody = "BEGIN\n  NULL;\nEND;";
        String whenClause = "(NEW.salary > OLD.salary)";

        ResultSet rs = createMockResultSet(
                "HR", "SALARY_INCREASE_TRG", "EMPLOYEES",
                "BEFORE EACH ROW", "UPDATE", "ENABLED",
                triggerBody, whenClause, null);

        Connection conn = createMockConnection(rs);
        List<TriggerMetadata> triggers = OracleTriggerExtractor.extractAllTriggers(conn, List.of("HR"));

        assertEquals(1, triggers.size());
        TriggerMetadata trigger = triggers.get(0);
        // Should remove outer parentheses
        assertEquals("NEW.salary > OLD.salary", trigger.getWhenClause());
    }

    @Test
    void testTriggerWithWhenClause_WithWhenKeyword() throws SQLException {
        String triggerBody = "BEGIN\n  NULL;\nEND;";
        String whenClause = "WHEN (NEW.status = 'ACTIVE')";

        ResultSet rs = createMockResultSet(
                "HR", "STATUS_TRG", "EMPLOYEES",
                "BEFORE EACH ROW", "INSERT", "ENABLED",
                triggerBody, whenClause, null);

        Connection conn = createMockConnection(rs);
        List<TriggerMetadata> triggers = OracleTriggerExtractor.extractAllTriggers(conn, List.of("HR"));

        assertEquals(1, triggers.size());
        TriggerMetadata trigger = triggers.get(0);
        // Should remove "WHEN (" prefix and trailing ")"
        assertEquals("NEW.status = 'ACTIVE'", trigger.getWhenClause());
    }

    @Test
    void testTriggerBodyWithDeclare() throws SQLException {
        String triggerBody = "DECLARE\n  v_count NUMBER;\nBEGIN\n  SELECT COUNT(*) INTO v_count FROM t;\nEND;";

        ResultSet rs = createMockResultSet(
                "HR", "COMPLEX_TRG", "EMPLOYEES",
                "BEFORE EACH ROW", "INSERT", "ENABLED",
                triggerBody, null, null);

        Connection conn = createMockConnection(rs);
        List<TriggerMetadata> triggers = OracleTriggerExtractor.extractAllTriggers(conn, List.of("HR"));

        assertEquals(1, triggers.size());
        TriggerMetadata trigger = triggers.get(0);
        assertTrue(trigger.getTriggerBody().contains("DECLARE"));
        assertTrue(trigger.getTriggerBody().contains("BEGIN"));
    }

    @Test
    void testTriggerBodyExtractedFromDescription() throws SQLException {
        // Trigger body is null/empty, but description contains full DDL
        String description = """
            CREATE OR REPLACE TRIGGER hr.audit_trg
              BEFORE INSERT ON hr.employees
              FOR EACH ROW
            BEGIN
              INSERT INTO audit_log VALUES (:NEW.id, SYSDATE);
            END;
            """;

        ResultSet rs = createMockResultSet(
                "HR", "AUDIT_TRG", "EMPLOYEES",
                "BEFORE EACH ROW", "INSERT", "ENABLED",
                null, null, description); // trigger_body is null

        Connection conn = createMockConnection(rs);
        List<TriggerMetadata> triggers = OracleTriggerExtractor.extractAllTriggers(conn, List.of("HR"));

        assertEquals(1, triggers.size());
        TriggerMetadata trigger = triggers.get(0);
        assertNotNull(trigger.getTriggerBody());
        assertTrue(trigger.getTriggerBody().contains("BEGIN"));
        assertTrue(trigger.getTriggerBody().contains("INSERT INTO audit_log"));
    }

    @Test
    void testDisabledTrigger() throws SQLException {
        String triggerBody = "BEGIN\n  NULL;\nEND;";

        ResultSet rs = createMockResultSet(
                "HR", "DISABLED_TRG", "EMPLOYEES",
                "BEFORE EACH ROW", "INSERT", "DISABLED",
                triggerBody, null, null);

        Connection conn = createMockConnection(rs);
        List<TriggerMetadata> triggers = OracleTriggerExtractor.extractAllTriggers(conn, List.of("HR"));

        assertEquals(1, triggers.size());
        TriggerMetadata trigger = triggers.get(0);
        assertEquals("DISABLED", trigger.getStatus());
    }

    @Test
    void testMultipleTriggersInSchema() throws SQLException {
        // Create a mock result set that returns multiple rows
        ResultSet rs = mock(ResultSet.class);

        // Setup for two triggers
        when(rs.next()).thenReturn(true, true, false);

        // First trigger
        when(rs.getString("owner")).thenReturn("HR", "HR");
        when(rs.getString("trigger_name")).thenReturn("TRG1", "TRG2");
        when(rs.getString("table_owner")).thenReturn("HR", "HR");
        when(rs.getString("table_name")).thenReturn("EMPLOYEES", "DEPARTMENTS");
        when(rs.getString("trigger_type")).thenReturn("BEFORE EACH ROW", "AFTER STATEMENT");
        when(rs.getString("triggering_event")).thenReturn("INSERT", "UPDATE");
        when(rs.getString("status")).thenReturn("ENABLED", "ENABLED");
        when(rs.getString("trigger_body")).thenReturn("BEGIN NULL; END;", "BEGIN NULL; END;");
        when(rs.getString("when_clause")).thenReturn(null, null);
        when(rs.getString("description")).thenReturn(null, null);

        Connection conn = createMockConnection(rs);
        List<TriggerMetadata> triggers = OracleTriggerExtractor.extractAllTriggers(conn, List.of("HR"));

        assertEquals(2, triggers.size());
        assertEquals("trg1", triggers.get(0).getTriggerName());
        assertEquals("trg2", triggers.get(1).getTriggerName());
    }

    @Test
    void testExcludedSchema() throws SQLException {
        // SYS schema should be excluded by UserExcluder
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false); // No results expected

        Connection conn = createMockConnection(rs);
        List<TriggerMetadata> triggers = OracleTriggerExtractor.extractAllTriggers(conn, List.of("SYS"));

        // Should return empty list (SYS is excluded)
        assertEquals(0, triggers.size());
    }

    @Test
    void testEmptyResultSet() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false); // No rows

        Connection conn = createMockConnection(rs);
        List<TriggerMetadata> triggers = OracleTriggerExtractor.extractAllTriggers(conn, List.of("HR"));

        assertEquals(0, triggers.size());
    }
}
