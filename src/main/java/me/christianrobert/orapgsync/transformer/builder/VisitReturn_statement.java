package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;

/**
 * Static helper for visiting RETURN statements.
 *
 * Oracle syntax:
 * RETURN expression;
 * RETURN;  (for procedures - returns control without value)
 *
 * PostgreSQL PL/pgSQL: (same)
 * RETURN expression;
 * RETURN;
 *
 * This is a direct pass-through since the syntax is identical.
 */
public class VisitReturn_statement {

    public static String v(PlSqlParser.Return_statementContext ctx, PostgresCodeBuilder b) {
        StringBuilder result = new StringBuilder("RETURN");

        // Expression is optional (procedures can have RETURN without value)
        if (ctx.expression() != null) {
            String expr = b.visit(ctx.expression());
            result.append(" ").append(expr);
        }

        return result.toString();
    }
}
