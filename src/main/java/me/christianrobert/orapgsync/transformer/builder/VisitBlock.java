package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;

/**
 * Static helper for visiting PL/SQL anonymous blocks (nested DECLARE...BEGIN...END).
 *
 * <p>Oracle structure:</p>
 * <pre>
 * block : (DECLARE declare_spec*)? body ;
 * </pre>
 *
 * <p>Example Oracle code:</p>
 * <pre>
 * DECLARE
 *   v_temp NUMBER;
 * BEGIN
 *   v_temp := 1;
 * END;
 * </pre>
 *
 * <p>PostgreSQL PL/pgSQL uses the same structure:</p>
 * <pre>
 * DECLARE
 *   v_temp numeric;
 * BEGIN
 *   v_temp := 1;
 * END;
 * </pre>
 *
 * <p>Note: Nested blocks in PostgreSQL create their own variable scope.</p>
 */
public class VisitBlock {

    public static String v(PlSqlParser.BlockContext ctx, PostgresCodeBuilder b) {
        StringBuilder result = new StringBuilder();

        // Handle optional DECLARE section
        if (ctx.DECLARE() != null && ctx.declare_spec() != null && !ctx.declare_spec().isEmpty()) {
            result.append("DECLARE\n");

            // Visit each declaration
            for (PlSqlParser.Declare_specContext declareSpec : ctx.declare_spec()) {
                String declaration = b.visit(declareSpec);
                if (declaration != null && !declaration.trim().isEmpty()) {
                    result.append("  ").append(declaration);
                    if (!declaration.trim().endsWith(";")) {
                        result.append(";");
                    }
                    result.append("\n");
                }
            }
        }

        // Visit the body (BEGIN...END)
        if (ctx.body() != null) {
            String body = b.visit(ctx.body());
            if (body != null) {
                result.append(body);
            }
        }

        return result.toString();
    }
}
