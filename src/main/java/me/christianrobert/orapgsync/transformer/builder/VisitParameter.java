package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.core.tools.TypeConverter;

/**
 * Static helper for visiting PL/SQL function/procedure parameters.
 *
 * <p>Transforms parameter definitions from Oracle to PostgreSQL syntax by visiting
 * the ANTLR parse tree.</p>
 *
 * <h3>Oracle Structure (from grammar):</h3>
 * <pre>
 * parameter: parameter_name (IN | OUT | INOUT | NOCOPY)* type_spec? default_value_part?
 * </pre>
 *
 * <h3>PostgreSQL Structure:</h3>
 * <pre>
 * parameter_name type              -- IN parameters (default)
 * parameter_name OUT type          -- OUT parameters
 * parameter_name INOUT type        -- INOUT parameters
 * </pre>
 *
 * <p>Note: PostgreSQL doesn't need explicit IN keyword (it's the default mode).</p>
 */
public class VisitParameter {

    /**
     * Transforms a parameter from Oracle to PostgreSQL syntax by visiting the AST.
     *
     * <p>Extracts parameter name, mode (IN/OUT/INOUT), and type from the parse tree.</p>
     *
     * @param ctx Parameter parse tree context
     * @param b PostgresCodeBuilder instance (provides access to context for type resolution)
     * @return PostgreSQL parameter string (e.g., "emp_id INOUT numeric") or null if parameter should be excluded
     */
    public static String v(PlSqlParser.ParameterContext ctx, PostgresCodeBuilder b) {
        if (ctx == null) {
            return null;
        }

        // STEP 1: Extract parameter mode (IN, OUT, INOUT)
        boolean hasIn = false;
        boolean hasOut = false;
        boolean hasInout = false;

        for (int i = 0; i < ctx.getChildCount(); i++) {
            String text = ctx.getChild(i).getText();
            if ("IN".equalsIgnoreCase(text)) {
                hasIn = true;
            } else if ("OUT".equalsIgnoreCase(text)) {
                hasOut = true;
            } else if ("INOUT".equalsIgnoreCase(text)) {
                hasInout = true;
            }
        }

        // Determine effective mode
        String mode;
        if (hasInout) {
            mode = "INOUT";
        } else if (hasIn && hasOut) {
            mode = "INOUT";  // IN OUT → INOUT
        } else if (hasOut) {
            mode = "OUT";
        } else {
            mode = "IN";  // Default if nothing specified
        }

        // Only include IN and INOUT parameters in signature
        // OUT-only parameters are handled differently in PostgreSQL
        if ("OUT".equals(mode)) {
            return null;  // TODO: Handle OUT parameters (may need different return type)
        }

        StringBuilder result = new StringBuilder();

        // STEP 2: Extract parameter name
        if (ctx.parameter_name() != null) {
            String paramName = ctx.parameter_name().getText();
            result.append(paramName.toLowerCase());
            result.append(" ");
        }

        // STEP 3: Add mode indicator (INOUT only, IN is default in PostgreSQL)
        if ("INOUT".equals(mode)) {
            result.append("INOUT ");
        }

        // STEP 4: Extract and transform type
        if (ctx.type_spec() != null) {
            // Get Oracle type text from AST
            String oracleType = ctx.type_spec().getText();

            // Convert to PostgreSQL type
            // TODO: Handle %TYPE, %ROWTYPE, REF types
            String postgresType = TypeConverter.toPostgre(oracleType);
            result.append(postgresType);
        } else {
            // Type might be missing in some Oracle syntax variants
            // For now, default to text
            result.append("text");
        }

        return result.toString();
    }
}
