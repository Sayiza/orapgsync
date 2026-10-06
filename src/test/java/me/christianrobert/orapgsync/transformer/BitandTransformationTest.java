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
 * Tests for Oracle {@code BITAND} → {@code oracle_compat.bitand} transformation.
 *
 * <p>BITAND has no dedicated grammar rule — it is a non-reserved keyword reaching
 * {@code general_element_part}, so it arrives as an ordinary function call and both arguments are
 * arbitrary expressions. Before this mapping existed it fell through to the "unqualified name is a
 * user function in the current schema" branch and was emitted as {@code hr.bitand(...)}: a call to
 * a function that does not exist, which inside a PL/pgSQL body is not detected until runtime.
 *
 * @see me.christianrobert.orapgsync.transformer.builder.functions.BitwiseFunctionTransformer
 */
public class BitandTransformationTest {

    private AntlrParser parser;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        emptyIndices = MetadataIndexBuilder.buildEmpty();
    }

    private String transform(String oracleSql) {
        TransformationContext context = new TransformationContext(
                "HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        assertFalse(parseResult.hasErrors(), "Parse should succeed: " + parseResult.getErrors());

        String postgresSql = new PostgresCodeBuilder(context).visit(parseResult.getTree());
        return postgresSql.trim().replaceAll("\\s+", " ");
    }

    @Test
    void bitandWithColumnAndLiteral() {
        String result = transform("SELECT BITAND(flags, 4) FROM t");

        assertTrue(result.contains("oracle_compat.bitand( flags , 4 )"),
                "BITAND should map to the compat function: " + result);
    }

    /**
     * The schema-qualification branch is what used to swallow BITAND. Asserting its absence keeps
     * the regression from returning by way of the {@code isKnownBuiltinFunction} list.
     */
    @Test
    void bitandIsNotSchemaQualifiedAsAUserFunction() {
        String result = transform("SELECT BITAND(flags, 4) FROM t");

        assertFalse(result.toLowerCase().contains("hr.bitand"),
                "BITAND must not be qualified into the migration schema: " + result);
    }

    /**
     * Both arguments are full expressions, not just identifiers — the visitor has to recurse into
     * them rather than take their text.
     */
    @Test
    void bitandWithArbitraryExpressionArguments() {
        String result = transform("SELECT BITAND(a + 1, POWER(2, b)) FROM t");

        assertTrue(result.contains("oracle_compat.bitand( a + 1 , POWER( 2 , b ) )"),
                "Both arguments should be transformed recursively: " + result);
    }

    /**
     * The real-world shape from the migration: a mask column against another column, inside a
     * predicate.
     */
    @Test
    void bitandWithTwoColumnsInWhereClause() {
        String result = transform(
                "SELECT * FROM t WHERE BITAND(mask_container, class_bit_mask) = class_bit_mask");

        assertTrue(result.contains("oracle_compat.bitand( mask_container , class_bit_mask )"),
                "Two-column BITAND should map: " + result);
    }

    @Test
    void bitandIsCaseInsensitive() {
        String result = transform("SELECT bitand(flags, 4) FROM t");

        assertTrue(result.contains("oracle_compat.bitand( flags , 4 )"),
                "Lower-case bitand should map identically: " + result);
    }

    @Test
    void bitandNested() {
        String result = transform("SELECT BITAND(BITAND(a, b), c) FROM t");

        assertTrue(result.contains("oracle_compat.bitand( oracle_compat.bitand( a , b ) , c )"),
                "Nested BITAND should map at both levels: " + result);
    }

    /**
     * A wrong argument count is refused rather than passed through — passing it through would
     * produce a call to a function that does not exist, undetectable until runtime in a PL/pgSQL
     * body.
     */
    @Test
    void bitandWithWrongArgumentCountIsRefused() {
        TransformationException ex = assertThrows(TransformationException.class,
                () -> transform("SELECT BITAND(a, b, c) FROM t"));

        assertTrue(ex.getMessage().contains("BITAND"),
                "Error should name BITAND: " + ex.getMessage());
    }
}
