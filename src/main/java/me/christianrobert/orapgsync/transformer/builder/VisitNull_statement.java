package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;

/**
 * Static helper for visiting PL/SQL NULL statements.
 *
 * <p>Transforms Oracle NULL statements to PostgreSQL equivalents.</p>
 *
 * <h3>Oracle NULL Statement:</h3>
 * <pre>
 * NULL;  -- No-op placeholder statement
 * </pre>
 *
 * <h3>PostgreSQL PL/pgSQL:</h3>
 * <pre>
 * NULL;  -- Identical syntax
 * </pre>
 *
 * <h3>Usage:</h3>
 * <p>NULL statements are commonly used as placeholders in conditional logic:
 * <pre>
 * IF condition THEN
 *   process_data();
 * ELSE
 *   NULL;  -- Explicitly do nothing
 * END IF;
 * </pre>
 *
 * <h3>Notes:</h3>
 * <ul>
 *   <li>PostgreSQL syntax is identical to Oracle for NULL statements</li>
 *   <li>NULL is a statement (procedural), not an expression (SQL)</li>
 *   <li>Useful for clarity when a branch intentionally does nothing</li>
 *   <li>Can be omitted in many cases, but explicit NULL is clearer</li>
 * </ul>
 */
public class VisitNull_statement {

    /**
     * Transforms NULL statement to PostgreSQL syntax.
     *
     * @param ctx NULL statement parse tree context
     * @param b PostgresCodeBuilder instance (unused, but kept for consistency)
     * @return PostgreSQL NULL statement
     */
    public static String v(PlSqlParser.Null_statementContext ctx, PostgresCodeBuilder b) {
        // Grammar: null_statement: NULL_
        // PostgreSQL syntax is identical to Oracle: just the keyword NULL
        return "NULL";
    }
}
