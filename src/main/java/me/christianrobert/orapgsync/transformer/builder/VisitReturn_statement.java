package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;

/**
 * Static helper for visiting RETURN statements.
 *
 * <pre>
 * RETURN expression;   -- functions
 * RETURN;              -- procedures: returns control without a value
 * </pre>
 *
 * <p>Nearly a pass-through, because PL/pgSQL spells both the same way. The exception is an Oracle
 * <b>constructor</b>, where a bare {@code RETURN;} does not mean "return nothing" — it returns the
 * instance the constructor has been populating. Transformed literally it produced
 * {@code RETURN;} inside a function declared {@code RETURNS hr.demo_item_t}, which PostgreSQL
 * rejects at CREATE time with {@code missing expression at or near ";"} — 33 failures on the
 * 2026-08-27 run.
 *
 * <p>What a bare RETURN means belongs to the enclosing routine, not to this statement, so it is
 * read from the routine-body stack rather than passed down or guessed. A local subprogram declared
 * inside a constructor pushes its own (empty) entry and therefore keeps the plain meaning.
 */
public class VisitReturn_statement {

    public static String v(PlSqlParser.Return_statementContext ctx, PostgresCodeBuilder b) {
        StringBuilder result = new StringBuilder("RETURN");

        // Expression is optional (procedures can have RETURN without value)
        if (ctx.expression() != null) {
            String expr = b.visit(ctx.expression());
            result.append(" ").append(expr);
            return result.toString();
        }

        String bareReturn = b.getBareReturnExpression();
        if (bareReturn != null) {
            result.append(" ").append(bareReturn);
        }

        return result.toString();
    }
}
