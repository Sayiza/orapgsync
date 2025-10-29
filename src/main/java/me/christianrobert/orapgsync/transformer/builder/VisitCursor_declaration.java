package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.core.tools.TypeConverter;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

/**
 * Static helper for visiting PL/SQL cursor declarations.
 *
 * <p>Transforms Oracle cursor declarations to PostgreSQL equivalents.</p>
 *
 * <h3>Oracle Cursor Declaration:</h3>
 * <pre>
 * CURSOR cursor_name IS SELECT ...;
 * CURSOR cursor_name(param type) IS SELECT ...;
 * CURSOR cursor_name RETURN type IS SELECT ...;  -- RETURN clause dropped
 * </pre>
 *
 * <h3>PostgreSQL Cursor Declaration:</h3>
 * <pre>
 * cursor_name CURSOR FOR SELECT ...;
 * cursor_name CURSOR (param type) FOR SELECT ...;
 * </pre>
 *
 * <h3>Key Transformations:</h3>
 * <ul>
 *   <li>Keyword reordering: CURSOR name IS → name CURSOR FOR</li>
 *   <li>Drop RETURN clause (not used in PostgreSQL)</li>
 *   <li>Transform parameter types (NUMBER → numeric)</li>
 *   <li>Transform SELECT statement using existing visitors</li>
 * </ul>
 *
 * <h3>Notes:</h3>
 * <ul>
 *   <li>FOR loops using these cursors require no changes (syntax identical)</li>
 *   <li>Loop variables still need RECORD declarations (handled by stack)</li>
 *   <li>Parameters support default values in both databases</li>
 * </ul>
 */
public class VisitCursor_declaration {

    /**
     * Transforms cursor declaration to PostgreSQL syntax.
     *
     * @param ctx Cursor declaration parse tree context
     * @param b PostgresCodeBuilder instance (for visiting SELECT and types)
     * @return PostgreSQL cursor declaration
     */
    public static String v(PlSqlParser.Cursor_declarationContext ctx, PostgresCodeBuilder b) {
        StringBuilder result = new StringBuilder();

        // STEP 1: Extract cursor name
        if (ctx.identifier() == null) {
            throw new TransformationException("Cursor declaration missing identifier");
        }
        String cursorName = ctx.identifier().getText().toLowerCase();

        // STEP 2: Build PostgreSQL syntax - name CURSOR
        result.append(cursorName).append(" CURSOR");

        // STEP 3: Handle optional parameters
        if (ctx.parameter_spec() != null && !ctx.parameter_spec().isEmpty()) {
            result.append(" (");

            boolean first = true;
            for (PlSqlParser.Parameter_specContext paramCtx : ctx.parameter_spec()) {
                if (!first) {
                    result.append(", ");
                }
                first = false;

                // Extract parameter name
                if (paramCtx.parameter_name() == null) {
                    throw new TransformationException("Cursor parameter missing name");
                }
                String paramName = paramCtx.parameter_name().getText().toLowerCase();
                result.append(paramName);

                // Extract and convert parameter type
                if (paramCtx.type_spec() != null) {
                    String oracleType = paramCtx.type_spec().getText();
                    String postgresType = TypeConverter.toPostgre(oracleType);
                    result.append(" ").append(postgresType);
                }

                // Handle default value (optional)
                if (paramCtx.default_value_part() != null) {
                    // Transform default value expression
                    String defaultValue = b.visit(paramCtx.default_value_part());
                    result.append(" ").append(defaultValue);
                }
            }

            result.append(")");
        }

        // STEP 4: Add FOR keyword (PostgreSQL syntax)
        result.append(" FOR");

        // STEP 5: Skip RETURN clause if present (PostgreSQL doesn't use it)
        // Oracle: CURSOR name RETURN type IS SELECT
        // PostgreSQL: name CURSOR FOR SELECT (no RETURN)
        // We just ignore ctx.type_spec() here

        // STEP 6: Transform SELECT statement
        if (ctx.select_statement() != null) {
            result.append(" ");
            String transformedSelect = b.visit(ctx.select_statement());
            result.append(transformedSelect);
        } else {
            // Cursor without SELECT (forward declaration) - rare but valid
            // In PostgreSQL, we still need a query, so this might be an error case
            throw new TransformationException(
                    "Cursor declaration without SELECT statement is not supported. " +
                    "Forward cursor declarations must be replaced with full definitions.");
        }

        // STEP 7: Semicolon and newline
        result.append(";\n");

        return result.toString();
    }
}
