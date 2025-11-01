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
     * <p><strong>Package Variable Support:</strong></p>
     * <p>If the left-hand side is a package variable reference (e.g., pkg.g_counter),
     * the assignment is transformed to a setter call:</p>
     * <pre>
     * Oracle:     pkg.g_counter := 100;
     * PostgreSQL: PERFORM schema.pkg__set_g_counter(100);
     * </pre>
     *
     * @param ctx Assignment statement parse tree context
     * @param b PostgresCodeBuilder instance (for visiting the expression)
     * @return PostgreSQL assignment statement or setter call
     */
    public static String v(PlSqlParser.Assignment_statementContext ctx, PostgresCodeBuilder b) {
        // STEP 1: Parse LHS with flag protection (prevents getter transformation)
        // This flag tells VisitGeneralElement to NOT transform package variables to getter calls
        b.setInAssignmentTarget(true);
        String leftSide;
        if (ctx.general_element() != null) {
            leftSide = b.visit(ctx.general_element());
        } else if (ctx.bind_variable() != null) {
            leftSide = b.visit(ctx.bind_variable());
        } else {
            throw new IllegalStateException("Assignment statement has no left-hand side");
        }
        b.setInAssignmentTarget(false);

        // STEP 2: Check if LHS is a package variable
        PostgresCodeBuilder.PackageVariableReference pkgVar = b.parsePackageVariableReference(leftSide);

        if (pkgVar != null) {
            // Transform to setter call
            String rightSide = b.visit(ctx.expression());
            return "PERFORM " + pkgVar.getSetterCall(rightSide);
        }

        // STEP 3: Normal assignment (not a package variable)
        StringBuilder result = new StringBuilder();
        result.append(leftSide);
        result.append(" := ");
        String rightSide = b.visit(ctx.expression());
        result.append(rightSide);

        return result.toString();
    }
}
