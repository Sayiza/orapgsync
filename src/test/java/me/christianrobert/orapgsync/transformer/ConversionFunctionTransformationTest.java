package me.christianrobert.orapgsync.transformer;

import me.christianrobert.orapgsync.transformer.builder.PostgresCodeBuilder;
import me.christianrobert.orapgsync.transformer.context.MetadataIndexBuilder;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationException;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.parser.AntlrParser;
import me.christianrobert.orapgsync.transformer.parser.ParseResult;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the TO_NUMBER / TO_TIMESTAMP / TO_TIMESTAMP_TZ conversion functions.
 *
 * <p>These live in the {@code other_function} grammar rule (unlike TO_CHAR and TO_DATE, which
 * are in {@code string_function}), and the key asymmetry they cover is that Oracle accepts a
 * single argument where PostgreSQL has no such function overload — that form must become a cast.
 */
public class ConversionFunctionTransformationTest {

    private AntlrParser parser;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        emptyIndices = MetadataIndexBuilder.buildEmpty();
    }

    private String transform(String oracleSql) {
        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed for: " + oracleSql);

        TransformationContext context = new TransformationContext(
            "HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        return new PostgresCodeBuilder(context).visit(parseResult.getTree())
            .trim().replaceAll("\\s+", " ");
    }

    // ==================== TO_NUMBER ====================

    @Test
    void toNumberWithSingleArgumentBecomesCast() {
        // PostgreSQL has no single-argument to_number, so the cast is the only correct mapping
        String postgresSql = transform("SELECT TO_NUMBER(order_no) FROM orders");

        assertEquals("SELECT ( order_no )::numeric FROM hr.orders", postgresSql);
    }

    @Test
    void toNumberWithStringLiteral() {
        String postgresSql = transform("SELECT TO_NUMBER('123.45') FROM dual");

        assertEquals("SELECT ( '123.45' )::numeric", postgresSql);
    }

    @Test
    void toNumberInWhereClause() {
        String postgresSql = transform("SELECT * FROM orders WHERE TO_NUMBER(order_no) > 5");

        assertEquals("SELECT * FROM hr.orders WHERE ( order_no )::numeric > 5", postgresSql);
    }

    @Test
    void toNumberWithNestedFunctionArgument() {
        // The argument is a full expression, not just a column reference
        String postgresSql = transform("SELECT TO_NUMBER(SUBSTR(code, 1, 3)) + 1 FROM orders");

        assertEquals("SELECT ( SUBSTRING( code FROM 1 FOR 3 ) )::numeric + 1 FROM hr.orders", postgresSql);
    }

    @Test
    void toNumberWithFormatUsesTwoArgumentFormAndCastsValueToText() {
        // PostgreSQL's to_number(text, text) requires a text first argument
        String postgresSql = transform("SELECT TO_NUMBER(amount, '9999D99') FROM orders");

        assertEquals("SELECT TO_NUMBER( ( amount )::text , '9999D99' ) FROM hr.orders", postgresSql);
    }

    @Test
    void toNumberFormatModelIsPassedThroughUnchanged() {
        // Number format models are deliberately NOT translated - an incompatible one is left to
        // fail loudly at CREATE VIEW with PostgreSQL's own message
        String postgresSql = transform("SELECT TO_NUMBER(amount, 'FM999G999D00') FROM orders");

        assertTrue(postgresSql.contains("'FM999G999D00'"),
            "Number format model should be passed through unchanged, was: " + postgresSql);
    }

    @Test
    void toNumberDropsNlsParameters() {
        // Third argument (NLS params) has no PostgreSQL equivalent, as for TO_CHAR/TO_DATE
        String postgresSql = transform(
            "SELECT TO_NUMBER(amount, '9999', 'NLS_NUMERIC_CHARACTERS = '',.''') FROM orders");

        assertEquals("SELECT TO_NUMBER( ( amount )::text , '9999' ) FROM hr.orders", postgresSql);
    }

    @Test
    void toNumberWithOnConversionErrorIsRejected() {
        // Silently dropping the DEFAULT would turn "substitute" into "raise" - fail loudly instead
        TransformationException ex = assertThrows(TransformationException.class,
            () -> transform("SELECT TO_NUMBER(order_no DEFAULT 0 ON CONVERSION ERROR) FROM orders"));

        assertTrue(ex.getMessage().contains("ON CONVERSION ERROR"),
            "Error should name the unsupported clause, was: " + ex.getMessage());
    }

    // ==================== TO_TIMESTAMP ====================

    @Test
    void toTimestampWithSingleArgumentBecomesCast() {
        String postgresSql = transform("SELECT TO_TIMESTAMP(created) FROM orders");

        assertEquals("SELECT ( created )::timestamp FROM hr.orders", postgresSql);
    }

    @Test
    void toTimestampWithFormatTranslatesDateFormatModel() {
        // Unlike number formats, date format models go through the same translation as TO_DATE
        String postgresSql = transform("SELECT TO_TIMESTAMP(created, 'RRRR-MM-DD') FROM orders");

        assertEquals("SELECT TO_TIMESTAMP( ( created )::text , 'YYYY-MM-DD' ) FROM hr.orders", postgresSql);
    }

    @Test
    void toTimestampTzWithSingleArgumentBecomesTimestamptzCast() {
        String postgresSql = transform("SELECT TO_TIMESTAMP_TZ(created) FROM orders");

        assertEquals("SELECT ( created )::timestamptz FROM hr.orders", postgresSql);
    }

    @Test
    void toTimestampTzWithFormatMapsToToTimestamp() {
        // PostgreSQL has no TO_TIMESTAMP_TZ; its two-argument to_timestamp already returns timestamptz
        String postgresSql = transform("SELECT TO_TIMESTAMP_TZ(created, 'YYYY-MM-DD') FROM orders");

        assertEquals("SELECT TO_TIMESTAMP( ( created )::text , 'YYYY-MM-DD' ) FROM hr.orders", postgresSql);
    }
}
