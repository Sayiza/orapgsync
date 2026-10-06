package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;

/**
 * Static helper for visiting PL/SQL SELECT INTO clauses.
 *
 * <p>Transforms Oracle SELECT INTO clauses to PostgreSQL equivalents.</p>
 *
 * <h3>Oracle Structure (from AST):</h3>
 * <pre>
 * into_clause:
 *   (BULK COLLECT)? INTO (general_element | bind_variable)
 *   (',' (general_element | bind_variable))*
 * </pre>
 *
 * <h3>PostgreSQL PL/pgSQL:</h3>
 * <pre>
 * INTO STRICT variable_list
 * </pre>
 *
 * <h3>CRITICAL: STRICT Keyword Required</h3>
 * <p><strong>Why STRICT is needed:</strong></p>
 * <ul>
 *   <li><strong>Oracle behavior:</strong> SELECT INTO automatically raises exceptions:
 *     <ul>
 *       <li>0 rows → NO_DATA_FOUND exception</li>
 *       <li>&gt;1 rows → TOO_MANY_ROWS exception</li>
 *     </ul>
 *   </li>
 *   <li><strong>PostgreSQL WITHOUT STRICT:</strong> Silent failure:
 *     <ul>
 *       <li>0 rows → Sets variables to NULL (no exception!)</li>
 *       <li>&gt;1 rows → Uses first row (no exception!)</li>
 *     </ul>
 *   </li>
 *   <li><strong>PostgreSQL WITH STRICT:</strong> Matches Oracle behavior:
 *     <ul>
 *       <li>0 rows → Raises no_data_found exception ✓</li>
 *       <li>&gt;1 rows → Raises too_many_rows exception ✓</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <h3>Notes:</h3>
 * <ul>
 *   <li>STRICT is ALWAYS added to match Oracle semantics</li>
 *   <li>BULK COLLECT is Oracle-specific for array operations (not yet supported)</li>
 *   <li>Variable names need transformation (lowercase, expression resolution)</li>
 *   <li>Used in SELECT INTO statements within PL/SQL function bodies</li>
 * </ul>
 *
 * <h3>Example:</h3>
 * <pre>
 * -- Oracle
 * SELECT employee_name, salary INTO v_name, v_sal
 * FROM employees WHERE employee_id = p_emp_id;
 *
 * -- PostgreSQL (Generated with STRICT)
 * SELECT employee_name, salary INTO STRICT v_name, v_sal
 * FROM hr.employees WHERE employee_id = p_emp_id;
 * </pre>
 */
public class VisitInto_clause {

    /**
     * Transforms INTO clause to PostgreSQL syntax.
     *
     * @param ctx INTO clause parse tree context
     * @param b PostgresCodeBuilder instance (for visiting variable expressions)
     * @return PostgreSQL INTO clause
     */
    public static String v(PlSqlParser.Into_clauseContext ctx, PostgresCodeBuilder b) {
        StringBuilder result = new StringBuilder();

        // Check for BULK COLLECT (not yet supported in this implementation)
        // BULK COLLECT is an advanced Oracle feature for fetching multiple rows into arrays
        // For now, we'll just skip it and emit a warning in the transformation
        if (ctx.BULK() != null || ctx.COLLECT() != null) {
            // TODO: Implement BULK COLLECT transformation
            // This requires array variable support and different PostgreSQL syntax
            throw new UnsupportedOperationException(
                    "BULK COLLECT INTO is not yet supported. " +
                    "Use simple SELECT INTO for single-row queries.");
        }

        // STEP 1: Add INTO STRICT keyword
        // STRICT is required to match Oracle's automatic exception raising behavior
        // Oracle: 0 rows → NO_DATA_FOUND, >1 rows → TOO_MANY_ROWS
        // PostgreSQL with STRICT: 0 rows → no_data_found, >1 rows → too_many_rows
        // PostgreSQL without STRICT: Silent failure (sets NULL or uses first row)
        result.append("INTO STRICT ");

        // STEP 2: Transform first variable
        // Can be either general_element (e.g., v_name) or bind_variable (e.g., :v_name)
        if (ctx.general_element() != null && !ctx.general_element().isEmpty()) {
            String firstVar = b.visit(ctx.general_element(0));
            result.append(firstVar);

            // STEP 3: Transform remaining variables (comma-separated)
            for (int i = 1; i < ctx.general_element().size(); i++) {
                String var = b.visit(ctx.general_element(i));
                result.append(", ").append(var);
            }
        } else if (ctx.bind_variable() != null && !ctx.bind_variable().isEmpty()) {
            String firstVar = b.visit(ctx.bind_variable(0));
            result.append(firstVar);

            // STEP 3: Transform remaining bind variables (comma-separated)
            for (int i = 1; i < ctx.bind_variable().size(); i++) {
                String var = b.visit(ctx.bind_variable(i));
                result.append(", ").append(var);
            }
        } else {
            throw new IllegalStateException("INTO clause has no variables");
        }

        return result.toString();
    }
}
