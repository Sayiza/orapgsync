package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;

/**
 * Static helper for visiting PL/SQL assignment statements.
 *
 * <p>Transforms Oracle assignment statements to PostgreSQL equivalents.</p>
 *
 * <h3>Oracle Structure (from AST):</h3>
 * <pre>
 * (general_element | bind_variable) ASSIGN_OP expression
 *
 * Examples:
 * v_count := 10;
 * v_total := v_price * v_quantity;
 * v_name := 'John Doe';
 * </pre>
 *
 * <h3>PostgreSQL PL/pgSQL:</h3>
 * <pre>
 * Same syntax - assignments use := in both Oracle and PostgreSQL
 *
 * Examples:
 * v_count := 10;
 * v_total := v_price * v_quantity;
 * v_name := 'John Doe';
 * </pre>
 *
 * <h3>Notes:</h3>
 * <ul>
 *   <li>Syntax is identical between Oracle and PostgreSQL</li>
 *   <li>Only the expression on the right-hand side needs transformation</li>
 *   <li>The := operator is the same in both databases</li>
 * </ul>
 */
public class VisitAssignment_statement {

    /**
     * Transforms assignment statement to PostgreSQL syntax.
     *
     * @param ctx Assignment statement parse tree context
     * @param b PostgresCodeBuilder instance (for visiting the expression)
     * @return PostgreSQL assignment statement
     */
    public static String v(PlSqlParser.Assignment_statementContext ctx, PostgresCodeBuilder b) {
        StringBuilder result = new StringBuilder();

        // STEP 1: Get the left-hand side (variable name)
        String leftSide;
        if (ctx.general_element() != null) {
            leftSide = b.visit(ctx.general_element());
        } else if (ctx.bind_variable() != null) {
            leftSide = b.visit(ctx.bind_variable());
        } else {
            throw new IllegalStateException("Assignment statement has no left-hand side");
        }

        result.append(leftSide);

        // STEP 2: Add := operator (same in Oracle and PostgreSQL)
        result.append(" := ");

        // STEP 3: Transform the right-hand side expression
        String rightSide = b.visit(ctx.expression());
        result.append(rightSide);

        return result.toString();
    }
}
