package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;

import java.util.List;

/**
 * Visitor helper for expressions_ grammar rule.
 *
 * <p>Grammar rule:
 * <pre>
 * expressions_
 *     : expression (',' expression)*
 *     ;
 * </pre>
 *
 * <p>This visitor handles comma-separated expression lists, commonly used in:
 * <ul>
 *   <li>PARTITION BY clauses in window functions</li>
 *   <li>GROUP BY clauses</li>
 *   <li>Other multi-expression contexts</li>
 * </ul>
 */
public class VisitExpressions {

    public static String v(PlSqlParser.Expressions_Context ctx, PostgresCodeBuilder b) {
        if (ctx == null) {
            throw new IllegalArgumentException("Expressions_Context cannot be null");
        }

        List<PlSqlParser.ExpressionContext> expressions = ctx.expression();
        if (expressions == null || expressions.isEmpty()) {
            return "";
        }

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < expressions.size(); i++) {
            if (i > 0) {
                result.append(" , ");
            }
            result.append(b.visit(expressions.get(i)));
        }

        return result.toString();
    }
}
