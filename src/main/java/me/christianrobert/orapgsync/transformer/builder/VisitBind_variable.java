package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.transformer.context.TransformationException;

/**
 * Static helper for visiting bind variables.
 *
 * <p>Transforms Oracle bind variable references to PostgreSQL variable references.</p>
 *
 * <h3>Oracle Bind Variables:</h3>
 * <pre>
 * :variable_name   -- Named bind variable
 * :1, :2, :3       -- Positional bind variables
 * ?                -- JDBC-style placeholder
 * </pre>
 *
 * <h3>Context:</h3>
 * <ul>
 *   <li>In static PL/SQL, variables are typically referenced WITHOUT : prefix</li>
 *   <li>Bind variables with : are mainly used in dynamic SQL (EXECUTE IMMEDIATE)</li>
 *   <li>However, some Oracle code may use bind variable syntax in static contexts</li>
 * </ul>
 *
 * <h3>PostgreSQL PL/pgSQL:</h3>
 * <pre>
 * variable_name    -- Variables referenced directly without : prefix
 * </pre>
 *
 * <h3>Transformation:</h3>
 * <ul>
 *   <li>:variable_name → variable_name (strip : prefix)</li>
 *   <li>:1, :2 → Positional parameters (context-dependent, error for now)</li>
 *   <li>? → Not supported (error)</li>
 * </ul>
 *
 * <h3>Limitations:</h3>
 * <ul>
 *   <li>Dynamic SQL (EXECUTE IMMEDIATE) not yet supported</li>
 *   <li>Positional bind variables (:1, :2) need special handling</li>
 *   <li>JDBC-style placeholders (?) not supported</li>
 * </ul>
 */
public class VisitBind_variable {

    /**
     * Transforms bind variable to PostgreSQL variable reference.
     *
     * @param ctx Bind variable parse tree context
     * @param b PostgresCodeBuilder instance
     * @return PostgreSQL variable reference (without : prefix)
     */
    public static String v(PlSqlParser.Bind_variableContext ctx, PostgresCodeBuilder b) {
        // Get the raw text of the bind variable
        String bindVarText = ctx.getText();

        // CASE 1: Named bind variable - :variable_name
        // Most common case in PL/SQL
        if (bindVarText.startsWith(":") && !Character.isDigit(bindVarText.charAt(1))) {
            // Strip the : prefix and return the variable name
            // Oracle: :p_dept_id → PostgreSQL: p_dept_id
            String variableName = bindVarText.substring(1).toLowerCase();
            return variableName;
        }

        // CASE 2: Positional bind variable - :1, :2, :3
        // These are context-dependent (used in EXECUTE IMMEDIATE with USING clause)
        // For static PL/SQL, these shouldn't appear
        if (bindVarText.startsWith(":") && Character.isDigit(bindVarText.charAt(1))) {
            throw new TransformationException(
                    "Positional bind variables (:1, :2, etc.) are not supported. " +
                    "These are typically used in dynamic SQL (EXECUTE IMMEDIATE), which is not yet supported.");
        }

        // CASE 3: JDBC-style placeholder - ?
        // Not standard PL/SQL, but grammar recognizes it
        if (bindVarText.equals("?")) {
            throw new TransformationException(
                    "JDBC-style bind variable placeholders (?) are not supported.");
        }

        // CASE 4: Pro*C indicator variables (rare)
        // Grammar: BINDVAR INDICATOR BINDVAR
        // These are used in embedded SQL (Pro*C/C++)
        if (ctx.INDICATOR() != null) {
            throw new TransformationException(
                    "Pro*C indicator variables are not supported.");
        }

        // Fallback: Unknown bind variable format
        throw new TransformationException(
                "Unsupported bind variable format: " + bindVarText);
    }
}
