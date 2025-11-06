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
     * <p><strong>Inline Type Field Assignment (Phase 1B):</strong></p>
     * <p>If the left-hand side is an inline type field access (e.g., v_range.min_sal),
     * the assignment is transformed to a jsonb_set call:</p>
     * <pre>
     * Oracle:     v_range.min_sal := 1000;
     * PostgreSQL: v_range := jsonb_set(v_range, '{min_sal}', to_jsonb(1000));
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

        // STEP 3: Check if LHS is an inline type field assignment (Phase 1B)
        // Pattern: variable.field or variable.field1.field2
        // Transform: variable := jsonb_set(variable, '{field}', to_jsonb(value))
        // For nested: variable := jsonb_set(variable, '{field1,field2}', to_jsonb(value), true)
        if (ctx.general_element() != null) {
            String fieldAssignment = tryTransformInlineTypeFieldAssignment(ctx.general_element(), ctx, b);
            if (fieldAssignment != null) {
                return fieldAssignment;
            }
        }

        // STEP 4: Normal assignment (not a package variable, not an inline type field)
        StringBuilder result = new StringBuilder();
        result.append(leftSide);
        result.append(" := ");
        String rightSide = b.visit(ctx.expression());
        result.append(rightSide);

        return result.toString();
    }

    /**
     * Tries to transform an inline type field assignment to jsonb_set call.
     *
     * @param elemCtx General element context (LHS)
     * @param assignCtx Assignment statement context (for RHS)
     * @param b PostgreSQL code builder
     * @return Transformed jsonb_set call, or null if not an inline type field assignment
     */
    private static String tryTransformInlineTypeFieldAssignment(
            PlSqlParser.General_elementContext elemCtx,
            PlSqlParser.Assignment_statementContext assignCtx,
            PostgresCodeBuilder b) {

        // Check if this is a dotted access pattern
        if (elemCtx.general_element() == null) {
            return null; // Not dotted access
        }

        // Collect all parts
        java.util.List<PlSqlParser.General_element_partContext> parts = collectAllParts(elemCtx);

        if (parts.size() < 2) {
            return null; // Need at least variable.field
        }

        // Extract variable name and field path
        String variableName = parts.get(0).id_expression().getText();

        // For Phase 1B, we use a simple heuristic:
        // If the variable name looks like a local variable (starts with v_, etc.)
        // AND the parts size suggests field access (2+ parts)
        // Transform to jsonb_set
        //
        // This is a simplification - proper implementation would track variable types
        // TODO Phase 1B.5: Track variable declarations and types in scope stack

        // For Phase 1B, always attempt transformation for dotted LHS
        // PostgreSQL will raise a runtime error if the variable is not jsonb
        // This is acceptable for Phase 1B testing

        // Build field path array
        StringBuilder fieldPath = new StringBuilder();
        fieldPath.append("'{ ");
        for (int i = 1; i < parts.size(); i++) {
            if (i > 1) {
                fieldPath.append(" , ");
            }
            fieldPath.append(parts.get(i).id_expression().getText());
        }
        fieldPath.append(" }'");

        // Transform RHS expression
        String rightSide = b.visit(assignCtx.expression());

        // PostgreSQL Bug Fix: String literals need explicit casting for to_jsonb()
        // PostgreSQL's to_jsonb() is polymorphic and cannot determine type from "unknown" literals
        // Example: to_jsonb('text') fails, but to_jsonb('text'::text) works
        String castedValue = addExplicitCastForLiterals(rightSide);

        // Build jsonb_set call
        // Syntax: variable := jsonb_set(variable, '{field}', to_jsonb(value), true)
        // The 'true' flag creates missing intermediate keys for nested paths
        StringBuilder result = new StringBuilder();
        result.append(variableName);
        result.append(" := jsonb_set( ");
        result.append(variableName);
        result.append(" , ");
        result.append(fieldPath);
        result.append(" , to_jsonb( ");
        result.append(castedValue);
        result.append(" ) ");

        // Add 'true' flag for nested paths (creates missing intermediate objects)
        if (parts.size() > 2) {
            result.append(" , true");
        }

        result.append(" )");

        return result.toString();
    }

    /**
     * Collects all parts from a recursive general_element structure.
     * Same logic as in VisitGeneralElement.
     */
    private static java.util.List<PlSqlParser.General_element_partContext> collectAllParts(
            PlSqlParser.General_elementContext ctx) {
        java.util.List<PlSqlParser.General_element_partContext> parts = new java.util.ArrayList<>();

        // Recursively collect from nested general_element
        PlSqlParser.General_elementContext nestedElement = ctx.general_element();
        if (nestedElement != null) {
            parts.addAll(collectAllParts(nestedElement));
        }

        // Add parts from this level
        java.util.List<PlSqlParser.General_element_partContext> currentParts = ctx.general_element_part();
        if (currentParts != null) {
            parts.addAll(currentParts);
        }

        return parts;
    }

    /**
     * Adds explicit type casting for literals to fix PostgreSQL polymorphic type resolution.
     *
     * <p>PostgreSQL's to_jsonb() function is polymorphic and cannot determine the type of
     * "unknown" literals (string literals without explicit type). This causes errors like:
     * "ERROR: could not determine polymorphic type because input has type unknown"</p>
     *
     * <p><b>Examples:</b></p>
     * <pre>
     * Input: 'Hello'       Output: 'Hello'::text
     * Input: 123           Output: 123 (unchanged - numeric literals are typed)
     * Input: v_variable    Output: v_variable (unchanged - variables have types)
     * Input: NULL          Output: NULL (unchanged - NULL is handled by to_jsonb)
     * </pre>
     *
     * @param value The expression value to potentially cast
     * @return The value with explicit cast if needed, or original value
     */
    private static String addExplicitCastForLiterals(String value) {
        if (value == null) {
            return value;
        }

        String trimmed = value.trim();

        // Check if it's a string literal (starts and ends with quotes)
        if ((trimmed.startsWith("'") && trimmed.endsWith("'")) ||
            (trimmed.startsWith("\"") && trimmed.endsWith("\""))) {
            // Add ::text cast for string literals
            return trimmed + "::text";
        }

        // For all other cases (variables, numbers, NULL, expressions), return unchanged
        // - Numeric literals: Already typed (e.g., 123 is integer, 12.5 is numeric)
        // - Variables: Have declared types
        // - NULL: Handled by to_jsonb()
        // - Expressions: Type inference determines type
        return value;
    }
}
