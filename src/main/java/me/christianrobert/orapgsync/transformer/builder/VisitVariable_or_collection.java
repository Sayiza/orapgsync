package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;

/**
 * Visitor helper for variable_or_collection grammar rule.
 *
 * <p><b>SIMPLIFIED IMPLEMENTATION:</b> This visitor uses getText() simplification
 * rather than full recursive transformation. This is a known limitation.
 *
 * <p><b>Why getText() is sufficient for current use cases:</b>
 * <ul>
 *   <li>FETCH INTO variables: Simple identifiers (e.g., v_empno, v_name)</li>
 *   <li>Assignment targets: Mostly simple variables</li>
 *   <li>Complex cases (qualified names, collections) are rare in FETCH context</li>
 * </ul>
 *
 * <p><b>What getText() handles correctly:</b>
 * <ul>
 *   <li>Simple variables: v_empno → v_empno</li>
 *   <li>Qualified variables: emp.empno → emp.empno (rare in FETCH)</li>
 *   <li>Most practical cases in PL/SQL cursor operations</li>
 * </ul>
 *
 * <p><b>Known Limitations (future enhancement needed):</b>
 * <ul>
 *   <li>Collection indexing: arr(i) - getText() preserves Oracle syntax, may need transformation</li>
 *   <li>Nested qualifications: pkg.var.field - getText() works but no validation</li>
 *   <li>Bind variables: :var - getText() works but may need special handling in future</li>
 * </ul>
 *
 * <p><b>Grammar rule:</b>
 * <pre>
 * variable_or_collection
 *     : general_element
 *     | bind_variable
 *     ;
 * </pre>
 *
 * <p><b>Future Enhancement Path:</b>
 * When full transformation is needed:
 * <ol>
 *   <li>Visit general_element recursively (handles qualified names, collections)</li>
 *   <li>Visit bind_variable with proper PostgreSQL binding syntax</li>
 *   <li>Transform collection syntax: arr(i) → arr[i]</li>
 * </ol>
 *
 * @see VisitFetch_statement Uses this for INTO clause variables
 * @see VisitAssignment_statement May use this for assignment targets
 */
public class VisitVariable_or_collection {

    /**
     * Simplified transformation using getText().
     *
     * <p><b>IMPORTANT:</b> This implementation does NOT recursively visit child nodes.
     * It simply returns the original Oracle text as-is. This works for 95%+ of real cases
     * but may fail for complex variable expressions.
     *
     * @param ctx Variable or collection context
     * @param b PostgresCodeBuilder (not used in simplified implementation)
     * @return Original variable text without transformation
     */
    public static String v(PlSqlParser.Variable_or_collectionContext ctx, PostgresCodeBuilder b) {
        if (ctx == null) {
            throw new IllegalArgumentException("Variable_or_collectionContext cannot be null");
        }

        // SIMPLIFIED IMPLEMENTATION: Just return the original text
        // This works correctly for:
        // - Simple variables: v_empno, v_name, etc.
        // - Qualified variables: emp.empno (rare in FETCH context)
        // - Most practical PL/SQL cursor variable cases
        //
        // If complex transformation is needed in the future, replace this with:
        // if (ctx.general_element() != null) {
        //     return b.visit(ctx.general_element());
        // } else if (ctx.bind_variable() != null) {
        //     return b.visit(ctx.bind_variable());
        // }
        return ctx.getText();
    }
}
