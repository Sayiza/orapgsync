package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@code table_element} grammar rule.
 *
 * <p>Despite the name, {@code table_element} is the alternative the grammar names <em>first</em> for
 * the argument of {@code TO_CHAR}, {@code TO_DATE} and {@code COALESCE}:
 *
 * <pre>
 * TO_CHAR  '(' (table_element | standard_function | expression) ... ')'
 * TO_DATE  '(' (table_element | standard_function | expression) ... ')'
 * COALESCE '(' table_element (',' (numeric | quoted_string))? ')'
 * </pre>
 *
 * <p>So a bare identifier written as an argument to those three bypasses {@code VisitGeneralElement}
 * entirely — where every other identifier in an expression is resolved. Everything this class pins
 * was broken by that split.
 */
public class TableElementTransformationTest {

    private AntlrParser parser;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        emptyIndices = MetadataIndexBuilder.buildEmpty();
    }

    private String transform(String oracleSql) {
        TransformationContext context =
            new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed: " + oracleSql);

        return new PostgresCodeBuilder(context).visit(parseResult.getTree())
            .trim().replaceAll("\\s+", " ");
    }

    // ==================== Pseudo-columns ====================

    /**
     * The reported bug: SYSDATE as a bare argument was emitted as a column reference, so PostgreSQL
     * failed with "column sysdate does not exist".
     */
    @Test
    void sysdateAsToCharArgumentBecomesCurrentTimestamp() {
        String result = transform("SELECT TO_CHAR(SYSDATE) FROM dual");

        assertTrue(result.contains("CURRENT_TIMESTAMP"),
            "SYSDATE must reach the pseudo-column rewrite, was: " + result);
        assertFalse(result.toLowerCase().contains("sysdate"),
            "SYSDATE must not survive as an identifier, was: " + result);
    }

    /**
     * Independent of the format model — the two-argument form was broken the same way, and had been
     * since long before the single-argument form was addressed.
     */
    @Test
    void sysdateWithFormatBecomesCurrentTimestamp() {
        String result = transform("SELECT TO_CHAR(SYSDATE, 'YYYY') FROM dual");

        assertEquals("SELECT TO_CHAR( CURRENT_TIMESTAMP , 'YYYY' )", result);
    }

    @Test
    void sysdateInToDateBecomesCurrentTimestamp() {
        String result = transform("SELECT TO_DATE(SYSDATE, 'YYYY') FROM dual");

        assertTrue(result.contains("CURRENT_TIMESTAMP"),
            "SYSDATE must be rewritten in TO_DATE too, was: " + result);
        assertFalse(result.toLowerCase().contains("sysdate"), result);
    }

    @Test
    void sysdateInCoalesceBecomesCurrentTimestamp() {
        String result = transform("SELECT COALESCE(SYSDATE, 'x') FROM dual");

        assertTrue(result.contains("CURRENT_TIMESTAMP"),
            "COALESCE is the third table_element site, was: " + result);
        assertFalse(result.toLowerCase().contains("sysdate"), result);
    }

    @Test
    void systimestampAsArgumentBecomesCurrentTimestamp() {
        String result = transform("SELECT TO_CHAR(SYSTIMESTAMP, 'YYYY') FROM dual");

        assertEquals("SELECT TO_CHAR( CURRENT_TIMESTAMP , 'YYYY' )", result);
    }

    /**
     * Pins the asymmetry that kept the bug alive: arithmetic pushes the name into the
     * {@code expression} branch, which always resolved it — so the construct looked supported.
     */
    @Test
    void sysdateArithmeticAlwaysWorkedAndStillDoes() {
        String result = transform("SELECT TO_CHAR(SYSDATE + 1, 'YYYY') FROM dual");

        assertTrue(result.contains("CURRENT_TIMESTAMP"), result);
    }

    // ==================== Quoted identifiers ====================

    /**
     * {@code table_element} read identifiers with {@code getText()}, so a delimited name reached
     * PostgreSQL quoted and case-sensitive and missed the migrated lower-case column.
     */
    @Test
    void quotedIdentifierIsNormalized() {
        String result = transform("SELECT TO_CHAR(\"HIRE_DATE\", 'YYYY') FROM employees");

        assertTrue(result.contains("TO_CHAR( hire_date , 'YYYY' )"),
            "Delimited identifier must be normalized, was: " + result);
        assertFalse(result.contains("\"HIRE_DATE\""),
            "Quoted upper-case name must not survive, was: " + result);
    }

    @Test
    void unquotedIdentifierPassesThroughUnchanged() {
        // emit() deliberately leaves unquoted identifiers byte-identical - that is what keeps
        // bare USER and LEVEL working as PostgreSQL keywords.
        String result = transform("SELECT TO_CHAR(hire_date, 'YYYY') FROM employees");

        assertTrue(result.contains("TO_CHAR( hire_date , 'YYYY' )"), result);
    }

    @Test
    void qualifiedColumnReferenceIsPreserved() {
        String result = transform("SELECT TO_CHAR(e.hire_date, 'YYYY') FROM employees e");

        assertTrue(result.contains("TO_CHAR( e.hire_date , 'YYYY' )"),
            "An aliased column must stay a qualified column reference, was: " + result);
    }

    // ==================== COALESCE default value ====================

    /**
     * Silent corruption, and the worst defect found in this area: {@code b.visit()} on the
     * {@code quoted_string} rule returned null (no visitor override, so ANTLR's default aggregation
     * yielded nothing), and the default was emitted as the literal {@code null}. The view compiled,
     * ran, and returned NULL for every row Oracle would have defaulted.
     */
    @Test
    void coalesceStringDefaultSurvives() {
        String result = transform("SELECT COALESCE(ename, 'UNKNOWN') FROM employees");

        assertEquals("SELECT COALESCE( ename , 'UNKNOWN' ) FROM hr.employees", result);
    }

    @Test
    void coalesceNumericDefaultSurvives() {
        String result = transform("SELECT COALESCE(comm, 0) FROM employees");

        assertEquals("SELECT COALESCE( comm , 0 ) FROM hr.employees", result);
    }

    @Test
    void coalesceWithoutDefaultIsUnchanged() {
        String result = transform("SELECT COALESCE(comm) FROM employees");

        assertEquals("SELECT COALESCE( comm ) FROM hr.employees", result);
    }
}
