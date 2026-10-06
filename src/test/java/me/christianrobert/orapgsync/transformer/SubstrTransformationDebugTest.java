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

/**
 * Debug test to see actual output from SUBSTR transformation.
 */
class SubstrTransformationDebugTest {

    private AntlrParser parser;
    private TransformationIndices emptyIndices;

    @BeforeEach
    void setUp() {
        parser = new AntlrParser();
        emptyIndices = MetadataIndexBuilder.buildEmpty();
    }

    @Test
    void debugQualifiedColumn() {
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT SUBSTR(e.name, 1, 10) FROM employees e";

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        System.out.println("Oracle:     " + oracleSql);
        System.out.println("PostgreSQL: " + postgresSql);
        System.out.println("Normalized: " + postgresSql.trim().replaceAll("\\s+", " "));
    }

    @Test
    void debugUpperFunction() {
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT SUBSTR(UPPER(name), 1, 5) FROM employees";

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        System.out.println("Oracle:     " + oracleSql);
        System.out.println("PostgreSQL: " + postgresSql);
        System.out.println("Normalized: " + postgresSql.trim().replaceAll("\\s+", " "));
    }

    @Test
    void debugFromDual() {
        TransformationContext context = new TransformationContext("HR", emptyIndices, new SimpleTypeEvaluator("HR", emptyIndices));
        String oracleSql = "SELECT SUBSTR('Hello World', 7, 5) FROM DUAL";

        ParseResult parseResult = parser.parseSelectStatement(oracleSql);
        PostgresCodeBuilder builder = new PostgresCodeBuilder(context);
        String postgresSql = builder.visit(parseResult.getTree());

        System.out.println("Oracle:     " + oracleSql);
        System.out.println("PostgreSQL: " + postgresSql);
        System.out.println("Normalized: " + postgresSql.trim().replaceAll("\\s+", " "));
        System.out.println("Contains 'FROM DUAL': " + postgresSql.contains("FROM DUAL"));
        System.out.println("Contains 'FROM': " + postgresSql.contains("FROM"));
    }
}
