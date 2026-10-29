package me.christianrobert.orapgsync.transformer.builder;

import me.christianrobert.orapgsync.antlr.PlSqlParser;
import me.christianrobert.orapgsync.core.tools.PostgresIdentifierNormalizer;
import me.christianrobert.orapgsync.transformer.util.IdentifierHelper;
import me.christianrobert.orapgsync.transformer.util.ParameterDefaults;
import me.christianrobert.orapgsync.transformer.util.TypeResolver;

import java.util.ArrayList;
import java.util.List;

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
 * parameter_name type DEFAULT expr -- defaulted IN parameter, see {@link ParameterDefaults}
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
        return v(ctx, b, false);
    }

    /**
     * As {@link #v(PlSqlParser.ParameterContext, PostgresCodeBuilder)}, appending the transformed
     * {@code DEFAULT} when {@code emitDefault} is set — which the caller decides over the whole
     * parameter list with {@link #emittableDefaults}.
     */
    public static String v(PlSqlParser.ParameterContext ctx, PostgresCodeBuilder b, boolean emitDefault) {
        if (ctx == null) {
            return null;
        }

        String mode = mode(ctx);

        StringBuilder result = new StringBuilder();

        // STEP 1: Extract parameter name
        if (ctx.parameter_name() != null) {
            // The stub names its parameters through the same normalizer, and CREATE OR REPLACE
            // refuses to rename one, so the two sides must agree; it also quotes a name like p#
            // that Oracle allows unquoted (ROADMAP P1.13).
            String paramName = IdentifierHelper.unquote(ctx.parameter_name().getText());
            result.append(PostgresIdentifierNormalizer.normalizeIdentifier(paramName));
            result.append(" ");
        }

        // STEP 2: Add mode indicator (OUT and INOUT need explicit keywords, IN is default)
        if ("OUT".equals(mode)) {
            result.append("OUT ");
        } else if ("INOUT".equals(mode)) {
            result.append("INOUT ");
        }

        // STEP 3: Extract and transform type
        if (ctx.type_spec() != null) {
            // Get Oracle type text from AST
            String oracleType = ctx.type_spec().getText();

            // Package types, migrated object types and built-ins, in that order.
            // TODO: Handle %TYPE, %ROWTYPE, REF types
            result.append(TypeResolver.toPostgres(oracleType, b.getContext()));
        } else {
            // Type might be missing in some Oracle syntax variants
            // For now, default to text
            result.append("text");
        }

        // STEP 4: Default value. The stub carries DEFAULT NULL for the same parameters, and
        // CREATE OR REPLACE may change a default's value but not drop it.
        if (emitDefault && ctx.default_value_part() != null) {
            result.append(" DEFAULT ").append(b.visit(ctx.default_value_part().expression()));
        }

        return result.toString();
    }

    /**
     * Which parameters of one routine may carry their DEFAULT — the same rule the stub job applies
     * to {@code ALL_ARGUMENTS}.
     */
    public static boolean[] emittableDefaults(List<PlSqlParser.ParameterContext> parameters) {
        List<ParameterDefaults.Param> params = new ArrayList<>();
        if (parameters != null) {
            for (PlSqlParser.ParameterContext p : parameters) {
                params.add(new ParameterDefaults.Param(
                        !"OUT".equals(mode(p)), p.default_value_part() != null));
            }
        }
        return ParameterDefaults.emittable(params);
    }

    /** {@code IN}, {@code OUT} or {@code INOUT}. */
    private static String mode(PlSqlParser.ParameterContext ctx) {
        // Extract parameter mode (IN, OUT, INOUT)
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

        return mode;
    }
}
