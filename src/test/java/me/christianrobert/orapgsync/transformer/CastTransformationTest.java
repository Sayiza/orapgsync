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
 * Tests for {@code CAST(expr AS type)}, which lives in the {@code other_function} grammar rule.
 *
 * <p>The cast target is mapped by an allowlist rather than by
 * {@link me.christianrobert.orapgsync.core.tools.TypeConverter}, because that class deliberately
 * discards the precision and length a cast is written to apply. Everything outside the allowlist
 * must throw rather than approximate.</p>
 */
public class CastTransformationTest {

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

    private TransformationException rejects(String oracleSql) {
        return assertThrows(TransformationException.class, () -> transform(oracleSql));
    }

    // ==================== DATE - the dangerous target ====================

    @Test
    void castToDateNeverProducesPostgresDate() {
        // PostgreSQL 'date' is day-only. Oracle DATE carries a time, and every migrated DATE
        // column is a timestamp - emitting ::date would silently move every value to midnight.
        String postgresSql = transform("SELECT CAST(created_ts AS DATE) FROM orders");

        assertFalse(postgresSql.matches(".*::\\s*date\\b.*"),
            "must not cast to PostgreSQL date: " + postgresSql);
        assertEquals(
            "SELECT DATE_TRUNC( 'second' , ( created_ts )::timestamp ) FROM hr.orders",
            postgresSql);
    }

    @Test
    void castToDateTruncatesRatherThanRoundsFractionalSeconds() {
        // Oracle truncates the fractional seconds. ::timestamp(0) would round, disagreeing on
        // every value at or above half a second, so it must not appear.
        String postgresSql = transform("SELECT CAST(created_ts AS DATE) FROM orders");

        assertTrue(postgresSql.contains("DATE_TRUNC( 'second' ,"), postgresSql);
        assertFalse(postgresSql.contains("timestamp(0)"), postgresSql);
    }

