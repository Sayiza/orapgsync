package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Visitor helper for quantified_expression grammar rule.
 *
 * <p>Grammar rule:
 * <pre>
 * quantified_expression
 *     : (SOME | EXISTS | ALL | ANY) (
 *         '(' select_only_statement ')'              // EXISTS, ANY, ALL with subqueries
 *         | '(' expression (',' expression)* ')'      // ANY, ALL with value lists
 *     )
 * </pre>
 *
 * <p>Oracle vs PostgreSQL differences:
 * <ul>
 *   <li>EXISTS: Identical in both databases (pass-through)</li>
 *   <li>ANY: Identical in both databases (pass-through)</li>
 *   <li>ALL: Identical in both databases (pass-through)</li>
 *   <li>SOME: Oracle-specific keyword, PostgreSQL uses ANY (transformation: SOME → ANY)</li>
 * </ul>
 *
 * <p>Common use cases:
 * <ul>
 *   <li>EXISTS: WHERE EXISTS (SELECT 1 FROM table WHERE condition)</li>
 *   <li>ANY: WHERE col > ANY (SELECT col FROM table)</li>
 *   <li>ALL: WHERE col > ALL (SELECT col FROM table)</li>
 *   <li>SOME: WHERE col > SOME (SELECT col FROM table) → WHERE col > ANY (...)</li>
 * </ul>
 */
public class VisitQuantifiedExpression {

    public static String v(PlSqlParser.Quantified_expressionContext ctx, PostgresCodeBuilder b) {
        if (ctx == null) {
            throw new IllegalArgumentException("Quantified_expressionContext cannot be null");
        }

        // Determine the quantifier keyword
        String quantifier;
        if (ctx.SOME() != null) {
            // Oracle SOME → PostgreSQL ANY
            quantifier = "ANY";
        } else if (ctx.EXISTS() != null) {
            quantifier = "EXISTS";
        } else if (ctx.ALL() != null) {
            quantifier = "ALL";
        } else if (ctx.ANY() != null) {
            quantifier = "ANY";
        } else {
            throw new TransformationException(
                "Quantified expression missing quantifier (SOME, EXISTS, ALL, ANY)");
        }

        // Check if it's a subquery: '(' select_only_statement ')'
        if (ctx.select_only_statement() != null) {
            // EXISTS (SELECT ...), ANY (SELECT ...), ALL (SELECT ...)
            String subquery = b.visit(ctx.select_only_statement());
            return quantifier + " ( " + subquery + " )";
        }

        // Check if it's an expression list: '(' expression (',' expression)* ')'
        List<PlSqlParser.ExpressionContext> expressions = ctx.expression();
        if (expressions != null && !expressions.isEmpty()) {
            // ANY (value1, value2, ...), ALL (value1, value2, ...)
            // Note: EXISTS does not support expression lists (only subqueries)
            if (ctx.EXISTS() != null) {
                throw new TransformationException(
                    "EXISTS can only be used with subqueries, not expression lists");
            }

            String expressionList = expressions.stream()
                .map(b::visit)
                .collect(Collectors.joining(" , "));

            return quantifier + " ( " + expressionList + " )";
        }

        throw new TransformationException(
            "Quantified expression missing both select_only_statement and expression list");
    }
}
