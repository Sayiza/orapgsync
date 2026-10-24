package me.christianrobert.orapgsync.database.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code ALL_*} views hide every object not granted to the connected user, so a read-only account
 * sees a fraction of the PL/SQL. These tests pin the rewrite to {@code DBA_*}, the all-or-nothing
 * probe, and that main code queries no dictionary view the probe does not cover.
 */
class OracleDataDictionaryTest {

    @Test
    void rewritesLowerAndUpperCaseReferences() {
        assertEquals("SELECT text FROM dba_source WHERE owner = ?",
                OracleDataDictionary.toDba("SELECT text FROM all_source WHERE owner = ?"));
        assertEquals("SELECT * FROM DBA_PROCEDURES p JOIN DBA_ARGUMENTS a ON 1 = 1",
                OracleDataDictionary.toDba("SELECT * FROM ALL_PROCEDURES p JOIN ALL_ARGUMENTS a ON 1 = 1"));
    }

    @Test
    void rewritesInsideTextBlocksAndSubqueries() {
        String sql = """
                SELECT m.method_name,
                       (SELECT COUNT(*) FROM all_method_results r WHERE r.owner = m.owner)
                FROM all_type_methods m
                """;
        String rewritten = OracleDataDictionary.toDba(sql);
        assertTrue(rewritten.contains("FROM dba_method_results r"), rewritten);
        assertTrue(rewritten.contains("FROM dba_type_methods m"), rewritten);
    }

    @Test
    @DisplayName("leaves quoted, qualified, literal and longer names alone")
    void leavesNonDictionaryNamesAlone() {
        for (String sql : List.of(
                "SELECT * FROM \"APP\".\"ALL_SOURCE\"",
                "SELECT * FROM app.all_source",
                "SELECT 'ALL_SOURCE' FROM dual",
                "SELECT * FROM all_sources",
                "SELECT * FROM all_tab_privs",
                "SELECT all_source_id FROM t")) {
            assertEquals(sql, OracleDataDictionary.toDba(sql));
        }
        assertNull(OracleDataDictionary.toDba(null));
    }

    @Test
    void probeChoosesDbaWhenEveryViewIsReadable() throws SQLException {
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.executeQuery(anyString())).thenReturn(mock(ResultSet.class));

        OracleDataDictionary.Choice choice = OracleDataDictionary.probe(conn);

        assertEquals(OracleDataDictionary.Family.DBA, choice.family());
        for (String view : OracleDataDictionary.VIEWS) {
            verify(stmt).executeQuery("SELECT 1 FROM dba_" + view + " WHERE 1 = 0");
        }
    }

    @Test
    @DisplayName("one unreadable DBA view keeps every view on ALL — families are never mixed")
    void probeFallsBackToAllWhenAnyViewIsUnreadable() throws SQLException {
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(conn.createStatement()).thenReturn(stmt);
        when(stmt.executeQuery(anyString())).thenReturn(mock(ResultSet.class));
        when(stmt.executeQuery(contains("dba_arguments")))
                .thenThrow(new SQLException("ORA-00942: table or view does not exist\nmore detail"));

        OracleDataDictionary.Choice choice = OracleDataDictionary.probe(conn);

        assertEquals(OracleDataDictionary.Family.ALL, choice.family());
        assertEquals("cannot read DBA_ARGUMENTS: ORA-00942: table or view does not exist", choice.reason());
    }

    @Test
    void wrappedConnectionRewritesPreparedAndPlainStatements() throws SQLException {
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(conn.prepareStatement(anyString())).thenReturn(mock(PreparedStatement.class));
        when(conn.createStatement()).thenReturn(stmt);

        Connection wrapped = OracleDataDictionary.wrap(conn, OracleDataDictionary.Family.DBA);
        wrapped.prepareStatement("SELECT text FROM all_source");
        wrapped.createStatement().executeQuery("SELECT username FROM all_users");

        verify(conn).prepareStatement("SELECT text FROM dba_source");
        verify(stmt).executeQuery("SELECT username FROM dba_users");
    }

    @Test
    void wrappedConnectionPropagatesTheDriversException() throws SQLException {
        Connection conn = mock(Connection.class);
        SQLException failure = new SQLException("ORA-00904");
        when(conn.prepareStatement(anyString())).thenThrow(failure);

        Connection wrapped = OracleDataDictionary.wrap(conn, OracleDataDictionary.Family.DBA);
        try {
            wrapped.prepareStatement("SELECT x FROM all_source");
            fail("expected SQLException");
        } catch (SQLException e) {
            assertSame(failure, e);
        }
    }

    @Test
    void allFamilyReturnsTheConnectionUnchanged() {
        Connection conn = mock(Connection.class);
        assertSame(conn, OracleDataDictionary.wrap(conn, OracleDataDictionary.Family.ALL));
    }

    /**
     * A query naming a dictionary view outside {@link OracleDataDictionary#VIEWS} is never probed
     * and never rewritten, so it would silently stay on ALL_* while its neighbours read DBA_*.
     */
    @Test
    void mainCodeQueriesOnlyDictionaryViewsTheProbeCovers() throws IOException {
        Pattern use = Pattern.compile("(?i)\\b(?:from|join)\\s+all_(\\w+)");
        List<String> uncovered = new ArrayList<>();
        int uses = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                Matcher m = use.matcher(source);
                while (m.find()) {
                    uses++;
                    if (!OracleDataDictionary.isKnownView(m.group(1))) {
                        uncovered.add(file.getFileName() + ": ALL_" + m.group(1).toUpperCase());
                    }
                }
            }
        }
        assertTrue(uses > 20, "scan found only " + uses + " dictionary queries — is the path right?");
        assertTrue(uncovered.isEmpty(), "add to OracleDataDictionary.VIEWS (and check DBA_* has the "
                + "same columns): " + uncovered);
    }
}
