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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Transformation tests for Oracle delimited identifiers ({@code "RUN_ID"}).
 *
 * <p>Oracle SQL exported by a GUI tool quotes every identifier. Those quotes used to reach
 * PostgreSQL untouched, where they are case-sensitive — so {@code "RUN_ID"} looked for a column
 * of that exact name while the DDL migration had created {@code run_id}, and the view failed
 * with {@code column "RUN_ID" does not exist}.
 *
 * <p>The counterpart assertions matter just as much: unquoted identifiers must come through
 * byte-identical, because that is what guarantees this fix cannot regress a view that already
 * worked.
 */
public class QuotedIdentifierTransformationTest {

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

        TransformationContext context =
                new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String postgresSql = new PostgresCodeBuilder(context).visit(parseResult.getTree());
        return postgresSql.trim().replaceAll("\\s+", " ");
    }

    // ==================== The reported failure ====================

    @Test
    void quotedSelectListColumnsAreFoldedToTheMigratedNames() {
        // The exact shape of the view that failed in production
        String postgresSql = transform(
                "select \"RUN_ID\",\"STATUS\",\"TIMESTAMP\",\"MSG\" "
                        + "from bagl_status "
                        + "where run_id = ( select max(run_id) from bagl_status )");

        assertTrue(postgresSql.contains("run_id"), "RUN_ID should be folded to run_id");
        assertTrue(postgresSql.contains("status"), "STATUS should be folded to status");
        assertTrue(postgresSql.contains("msg"), "MSG should be folded to msg");

        assertFalse(postgresSql.contains("\"RUN_ID\""),
                "Upper-case quoted column must not survive - PostgreSQL would not find it");
        assertFalse(postgresSql.contains("\"STATUS\""), "Upper-case quoted column must not survive");
        assertFalse(postgresSql.contains("\"MSG\""), "Upper-case quoted column must not survive");
    }

    @Test
    void quotedReservedWordColumnKeepsItsQuotesButLowerCased() {
        // TIMESTAMP is a reserved word, so PostgresIdentifierNormalizer quotes it - and the DDL
        // migration created it the same way, so the two match
        String postgresSql = transform("select \"TIMESTAMP\" from bagl_status");

        assertTrue(postgresSql.contains("\"timestamp\""),
                "Reserved word must stay quoted, but lower-cased to match the created column");
        assertFalse(postgresSql.contains("\"TIMESTAMP\""), "Must not stay upper-case");
    }

    @Test
    void quotedTableNameIsResolvedAndSchemaQualified() {
        String postgresSql = transform("select run_id from \"BAGL_STATUS\"");

        assertTrue(postgresSql.contains("hr.bagl_status"),
                "Quoted table name should still be schema-qualified and folded: " + postgresSql);
        assertFalse(postgresSql.contains("\"BAGL_STATUS\""), "Quoted upper-case table must not survive");
    }

    @Test
    void quotedAliasAndQualifiedColumnAgreeOnCase() {
        // The alias is registered in a lower-case keyed registry and referenced by the column;
        // both sides must normalize identically or the reference dangles
        String postgresSql = transform("select \"B\".\"RUN_ID\" from bagl_status \"B\"");

        assertTrue(postgresSql.contains("b . run_id"),
                "Alias and column should both be folded: " + postgresSql);
    }

    // ==================== Non-regression: unquoted input is untouched ====================

    @Test
    void unquotedIdentifiersAreEmittedByteIdentical() {
        assertEquals("SELECT run_id FROM hr.bagl_status",
                transform("select run_id from bagl_status"));
        assertEquals("SELECT RUN_ID FROM hr.bagl_status",
                transform("select RUN_ID from bagl_status"));
    }

    @Test
    void bareKeywordPseudoColumnsKeepTheirPostgresMeaning() {
        // Normalizing these would produce "user", a column reference, instead of the keyword
        assertTrue(transform("select USER from dual").contains("USER"),
                "Bare USER must stay an unquoted keyword");
    }
}
