package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TO_CHAR function transformation.
 *
 * <p>Oracle and PostgreSQL both have TO_CHAR, but with some differences:
 * <ul>
 *   <li>Function name: Same (TO_CHAR)</li>
 *   <li>Most format codes: Identical</li>
 *   <li>Some Oracle-specific codes need transformation (RR, RRRR, D, G)</li>
 *   <li>NLS parameters (3rd arg): Not supported in PostgreSQL, must be dropped</li>
 * </ul>
 *
 * <p>Common use cases:
 * <ul>
 *   <li>Date to string: TO_CHAR(date, 'YYYY-MM-DD')</li>
 *   <li>Number to string: TO_CHAR(number, '999,999.99')</li>
 *   <li>Timestamp formatting: TO_CHAR(timestamp, 'YYYY-MM-DD HH24:MI:SS')</li>
 * </ul>
 */
public class ToCharTransformationTest {

    private AntlrParser parser;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        emptyIndices = MetadataIndexBuilder.buildEmpty();
    }

    // ==================== Date Format Tests ====================

    @Test
    void toCharDateSimpleFormat() {
        // Given: TO_CHAR with simple date format
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT TO_CHAR(hire_date, 'YYYY-MM-DD') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Format should be preserved (identical in both databases)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TO_CHAR( hire_date , 'YYYY-MM-DD' )"),
            "TO_CHAR with standard date format should be preserved");
    }

    @Test
    void toCharDateWithTime() {
        // Given: TO_CHAR with date and time format
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT TO_CHAR(hire_date, 'YYYY-MM-DD HH24:MI:SS') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Format should be preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TO_CHAR( hire_date , 'YYYY-MM-DD HH24:MI:SS' )"),
            "TO_CHAR with date-time format should be preserved");
    }

    @Test
    void toCharDateWithRR() {
        // Given: TO_CHAR with RR (Oracle 2-digit year)
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT TO_CHAR(hire_date, 'RR-MM-DD') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: RR should be transformed to YY
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TO_CHAR( hire_date , 'YY-MM-DD' )"),
            "RR should be transformed to YY");
        assertFalse(normalized.contains("'RR-"),
            "Should not contain RR format code");
    }

    @Test
    void toCharDateWithRRRR() {
        // Given: TO_CHAR with RRRR (Oracle 4-digit year)
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT TO_CHAR(hire_date, 'RRRR-MM-DD') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: RRRR should be transformed to YYYY
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TO_CHAR( hire_date , 'YYYY-MM-DD' )"),
            "RRRR should be transformed to YYYY");
        assertFalse(normalized.contains("'RRRR-"),
            "Should not contain RRRR format code");
    }

    @Test
    void toCharDateWithMonthNames() {
        // Given: TO_CHAR with month names
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT TO_CHAR(hire_date, 'DD-MON-YYYY') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Format should be preserved (MON works in both)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TO_CHAR( hire_date , 'DD-MON-YYYY' )"),
            "TO_CHAR with MON should be preserved");
    }

    @Test
    void toCharDateWithDayNames() {
        // Given: TO_CHAR with day names
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT TO_CHAR(hire_date, 'DAY, DD MONTH YYYY') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Format should be preserved (DAY/MONTH work, but with case sensitivity differences)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("'DAY, DD MONTH YYYY'"),
            "TO_CHAR with DAY/MONTH should be preserved");
    }

    // ==================== Number Format Tests ====================

    @Test
    void toCharNumberSimple() {
        // Given: TO_CHAR with simple number format
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT TO_CHAR(salary, '999,999.99') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Format should be preserved (identical in both)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TO_CHAR( salary , '999,999.99' )"),
            "TO_CHAR with number format should be preserved");
    }

    @Test
    void toCharNumberWithFM() {
        // Given: TO_CHAR with FM (fill mode - suppress padding)
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT TO_CHAR(amount, 'FM999,999.00') FROM transactions";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: FM should be preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TO_CHAR( amount , 'FM999,999.00' )"),
            "TO_CHAR with FM should be preserved");
    }

    @Test
    void toCharNumberWithDollarSign() {
        // Given: TO_CHAR with dollar sign
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT TO_CHAR(price, '$999,999.99') FROM products";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Dollar sign should be preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TO_CHAR( price , '$999,999.99' )"),
            "TO_CHAR with $ should be preserved");
    }

    @Test
    void toCharNumberWithD() {
        // Given: TO_CHAR with D (decimal point, locale-aware in Oracle)
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT TO_CHAR(amount, '999D99') FROM transactions";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: D should be transformed to . (literal decimal point)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TO_CHAR( amount , '999.99' )"),
            "D should be transformed to .");
        assertFalse(normalized.contains("999D99"),
            "Should not contain D format code");
    }

    @Test
    void toCharNumberWithG() {
        // Given: TO_CHAR with G (grouping separator, locale-aware in Oracle)
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT TO_CHAR(amount, '999G999G999') FROM transactions";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: G should be transformed to , (literal comma)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TO_CHAR( amount , '999,999,999' )"),
            "G should be transformed to ,");
        assertFalse(normalized.contains("999G999"),
            "Should not contain G format code");
    }

    @Test
    void toCharNumberWithDAndG() {
        // Given: TO_CHAR with both D and G
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT TO_CHAR(amount, '999G999D99') FROM transactions";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both should be transformed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TO_CHAR( amount , '999,999.99' )"),
            "D and G should be transformed to . and ,");
    }

    // ==================== TO_CHAR Without Format ====================

    @Test
    void toCharWithoutFormat() {
        // Given: TO_CHAR without format string (converts to default string representation)
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT TO_CHAR(empno) FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: TO_CHAR without format should work
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TO_CHAR( empno )"),
            "TO_CHAR without format should be preserved");
    }

    // ==================== NLS Parameters (3rd argument) ====================

    @Test
    void toCharWithNLSParameter() {
        // Given: TO_CHAR with NLS parameter (3rd argument)
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT TO_CHAR(hire_date, 'DD-MON-YYYY', 'NLS_DATE_LANGUAGE=FRENCH') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: NLS parameter should be dropped (PostgreSQL doesn't support it)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TO_CHAR( hire_date , 'DD-MON-YYYY' )"),
            "TO_CHAR should have format but NLS param dropped");
        assertFalse(normalized.contains("NLS_DATE_LANGUAGE"),
            "NLS parameter should not appear in output");
    }

    // ==================== TO_CHAR in Different Contexts ====================

    @Test
    void toCharInWhereClause() {
        // Given: TO_CHAR in WHERE clause
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT empno FROM employees WHERE TO_CHAR(hire_date, 'YYYY') = '2020'";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: TO_CHAR should work in WHERE
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("WHERE TO_CHAR( hire_date , 'YYYY' ) = '2020'"),
            "TO_CHAR should work in WHERE clause");
    }

    @Test
    void toCharWithColumnAlias() {
        // Given: TO_CHAR with column alias
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT TO_CHAR(hire_date, 'YYYY-MM-DD') AS hire_date_str FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Alias should be preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("AS hire_date_str"),
            "Column alias should be preserved");
    }

    @Test
    void toCharInOrderBy() {
        // Given: TO_CHAR in ORDER BY clause
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT empno FROM employees ORDER BY TO_CHAR(hire_date, 'YYYY-MM')";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: TO_CHAR should work in ORDER BY
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("ORDER BY TO_CHAR( hire_date , 'YYYY-MM' )"),
            "TO_CHAR should work in ORDER BY");
    }

    @Test
    void toCharNested() {
        // Given: TO_CHAR with function call as argument
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT TO_CHAR(NVL(hire_date, SYSDATE), 'YYYY-MM-DD') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Nested functions should work (NVL → COALESCE, SYSDATE → CURRENT_TIMESTAMP)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TO_CHAR( COALESCE("),
            "Should have TO_CHAR with COALESCE");
        assertTrue(normalized.contains("CURRENT_TIMESTAMP"),
            "Should have CURRENT_TIMESTAMP");
    }

    // ==================== Edge Cases ====================

    @Test
    void toCharWithComplexDateFormat() {
        // Given: TO_CHAR with complex date format
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT TO_CHAR(hire_date, 'Day, DD Month YYYY HH24:MI:SS') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Complex format should be preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("'Day, DD Month YYYY HH24:MI:SS'"),
            "Complex date format should be preserved");
    }

    @Test
    void toCharDateWithDD() {
        // Given: TO_CHAR with DD (day of month) - should NOT be affected by D→. transformation
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT TO_CHAR(hire_date, 'DD-MM-YYYY') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: DD should NOT be transformed (it's a date format, not number format D)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("'DD-MM-YYYY'"),
            "DD should be preserved (not transformed to ..)");
        assertFalse(normalized.contains("'..-MM-YYYY'"),
            "DD should not become ..");
    }

    @Test
    void toCharMultipleInSelect() {
        // Given: Multiple TO_CHAR calls in SELECT
        TransformationContext context = new TransformationContext("HR", emptyIndices);

        String oracleSql = "SELECT TO_CHAR(hire_date, 'YYYY'), TO_CHAR(salary, '999,999') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both TO_CHAR calls should work
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        int toCharCount = normalized.split("TO_CHAR\\(", -1).length - 1;
        assertEquals(2, toCharCount, "Should have 2 TO_CHAR calls");
    }
}
