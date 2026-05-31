package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlLexer;
import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationContext;
import me.christianrobert.orapgsync.transformer.context.TransformationIndices;
import me.christianrobert.orapgsync.transformer.type.SimpleTypeEvaluator;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for VisitMerge_statement.
 * Tests Oracle MERGE to PostgreSQL INSERT ON CONFLICT transformation.
 */
class VisitMerge_statementTest {

    private PostgresCodeBuilder builder;

    @BeforeEach
    void setUp() {
        // Create minimal indices (empty)
        TransformationIndices indices = new TransformationIndices(
                new HashMap<>(),  // tableColumns
                new HashMap<>(),  // typeMethods
                new HashSet<>(),  // packageFunctions
                new HashMap<>(),  // synonyms
                Collections.emptyMap(), // typeFieldTypes
                Collections.emptySet()  // objectTypeNames
        );

        // Create context
        TransformationContext context = new TransformationContext(
                "hr",
                indices,
                new SimpleTypeEvaluator("hr", indices)
        );

        builder = new PostgresCodeBuilder(context);
    }

    @Test
    void testSimpleMergeUsingDual() {
        String oracle = """
            MERGE INTO hr.revisions r
            USING dual ON (r.id = 123)
            WHEN MATCHED THEN UPDATE SET revision = r.revision + 1
            WHEN NOT MATCHED THEN INSERT (id, revision) VALUES (123, 1)
            """;

        PlSqlParser.Merge_statementContext ctx = parseMergeStatement(oracle);
        String result = VisitMerge_statement.v(ctx, builder);

        assertNotNull(result, "MERGE transformation should not return null");
        assertTrue(result.contains("INSERT INTO"), "Should contain INSERT INTO");
        assertTrue(result.contains("ON CONFLICT"), "Should contain ON CONFLICT");
        assertTrue(result.contains("DO UPDATE SET"), "Should contain DO UPDATE SET");
    }

    @Test
    void testMergeWithoutUpdateClause() {
        String oracle = """
            MERGE INTO hr.revisions r
            USING dual ON (r.id = 123)
            WHEN NOT MATCHED THEN INSERT (id, revision) VALUES (123, 1)
            """;

        PlSqlParser.Merge_statementContext ctx = parseMergeStatement(oracle);
        String result = VisitMerge_statement.v(ctx, builder);

        assertNotNull(result);
        assertTrue(result.contains("INSERT INTO"));
        assertTrue(result.contains("ON CONFLICT"));
        assertTrue(result.contains("DO NOTHING"), "Without UPDATE clause, should use DO NOTHING");
    }

    @Test
    void testMergeExtractsConflictColumn() {
        String oracle = """
            MERGE INTO hr.study_revision r
            USING dual ON (r.study_uid = 999)
            WHEN MATCHED THEN UPDATE SET revision = 2
            WHEN NOT MATCHED THEN INSERT (study_uid, revision) VALUES (999, 1)
            """;

        PlSqlParser.Merge_statementContext ctx = parseMergeStatement(oracle);
        String result = VisitMerge_statement.v(ctx, builder);

        assertNotNull(result);
        // Should extract study_uid as the conflict column
        assertTrue(result.contains("ON CONFLICT (study_uid)"),
                "Should extract conflict column from ON condition. Result: " + result);
    }

    @Test
    void testMergeWithTriggerReferences() {
        // Test case matching the actual trigger pattern from AC_STUDY_REV_UPDATE_TRG
        String oracle = """
            MERGE INTO hr.ac_study_revision r
            USING dual ON (r.study_uid = :OLD.STUDY_ID)
            WHEN MATCHED THEN UPDATE SET revision = r.revision + 1
            WHEN NOT MATCHED THEN INSERT (study_uid, revision) VALUES (:OLD.STUDY_ID, 1)
            """;

        PlSqlParser.Merge_statementContext ctx = parseMergeStatement(oracle);
        String result = VisitMerge_statement.v(ctx, builder);

        assertNotNull(result, "MERGE transformation should not return null");
        System.out.println("Transformed MERGE: " + result);

        // Verify structure
        assertTrue(result.contains("INSERT INTO"), "Should contain INSERT INTO");
        assertTrue(result.contains("ON CONFLICT"), "Should contain ON CONFLICT. Result: " + result);
        // Column list may have no space after comma (raw getText)
        assertTrue(result.contains("study_uid") && result.contains("revision"),
                "Should contain column list. Result: " + result);
        assertTrue(result.contains("VALUES"), "Should contain VALUES. Result: " + result);
        assertTrue(result.contains("DO UPDATE SET"), "Should contain DO UPDATE SET. Result: " + result);

        // Should not contain "null" as literal text (except in expressions)
        assertFalse(result.contains("INSERT INTO hr.ac_study_revision null"),
                "Should not have 'null' instead of column list. Result: " + result);
    }

    /**
     * Parses a MERGE statement from Oracle SQL text.
     */
    private PlSqlParser.Merge_statementContext parseMergeStatement(String sql) {
        // Wrap in a procedure to make it a valid compilation unit
        String wrapped = "PROCEDURE test IS BEGIN " + sql + "; END;";

        PlSqlLexer lexer = new PlSqlLexer(CharStreams.fromString(wrapped));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        PlSqlParser parser = new PlSqlParser(tokens);
        PlSqlParser.Create_procedure_bodyContext procCtx = parser.create_procedure_body();

        // Navigate to the merge_statement
        // procedure -> body -> seq_of_statements -> statement -> sql_statement -> data_manipulation_language_statements -> merge_statement
        return procCtx.body()
                .seq_of_statements()
                .statement(0)
                .sql_statement()
                .data_manipulation_language_statements()
                .merge_statement();
    }
}
