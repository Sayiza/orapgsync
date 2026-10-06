package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;

/**
 * Static helper for visiting PL/SQL CONTINUE statements.
 *
 * <p>Transforms Oracle CONTINUE statements to PostgreSQL equivalents.</p>
 *
 * <h3>Oracle CONTINUE Statement:</h3>
 * <pre>
 * CONTINUE;                    -- Skip to next iteration
 * CONTINUE WHEN condition;     -- Conditional continue
 * CONTINUE label_name;         -- Continue to next iteration of labeled loop
 * CONTINUE label_name WHEN condition;  -- Conditional continue for labeled loop
 * </pre>
 *
 * <h3>PostgreSQL PL/pgSQL:</h3>
 * <pre>
 * CONTINUE;                    -- Identical syntax
 * CONTINUE WHEN condition;     -- Identical syntax
 * CONTINUE label_name;         -- Identical syntax
 * CONTINUE label_name WHEN condition;  -- Identical syntax
 * </pre>
 *
 * <h3>Notes:</h3>
 * <ul>
 *   <li>PostgreSQL syntax is identical to Oracle for CONTINUE statements</li>
 *   <li>CONTINUE can be used with or without labels</li>
 *   <li>WHEN clause is optional in both</li>
 *   <li>CONTINUE skips to the next loop iteration</li>
 * </ul>
 */
public class VisitContinue_statement {

    /**
     * Transforms CONTINUE statement to PostgreSQL syntax.
     *
     * @param ctx CONTINUE statement parse tree context
     * @param b PostgresCodeBuilder instance (for visiting condition)
     * @return PostgreSQL CONTINUE statement
     */
    public static String v(PlSqlParser.Continue_statementContext ctx, PostgresCodeBuilder b) {
        // Grammar: continue_statement: CONTINUE label_name? (WHEN condition)?

        StringBuilder result = new StringBuilder();
        result.append("CONTINUE");

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
