package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;

/**
 * Static helper for visiting PL/SQL EXIT statements.
 *
 * <p>Transforms Oracle EXIT statements to PostgreSQL equivalents.</p>
 *
 * <h3>Oracle EXIT Statement:</h3>
 * <pre>
 * EXIT;                    -- Unconditional exit from loop
 * EXIT WHEN condition;     -- Conditional exit
 * EXIT label_name;         -- Exit from labeled loop
 * EXIT label_name WHEN condition;  -- Exit from labeled loop with condition
 * </pre>
 *
 * <h3>PostgreSQL PL/pgSQL:</h3>
 * <pre>
 * EXIT;                    -- Identical syntax
 * EXIT WHEN condition;     -- Identical syntax
 * EXIT label_name;         -- Identical syntax
 * EXIT label_name WHEN condition;  -- Identical syntax
 * </pre>
 *
 * <h3>Notes:</h3>
 * <ul>
 *   <li>PostgreSQL syntax is identical to Oracle for EXIT statements</li>
 *   <li>EXIT can be used with or without labels</li>
 *   <li>WHEN clause is optional in both</li>
 *   <li>EXIT without WHEN exits immediately</li>
 * </ul>
 */
public class VisitExit_statement {

    /**
     * Transforms EXIT statement to PostgreSQL syntax.
     *
     * @param ctx EXIT statement parse tree context
     * @param b PostgresCodeBuilder instance (for visiting condition)
     * @return PostgreSQL EXIT statement
     */
    public static String v(PlSqlParser.Exit_statementContext ctx, PostgresCodeBuilder b) {
        // Grammar: exit_statement: EXIT label_name? (WHEN condition)?

        StringBuilder result = new StringBuilder();
        result.append("EXIT");

        // Optional label_name
        if (ctx.label_name() != null) {
            String label = ctx.label_name().getText().toLowerCase();
            result.append(" ").append(label);
        }

        // Optional WHEN condition
        if (ctx.WHEN() != null && ctx.condition() != null) {
            result.append(" WHEN ");
            String condition = b.visit(ctx.condition());
            result.append(condition);
        }

        return result.toString();
    }
}