    @Test
    void castToDateInPlSqlContextUsesTheSameMapping() {
        String plsql = """
            FUNCTION day_of(p_ts IN TIMESTAMP) RETURN DATE IS
            BEGIN
              RETURN CAST(p_ts AS DATE);
            END;
            """;
        ParseResult parseResult = parser.parseFunctionBody(plsql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed");

        TransformationContext context = new TransformationContext(
            "HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String postgres = new PostgresCodeBuilder(context).visit(parseResult.getTree())
            .replaceAll("\\s+", " ");

        assertTrue(postgres.contains("DATE_TRUNC( 'second' , ( p_ts )::timestamp )"), postgres);
    }

    @Test
    void castToDateRejectsAPrecision() {
        rejects("SELECT CAST(created_ts AS DATE(3)) FROM orders");
    }

    // ==================== TIMESTAMP ====================

    @Test
    void castToTimestamp() {
        assertEquals("SELECT ( d )::timestamp FROM hr.orders",
            transform("SELECT CAST(d AS TIMESTAMP) FROM orders"));
    }

    @Test
    void castToTimestampKeepsPrecision() {
        // Both engines round the fractional seconds to p here, so the precision maps directly
        assertEquals("SELECT ( d )::timestamp(3) FROM hr.orders",
            transform("SELECT CAST(d AS TIMESTAMP(3)) FROM orders"));
    }

    @Test
    void castToTimestampWithTimeZone() {
        assertEquals("SELECT ( d )::timestamptz(6) FROM hr.orders",
            transform("SELECT CAST(d AS TIMESTAMP(6) WITH TIME ZONE) FROM orders"));
    }

    @Test
    void castToTimestampWithLocalTimeZone() {
        assertEquals("SELECT ( d )::timestamptz FROM hr.orders",
            transform("SELECT CAST(d AS TIMESTAMP WITH LOCAL TIME ZONE) FROM orders"));
    }

    // ==================== NUMBER - precision is the point ====================

    @Test
    void castToNumberKeepsPrecisionAndScale() {
        // TypeConverter would collapse this to bare numeric, dropping the rounding to 2 decimals
        // that is the entire reason the cast was written.
        assertEquals("SELECT ( amount )::numeric(10,2) FROM hr.orders",
            transform("SELECT CAST(amount AS NUMBER(10,2)) FROM orders"));
    }

    @Test
    void castToNumberWithPrecisionOnly() {
        assertEquals("SELECT ( amount )::numeric(10) FROM hr.orders",
            transform("SELECT CAST(amount AS NUMBER(10)) FROM orders"));
    }

    @Test
    void castToBareNumber() {
        assertEquals("SELECT ( amount )::numeric FROM hr.orders",
            transform("SELECT CAST(amount AS NUMBER) FROM orders"));
    }

    @Test
    void castToNumberWithAsteriskPrecisionUsesOracleDefaultOf38() {
        assertEquals("SELECT ( amount )::numeric(38,2) FROM hr.orders",
            transform("SELECT CAST(amount AS NUMBER(*,2)) FROM orders"));
    }

    @Test
    void castToNumberWithNegativeScale() {
        // PostgreSQL 15 added negative scale; the target is 17
        assertEquals("SELECT ( amount )::numeric(10,-2) FROM hr.orders",
            transform("SELECT CAST(amount AS NUMBER(10,-2)) FROM orders"));
    }

    @Test
    void castToDecimalAndNumericAlsoMapToNumeric() {
        assertEquals("SELECT ( amount )::numeric(8,3) FROM hr.orders",
            transform("SELECT CAST(amount AS DECIMAL(8,3)) FROM orders"));
        assertEquals("SELECT ( amount )::numeric(8,3) FROM hr.orders",
            transform("SELECT CAST(amount AS NUMERIC(8,3)) FROM orders"));
    }

    @Test
    void castWithSymbolicPrecisionIsRejected() {
        rejects("SELECT CAST(amount AS NUMBER(c_precision,2)) FROM orders");
    }

    // ==================== Integer aliases ====================

    @Test
    void ansiIntegerAliasesBecomeNumeric38SoTheyCannotOverflow() {
        // Oracle INTEGER/INT/SMALLINT are all NUMBER(38,0). ::integer and ::smallint would raise
        // "out of range" on values Oracle accepts - CAST(3000000000 AS SMALLINT) is legal Oracle.
        assertEquals("SELECT ( n )::numeric(38,0) FROM hr.orders",
            transform("SELECT CAST(n AS INTEGER) FROM orders"));
        assertEquals("SELECT ( n )::numeric(38,0) FROM hr.orders",
            transform("SELECT CAST(n AS INT) FROM orders"));
        assertEquals("SELECT ( n )::numeric(38,0) FROM hr.orders",
            transform("SELECT CAST(n AS SMALLINT) FROM orders"));
    }

    @Test
    void plsqlIntegerTypesAreGenuinelyMachineWidth() {
        assertEquals("SELECT ( n )::integer FROM hr.orders",
            transform("SELECT CAST(n AS PLS_INTEGER) FROM orders"));
        assertEquals("SELECT ( n )::integer FROM hr.orders",
            transform("SELECT CAST(n AS BINARY_INTEGER) FROM orders"));
    }

    @Test
    void binaryFloatingPointTypesAreIeeeOnBothSides() {
        assertEquals("SELECT ( n )::real FROM hr.orders",
            transform("SELECT CAST(n AS BINARY_FLOAT) FROM orders"));
        assertEquals("SELECT ( n )::double precision FROM hr.orders",
            transform("SELECT CAST(n AS BINARY_DOUBLE) FROM orders"));
    }

    @Test
    void oracleDecimalFloatTypesAreRejected() {
        // Oracle FLOAT/REAL/DOUBLE PRECISION are binary-precision NUMBERs carrying up to 38
        // decimal digits, not IEEE 754. PostgreSQL's real keeps about six - a silent digit loss.
        rejects("SELECT CAST(n AS FLOAT) FROM orders");
        rejects("SELECT CAST(n AS REAL) FROM orders");
        rejects("SELECT CAST(n AS DOUBLE PRECISION) FROM orders");
    }

    // ==================== Character ====================

    @Test
    void castToVarchar2KeepsTheLength() {
        assertEquals("SELECT ( name )::varchar(30) FROM hr.orders",
            transform("SELECT CAST(name AS VARCHAR2(30)) FROM orders"));
    }

    @Test
    void castToVarchar2WithByteSemanticsKeepsTheLength() {
        // BYTE is the Oracle default (NLS_LENGTH_SEMANTICS); rejecting it would reject nearly
        // every string cast. Documented relaxation: it diverges only on multi-byte data.
        assertEquals("SELECT ( name )::varchar(30) FROM hr.orders",
            transform("SELECT CAST(name AS VARCHAR2(30 BYTE)) FROM orders"));
        assertEquals("SELECT ( name )::varchar(30) FROM hr.orders",
            transform("SELECT CAST(name AS VARCHAR2(30 CHAR)) FROM orders"));
    }

    @Test
    void castToChar() {
        assertEquals("SELECT ( code )::char(5) FROM hr.orders",
            transform("SELECT CAST(code AS CHAR(5)) FROM orders"));
    }

    @Test
    void castWithCharacterSetIsRejected() {
        rejects("SELECT CAST(name AS VARCHAR2(30) CHARACTER SET NCHAR_CS) FROM orders");
    }

    // ==================== Rejected targets ====================

    @Test
    void castToLobTypesIsRejected() {
        // LOBs migrate to oid, a Large Object reference and an integer type - meaningless as a
        // cast target - while text would not match the oid column the value is written to.
        rejects("SELECT CAST(txt AS CLOB) FROM orders");
        rejects("SELECT CAST(txt AS NCLOB) FROM orders");
        rejects("SELECT CAST(b AS BLOB) FROM orders");
    }

    @Test
    void castToConstrainedSubtypesIsRejected() {
        // PostgreSQL cannot express the range check, and dropping it is a silent relaxation
        rejects("SELECT CAST(n AS POSITIVE) FROM orders");
        rejects("SELECT CAST(n AS NATURAL) FROM orders");
    }

    @Test
    void castToUserDefinedTypeIsRejected() {
        rejects("SELECT CAST(a AS address_type) FROM orders");
    }

    @Test
    void castMultisetIsRejected() {
        rejects("SELECT CAST(MULTISET(SELECT a FROM t) AS my_tab_type) FROM dual");
    }

    @Test
    void xmlcastIsRejected() {
        rejects("SELECT XMLCAST(x AS VARCHAR2(30)) FROM orders");
    }

    @Test
    void castToRawAndRowidIsRejected() {
        rejects("SELECT CAST(b AS RAW(100)) FROM orders");
        rejects("SELECT CAST(r AS ROWID) FROM orders");
    }

    @Test
    void castToIntervalIsRejected() {
        rejects("SELECT CAST(d AS INTERVAL DAY TO SECOND) FROM orders");
    }

    @Test
    void castWithOnConversionErrorIsRejected() {
        // Dropping it turns "substitute" into "raise" - same reason TO_NUMBER rejects it
        rejects("SELECT CAST(n AS NUMBER DEFAULT 0 ON CONVERSION ERROR) FROM orders");
    }

    @Test
    void castWithFormatModelIsRejected() {
        // The format decides the conversion, so it cannot be dropped
        rejects("SELECT CAST(s AS DATE, 'YYYY-MM-DD') FROM orders");
    }

    // ==================== Nesting and composition ====================

    @Test
    void castArgumentIsItselfTransformed() {
        // The value expression goes through the builder, so Oracle functions inside it are mapped
        String postgresSql = transform("SELECT CAST(NVL(amount, 0) AS NUMBER(6,2)) FROM orders");

        assertEquals("SELECT ( COALESCE( amount , 0 ) )::numeric(6,2) FROM hr.orders", postgresSql);
    }

    @Test
    void castComposesInsideAnExpression() {
        String postgresSql = transform(
            "SELECT * FROM orders WHERE CAST(created_ts AS DATE) = TRUNC(SYSDATE)");

        assertTrue(postgresSql.contains("DATE_TRUNC( 'second' , ( created_ts )::timestamp ) ="),
            postgresSql);
    }
}
