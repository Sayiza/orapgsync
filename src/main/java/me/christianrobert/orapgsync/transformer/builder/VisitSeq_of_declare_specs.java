package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;

/**
 * Static helper for visiting PL/SQL DECLARE section (seq_of_declare_specs).
 *
 * <p>Transforms Oracle DECLARE specifications to PostgreSQL equivalents.</p>
 *
 * <h3>Oracle Structure (from AST):</h3>
 * <pre>
 * declare_spec+
 *
 * Where each declare_spec can be:
 * - variable_declaration
 * - exception_declaration
 * - pragma_declaration
 * - procedure_spec
 * - function_spec
 * - subtype_declaration
 * - cursor_declaration
 * - type_declaration
 * </pre>
 *
 * <h3>PostgreSQL PL/pgSQL:</h3>
 * <pre>
 * Same structure - declarations are concatenated with newlines
 * </pre>
 *
 * <h3>Notes:</h3>
 * <ul>
 *   <li>Iterates over all declare_spec nodes</li>
 *   <li>Each declaration is transformed by its specific visitor</li>
 *   <li>Results are concatenated (each visitor adds its own newline)</li>
 * </ul>
 */
public class VisitSeq_of_declare_specs {

    /**
     * Transforms DECLARE section to PostgreSQL syntax.
     *
     * @param ctx DECLARE section parse tree context
     * @param b PostgresCodeBuilder instance (for visiting each declaration)
     * @return PostgreSQL DECLARE section content
     */
    public static String v(PlSqlParser.Seq_of_declare_specsContext ctx, PostgresCodeBuilder b) {
        StringBuilder result = new StringBuilder();

        // Iterate over all declare_spec nodes
        for (PlSqlParser.Declare_specContext declareSpec : ctx.declare_spec()) {
            // Visit each declaration (variable, exception, pragma, etc.)
            String declaration = b.visit(declareSpec);

            // Append to result (each visitor adds its own newline)
            if (declaration != null && !declaration.trim().isEmpty()) {
                result.append(declaration);
            }
        }

        // Note: Cursor tracking variables are NOT injected here
        // They must be injected AFTER visiting the body (in VisitFunctionBody/VisitProcedureBody)
        // because we need to know which cursors use attributes before generating declarations

        return result.toString();
    }
}
