package me.christianrobert.orapgsync.integration;

import me.christianrobert.orapgsync.transformer.context.TransformationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Execution tests for Oracle (+) outer joins (ROADMAP P1.14).
 *
 * <p>Every defect this guards against produced valid PostgreSQL: a stripped (+) is an inner join,
 * a dropped condition a cross join, an inverted direction preserves the wrong side. All of them
 * compile and run; only the rows differ. So each test runs the transformed query over data with
 * <em>unmatched</em> rows and checks the rows Oracle would return — a string test cannot tell an
 * outer join from an inner one that happens to be spelled similarly.
 *
 * <p>The data: three orders. Order 1 matches everything. Order 2 has no scale, no notice and a type
 * without a category. Order 3 has a status the query filters out.
 */
public class PostgresOuterJoinValidationTest extends PostgresSqlValidationTestBase {

    @BeforeEach
    @Override
    void setup() throws SQLException {
        super.setup();

        executeBatch("""
            CREATE SCHEMA shop;
            CREATE TABLE shop.orders (id int, status_nr int, type_nr int, scale_nr int, notice_nr int);
            CREATE TABLE shop.statuses (nr int, code text);
            CREATE TABLE shop.order_types (nr int, category_nr int);
            CREATE TABLE shop.categories (nr int, label text, active text);
            CREATE TABLE shop.notices (nr int, txt text);
            CREATE TABLE shop.scales (nr int, name text);
            INSERT INTO shop.orders VALUES (1, 1, 1, 1, 1), (2, 1, 2, 99, 99), (3, 2, 1, 1, 1);
            INSERT INTO shop.statuses VALUES (1, 'OPEN'), (2, 'CLOSED');
            INSERT INTO shop.order_types VALUES (1, 1), (2, 99);
            INSERT INTO shop.categories VALUES (1, 'Books', 'N');
            INSERT INTO shop.notices VALUES (1, 'Notice one');
            INSERT INTO shop.scales VALUES (1, 'Scale one');
            """);
    }

    private List<Map<String, Object>> run(String oracleSql) throws SQLException {
        TransformationResult result = transformSql(oracleSql, "shop");
        assertTrue(result.isSuccess(), "Transformation should succeed: " + result.getErrorMessage());
        return executeQuery(result.getPostgresSql() + " ORDER BY 1");
    }

    /** The shape that was found losing all three outer joins: every condition in parentheses. */
    @Test
    void parenthesizedOuterJoinsKeepUnmatchedRows() throws SQLException {
        List<Map<String, Object>> rows = run("""
            SELECT a.id, h.label, i.txt, n.name
            FROM orders a, statuses c, order_types d, categories h, notices i, scales n
            WHERE 1 = 1
              and ( a.scale_nr = n.nr(+) )
              AND ( a.status_nr = c.nr )
              AND ( a.type_nr = d.nr )
              AND ( d.category_nr = h.nr (+) )
              AND ( a.notice_nr = i.nr (+) )
              and c.code in ('OPEN')
            """);

        assertEquals(2, rows.size(), "order 2 has no category, notice or scale and must still appear: " + rows);
        assertEquals("Books", rows.get(0).get("label"));
        assertEquals(2, rows.get(1).get("id"));
        assertEquals(null, rows.get(1).get("label"));
        assertEquals(null, rows.get(1).get("txt"));
        assertEquals(null, rows.get(1).get("name"));
    }

    /** a(+) listed first: a is the optional side, so every status survives, not every order. */
    @Test
    void optionalSideListedFirst() throws SQLException {
        List<Map<String, Object>> rows = run("""
            SELECT c.nr, a.id FROM orders a, statuses c WHERE a.status_nr(+) = c.nr AND a.id(+) = 3
            """);

        assertEquals(2, rows.size(), rows.toString());
        assertEquals(null, rows.get(0).get("id"), "status 1 has no order 3");
        assertEquals(3, rows.get(1).get("id"));
    }

    /** A constant on the optional side restricts the join, not the result. */
    @Test
    void constantOnOptionalSideRestrictsTheJoin() throws SQLException {
        List<Map<String, Object>> rows = run("""
            SELECT d.nr, h.label FROM order_types d, categories h
            WHERE d.category_nr = h.nr(+) AND h.active(+) = 'Y'
            """);

        assertEquals(2, rows.size(), "both types survive; no category is active: " + rows);
        assertEquals(null, rows.get(0).get("label"));
        assertEquals(null, rows.get(1).get("label"));
    }

    /** Joined to two tables: both conditions belong to the one ON clause. */
    @Test
    void optionalSideJoinedToTwoTables() throws SQLException {
        List<Map<String, Object>> rows = run("""
            SELECT a.id, i.txt FROM orders a, scales n, notices i
            WHERE a.scale_nr = n.nr AND a.notice_nr = i.nr(+) AND n.nr = i.nr(+)
            """);

        assertEquals(2, rows.size(), rows.toString());
        assertEquals("Notice one", rows.get(0).get("txt"));
        assertEquals("Notice one", rows.get(1).get("txt"));
    }

    /** The preserved table is not the first in FROM, and the first takes no part in the join. */
    @Test
    void preservedTableNotFirst() throws SQLException {
        List<Map<String, Object>> rows = run("""
            SELECT a.id, i.txt FROM statuses c, notices i, orders a
            WHERE a.status_nr = c.nr AND a.notice_nr = i.nr(+)
            """);

        assertEquals(3, rows.size(), rows.toString());
        assertEquals(null, rows.get(1).get("txt"), "order 2 has no notice");
    }

    /** Outer-joined through a function call. */
    @Test
    void outerJoinInsideFunction() throws SQLException {
        List<Map<String, Object>> rows = run("""
            SELECT a.id, n.name FROM orders a, scales n WHERE nvl(n.nr(+), 0) = a.scale_nr
            """);

        assertEquals(3, rows.size(), "no order may be dropped, and none duplicated: " + rows);
        assertEquals(null, rows.get(1).get("name"));
    }
}
