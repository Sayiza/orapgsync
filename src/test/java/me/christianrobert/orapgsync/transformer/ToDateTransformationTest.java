package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import me.christianrobert.orapgsync.transformer.type.TypeEvaluator;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TO_DATE → TO_TIMESTAMP transformation.
 *
 * <p>Oracle uses TO_DATE for parsing date/time strings with format codes.
 * PostgreSQL uses TO_TIMESTAMP for the same purpose with similar (but not identical) format codes.
 *
 * <h3>Oracle vs PostgreSQL:</h3>
 * <pre>
 * Oracle:     TO_DATE(string, 'format')
 * PostgreSQL: TO_TIMESTAMP(string, 'format')
 *
 * Oracle:     TO_DATE(string, 'format', 'nls_params')
 * PostgreSQL: TO_TIMESTAMP(string, 'format')  -- NLS params dropped
 * </pre>
 */
class ToDateTransformationTest {

    private AntlrParser parser;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        emptyIndices = MetadataIndexBuilder.buildEmpty();
    }

    // ==================== BASIC TO_DATE QUERIES ====================

    @Test
    void toDateWithStringLiteral() {
        // Given: TO_DATE with string literal and format
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT TO_DATE('2025-01-15', 'YYYY-MM-DD') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: TO_DATE → TO_TIMESTAMP
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TO_TIMESTAMP( '2025-01-15' , 'YYYY-MM-DD' )"),
                "Should transform TO_DATE to TO_TIMESTAMP");
    }

    @Test
    void toDateWithoutFormat() {
        // Given: TO_DATE with only string (uses default format)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT TO_DATE('15-JAN-25') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: TO_DATE → TO_TIMESTAMP (no format)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TO_TIMESTAMP( '15-JAN-25' )"),
                "Should transform TO_DATE to TO_TIMESTAMP without format");
    }

    // ==================== COLUMN REFERENCES ====================

    @Test
    void toDateWithColumnReference() {
        // Given: TO_DATE with column reference
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT TO_DATE(hire_date_str, 'YYYY-MM-DD') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Column reference preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TO_TIMESTAMP( hire_date_str , 'YYYY-MM-DD' )"),
                "Should preserve column reference in TO_TIMESTAMP");
    }

    @Test
    void toDateWithQualifiedColumn() {
        // Given: TO_DATE with qualified column reference
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT TO_DATE(e.hire_date_str, 'YYYY-MM-DD') FROM employees e";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Qualified column reference preserved (no spaces around dot in expression context)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TO_TIMESTAMP( e.hire_date_str , 'YYYY-MM-DD' )"),
                "Should preserve qualified column reference in TO_TIMESTAMP");
    }

    // ==================== FORMAT CODE TRANSFORMATIONS ====================

    @Test
    void toDateWithOracleRRFormat() {
        // Given: TO_DATE with RR format (2-digit year)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT TO_DATE('25-01-15', 'RR-MM-DD') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: RR → YY transformation
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TO_TIMESTAMP( '25-01-15' , 'YY-MM-DD' )"),
                "Should transform RR to YY");
    }

    @Test
    void toDateWithOracleRRRRFormat() {
        // Given: TO_DATE with RRRR format (4-digit year)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT TO_DATE('2025-01-15', 'RRRR-MM-DD') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: RRRR → YYYY transformation
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TO_TIMESTAMP( '2025-01-15' , 'YYYY-MM-DD' )"),
                "Should transform RRRR to YYYY");
    }

    @Test
    void toDateWithStandardFormat() {
        // Given: TO_DATE with standard format (YYYY-MM-DD HH24:MI:SS)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT TO_DATE('2025-01-15 14:30:45', 'YYYY-MM-DD HH24:MI:SS') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Format unchanged (standard codes work in both)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TO_TIMESTAMP( '2025-01-15 14:30:45' , 'YYYY-MM-DD HH24:MI:SS' )"),
                "Should preserve standard format codes");
    }

    // ==================== NLS PARAMETERS ====================

    @Test
    void toDateWithNlsParameters() {
        // Given: TO_DATE with NLS parameters (third argument)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT TO_DATE('15-JAN-2025', 'DD-MON-YYYY', 'NLS_DATE_LANGUAGE=AMERICAN') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: NLS parameter dropped (PostgreSQL doesn't support it)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TO_TIMESTAMP( '15-JAN-2025' , 'DD-MON-YYYY' )"),
                "Should drop NLS parameters");
        assertFalse(normalized.contains("NLS_DATE_LANGUAGE"),
                "Should not include NLS parameters in output");
    }

    // ==================== NESTED FUNCTIONS ====================

    @Test
    void toDateWithSubstr() {
        // Given: TO_DATE with SUBSTR function
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT TO_DATE(SUBSTR(date_str, 1, 10), 'YYYY-MM-DD') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both functions transformed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TO_TIMESTAMP( SUBSTRING( date_str FROM 1 FOR 10 ) , 'YYYY-MM-DD' )"),
                "Should transform both TO_DATE and SUBSTR");
    }

    // ==================== WHERE CLAUSE ====================

    @Test
    void toDateInWhereClause() {
        // Given: TO_DATE in WHERE clause comparison
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT empno FROM employees WHERE hire_date > TO_DATE('2020-01-01', 'YYYY-MM-DD')";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: TO_DATE in WHERE transformed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("WHERE hire_date > TO_TIMESTAMP( '2020-01-01' , 'YYYY-MM-DD' )"),
                "Should transform TO_DATE in WHERE clause");
    }

    // ==================== COLUMN ALIASES ====================

    @Test
    void toDateWithColumnAlias() {
        // Given: TO_DATE with column alias
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT TO_DATE('2025-01-15', 'YYYY-MM-DD') AS parsed_date FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Column alias preserved
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TO_TIMESTAMP( '2025-01-15' , 'YYYY-MM-DD' ) AS parsed_date"),
                "Should preserve column alias");
    }

    // ==================== MULTIPLE TO_DATE CALLS ====================

    @Test
    void multipleToDateInSelectList() {
        // Given: Multiple TO_DATE calls in SELECT list
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT TO_DATE(start_date, 'YYYY-MM-DD'), TO_DATE(end_date, 'YYYY-MM-DD') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Both TO_DATE calls transformed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TO_TIMESTAMP( start_date , 'YYYY-MM-DD' )"),
                "Should transform first TO_DATE");
        assertTrue(normalized.contains("TO_TIMESTAMP( end_date , 'YYYY-MM-DD' )"),
                "Should transform second TO_DATE");
    }

    // ==================== CASE VARIATIONS ====================

    @Test
    void toDateLowercase() {
        // Given: lowercase to_date
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT to_date('2025-01-15', 'YYYY-MM-DD') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Transformed to TO_TIMESTAMP (uppercase)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TO_TIMESTAMP( '2025-01-15' , 'YYYY-MM-DD' )"),
                "Should transform lowercase to_date");
    }

    @Test
    void toDateMixedCase() {
        // Given: mixed case To_Date
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT To_Date('2025-01-15', 'YYYY-MM-DD') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Transformed to TO_TIMESTAMP (uppercase)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TO_TIMESTAMP( '2025-01-15' , 'YYYY-MM-DD' )"),
                "Should transform mixed case To_Date");
    }

    // ==================== FROM DUAL ====================

    @Test
    void toDateFromDual() {
        // Given: TO_DATE in scalar query (FROM DUAL)
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT TO_DATE('2025-01-15', 'YYYY-MM-DD') FROM DUAL";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: TO_DATE transformed and FROM DUAL removed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TO_TIMESTAMP( '2025-01-15' , 'YYYY-MM-DD' )"),
                "Should transform TO_DATE");
        assertFalse(normalized.contains("DUAL"),
                "Should remove FROM DUAL");
    }

    // ==================== COMPLEX FORMATS ====================

    @Test
    void toDateWithComplexFormat() {
        // Given: TO_DATE with complex format string
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT TO_DATE('2025-01-15 14:30:45.123', 'YYYY-MM-DD HH24:MI:SS.FF3') FROM employees";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: Complex format preserved (FF3 works in both Oracle and PostgreSQL)
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("TO_TIMESTAMP( '2025-01-15 14:30:45.123' , 'YYYY-MM-DD HH24:MI:SS.FF3' )"),
                "Should preserve complex format");
    }

    // ==================== ORDER BY ====================

    @Test
    void toDateInOrderBy() {
        // Given: TO_DATE in ORDER BY clause
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT empno FROM employees ORDER BY TO_DATE(hire_date_str, 'YYYY-MM-DD') DESC";

        // When: Parse and transform
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        // Then: TO_DATE in ORDER BY transformed
        String normalized = postgresSql.trim().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("ORDER BY TO_TIMESTAMP( hire_date_str , 'YYYY-MM-DD' ) DESC"),
                "Should transform TO_DATE in ORDER BY");
    }
}
